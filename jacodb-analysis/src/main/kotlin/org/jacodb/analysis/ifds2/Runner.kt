/*
 *  Copyright 2022 UnitTestBot contributors (utbot.org)
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.jacodb.analysis.ifds2

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import org.jacodb.analysis.engine.UnitResolver
import org.jacodb.analysis.engine.UnitType
import org.jacodb.analysis.ifds2.taint.BidiRunner
import org.jacodb.analysis.ifds2.taint.Zero
import org.jacodb.api.JcMethod
import org.jacodb.api.analysis.JcApplicationGraph
import org.jacodb.api.cfg.JcInst
import org.jacodb.api.ext.cfg.callExpr
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

typealias Method = JcMethod
typealias Statement = JcInst

interface IRunner<Fact> {

    val unit: UnitType

    suspend fun run(startMethods: List<Method>)
    fun submitNewEdge(edge: Edge<Fact>)

    fun getAggregate(): Aggregate<Fact>
}

@Suppress("RecursivePropertyAccessor")
val IRunner<*>.pathEdges: Set<Edge<*>>
    get() = when (this) {
        is Runner<*, *> -> pathEdges
        is BidiRunner -> forwardRunner.pathEdges + backwardRunner.pathEdges
        else -> error("Cannot extract pathEdges for $this")
    }

// TODO: make all fields private again
class Runner<Fact, Event>(
    internal val graph: JcApplicationGraph,
    internal val analyzer: Analyzer<Fact, Event>,
    internal val manager: Manager<Fact, Event>,
    internal val unitResolver: UnitResolver,
    override val unit: UnitType,
) : IRunner<Fact> {

    internal val flowSpace: FlowFunctions<Fact> =
        analyzer.flowFunctions
    internal val workList: Channel<Edge<Fact>> =
        Channel(Channel.UNLIMITED)
    internal val pathEdges: MutableSet<Edge<Fact>> =
        ConcurrentHashMap.newKeySet()
    internal val reasons: MutableMap<Edge<Fact>, MutableSet<Reason>> =
        ConcurrentHashMap()
    internal val summaryEdges: MutableMap<Vertex<Fact>, MutableSet<Vertex<Fact>>> =
        hashMapOf()
    internal val callerPathEdgeOf: MutableMap<Vertex<Fact>, MutableSet<Edge<Fact>>> =
        hashMapOf()

    override suspend fun run(startMethods: List<JcMethod>) {
        for (method in startMethods) {
            addStart(method)
        }

        tabulationAlgorithm()
    }

    // TODO: should 'addStart' be public?
    // TODO: should 'addStart' replace 'submitNewEdge'?
    // TODO: inline
    private fun addStart(method: JcMethod) {
        require(unitResolver.resolve(method) == unit)
        val startFacts = flowSpace.obtainPossibleStartFacts(method)
        for (startFact in startFacts) {
            for (start in graph.entryPoints(method)) {
                val vertex = Vertex(start, startFact)
                val edge = Edge(vertex, vertex) // loop
                val reason = Reason.Initial
                propagate(edge, reason)
            }
        }
    }

    override fun submitNewEdge(edge: Edge<Fact>) {
        // TODO: add default-argument 'reason = Reason.External' to 'submitNewEdge'
        propagate(edge, Reason.External)
    }

    private fun propagate(
        edge: Edge<Fact>,
        reason: Reason,
    ): Boolean {
        require(unitResolver.resolve(edge.method) == unit) {
            "Propagated edge must be in the same unit"
        }

        reasons.getOrPut(edge) { hashSetOf() }.add(reason)

        // Handle only NEW edges:
        if (pathEdges.add(edge)) {
            val doPrintOnlyForward = true
            val doPrintZero = false
            if (!doPrintOnlyForward || edge.from.statement.toString() == "noop") {
                if (doPrintZero || edge.to.fact != Zero) {
                    logger.trace { "Propagating edge=$edge in method=${edge.method} via reason=${reason}" }
                }
            }

            // Send edge to analyzer/manager:
            for (event in analyzer.handleNewEdge(edge)) {
                manager.handleEvent(event)
            }

            // Add edge to worklist:
            // workList.send(edge)
            workList.trySend(edge).getOrThrow()

            return true
        }

        return false
    }

    private suspend fun tabulationAlgorithm() = coroutineScope {
        while (isActive) {
            val edge = workList.tryReceive().getOrElse {
                manager.handleControlEvent(QueueEmptinessChanged(this@Runner, true))
                val edge = workList.receive()
                manager.handleControlEvent(QueueEmptinessChanged(this@Runner, false))
                edge
            }
            tabulationAlgorithmStep(edge, this@coroutineScope)
        }
    }

    private val Method.isExtern: Boolean
        get() = unitResolver.resolve(this) != unit

    private fun tabulationAlgorithmStep(
        currentEdge: Edge<Fact>,
        scope: CoroutineScope,
    ) {
        val (startVertex, currentVertex) = currentEdge
        val (current, currentFact) = currentVertex

        val currentCallees = graph.callees(current).toList()
        val currentIsCall = current.callExpr != null
        val currentIsExit = current in graph.exitPoints(current.location.method)

        if (currentIsCall) {
            // Propagate through the call-to-return-site edge:
            for (returnSite in graph.successors(current)) {
                val factsAtReturnSite = flowSpace
                    .obtainCallToReturnSiteFlowFunction(current, returnSite)
                    .compute(currentFact)
                for (returnSiteFact in factsAtReturnSite) {
                    val returnSiteVertex = Vertex(returnSite, returnSiteFact)
                    val newEdge = Edge(startVertex, returnSiteVertex)
                    val reason = Reason.Sequent(currentEdge)
                    propagate(newEdge, reason)
                }
            }

            // Propagate through the call:
            for (callee in currentCallees) {
                // TODO: check whether we need to analyze the callee (or it was skipped due to MethodSource)
                if (analyzer.isSkipped(callee)) {
                    logger.info { "Skipping method $callee" }
                    continue
                }

                for (calleeStart in graph.entryPoints(callee)) {
                    val factsAtCalleeStart = flowSpace
                        .obtainCallToStartFlowFunction(current, calleeStart)
                        .compute(currentFact)
                    for (calleeStartFact in factsAtCalleeStart) {
                        val calleeStartVertex = Vertex(calleeStart, calleeStartFact)

                        if (callee.isExtern) {
                            // Initialize analysis of callee:
                            for (event in analyzer.handleCrossUnitCall(currentVertex, calleeStartVertex)) {
                                manager.handleEvent(event)
                            }

                            // Subscribe on summary edges:
                            manager.subscribeOnSummaryEdges(callee, scope) { summaryEdge ->
                                if (summaryEdge.from == calleeStartVertex) {
                                    handleSummaryEdge(currentEdge, summaryEdge)
                                } else {
                                    logger.debug { "Skipping unsuitable summary edge: $summaryEdge" }
                                }
                            }
                        } else {
                            // Save info about the call for summary edges that will be found later:
                            callerPathEdgeOf.getOrPut(calleeStartVertex) { hashSetOf() }.add(currentEdge)

                            // Initialize analysis of callee:
                            run {
                                val newEdge = Edge(calleeStartVertex, calleeStartVertex) // loop
                                val reason = Reason.CallToStart(currentEdge)
                                propagate(newEdge, reason)
                            }

                            // Handle already-found summary edges:
                            for (exitVertex in summaryEdges[calleeStartVertex].orEmpty()) {
                                val summaryEdge = Edge(calleeStartVertex, exitVertex)
                                handleSummaryEdge(currentEdge, summaryEdge)
                            }
                        }
                    }
                }
            }
        } else {
            if (currentIsExit) {
                // Propagate through the summary edge:
                for (callerPathEdge in callerPathEdgeOf[startVertex].orEmpty()) {
                    handleSummaryEdge(currentEdge = callerPathEdge, summaryEdge = currentEdge)
                }

                // Add new summary edge:
                summaryEdges.getOrPut(startVertex) { hashSetOf() }.add(currentVertex)
            }

            // Simple (sequential) propagation to the next instruction:
            for (next in graph.successors(current)) {
                val factsAtNext = flowSpace
                    .obtainSequentFlowFunction(current, next)
                    .compute(currentFact)
                for (nextFact in factsAtNext) {
                    val nextVertex = Vertex(next, nextFact)
                    val newEdge = Edge(startVertex, nextVertex)
                    val reason = Reason.Sequent(currentEdge)
                    propagate(newEdge, reason)
                }
            }
        }
    }

    private fun handleSummaryEdge(
        currentEdge: Edge<Fact>,
        summaryEdge: Edge<Fact>,
    ) {
        val (startVertex, currentVertex) = currentEdge
        val caller = currentVertex.statement
        for (returnSite in graph.successors(caller)) {
            val (exit, exitFact) = summaryEdge.to
            val finalFacts = flowSpace
                .obtainExitToReturnSiteFlowFunction(caller, returnSite, exit)
                .compute(exitFact)
            for (returnSiteFact in finalFacts) {
                val returnSiteVertex = Vertex(returnSite, returnSiteFact)
                val newEdge = Edge(startVertex, returnSiteVertex)
                val reason = Reason.ThroughSummary(currentEdge, summaryEdge)
                propagate(newEdge, reason)
            }
        }
    }

    private fun getFinalFacts(): Map<Statement, Set<Fact>> {
        val resultFacts: MutableMap<Statement, MutableSet<Fact>> = mutableMapOf()
        for (edge in pathEdges) {
            resultFacts.getOrPut(edge.to.statement) { mutableSetOf() }.add(edge.to.fact)
        }
        return resultFacts
    }

    override fun getAggregate(): Aggregate<Fact> {
        val facts = getFinalFacts()
        return Aggregate(pathEdges, facts, reasons)
    }
}
