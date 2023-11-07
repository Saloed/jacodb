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

package org.jacodb.analysis.impl

import kotlinx.coroutines.runBlocking
import org.jacodb.analysis.engine.VulnerabilityInstance
import org.jacodb.analysis.graph.newApplicationGraphForAnalysis
import org.jacodb.analysis.library.SingletonUnitResolver
import org.jacodb.analysis.library.UnusedVariableRunnerFactory
import org.jacodb.analysis.library.analyzers.UnusedVariableAnalyzer
import org.jacodb.analysis.runAnalysis
import org.jacodb.api.JcMethod
import org.jacodb.impl.features.InMemoryHierarchy
import org.jacodb.impl.features.Usages
import org.jacodb.testing.WithDB
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class UnusedVariableTest : BaseAnalysisTest() {
    companion object : WithDB(Usages, InMemoryHierarchy) {
        @JvmStatic
        fun provideClassesForJuliet563(): Stream<Arguments> = provideClassesForJuliet(563, listOf(
            // Unused variables are already optimized out by cfg
            "unused_uninit_variable_", "unused_init_variable_int", "unused_init_variable_long", "unused_init_variable_String_",

            // Unused variable is generated by cfg (!!)
            "unused_value_StringBuilder_17",

            // Expected answers are strange, seems to be problem in tests
            "_12",

            // The variable isn't expected to be detected as unused actually
            "_81"
        ))

        private const val vulnerabilityType = UnusedVariableAnalyzer.ruleId
    }

    @ParameterizedTest
    @MethodSource("provideClassesForJuliet563")
    fun `test on Juliet's CWE 563`(className: String) {
        testSingleJulietClass(vulnerabilityType, className)
    }

    override fun launchAnalysis(methods: List<JcMethod>): List<VulnerabilityInstance> {
        val graph = runBlocking {
            cp.newApplicationGraphForAnalysis()
        }
        return runAnalysis(graph, SingletonUnitResolver, UnusedVariableRunnerFactory, methods)
    }
}
