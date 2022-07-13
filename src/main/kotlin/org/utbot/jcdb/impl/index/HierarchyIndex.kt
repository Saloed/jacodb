package org.utbot.jcdb.impl.index

import kotlinx.collections.immutable.toImmutableMap
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.utbot.jcdb.api.ByteCodeLocation
import org.utbot.jcdb.api.ByteCodeLocationIndex
import org.utbot.jcdb.api.ByteCodeLocationIndexBuilder
import org.utbot.jcdb.api.Feature
import java.io.InputStream
import java.io.OutputStream

class HierarchyIndexBuilder : ByteCodeLocationIndexBuilder<String, HierarchyIndex> {

    // super class -> implementations
    private val parentToSubClasses = hashMapOf<Int, HashSet<Int>>()

    override fun index(classNode: ClassNode) {
        val superClass = classNode.superName?.takeIf {
            it != "java/lang/Object"
        }
        if (superClass != null) {
            addToIndex(superClass, classNode.name)
        }
        classNode.interfaces.forEach {
            addToIndex(it, classNode.name)
        }
    }

    override fun index(classNode: ClassNode, methodNode: MethodNode) {
        // nothing to do here
    }

    private fun addToIndex(parentInternalName: String, subClassInternalName: String) {
        val parentName = parentInternalName.intId
        val subClassName = subClassInternalName.intId
        val subClasses = parentToSubClasses.getOrPut(parentName) {
            HashSet()
        }
        subClasses.add(subClassName)
    }

    private val String.intId: Int
        get() {
            val pureName = Type.getObjectType(this).className
            return GlobalIds.getId(pureName)
        }

    override fun build(location: ByteCodeLocation): HierarchyIndex {
        return HierarchyIndex(
            location = location,
            parentToSubClasses = parentToSubClasses.toImmutableMap()
        )
    }

}


class HierarchyIndex(
    override val location: ByteCodeLocation,
    internal val parentToSubClasses: Map<Int, Set<Int>>
) : ByteCodeLocationIndex<String> {

    override fun query(term: String): Sequence<String> {
        val parentClassId = GlobalIds.getId(term)
        return parentToSubClasses[parentClassId]?.map {
            GlobalIds.getName(it)
        }?.asSequence()?.filterNotNull() ?: emptySequence()
    }

}

object Hierarchy : Feature<String, HierarchyIndex> {

    override val key = "hierarchy"

    override fun newBuilder() = HierarchyIndexBuilder()

    override fun deserialize(location: ByteCodeLocation, stream: InputStream): HierarchyIndex {
        val reader = stream.reader()
        val result = hashMapOf<Int, Set<Int>>()
        reader.use {
            reader.forEachLine {
                val splitted = it.split("=")
                if (splitted.size == 2) {
                    val key = splitted[0].toInt()
                    val value = splitted[1].split(",").map { it.toInt() }.toSet()
                    result[key] = value
                }
            }
        }
        return HierarchyIndex(location, result)
    }

    override fun serialize(index: HierarchyIndex, out: OutputStream) {
        out.writer().use { writer ->
            index.parentToSubClasses.forEach {
                writer.write(
                    it.key.toString()
                            + "="
                            + it.value.joinToString(",") + "\n"
                )
            }
        }
    }

}