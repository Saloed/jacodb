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

package org.jacodb.analysis.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.jacodb.analysis.sarif.VulnerabilityDescription
import org.jacodb.api.JcMethod
import org.jacodb.taint.configuration.TaintMethodSink
import java.util.concurrent.ConcurrentHashMap

/**
 * A common interface for anything that should be remembered and used
 * after the analysis of some unit is completed.
 */
interface SummaryFact {
    val method: JcMethod
}

/**
 * [SummaryFact] that denotes a possible vulnerability at [sink]
 */
data class VulnerabilityLocation(
    val vulnerabilityDescription: VulnerabilityDescription,
    val sink: IfdsVertex,
    val edge: IfdsEdge? = null,
    val rule: TaintMethodSink? = null,
) : SummaryFact {
    override val method: JcMethod = sink.method
}

/**
 * Denotes some start-to-end edge that should be saved for the method
 */
data class SummaryEdgeFact(val edge: IfdsEdge) : SummaryFact {
    override val method: JcMethod = edge.method
}

/**
 * Saves info about cross-unit call.
 * This info could later be used to restore full [TraceGraph]s
 */
data class CrossUnitCallFact(
    val callerVertex: IfdsVertex,
    val calleeVertex: IfdsVertex,
) : SummaryFact {
    override val method: JcMethod = callerVertex.method
}

/**
 * Wraps a [TraceGraph] that should be saved for some sink
 */
data class TraceGraphFact(val graph: TraceGraph) : SummaryFact {
    override val method: JcMethod = graph.sink.method
}

/**
 * Contains summaries for many methods and allows to update them and subscribe for them.
 */
interface SummaryStorage<T : SummaryFact> {
    /**
     * Adds [fact] to summary of its method
     */
    fun add(fact: T)

    /**
     * @return a flow with all facts summarized for the given [method].
     * Already received facts, along with the facts that will be sent to this storage later,
     * will be emitted to the returned flow.
     */
    fun getFacts(method: JcMethod): Flow<T>

    /**
     * @return a list will all facts summarized for the given [method] so far.
     */
    fun getCurrentFacts(method: JcMethod): List<T>

    /**
     * A list of all methods for which summaries are not empty.
     */
    val knownMethods: List<JcMethod>
}

class SummaryStorageImpl<T : SummaryFact> : SummaryStorage<T> {
    // FIXME: currently, objects are duplicated in 'summaries' and 'outFlows'
    // TODO: remove 'summaries' and keep only 'outFlows', since 'flow.replayCache == summaries'
    private val summaries: MutableMap<JcMethod, MutableSet<T>> = ConcurrentHashMap()
    private val outFlows: MutableMap<JcMethod, MutableSharedFlow<T>> = ConcurrentHashMap()

    override fun add(fact: T) {
        val isNew = summaries.computeIfAbsent(fact.method) { ConcurrentHashMap.newKeySet() }.add(fact)
        if (isNew) {
            val flow = outFlows.computeIfAbsent(fact.method) {
                MutableSharedFlow(replay = Int.MAX_VALUE)
            }
            check(flow.tryEmit(fact))
        }
    }

    override fun getFacts(method: JcMethod): SharedFlow<T> {
        // TODO: check simplified code:
        return outFlows[method] ?: MutableSharedFlow()
        // return outFlows.computeIfAbsent(method) {
        //     val flow = MutableSharedFlow<T>(replay = Int.MAX_VALUE)
        //     for (fact in summaries[method].orEmpty()) {
        //         check(flow.tryEmit(fact))
        //     }
        //     flow
        // }
    }

    override fun getCurrentFacts(method: JcMethod): List<T> {
        return getFacts(method).replayCache
    }

    override val knownMethods: List<JcMethod>
        get() = summaries.keys.toList()
}
