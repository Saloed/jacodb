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

package org.jacodb.cli

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import mu.KLogging
import org.jacodb.analysis.AnalysisConfig
import org.jacodb.analysis.engine.UnitResolver
import org.jacodb.analysis.engine.VulnerabilityInstance
import org.jacodb.analysis.graph.newApplicationGraphForAnalysis
import org.jacodb.analysis.library.MethodUnitResolver
import org.jacodb.analysis.library.UnusedVariableRunner
import org.jacodb.analysis.library.newNpeRunner
import org.jacodb.analysis.library.newSqlInjectionRunner
import org.jacodb.analysis.runAnalysis
import org.jacodb.analysis.toDumpable
import org.jacodb.api.JcClassOrInterface
import org.jacodb.api.JcClassProcessingTask
import org.jacodb.api.JcMethod
import org.jacodb.api.analysis.JcApplicationGraph
import org.jacodb.api.ext.methods
import org.jacodb.impl.features.InMemoryHierarchy
import org.jacodb.impl.features.Usages
import org.jacodb.impl.jacodb
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = object : KLogging() {}.logger

class AnalysisMain {
    fun run(args: List<String>) = main(args.toTypedArray())
}

fun launchAnalysesByConfig(config: AnalysisConfig, graph: JcApplicationGraph, methods: List<JcMethod>): List<List<VulnerabilityInstance>> {
    return config.analyses.mapNotNull { (analysis, options) ->
        val unitResolver = options["UnitResolver"]?.let {
            UnitResolver.getByName(it)
        } ?: MethodUnitResolver

        val runner = when (analysis) {
            "NPE" -> newNpeRunner()
            "Unused" -> UnusedVariableRunner
            "SQL" -> newSqlInjectionRunner()
            else -> {
                logger.error { "Unknown analysis type: $analysis" }
                return@mapNotNull null
            }
        }

        logger.info { "Launching analysis $analysis" }
        runAnalysis(graph, unitResolver, runner, methods)
    }
}


fun main(args: Array<String>) {
    val parser = ArgParser("taint-analysis")
    val configFilePath by parser.option(
        ArgType.String,
        fullName = "analysisConf",
        shortName = "a",
        description = "File with analysis configuration in JSON format"
    ).required()
    val dbLocation by parser.option(
        ArgType.String,
        fullName = "dbLocation",
        shortName = "l",
        description = "Location of SQLite database for storing bytecode data"
    )
    val startClasses by parser.option(
        ArgType.String,
        fullName = "start",
        shortName = "s",
        description = "classes from which to start the analysis"
    ).required()
    val outputPath by parser.option(
        ArgType.String,
        fullName = "output",
        shortName = "o",
        description = "File where analysis report will be written. All parent directories will be created if not exists. File will be created if not exists. Existing file will be overwritten."
    ).default("report.json") // TODO: create SARIF here
    val classpath by parser.option(
        ArgType.String,
        fullName = "classpath",
        shortName = "cp",
        description = "Classpath for analysis. Used by JacoDB."
    ).default(System.getProperty("java.class.path"))

    parser.parse(args)

    val outputFile = File(outputPath)

    if (outputFile.exists() && outputFile.isDirectory) {
        throw IllegalArgumentException("Provided path for output file is directory, please provide correct path")
    } else if (outputFile.exists()) {
        logger.info { "Output file $outputFile already exists, results will be overwritten" }
    }

    val configFile = File(configFilePath)
    if (!configFile.isFile) {
        throw IllegalArgumentException("Can't find provided config file $configFilePath")
    }
    val config = Json.decodeFromString<AnalysisConfig>(configFile.readText())

    val classpathAsFiles = classpath.split(File.pathSeparatorChar).sorted().map { File(it) }

    val cp = runBlocking {
        val jacodb = jacodb {
            loadByteCode(classpathAsFiles)
            dbLocation?.let {
                persistent(it)
            }
            installFeatures(InMemoryHierarchy, Usages)
        }
        jacodb.classpath(classpathAsFiles)
    }

    val startClassesAsList = startClasses.split(";")
    val startJcClasses = ConcurrentHashMap.newKeySet<JcClassOrInterface>()
    cp.executeAsync(object : JcClassProcessingTask {
        override fun process(clazz: JcClassOrInterface) {
            if (startClassesAsList.any { clazz.name.startsWith(it) }) {
                startJcClasses.add(clazz)
            }
        }
    }).get()
    val startJcMethods = startJcClasses.flatMap { it.methods }.filter { it.isPublic }

    val graph = runBlocking {
        cp.newApplicationGraphForAnalysis()
    }

    val analysesResults = launchAnalysesByConfig(config, graph, startJcMethods).flatten().toDumpable()

    val json = Json { prettyPrint = true }
    outputFile.outputStream().use { fileOutputStream ->
        json.encodeToStream(analysesResults, fileOutputStream)
    }
}