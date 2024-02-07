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

import org.jacodb.analysis.ifds2.taint.Zero

/**
 * Aggregates all facts and edges found by the tabulation algorithm.
 */
class Aggregate<Fact>(
    pathEdges: Collection<Edge<Fact>>,
    val facts: Map<Statement, Set<Fact>>,
    val reasons: Map<Edge<Fact>, Set<Reason>>,
) {
    private val pathEdgesBySink: Map<Vertex<Fact>, Collection<Edge<Fact>>> =
        pathEdges.groupByTo(HashMap()) { it.to }

    val sinks: Set<Vertex<Fact>>
        get() = pathEdgesBySink.keys

    private inner class TraceGraphBuilder {
        val sources: MutableSet<Vertex<Fact>> = hashSetOf()
        val edges: MutableMap<Vertex<Fact>, MutableSet<Vertex<Fact>>> = hashMapOf()
        val visited: MutableSet<Pair<Edge<Fact>, Vertex<Fact>>> = hashSetOf()

        val sourceEdges: MutableSet<Edge<Fact>> = hashSetOf()
        val entryPoints: MutableSet<Vertex<Fact>> = hashSetOf()
        val visitedEntryPointEdges: MutableSet<Edge<Fact>> = hashSetOf()

        fun addEdge(
            from: Vertex<Fact>,
            to: Vertex<Fact>,
        ) {
            if (from != to) {
                edges.getOrPut(from) { hashSetOf() }.add(to)
            }
        }

        fun dfs(
            edge: Edge<Fact>,
            lastVertex: Vertex<Fact>,
            stopAtMethodStart: Boolean,
        ) {
            if (!visited.add(edge to lastVertex)) {
                return
            }

            if (stopAtMethodStart && edge.from == edge.to) {
                addEdge(edge.from, lastVertex)
                return
            }

            val vertex = edge.to
            // FIXME: not all domains have "Zero" fact!
            if (vertex.fact == Zero) {
                addEdge(vertex, lastVertex)
                sourceEdges.add(edge)
                sources.add(vertex)
                return
            }

            for (reason in reasons[edge].orEmpty()) {
                when (reason) {
                    is Reason.Sequent<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        reason as Reason.Sequent<Fact>
                        val predEdge = reason.edge
                        if (predEdge.to.fact == vertex.fact) {
                            dfs(predEdge, lastVertex, stopAtMethodStart)
                        } else {
                            addEdge(predEdge.to, lastVertex)
                            dfs(predEdge, predEdge.to, stopAtMethodStart)
                        }
                    }

                    is Reason.CallToStart<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        reason as Reason.CallToStart<Fact>
                        val predEdge = reason.edge
                        if (!stopAtMethodStart) {
                            addEdge(predEdge.to, lastVertex)
                            dfs(predEdge, predEdge.to, false)
                        }
                    }

                    is Reason.ThroughSummary<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        reason as Reason.ThroughSummary<Fact>
                        val predEdge = reason.edge
                        val summaryEdge = reason.summaryEdge
                        addEdge(summaryEdge.to, lastVertex) // Return to next vertex
                        addEdge(predEdge.to, summaryEdge.from) // Call to start
                        dfs(summaryEdge, summaryEdge.to, true) // Expand summary edge
                        dfs(predEdge, predEdge.to, stopAtMethodStart) // Continue normal analysis
                    }

                    is Reason.External -> {
                        // TODO: check
                        addEdge(edge.to, lastVertex)
                        if (edge.from != edge.to) {
                            // TODO: ideally, we should analyze the place from which the edge was given to ifds,
                            //  for now we just go to method start
                            dfs(Edge(edge.from, edge.from), edge.to, stopAtMethodStart)
                        }
                    }

                    is Reason.Initial -> {
                        // TODO: check
                        sources.add(vertex)
                        addEdge(edge.to, lastVertex)
                        sourceEdges.add(edge)
                    }
                }
            }
        }

        fun findEntryPoints() {
            for (sourceEdge in sourceEdges) {
                searchForEntryPoint(sourceEdge)
            }
        }

        private fun searchForEntryPoint(initialEdge: Edge<Fact>) {
            val unprocessedEdges = mutableListOf(initialEdge)
            while (unprocessedEdges.isNotEmpty()) {
                val edge = unprocessedEdges.removeLast()
                if (!visitedEntryPointEdges.add(edge)) continue

                val reasons = reasons[edge] ?: continue

                var isTerminal = true
                val entryPointVertices = mutableListOf<Vertex<Fact>>()
                for (reason in reasons) {
                    when (reason) {
                        Reason.External,
                        Reason.Initial -> {
                            entryPointVertices.add(edge.from)
                        }

                        is Reason.CallToStart<*> -> {
                            isTerminal = false
                            @Suppress("UNCHECKED_CAST")
                            unprocessedEdges.add(reason.edge as Edge<Fact>)
                        }

                        is Reason.Sequent<*> -> {
                            isTerminal = false
                            @Suppress("UNCHECKED_CAST")
                            unprocessedEdges.add(reason.edge as Edge<Fact>)
                        }

                        is Reason.ThroughSummary<*> -> {
                            isTerminal = false
                            @Suppress("UNCHECKED_CAST")
                            unprocessedEdges.add(reason.edge as Edge<Fact>)
                        }
                    }
                }

                // todo: track all initial edges?
                if (entryPointVertices.isNotEmpty() && isTerminal) {
                    entryPoints.addAll(entryPointVertices)
                }
            }
        }
    }

    fun buildTraceGraph(sink: Vertex<Fact>): TraceGraph<Fact> {
        val builder = TraceGraphBuilder()

        for (edge in pathEdgesBySink[sink].orEmpty()) {
            builder.dfs(edge, edge.to, false)
        }

        builder.findEntryPoints()

        return TraceGraph(sink, builder.sources, builder.edges, builder.entryPoints)
    }
}
