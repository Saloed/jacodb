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

@file:Suppress("OPT_IN_USAGE")

package org.jacodb.typesolver

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jacodb.api.JcClassOrInterface
import org.jacodb.api.JcClasspath
import org.jacodb.classtable.extractClassesTable
import org.jacodb.impl.features.classpaths.UnknownClasses
import org.jacodb.testing.allJars
import org.jacodb.typesolver.table.Class
import org.jacodb.typesolver.table.ClassDeclaration
import org.jacodb.typesolver.table.ClassesTable
import org.jacodb.typesolver.table.Interface
import org.jacodb.typesolver.table.InterfaceDeclaration
import org.jacodb.typesolver.table.Intersect
import org.jacodb.typesolver.table.JvmType
import org.jacodb.typesolver.table.JvmWildcardPolarity
import org.jacodb.typesolver.table.Null
import org.jacodb.typesolver.table.PrimitiveType
import org.jacodb.typesolver.table.Var
import org.jacodb.typesolver.table.Wildcard
import org.jacodb.typesolver.table.toJvmDeclaration
import java.io.File
import java.lang.reflect.Type
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

interface ClassesTableSerializer {
}

class OCanrenClassesTableSerializer {

}

class ClassDeclarationSerializer : JsonSerializer<ClassDeclaration> {
    override fun serialize(src: ClassDeclaration, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val array = JsonArray()
        val prefix = "C"

        array.add(prefix)
        val json = JsonObject()
        with(context) {
            with(json) {
                with(src) {
                    addProperty("cname", cname)
                    add("params", serialize(params))
                    add("super", serialize(`super`))
                    add("supers", serialize(supers))
                }
            }

            array.add(json)
        }

        return array
    }
}

class InterfaceDeclarationSerializer : JsonSerializer<InterfaceDeclaration> {
    override fun serialize(src: InterfaceDeclaration, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val array = JsonArray()
        val prefix = "I"

        array.add(prefix)
        val json = JsonObject()
        with(context) {
            with(json) {
                with(src) {
                    addProperty("iname", iname)
                    add("iparams", serialize(iparams))
                    add("isupers", serialize(isupers))
                }
            }

            array.add(json)
        }

        return array
    }
}

class ArraySerializer : JsonSerializer<org.jacodb.typesolver.table.Array> {
    override fun serialize(
        src: org.jacodb.typesolver.table.Array,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        val prefix = "Array"

        array.add(prefix)
        array.add(context.serialize(src.elementType))

        return array
    }
}

class ClassSerializer : JsonSerializer<Class> {
    override fun serialize(
        src: Class,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        val prefix = "Class"

        array.add(prefix)
        array.add(src.cname)
        array.add(context.serialize(src.typeArguments))

        return array
    }
}

class InterfaceSerializer : JsonSerializer<Interface> {
    override fun serialize(
        src: Interface,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        val prefix = "Interface"

        array.add(prefix)
        array.add(src.iname)
        array.add(context.serialize(src.typeArguments))

        return array
    }
}

class VarSerializer : JsonSerializer<org.jacodb.typesolver.table.Var> {
    override fun serialize(
        src: org.jacodb.typesolver.table.Var,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        val prefix = "Var"

        array.add(prefix)
        val json = JsonObject()
        with(context) {
            with(json) {
                with(src) {
                    addProperty("id", id)
                    addProperty("index", index)
                    add("upb", serialize(upb))
                    add("lwb", serialize(lwb))
                }
            }

            array.add(json)
        }

        return array
    }
}

class NullSerializer : JsonSerializer<org.jacodb.typesolver.table.Null> {
    override fun serialize(
        src: org.jacodb.typesolver.table.Null,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        val prefix = "Null"
        array.add(prefix)

        return array
    }
}

class IntersectSerializer : JsonSerializer<org.jacodb.typesolver.table.Intersect> {
    override fun serialize(
        src: org.jacodb.typesolver.table.Intersect,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        val prefix = "Intersect"
        array.add(prefix)
        array.add(context.serialize(src.types))

        return array
    }
}

class TypeSerializer : JsonSerializer<org.jacodb.typesolver.table.Type> {
    override fun serialize(
        src: org.jacodb.typesolver.table.Type,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        val prefix = "Type"
        array.add(prefix)

        array.add(context.serialize(src.type))

        return array
    }
}

class PairSerializer : JsonSerializer<Pair<JvmWildcardPolarity, JvmType>> {
    override fun serialize(
        src: Pair<JvmWildcardPolarity, JvmType>,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()

        array.add(context.serialize(src.first))
        array.add(context.serialize(src.second))

        return array
    }
}

class WildcardSerializer : JsonSerializer<Wildcard> {
    override fun serialize(
        src: Wildcard,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        val prefix = "Wildcard"
        array.add(prefix)

        array.add(context.serialize(src.bound))

        return array
    }
}

class PrimitiveTypeSerializer : JsonSerializer<PrimitiveType> {
    override fun serialize(
        src: PrimitiveType,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        val prefix = "Primitive"
        array.add(prefix)

        array.add(src.jvmName)

        return array
    }
}

fun createGsonBuilder(): GsonBuilder = GsonBuilder()
    .registerTypeAdapter(ClassDeclaration::class.java, ClassDeclarationSerializer())
    .registerTypeAdapter(InterfaceDeclaration::class.java, InterfaceDeclarationSerializer())
    .registerTypeAdapter(org.jacodb.typesolver.table.Array::class.java, ArraySerializer())
    .registerTypeAdapter(Class::class.java, ClassSerializer())
    .registerTypeAdapter(Interface::class.java, InterfaceSerializer())
    .registerTypeAdapter(Var::class.java, VarSerializer())
    .registerTypeAdapter(Null::class.java, NullSerializer())
    .registerTypeAdapter(Intersect::class.java, IntersectSerializer())
    .registerTypeAdapter(org.jacodb.typesolver.table.Type::class.java, TypeSerializer())
    .registerTypeAdapter(Wildcard::class.java, WildcardSerializer())
    .registerTypeAdapter(PrimitiveType::class.java, PrimitiveTypeSerializer())
    .serializeNulls()
    .setPrettyPrinting()

fun main() {
//    val gson = createGsonBuilder().create()
//    val (classes, classpath) = extractClassesTable(allJars)
//    val classesTable = makeClassesTable(classes, classpath)
//    val json = gson.toJson(classesTable)
//
//    // jdk.internal.jshell.tool.Selector$SelectorBuilder$SelectorCollector - Intersect
//    File("all_jars.json").bufferedWriter().use {
//        it.write(json)
//    }

    generateJCrashClassTables()
}

fun makeClassesTable(
    classes: List<JcClassOrInterface>,
    classpath: JcClasspath
) = ClassesTable(classes.toJvmDeclarationsSafe(classpath).toTypedArray())

private fun List<JcClassOrInterface>.toJvmDeclarationsSafe(
    classpath: JcClasspath
) = mapNotNull {
    runCatching { it.toJvmDeclaration(classpath) }
        .fold({ it }) { error ->
//                    if (!(error is NoClassInClasspathException)) {
            println("Class ${it.name} | $error")
//            println("Error: $error")
//                        println("Stacktrace: ${error.stackTraceToString()}")
//            println()
//                    }

            null
        }
}


@Serializable
data class CrashPackApplicationVersion(
    @SerialName("src_url")
    val srcUrl: String,
    val version: String
)

@Serializable
data class CrashPackApplication(
    val name: String,
    val url: String,
    val versions: Map<String, CrashPackApplicationVersion>
)

@Serializable
data class CrashPackCrash(
    val application: String, // "JFreeChart"
    @SerialName("buggy_frame")
    val buggyFrame: String, // 6
    @SerialName("fixed_commit")
    val fixedCommit: String,
    val id: String, // "ES-14457"
    val issue: String, // "https://github.com/elastic/elasticsearch/issues/14457"
    @SerialName("target_frames")
    val targetFrames: String, // ".*elasticsearch.*"
    val version: String,
    @SerialName("version_fixed")
    val versionFixed: String,
)

@Serializable
data class CrashPack(
    val applications: Map<String, CrashPackApplication>,
    val crashes: Map<String, CrashPackCrash>
)

fun generateJCrashClassTables() {
    val classTablePath = Path("C:\\Users\\vwx1181288\\IdeaProjects\\jacodb\\typeQueries\\classes")
    val crashPackPath = Path("D:\\JCrashPack")

    val crashPackDescriptionPath = crashPackPath / "jcrashpack.json"
    val crashPack = Json.decodeFromStream<CrashPack>(crashPackDescriptionPath.inputStream())

    runBlocking {
        crashPack.crashes.values.mapIndexedNotNull { index, it ->
            try {
                println("#".repeat(50))
                println("Start ${it.id} $index / ${crashPack.crashes.size}")
                dumpClassTable(classTablePath, crashPackPath, it)
            } catch (ex: Throwable) {
                System.err.println(ex)
                null
            }
        }
    }
}

private fun dumpClassTable(resultPath: Path, crashPackPath: Path, crash: CrashPackCrash) {
    val crashCp = crashPackPath / "applications" / crash.application / crash.version / "bin"
    val cpFiles = crashCp.listDirectoryEntries("*.jar").map { it.toFile() }

    val gson = createGsonBuilder().create()
    extractClassesTable(cpFiles).use { (classes, classpath) ->
        val classesTable = makeClassesTable(classes, classpath)
        val json = gson.toJson(classesTable)

        val dumPath = resultPath / "${crash.id}.json"
        dumPath.writeText(json)
    }
}
