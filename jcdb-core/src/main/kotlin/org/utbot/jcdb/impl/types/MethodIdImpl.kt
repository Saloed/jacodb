package org.utbot.jcdb.impl.types

import org.objectweb.asm.tree.MethodNode
import org.utbot.jcdb.api.ClassId
import org.utbot.jcdb.api.MethodId
import org.utbot.jcdb.api.MethodResolution
import org.utbot.jcdb.api.throwClassNotFound
import org.utbot.jcdb.impl.ClassIdService
import org.utbot.jcdb.impl.signature.MethodSignature
import org.utbot.jcdb.impl.suspendableLazy
import org.utbot.jcdb.impl.tree.ClassNode

class MethodIdImpl(
    private val methodInfo: MethodInfo,
    private val classNode: ClassNode,
    override val classId: ClassId,
    private val classIdService: ClassIdService
) : MethodId {

    override val name: String get() = methodInfo.name
    override suspend fun access() = methodInfo.access

    private val lazyParameters = suspendableLazy {
        methodInfo.parameters.map {
            classIdService.toClassId(it) ?: it.throwClassNotFound()
        }
    }
    private val lazyAnnotations = suspendableLazy {
        methodInfo.annotations.map {
            AnnotationIdImpl(it, classIdService.cp)
        }
    }

    private val lazyParamsInfo = suspendableLazy {
        methodInfo.parametersInfo.map { MethodParameterIdImpl(it, classIdService.cp) }
    }

    override suspend fun resolution(): MethodResolution {
        return MethodSignature.of(methodInfo.signature, classId.classpath)
    }

    override suspend fun returnType() =
        classIdService.toClassId(methodInfo.returnClass) ?: methodInfo.returnClass.throwClassNotFound()

    override suspend fun parameters() = lazyParameters()

    override suspend fun parameterIds() = lazyParamsInfo()

    override suspend fun annotations() = lazyAnnotations()

    override suspend fun description() = methodInfo.desc

    override suspend fun signature(internalNames: Boolean) = methodInfo.signature(internalNames)

    override suspend fun readBody(): MethodNode? {
        val location = classId.location
        if (location?.isChanged() == true) {
            return null
        }
        return classNode.fullByteCode().methods.firstOrNull { it.name == name && it.desc == methodInfo.desc }
    }

    override fun equals(other: Any?): Boolean {
        if (other == null || other !is MethodIdImpl) {
            return false
        }
        return other.name == name && classId == other.classId && methodInfo.desc == other.methodInfo.desc
    }

    override fun hashCode(): Int {
        return 31 * classId.hashCode() + name.hashCode()
    }


}