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

package org.jacodb.analysis.ifds2.taint

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.jacodb.analysis.engine.SummaryStorageImpl
import org.jacodb.analysis.engine.UnitResolver
import org.jacodb.analysis.engine.UnitType
import org.jacodb.analysis.graph.reversed
import org.jacodb.analysis.ifds2.Aggregate
import org.jacodb.analysis.ifds2.ControlEvent
import org.jacodb.analysis.ifds2.Manager
import org.jacodb.analysis.ifds2.QueueEmptinessChanged
import org.jacodb.analysis.ifds2.Runner
import org.jacodb.analysis.ifds2.TraceGraph
import org.jacodb.analysis.ifds2.Vertex
import org.jacodb.analysis.ifds2.pathEdges
import org.jacodb.api.JcMethod
import org.jacodb.api.analysis.JcApplicationGraph
import org.jacodb.taint.configuration.TaintMark
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

class TaintManager(
    private val graph: JcApplicationGraph,
    private val unitResolver: UnitResolver,
    private val timeout: Duration = 100.seconds
) : Manager<TaintFact, TaintEvent> {

    private val methodsForUnit: MutableMap<UnitType, MutableSet<JcMethod>> = hashMapOf()
    private val runnerForUnit: MutableMap<UnitType, TaintRunner> = hashMapOf()
    private val queueIsEmpty: MutableMap<UnitType, Boolean> = ConcurrentHashMap()

    private val summaryEdgesStorage = SummaryStorageImpl<SummaryEdge>()
    private val vulnerabilitiesStorage = SummaryStorageImpl<Vulnerability>()

    private val stopRendezvous = Channel<Unit>(Channel.RENDEZVOUS)

    private fun newRunner(
        unit: UnitType,
    ): TaintRunner {
        check(unit !in runnerForUnit) { "Runner for $unit already exists" }

        val runner = if (Globals.BIDI_RUNNER) {
            BidiRunner(
                manager = this@TaintManager,
                unitResolver = unitResolver,
                unit = unit,
                { manager ->
                    val analyzer = TaintAnalyzer(graph)
                    Runner(
                        graph = graph,
                        analyzer = analyzer,
                        manager = manager,
                        unitResolver = unitResolver,
                        unit = unit
                    )
                },
                { manager ->
                    val analyzer = BackwardTaintAnalyzer(graph)
                    Runner(
                        graph = graph.reversed,
                        analyzer = analyzer,
                        manager = manager,
                        unitResolver = unitResolver,
                        unit = unit
                    )
                }
            )
        } else {
            val analyzer = TaintAnalyzer(graph)
            Runner(graph, analyzer, this@TaintManager, unitResolver, unit)
        }

        runnerForUnit[unit] = runner
        return runner
    }

    private fun addStart(method: JcMethod) {
        logger.debug { "Adding start method: $method" }
        val unit = unitResolver.resolve(method)
        methodsForUnit.getOrPut(unit) { mutableSetOf() }.add(method)
        // TODO: val isNew = (...).add(); if (isNew) { deps.forEach { addStart(it) } }
    }

    @OptIn(ExperimentalTime::class)
    fun analyze(
        startMethods: List<JcMethod>,
    ): List<Pair<Vulnerability, TraceGraph<TaintFact>>> = runBlocking(Dispatchers.Default) {
        val timeStart = TimeSource.Monotonic.markNow()

        // Add start methods:
        for (method in startMethods) {
            addStart(method)
        }

        // Determine all units:
        val allUnits = methodsForUnit.keys.toList()
        logger.info {
            "Starting analysis of ${
                methodsForUnit.values.sumOf { it.size }
            } methods in ${allUnits.size} units"
        }

        val unitTraceGraphBuilders = ConcurrentHashMap<UnitType, List<Aggregate<TaintFact>>>()

        // Spawn runner jobs:
        val allJobs = allUnits.map { unit ->
            // Create the runner:
            val runner = newRunner(unit)

            // Start the runner:
            launch(start = CoroutineStart.LAZY) {
                val methods = methodsForUnit[unit]!!.toList()

                try {
                    runner.run(methods)
                } finally {
                    val traceGraph = runner.getAggregate()
                    unitTraceGraphBuilders.merge(unit, listOf(traceGraph)) { prev, newList -> prev + newList }
                }
            }
        }

        // Spawn progress job:
        val progress = launch(Dispatchers.IO) {
            logger.info { "Progress job started" }
            while (isActive) {
                delay(1.seconds)
                logger.info {
                    "Progress: total propagated ${
                        runnerForUnit.values.sumOf { it.pathEdges.size }
                    } path edges"
                }
            }
            logger.info { "Progress job finished" }
        }

        // Spawn stopper job:
        val stopper = launch(Dispatchers.IO) {
            logger.info { "Stopper job started" }
            stopRendezvous.receive()
            // delay(100)
            // @OptIn(ExperimentalCoroutinesApi::class)
            // if (runnerForUnit.values.any { !it.workList.isEmpty }) {
            //     logger.warn { "NOT all runners have empty work list" }
            //     error("?")
            // }
            logger.info { "Stopping all runners..." }
            allJobs.forEach { it.cancel() }
            logger.info { "Stopper job finished" }
        }

        // Start all runner jobs:
        val timeStartJobs = TimeSource.Monotonic.markNow()
        allJobs.forEach { it.start() }

        // Await all runners:
        withTimeoutOrNull(timeout) {
            allJobs.joinAll()
        } ?: run {
            allJobs.forEach { it.cancel() }
            allJobs.joinAll()
        }
        progress.cancelAndJoin()
        stopper.cancelAndJoin()
        logger.info {
            "All ${allJobs.size} jobs completed in %.1f s".format(
                timeStartJobs.elapsedNow().toDouble(DurationUnit.SECONDS)
            )
        }

        // Extract found vulnerabilities (sinks):
        val foundVulnerabilities = vulnerabilitiesStorage.knownMethods
            .flatMap { method ->
                vulnerabilitiesStorage.getCurrentFacts(method)
            }

        val traceGraphBuilders = hashMapOf<Vertex<TaintFact>, Aggregate<TaintFact>>()
        for (unitBuilders in unitTraceGraphBuilders) {
            for (builder in unitBuilders.value) {
                for (sink in builder.sinks) {
                    val prev = traceGraphBuilders.putIfAbsent(sink, builder)
                    check(prev == null) { "Multiple graph builders for sink: $sink" }
                }
            }
        }

        val vulnerabilitiesWithTraces = foundVulnerabilities.map {
            it to buildTraceGraph(it, traceGraphBuilders)
        }

        logger.debug { "Total found ${foundVulnerabilities.size} vulnerabilities" }
        logger.debug {
            buildString {
                for (vulnerability in foundVulnerabilities) {
                    appendLine("$vulnerability in ${vulnerability.method}")
                }
            }
        }
        logger.info { "Total sinks: ${foundVulnerabilities.size}" }

        logger.info { "Total propagated ${runnerForUnit.values.sumOf { it.pathEdges.size }} path edges" }

        if (logger.isDebugEnabled()) {
            val statsFileName = "stats.csv"
            logger.debug { "Writing stats in '$statsFileName'..." }
            File(statsFileName).outputStream().bufferedWriter().use { writer ->
                val sep = ";"
                writer.write(listOf("classname", "cwe", "method", "sink", "fact").joinToString(sep) + "\n")
                for (vulnerability in foundVulnerabilities) {
                    val m = vulnerability.method
                    if (vulnerability.rule != null) {
                        for (cwe in vulnerability.rule.cwe) {
                            writer.write(
                                listOf(
                                    m.enclosingClass.simpleName,
                                    cwe,
                                    m.name,
                                    vulnerability.sink.statement,
                                    vulnerability.sink.fact
                                ).joinToString(sep) { "\"$it\"" } + "\n")
                        }
                    } else if (
                        vulnerability.sink.fact is Tainted
                        && vulnerability.sink.fact.mark == TaintMark.NULLNESS
                    ) {
                        val cwe = 476
                        writer.write(
                            listOf(
                                m.enclosingClass.simpleName,
                                cwe,
                                m.name,
                                vulnerability.sink.statement,
                                vulnerability.sink.fact
                            ).joinToString(sep) { "\"$it\"" } + "\n")
                    } else {
                        logger.warn { "Bad vulnerability without rule: $vulnerability" }
                    }
                }
            }
        }

        logger.info {
            "Analysis done in %.1f s".format(
                timeStart.elapsedNow().toDouble(DurationUnit.SECONDS)
            )
        }
        vulnerabilitiesWithTraces
    }

    private fun buildTraceGraph(
        vulnerability: Vulnerability,
        traceGraphBuilders: Map<Vertex<TaintFact>, Aggregate<TaintFact>>
    ): TraceGraph<TaintFact> {
        val builder = traceGraphBuilders[vulnerability.sink]
            ?: error("No trace graph builder for: $vulnerability")

        val traceGraph = builder.buildTraceGraph(vulnerability.sink)
        // todo: extend?
        return traceGraph
    }

    override fun handleEvent(event: TaintEvent) {
        when (event) {
            is NewSummaryEdge -> {
                summaryEdgesStorage.add(SummaryEdge(event.edge))
            }

            is NewVulnerability -> {
                vulnerabilitiesStorage.add(event.vulnerability)
            }

            is EdgeForOtherRunner -> {
                val method = event.edge.method
                val unit = unitResolver.resolve(method)
                val otherRunner = runnerForUnit[unit] ?: run {
                    logger.trace { "Ignoring event=$event for non-existing runner for unit=$unit" }
                    return
                }
                otherRunner.submitNewEdge(event.edge)
            }
        }
    }

    override suspend fun handleControlEvent(event: ControlEvent) {
        when (event) {
            is QueueEmptinessChanged -> {
                queueIsEmpty[event.runner.unit] = event.isEmpty
                if (event.isEmpty) {
                    yield()
                    if (runnerForUnit.keys.all { queueIsEmpty[it] == true }) {
                        stopRendezvous.send(Unit)
                    }
                }
            }
        }
    }

    override fun subscribeOnSummaryEdges(
        method: JcMethod,
        scope: CoroutineScope,
        handler: suspend (TaintEdge) -> Unit,
    ) {
        summaryEdgesStorage
            .getFacts(method)
            .map { it.edge }
            .onEach(handler)
            .launchIn(scope)
    }
}
