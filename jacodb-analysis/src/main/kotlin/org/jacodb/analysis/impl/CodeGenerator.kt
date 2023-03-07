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

@file:OptIn(ExperimentalPathApi::class)

package org.jacodb.analysis.impl

import mu.KotlinLogging
import net.lingala.zip4j.ZipFile
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.Collections.min
import kotlin.io.path.*
import kotlin.random.Random

private val logger = KotlinLogging.logger { }

// region generation

class DeZipper(private val language: TargetLanguage, private val fullClear: Boolean) {
    private fun transferTemplateZipToTemp(): Path {
        val pathWhereToUnzipTemplate = Files.createTempFile(null, null)
        this.javaClass.classLoader.getResourceAsStream(language.projectZipInResourcesName())!!
            .use { templateFromResourceStream ->
                FileOutputStream(pathWhereToUnzipTemplate.toFile()).use { streamToLocationForTemplate ->
                    templateFromResourceStream.copyTo(streamToLocationForTemplate)
                }
            }
        return pathWhereToUnzipTemplate
    }

    fun dezip(pathToDirectoryWhereToUnzipTemplate: Path) {
        pathToDirectoryWhereToUnzipTemplate.createDirectories()
        if (fullClear) {
            pathToDirectoryWhereToUnzipTemplate.toFile().deleteRecursively()
            pathToDirectoryWhereToUnzipTemplate.createDirectories()
        }
        val templateZipAtTemp = transferTemplateZipToTemp()
        ZipFile(templateZipAtTemp.toFile()).extractAll(pathToDirectoryWhereToUnzipTemplate.absolutePathString())
    }
}

class AccessibilityCache(n: Int, private val graph: Map<Int, Set<Int>>) {
    private val used = Array(n) { 0 }
    private var currentWave = 0
    val badQuery = -1 to -1
    val badPath = listOf(-1)
    private var lastQuery = badQuery
    private var lastQueryPath = mutableListOf<Int>()

    private fun dfs(u: Int, target: Int): Boolean {
        used[u] = currentWave
        if (u == target) {
            lastQueryPath = mutableListOf(u)
            return true
        }

        for (v in graph.getOrDefault(u, emptySet())) {
            if (used[v] != currentWave && dfs(v, target)) {
                lastQueryPath.add(u)
                return true
            }
        }

        return false
    }

    fun isAccessible(u: Int, v: Int): Boolean {
        ++currentWave
        lastQuery = badQuery
        if (dfs(u, v)) {
            lastQueryPath.reverse()
            lastQuery = u to v
            return true
        }
        return false
    }


    fun getAccessPath(u: Int, v: Int): List<Int> {
        if (lastQuery == u to v || isAccessible(u, v))
            return lastQueryPath

        return badPath
    }
}

// endregion

// region common

const val impossibleGraphId = -1

// primary languages - java, cpp.
// secondary - python, go, js, kotlin, etc.

// language features that are not supported and (most probably) will never be
// 1. package-private/internal/sealed modifiers - as they are not present in all languages
// 2. cpp private inheritance, multiple inheritance - as this complicates code model way too much
// 3. cpp constructor initialization features - complicates ast too much

/**
 * Anything that can be dumped via [TargetLanguage]
 */
interface CodeElement

/**
 * Universal visibility that is present in most of the languages.
 */
enum class VisibilityModifier {
    PUBLIC,
    PRIVATE,
    // TODO PROTECTED - as it is relevant only when we will have inheritance
}

/**
 * Both tag for inheritance and type form
 */
enum class InheritanceModifier {
    ABSTRACT,
    OPEN,
    FINAL,
    INTERFACE,
    STATIC,
    ENUM
}

/**
 * Entity that can be located
 */
interface NameOwner : CodeElement {
    val shortName: String
    val fqnName: String
        get() = shortName
}

interface VisibilityOwner : CodeElement {
    val visibility: VisibilityModifier
}

interface Inheritable : CodeElement {
    val inheritanceModifier: InheritanceModifier
    val inheritedFrom: Inheritable?
}

/**
 * Anything that resides in type
 */
interface TypePart : CodeElement {
    val containingType: TypePresentation
}

/**
 * Anything that resides in type and can be located
 */
interface NamedTypePart : NameOwner, TypePart {
    override val fqnName: String
        get() = containingType.fqnName + "." + shortName
}

interface CodePresentation : CodeElement

interface CodeExpression : CodeElement

interface AssignmentExpression: CodeExpression {
    val assignmentValue: CodeValue
    val assignmentTarget: CodeValue
}

interface CodeValue : CodeElement {
    val evaluatedType: TypeUsage
}

interface ValueReference : NameOwner, CodeValue {
    fun resolve(): ValuePresentation
}

interface FieldReference : ValueReference {
    val qualifier: CodeValue
}

interface ValueExpression : CodeExpression, CodeValue
// endregion

// region type

// we will differ static class and instance class like in kotlin.
// for Java and CPP this means that in case of static elements we will create static duplicate
/**
 * Class, interface, abstract class, structure, enum.
 */
interface TypePresentation : CodePresentation, VisibilityOwner, NameOwner, Inheritable {
    companion object {
        val voidType: TypePresentation = TypeImpl("void")
    }

    // most of the time this is ObjectCreationExpression - for open and final classes
    // in case of abstract, static, interface and enum - this would throw exception
    val defaultValue: CodeValue

    // if created class is not abstract - we will find all methods that do not have implementation
    // and provide default overload for it. Default means completely empty body, same parameters and return type.
    val typeParts: Collection<TypePart>

    // there are no way to add interface as we expect:
    // 1. all interface hierarchy will be predefined beforehand - in graph
    // 2. all class hierarchy also will be predefined beforehand - as tree
    // 3. we will know about abstract class to interface mappings
    // 4. we will topologically sort that class-interface graph and generate hierarchy accordingly
    val implementedInterfaces: Collection<TypePresentation>

    // as we know class-interface hierarchy - we do not need to redefine who we inherited from
    override val inheritedFrom: TypePresentation?

    val defaultConstructor: ConstructorPresentation
    val staticCounterPart: TypePresentation
    val instanceType: InstanceTypeUsage

    fun overrideMethod(methodToOverride: MethodPresentation): MethodPresentation
    fun createMethod(
        graphId: Int,
        name: String = "methodFor$graphId",
        visibility: VisibilityModifier = VisibilityModifier.PUBLIC,
        returnType: TypeUsage = voidType.instanceType,
        inheritanceModifier: InheritanceModifier = InheritanceModifier.FINAL,
        parameters: List<Pair<TypeUsage, String>> = emptyList(),
    ): MethodPresentation

    fun createConstructor(
        graphId: Int,
        visibility: VisibilityModifier = VisibilityModifier.PUBLIC,
        parentConstructorCall: ObjectCreationExpression? = null,
        parameters: List<Pair<TypeUsage, String>> = emptyList()
    ): ConstructorPresentation

    fun createField(name: String, type: TypePresentation, initialValue: CodeValue? = null): FieldPresentation

    val implementedMethods: Collection<MethodPresentation>
        get() = typeParts.filterIsInstance<MethodPresentation>()
    val allAvailableMethods: Collection<MethodPresentation>
        get() = (implementedInterfaces.flatMap { it.allAvailableMethods })
            .union(inheritedFrom?.allAvailableMethods ?: mutableListOf())
            .union(implementedMethods)

    val constructors: Collection<ConstructorPresentation>
        get() = typeParts.filterIsInstance<ConstructorPresentation>()

    val implementedFields: Collection<FieldPresentation>
        get() = typeParts.filterIsInstance<FieldPresentation>()
    val allAvailableFields: Collection<FieldPresentation>
        get() = implementedFields.union(inheritedFrom?.allAvailableFields ?: emptyList())

    fun getImplementedField(name: String): FieldPresentation? = implementedFields.singleOrNull { it.shortName == name }
    fun getField(name: String): FieldPresentation? = allAvailableFields.singleOrNull { it.shortName == name }
    fun getImplementedFields(type: TypeUsage): Collection<FieldPresentation> =
        implementedFields.filter { it.usage == type }

    fun getFields(type: TypeUsage): Collection<FieldPresentation> = allAvailableFields.filter { it.usage == type }

    fun getImplementedMethods(name: String): Collection<MethodPresentation> =
        implementedMethods.filter { it.shortName == name }

    fun getMethods(name: String): Collection<MethodPresentation> = allAvailableMethods.filter { it.shortName == name }
}

//endregion

// region functions

/**
 * Anything that can be called. Parent for functions, methods, lambdas, constructors, destructors etc.
 */
interface CallablePresentation : CodePresentation {
    val signature: String
        get() = parameters.joinToString { it.usage.stringPresentation }

    // consists from parameters and local variables
    val visibleLocals: Collection<CallableLocal>
    val returnType: TypeUsage

    // should be aware of local variables
    fun createParameter(name: String, type: TypeUsage): ParameterPresentation

    // should be aware of parameters
    fun createLocalVariable(
        name: String,
        type: TypeUsage,
        initialValue: CodeValue? = null
    ): LocalVariablePresentation

    val preparationSite: Site
    /**
     * Each site represent different way to execute this callable
     */
    val callSites: Collection<CallSite>
    fun createCallSite(callee: CallablePresentation, invokedOn: CodeValue? = null): CallSite
    val terminationSite: TerminationSite

    val graphId: Int

    val parameters: Collection<ParameterPresentation>
        get() = visibleLocals.filterIsInstance<ParameterPresentation>()
    val localVariables: Collection<LocalVariablePresentation>
        get() = visibleLocals.filterIsInstance<LocalVariablePresentation>()

    fun getLocal(name: String) = visibleLocals.singleOrNull { it.shortName == name }
    fun getLocals(type: TypeUsage) = visibleLocals.filter { it.usage == type }

    fun getLocalVariable(name: String) = localVariables.singleOrNull { it.shortName == name }
    fun getLocalVariables(type: TypeUsage) = localVariables.filter { it.usage == type }
    fun getOrCreateLocalVariable(
        name: String,
        type: TypeUsage,
        initialValue: CodeValue? = null
    ) = getLocalVariable(name) ?: createLocalVariable(name, type, initialValue)

    fun getParameter(name: String) = parameters.singleOrNull { it.shortName == name }
    fun getParameters(type: TypeUsage) = parameters.filter { it.usage == type }
    fun getOrCreateParameter(name: String, type: TypeUsage) =
        getParameter(name) ?: createParameter(name, type)

    fun getCallSite(callee: CallablePresentation, invokedOn: CodeValue? = null): CallSite? =
        callSites.singleOrNull {
            it.invocationExpression.invokedOn == invokedOn && it.invocationExpression.invokedCallable == callee
        }

    fun getOrCreateCallSite(callee: CallablePresentation, invokedOn: CodeValue? = null): CallSite =
        getCallSite(callee, invokedOn) ?: createCallSite(callee, invokedOn)
}

interface ConstructorPresentation : CallablePresentation, VisibilityOwner, TypePart {
    val parentConstructorCall: ObjectCreationExpression?
    override val returnType: TypeUsage
        get() = containingType.instanceType
}

/**
 * Any named functions. Global, static functions and methods.
 */
interface FunctionPresentation : CallablePresentation, VisibilityOwner, NameOwner {
    override val fqnName: String
        get() = shortName
}

interface MethodPresentation : FunctionPresentation, NamedTypePart, Inheritable {
    override val inheritedFrom: MethodPresentation?
    override val fqnName: String
        get() = super<NamedTypePart>.fqnName
}

// endregion

// region sites

/**
 * Some code block in execution path in single function.
 * Any callable instance is list of sites.
 * In any execution path each function
 */
interface Site : CodeElement {
    val parentCallable: CallablePresentation
    val expressionsBefore: Collection<CodeExpression>
    val expressionsAfter: Collection<CodeExpression>
    fun addBefore(expression: CodeExpression)
    fun addAfter(expression: CodeExpression)
}

interface PreparationSite: Site

/**
 * Represents call and all preparation for this call
 */
interface CallSite : Site {
    val graphId: Int
    val invocationExpression: InvocationExpression
}

/**
 * End of any call sequence.
 */
interface TerminationSite : Site {
    val dereferences: Collection<CodeValue>
    fun addDereference(reference: CodeValue)
}

// endregion

// region code expressions

interface ReturnExpression : CodeExpression {
    val toReturn: CodeValue
}

/**
 * Expression that have arguments. Each argument should specify for which parameter it is used for.
 * If some parameters are not matched - default values of types will be used.
 */
interface ArgumentsOwnerExpression : ValueExpression {
    val parameterToArgument: Map<ParameterPresentation, CodeValue>
    fun addInCall(parameter: ParameterPresentation, argument: CodeValue)
}

interface InvocationExpression : ArgumentsOwnerExpression {
    val invokedCallable: CallablePresentation
    val invokedOn: CodeValue?

    override val evaluatedType: TypeUsage
        get() = invokedCallable.returnType
}

interface FunctionInvocationExpression : InvocationExpression {
    override val invokedOn: CodeValue?
        get() = null
    override val invokedCallable: FunctionPresentation
}

interface MethodInvocationExpression : InvocationExpression {
    val invokedMethod: MethodPresentation
    override val invokedOn: CodeValue

    override val invokedCallable: MethodPresentation
        get() = invokedMethod
}

interface ObjectCreationExpression : InvocationExpression {
    val invokedConstructor: ConstructorPresentation

    override val invokedCallable: ConstructorPresentation
        get() = invokedConstructor
    override val invokedOn: CodeValue?
        get() = null
}

/**
 * Hack if AST is not sufficient, proposing [TargetLanguage] writing [substitution] string directly.
 * Create anonymous object of this interface and mock anything you need to.
 * [TargetLanguage] will use [substitution] for generating code.
 */
interface DirectStringSubstitution : CodeValue {
    val substitution: String
}
// endregion

// region TypeUsage

interface TypeUsage : CodeElement {
    val stringPresentation: String

    fun wrapInArray(): TypeUsage

    val isNullable: Boolean
    fun flipNullability(): TypeUsage
}

interface ArrayTypeUsage : TypeUsage {
    val element: TypeUsage
    fun furthestElementType(): TypeUsage
}

interface InstanceTypeUsage : TypeUsage {
    val typePresentation: TypePresentation
}
// endregion

// region values
/**
 * Anything that can be assigned or invoked.
 */
interface ValuePresentation : CodePresentation, NameOwner {
    val usage: TypeUsage
}

/**
 * Named entities of callable. For now we require all locals to be unique.
 */
interface CallableLocal : ValuePresentation {
    val parentCallable: CallablePresentation
    val reference: ValueReference
}

interface ParameterPresentation : CallableLocal {
    val indexInSignature: Int
}

interface InitializerOwner {
    val initialValue: CodeValue?
}

interface FieldPresentation : TypePart, ValuePresentation, InitializerOwner, Inheritable {
    // todo assert that field is accessible in code value type
    fun createReference(codeValue: CodeValue): FieldReference
}

interface LocalVariablePresentation : CallableLocal, InitializerOwner
// endregion

// region typeImpl

open class TypeImpl(
    final override val shortName: String,
    defaultConstructorGraphId: Int = impossibleGraphId,
    final override val visibility: VisibilityModifier = VisibilityModifier.PUBLIC,
    final override val inheritanceModifier: InheritanceModifier = InheritanceModifier.FINAL,
    interfaces: List<TypePresentation> = emptyList(),
    constructorVisibilityModifier: VisibilityModifier = VisibilityModifier.PUBLIC,
    defaultConstructorParameters: List<Pair<TypeUsage, String>> = emptyList(),
    parentConstructorCall: ObjectCreationExpression? = null,
    final override val inheritedFrom: TypePresentation? = null,
) : TypePresentation {
    final override val implementedInterfaces = interfaces.toSet()

    init {
        // interfaces have separate inheritance mechanism
        // enum and static classes cant inherited classes
        if (inheritanceModifier == InheritanceModifier.INTERFACE ||
            inheritanceModifier == InheritanceModifier.ENUM ||
            inheritanceModifier == InheritanceModifier.STATIC
        ) {
            assert(inheritedFrom == null)
        }

        if (inheritedFrom != null) {
            // type can be inherited only from abstract or open
            assert(
                inheritedFrom.inheritanceModifier == InheritanceModifier.ABSTRACT ||
                        inheritedFrom.inheritanceModifier == InheritanceModifier.OPEN
            )
            // if type is inherited - it can only be abstract, open or final
            assert(
                inheritanceModifier == InheritanceModifier.ABSTRACT ||
                        inheritanceModifier == InheritanceModifier.OPEN ||
                        inheritanceModifier == InheritanceModifier.FINAL
            )
        }
    }

    override val instanceType: InstanceTypeUsage by lazy { InstanceTypeImpl(this, false) }
    override val staticCounterPart: TypePresentation by lazy { StaticCounterPartTypeImpl(this) }
    override val defaultConstructor: ConstructorPresentation by lazy {
        createConstructor(
            defaultConstructorGraphId,
            constructorVisibilityModifier,
            parentConstructorCall,
            defaultConstructorParameters
        )
    }
    override val defaultValue: ObjectCreationExpression by lazy { ObjectCreationExpressionImpl(defaultConstructor) }

    override fun equals(other: Any?): Boolean {
        if (other == null || other !is TypeImpl)
            return false

        // different types should have different fqn names
        assert(this === other || this.fqnName != other.fqnName || this.staticCounterPart === other)

        return other === this
    }

    override fun hashCode(): Int {
        // type is uniquely identified by its fqn name
        return fqnName.hashCode()
    }

    final override val typeParts = mutableListOf<TypePart>()

    // this is the hardest method as it should check
    // 1. method really exists and relates to this type hierarchy
    // 2. not already overridden
    // 3. no collision with already defined methods
    // 4. signature compatability
    // 5. name compatability
    // 6. return type compatability
    // 7. available for override
    // 8. no final override in between 2 types
    /**
     * @param methodToOverride method available for override. Should be defined in type parents
     * @throws MethodAlreadyOverridenException if [methodToOverride] is already overriden in this class
     * @throws MethodNotFoundException if [methodToOverride] is not defined in any of parent types
     * @throws MethodCollisionException if [methodToOverride] collides with some other method already defined method in this type hierarchy
     */
    override fun overrideMethod(methodToOverride: MethodPresentation): MethodPresentation {
        // 1. check not overridden
        // 2. check no same
        // 3. check no any other conflict
        TODO("Not yet implemented")
    }

    // throws if fqn + parameters already presented in available methods
    // this method intentionally does not allow overriding, use another method for it
    /**
     * Creates method from scratch. This method throws if proposed method already available in hierarchy.
     * Particularly, it throws if you try to implicitly override another method.
     * For overriding use [overrideMethod].
     */
    override fun createMethod(
        graphId: Int,
        name: String,
        visibility: VisibilityModifier,
        returnType: TypeUsage,
        inheritanceModifier: InheritanceModifier,
        parameters: List<Pair<TypeUsage, String>>,
    ): MethodPresentation {
        val methodToAdd = MethodImpl(graphId, this, name, visibility, returnType, inheritanceModifier, null, parameters)
        val collidedMethods = getMethods(name).filter { it.signature == methodToAdd.signature }

        if (collidedMethods.isEmpty()) {
            typeParts.add(methodToAdd)
            return methodToAdd
        } else {
            throw IllegalStateException("Method with the same signature already present: ${collidedMethods.size}")
        }
    }

    // throws if signature already present
    // or
    // parent call is incorrect - not provided or incorrect parent constructor targeted
    /**
     * Creates constructor for this type
     * @throws ConstructorCollisionException proposed constructor is already declared in this type
     * @throws NoParentCallException this type requires parent initialization, provide which parent constructor to call
     */
    override fun createConstructor(
        graphId: Int,
        visibility: VisibilityModifier,
        parentConstructorCall: ObjectCreationExpression?,
        parameters: List<Pair<TypeUsage, String>>
    ): ConstructorPresentation {
        val constructorToAdd = ConstructorImpl(graphId, this, visibility, parentConstructorCall, parameters)
        // 3. assert constructor is created with parent call in case of need
        val collidedConstructors = typeParts
            .filterIsInstance<ConstructorPresentation>()
            .filter { it.signature == constructorToAdd.signature }

        if (collidedConstructors.isEmpty()) {
            typeParts.add(constructorToAdd)
            return constructorToAdd
        } else {
            throw IllegalStateException("Constructors with same signature already present: ${collidedConstructors.size}")
        }
    }

    // throws if name already present in hierachy
    /**
     * Creates field in this type
     * @throws FieldCollisionException proposed field is already declared in this type hierarchy
     */
    override fun createField(
        name: String,
        type: TypePresentation,
        initialValue: CodeValue?
    ): FieldPresentation {
        TODO("Not yet implemented")
    }
}

class StaticCounterPartTypeImpl(typeImpl: TypeImpl) : TypeImpl(typeImpl.shortName), TypePresentation {
    override val staticCounterPart: TypePresentation = typeImpl

    override fun overrideMethod(methodToOverride: MethodPresentation): MethodPresentation {
        throw IllegalStateException("Methods in static counterparts cannot be overridden")
    }

    override fun createConstructor(
        graphId: Int,
        visibility: VisibilityModifier,
        parentConstructorCall: ObjectCreationExpression?,
        parameters: List<Pair<TypeUsage, String>>
    ): ConstructorPresentation {
        throw IllegalStateException("Constructors in static counterparts cannot be created")
    }

    override val defaultValue
        // todo class<type>?
        get() = throw IllegalStateException("static types dont have default value")

    override val defaultConstructor: ConstructorPresentation
        // todo static initializer?
        get() = throw IllegalStateException("static types cannot be instantiated")

    override val instanceType: InstanceTypeUsage
        // todo Class<Type>
        get() = throw IllegalStateException("static types cannot be referenced")
}

// endregion

// region sites_impl

abstract class SiteImpl : Site {
    override val expressionsAfter = mutableListOf<CodeExpression>()
    override val expressionsBefore = mutableListOf<CodeExpression>()

    override fun addBefore(expression: CodeExpression) {
        expressionsBefore.add(expression)
    }

    override fun addAfter(expression: CodeExpression) {
        expressionsAfter.add(expression)
    }
}

class PreparationSiteImpl(override val parentCallable: CallablePresentation) : SiteImpl(), PreparationSite

class CallSiteImpl(
    // unique identifier with the function
    override val graphId: Int,
    override val parentCallable: CallablePresentation,
    override val invocationExpression: InvocationExpression
) : SiteImpl(), CallSite {
    override fun equals(other: Any?): Boolean {
        if (other !is CallSite) {
            return false
        }

        if (parentCallable == other.parentCallable && graphId == other.graphId) {
            assert(other === this)
        }

        return other === this
    }

    override fun hashCode(): Int {
        return parentCallable.hashCode() * 31 + graphId
    }
}

class TerminationSiteImpl(override val parentCallable: CallablePresentation) : SiteImpl(), TerminationSite {
    override val dereferences = mutableListOf<CodeValue>()

    override fun addDereference(reference: CodeValue) {
        // you can add multiple dereferences on the same reference
        dereferences.add(reference)
    }
}

// endregion

// region callables_impl

abstract class CallableImpl(
    override val graphId: Int,
    override val returnType: TypeUsage,
    parameters: List<Pair<TypeUsage, String>>
) : CallablePresentation {

    override val callSites = mutableListOf<CallSite>()

    override fun hashCode(): Int {
        return graphId.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is CallableImpl)
            return false

        if (graphId == other.graphId) {
            // we uniquely identify callable instance by theirs vertices in graph
            assert(this === other)
        }

        return this === other
    }

    override val visibleLocals = parameters
        .mapIndexed { index, it -> ParameterImpl(it.first, it.second, this, index) }
        .toMutableList<CallableLocal>()

    override fun createLocalVariable(
        name: String,
        type: TypeUsage,
        initialValue: CodeValue?
    ): LocalVariablePresentation {
        assert(getLocal(name) == null) { "Already have local entity with name $name" }
        return LocalVariableImpl(type, name, initialValue, this).also { visibleLocals.add(it) }
    }

    override fun createParameter(name: String, type: TypeUsage): ParameterPresentation {
        assert(getLocal(name) == null) { "Already have local entity with name $name" }
        return ParameterImpl(type, name, this, parameters.size).also { visibleLocals.add(it) }
    }

    override fun createCallSite(
        callee: CallablePresentation,
        invokedOn: CodeValue?
    ): CallSite {
        assert(!callSites.any { it.graphId == callee.graphId }) { "already contains call-site for to such method" }

        val invocationExpression: InvocationExpression = if (invokedOn != null && callee is MethodPresentation) {
            MethodInvocationExpressionImpl(callee, invokedOn)
        } else if (invokedOn == null && callee is FunctionPresentation) {
            FunctionInvocationExpressionImpl(callee)
        } else if (invokedOn == null && callee is ConstructorPresentation) {
            ObjectCreationExpressionImpl(callee)
        } else {
            throw Exception("unknown call site creation")
        }

        return CallSiteImpl(callee.graphId, this, invocationExpression).also { callSites.add(it) }
    }

    override val terminationSite: TerminationSite by lazy { TerminationSiteImpl(this) }
    override val preparationSite: Site by lazy { PreparationSiteImpl(this) }
}

class ConstructorImpl(
    graphId: Int,
    override val containingType: TypePresentation,
    override val visibility: VisibilityModifier,
    override val parentConstructorCall: ObjectCreationExpression?,
    parameters: List<Pair<TypeUsage, String>>
) : CallableImpl(graphId, containingType.instanceType, parameters), ConstructorPresentation {
    override val returnType: TypeUsage
        get() = super<CallableImpl>.returnType

    override fun equals(other: Any?): Boolean {
        if (other !is ConstructorImpl) {
            return false
        }

        if (other.containingType != containingType) {
            return false
        }

        if (!super.equals(other)) {
            // we prohibit different constructors with the same signature
            assert(signature != other.signature)
            return false
        }

        return true
    }
}

open class FunctionImpl(
    graphId: Int,
    override val shortName: String = "functionFor$graphId",
    override val visibility: VisibilityModifier = VisibilityModifier.PUBLIC,
    returnType: TypeUsage = TypePresentation.voidType.instanceType,
    parameters: List<Pair<TypeUsage, String>> = emptyList()
) : CallableImpl(graphId, returnType, parameters), FunctionPresentation {
    override fun equals(other: Any?): Boolean {
        if (other !is FunctionImpl)
            return false

        if (graphId == other.graphId) {
            assert(this === other)
        } else {
            // all functions(including methods) should have unique <fqn, signatures>
            // and so 2 functions should not have same fqn and signature simultaneously
            // todo or one should override another if this is method
            assert(fqnName != other.fqnName || signature != other.signature)
        }

        return this === other
    }
}

class MethodImpl(
    graphId: Int,
    override val containingType: TypePresentation,
    name: String,
    visibility: VisibilityModifier,
    returnType: TypeUsage,
    override val inheritanceModifier: InheritanceModifier,
    override val inheritedFrom: MethodImpl?,
    parameters: List<Pair<TypeUsage, String>>
) : FunctionImpl(graphId, name, visibility, returnType, parameters), MethodPresentation

// endregion

// region expressions_impl

class AssignmentExpressionImpl(override val assignmentTarget: CodeValue, override val assignmentValue: CodeValue) : AssignmentExpression

abstract class ArgumentsOwnerExpressionImpl : ArgumentsOwnerExpression {
    override val parameterToArgument = hashMapOf<ParameterPresentation, CodeValue>()
    override fun addInCall(parameter: ParameterPresentation, argument: CodeValue) {
        assert(!parameterToArgument.contains(parameter)) { "redeclaration of parameter value, do not do it!" }
        // todo assert types correlate
        parameterToArgument[parameter] = argument
    }
}

class FunctionInvocationExpressionImpl(
    override val invokedCallable: FunctionPresentation
) : ArgumentsOwnerExpressionImpl(), FunctionInvocationExpression

class MethodInvocationExpressionImpl(
    override val invokedMethod: MethodPresentation,
    override val invokedOn: CodeValue
) : ArgumentsOwnerExpressionImpl(), MethodInvocationExpression

class ObjectCreationExpressionImpl(override val invokedConstructor: ConstructorPresentation) :
    ArgumentsOwnerExpressionImpl(), ObjectCreationExpression

// endregion

// region values_impl

abstract class FunctionLocalImpl : CallableLocal {
    override val reference: ValueReference by lazy { SimpleValueReference(this) }

    override fun hashCode(): Int {
        return parentCallable.hashCode() * 31 + shortName.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is CallableLocal || parentCallable != other.parentCallable) {
            // there are no constraints on locals that refer to different callables
            return false
        }

        if (other !== this) {
            // locals in the same callable should have unique names
            assert(shortName != other.shortName)
        }

        return this === other
    }

    override val fqnName: String
        get() = (parentCallable as? NameOwner)?.fqnName?.let { "$it." } + shortName
}

class LocalVariableImpl(
    override val usage: TypeUsage,
    // currently we prohibit shadowing local variables,
    // it means that local variables and parameters can be identified by its name and parent function
    override val shortName: String,
    // todo assert initial value type is assignable to type
    override val initialValue: CodeValue?,
    override val parentCallable: CallablePresentation
) : FunctionLocalImpl(), LocalVariablePresentation

class ParameterImpl(
    override val usage: TypeUsage,
    override val shortName: String,
    // invariant - two parameters relates to the same function if they point to the same function
    // currently we prohibit shadowing local variables,
    // it means that local variables and parameters can be identified by its name and parent function
    override val parentCallable: CallablePresentation,
    // just for correct code generation
    override val indexInSignature: Int
) : FunctionLocalImpl(), ParameterPresentation {
    override fun equals(other: Any?): Boolean {
        assert(
            // here we check that if this is 2 different parameters that refers to the same function --
            this === other
                    || other !is ParameterPresentation
                    || parentCallable != other.parentCallable
                    // -- they stay on different indices
                    || indexInSignature != other.indexInSignature
        )
        // and here we check their names
        return super.equals(other)
    }
}

class SimpleValueReference(private val presentation: ValuePresentation) : ValueReference, NameOwner by presentation {
    override fun resolve(): ValuePresentation = presentation
    override val evaluatedType: TypeUsage
        get() = presentation.usage
}

// endregion

// region resolvedTypeImpl

abstract class TypeUsageImpl : TypeUsage {
    override fun wrapInArray(): TypeUsage {
        return ArrayTypeUsageImpl(this, false)
    }
}

class ArrayTypeUsageImpl(override val element: TypeUsage, override val isNullable: Boolean) :
    TypeUsageImpl(), ArrayTypeUsage {

    override val stringPresentation: String = element.stringPresentation + "[]"

    override fun furthestElementType(): TypeUsage {
        if (element is ArrayTypeUsage)
            return element.furthestElementType()
        return element
    }

    override fun flipNullability(): TypeUsage {
        // todo flip inner element nullability?
        return ArrayTypeUsageImpl(element, !isNullable)
    }
}

class InstanceTypeImpl(override val typePresentation: TypePresentation, override val isNullable: Boolean) :
    TypeUsageImpl(), InstanceTypeUsage {

    override val stringPresentation: String
        get() = typePresentation.shortName

    override fun flipNullability(): TypeUsage {
        return InstanceTypeImpl(typePresentation, !isNullable)
    }
}

// endregion

class CodeRepresentation(private val graph: Map<Int, Set<Int>>, private val language: TargetLanguage) : CodeElement {
    private val functions = mutableMapOf<Int, FunctionPresentation>()
    private var startFunctionIdCounter = startFunctionFirstId
    private val startFunctionToGenericId = mutableMapOf<String, Int>()
    private val generatedTypes = mutableMapOf<String, TypePresentation>()
    private val dispatchedCallables = mutableSetOf<Int>()

    fun createDispatch(callable: CallablePresentation) {
        if (dispatchedCallables.add(callable.graphId)) {
            language.dispatch(callable)
        }
    }

    fun getOrCreateFunctionFor(v: Int): FunctionPresentation {
        return functions.getOrPut(v) { FunctionImpl(v) }
    }

    fun getOrCreateStartFunction(name: String): FunctionPresentation {
        val startFunctionId = startFunctionToGenericId.getOrPut(name) { startFunctionIdCounter++ }

        return getOrCreateFunctionFor(startFunctionId)
    }

    fun getOrCreateType(name: String): TypePresentation {
        val generated = generatedTypes[name]
        val predefined = language.getPredefinedType(name)

        assert(generated == null || predefined == null) { "type with $name is generated and predefined simultaneously" }

        return generated ?: predefined ?: generatedTypes.getOrPut(name) { TypeImpl(name.capitalize()) }
    }

    fun getPredefinedType(name: String): TypePresentation? {
        return language.getPredefinedType(name)
    }

    fun getPredefinedPrimitive(primitive: TargetLanguage.PredefinedPrimitives): TypePresentation? {
        return language.getPredefinedPrimitive(primitive)
    }

    fun dumpTo(projectPath: Path) {
        val pathToSourcesDir = language.resolveProjectPathToSources(projectPath)
        for ((name, presentation) in generatedTypes) {
            language.dumpType(presentation, pathToSourcesDir)
        }

        for ((id, function) in functions) {
            if (id < startFunctionFirstId)
                language.dumpFunction(function, pathToSourcesDir)
        }

        for ((name, id) in startFunctionToGenericId) {
            val function = functions.getValue(id)
            language.dumpStartFunction(name, function, pathToSourcesDir)
        }
    }

    companion object {
        private const val startFunctionFirstId = Int.MAX_VALUE / 2
    }
}

interface TargetLanguage {
    enum class PredefinedPrimitives {
        INT,
        VOID
    }

    fun projectZipInResourcesName(): String
    fun resolveProjectPathToSources(projectPath: Path): Path
    fun getPredefinedType(name: String): TypePresentation?
    fun getPredefinedPrimitive(primitive: PredefinedPrimitives): TypePresentation?
    fun dumpType(type: TypePresentation, pathToSourcesDir: Path)
    fun dumpFunction(func: FunctionPresentation, pathToSourcesDir: Path)
    fun dumpStartFunction(name: String, func: FunctionPresentation, pathToSourcesDir: Path)
    fun dispatch(callable: CallablePresentation)
}

private val dispatcherQueueName = "dispatchQueue"
private val currentDispatch = "currentDispatch"

class JavaLanguage : TargetLanguage {
    private val realPrimitivesName = mutableMapOf<TargetLanguage.PredefinedPrimitives, String>()
    private val predefinedTypes = mutableMapOf<String, TypePresentation>()
    private val integer: TypePresentation
    private val arrayDeque: TypePresentation

    init {
        realPrimitivesName[TargetLanguage.PredefinedPrimitives.VOID] = TypePresentation.voidType.shortName
        predefinedTypes[TypePresentation.voidType.shortName] = TypePresentation.voidType

        integer = TypeImpl("Integer")
        realPrimitivesName[TargetLanguage.PredefinedPrimitives.INT] = integer.shortName
        predefinedTypes[integer.shortName] = integer

        // type argument cannot be primitive
        arrayDeque = TypeImpl("ArrayDeque<Integer>")
        arrayDeque.createMethod(impossibleGraphId, name = "add", parameters = listOf(integer.instanceType to "e"))
        arrayDeque.createMethod(impossibleGraphId, name = "remove", returnType = integer.instanceType)
        predefinedTypes[arrayDeque.shortName] = arrayDeque
    }

    override fun dispatch(callable: CallablePresentation) {
        val integerType = getPredefinedPrimitive(TargetLanguage.PredefinedPrimitives.INT)!!
        val integerUsage = integerType.instanceType
        val current = callable.getOrCreateLocalVariable(currentDispatch, integerUsage, object : DirectStringSubstitution {
            override val substitution: String = "-1"
            override val evaluatedType: TypeUsage = integerUsage
        })
        val dispatcherQueue = callable.getLocal(dispatcherQueueName)!!
        val dispatcherReference = dispatcherQueue.reference
        val removeMethod = arrayDeque.getMethods("remove").single()
        val methodInvocation = MethodInvocationExpressionImpl(removeMethod, dispatcherReference)
        val assignment = AssignmentExpressionImpl(current.reference, methodInvocation)
        callable.preparationSite.addAfter(assignment)
    }

    override fun resolveProjectPathToSources(projectPath: Path): Path {
        val relativeJavaSourceSetPath = "UtBotTemplateForIfdsSyntheticTests\\src\\main\\java\\org\\utbot\\ifds\\synthetic\\tests"

        return projectPath.resolve(relativeJavaSourceSetPath.replace('\\', File.separatorChar))
    }

    override fun projectZipInResourcesName(): String {
        return "UtBotTemplateForIfdsSyntheticTests.zip"
    }

    override fun getPredefinedType(name: String): TypePresentation? {
        return predefinedTypes[name]
    }

    override fun getPredefinedPrimitive(primitive: TargetLanguage.PredefinedPrimitives): TypePresentation? {
        return predefinedTypes[realPrimitivesName[primitive]]
    }

    private var fileWriter: OutputStreamWriter? = null

    private fun inFile(fileName: String, pathToSourcesDir: Path, block: () -> Unit) {
        val javaFilePath = pathToSourcesDir.resolve("${fileName.capitalize()}.java")
        try {
            javaFilePath.outputStream().use {
                fileWriter = OutputStreamWriter(BufferedOutputStream(it))
                appendLine { write("package org.utbot.ifds.synthetic.tests") }
                // for simplification - all generated code will be in a single package
                // with all required imports added unconditionally to all files.
                appendLine { write("import java.util.*") }
                write("\n")
                block()
                flush()
            }
        } finally {
            fileWriter = null
        }
    }

    private fun flush() {
        fileWriter!!.flush()
    }

    private fun write(content: String) {
        fileWriter!!.write(content)
    }

    private fun writeSeparated(content: String) {
        write(content)
        write(" ")
    }

    // write - just writes
    // append - handles with tabulation and necessary semicolons
    // dump - accept code element and creates text file with its representation

    private fun writeVisibility(visibility: VisibilityModifier) {
        writeSeparated(visibility.toString().lowercase())
    }

    private fun writeTypeSignature(type: TypePresentation) {
        writeVisibility(type.visibility)
        when (type.inheritanceModifier) {
            InheritanceModifier.ABSTRACT -> writeSeparated("abstract class")
            InheritanceModifier.OPEN -> writeSeparated("class")
            InheritanceModifier.FINAL -> writeSeparated("final class")
            InheritanceModifier.INTERFACE -> writeSeparated("interface")
            InheritanceModifier.STATIC -> writeSeparated("static class")
            InheritanceModifier.ENUM -> writeSeparated("enum")
        }
        writeSeparated(type.shortName)
    }

    private var offset = 0

    private fun addTab() {
        ++offset
    }

    private fun removeTab() {
        --offset
    }

    private fun tabulate() {
        for (i in 0 until offset) {
            write("\t")
        }
    }

    private fun throwCannotDump(ce: CodeElement) {
        assert(false) { "Do not know how to dump ${ce.javaClass.simpleName}" }
    }

    private fun inScope(block: () -> Unit) {
        try {
            write("{\n")
            addTab()
            // do not tabulate here.
            // each code element should be aware if it has to be tabulated and manage tabulation on its own
            block()
        } finally {
            write("\n")
            removeTab()
            tabulate()
            write("}\n")
        }
    }

    private fun writeParametersList(callable: CallablePresentation) {
        write("(")
        var first = true
        for (parameter in callable.parameters) {
            if (!first) {
                write(", ")
            }
            first = false
            writeTypeUsage(parameter.usage)
            write(parameter.shortName)
        }
        writeSeparated(")")
    }

    private fun appendField(field: FieldPresentation) {
        // todo
    }

    private fun writeTypeUsage(typeUsage: TypeUsage) {
        writeSeparated(typeUsage.stringPresentation)
    }

    private fun writeDefaultValueForTypeUsage(typeUsage: TypeUsage) {
        when (typeUsage) {
            is ArrayTypeUsage -> {
                throwCannotDump(typeUsage)
            }
            is InstanceTypeUsage -> {
                val presentation = typeUsage.typePresentation
                val valueToWrite = presentation.defaultValue
                writeCodeValue(valueToWrite)
            }
            else -> {
                throwCannotDump(typeUsage)
            }
        }
    }

    private fun appendCodeExpression(codeExpression: CodeExpression) = appendLine { writeCodeExpression(codeExpression) }

    private fun writeCodeExpression(codeExpression: CodeExpression) {
        when (codeExpression) {
            is ValueExpression -> writeValueExpression(codeExpression)
            is AssignmentExpression -> {
                writeCodeValue(codeExpression.assignmentTarget)
                write(" = ")
                writeCodeValue(codeExpression.assignmentValue)
            }
            // todo returnStatement
            else -> {
                throwCannotDump(codeExpression)
            }
            // 1. localVariable presentation
            // 2. return statement - expression
        }
    }

    private fun appendCodeValue(codeValue: CodeValue) = appendLine { writeCodeValue(codeValue) }

    private fun writeCodeValue(codeValue: CodeValue) {
        when (codeValue) {
            is DirectStringSubstitution -> write(codeValue.substitution)
            is ValueReference -> {
                val presentation = codeValue.resolve()
                write(presentation.shortName)
            }
            is ValueExpression -> writeValueExpression(codeValue)
            else -> {
                throwCannotDump(codeValue)
            }
        }
    }

    private fun writeValueExpression(valueExpression: ValueExpression) {
        when (valueExpression) {
            // TODO creation of arrays?
            is ObjectCreationExpression -> {
                writeSeparated("new")
                writeSeparated(valueExpression.invokedConstructor.containingType.shortName)
                writeCallList(valueExpression)
            }
            is MethodInvocationExpression -> {
                writeCodeValue(valueExpression.invokedOn)
                write(".")
                write(valueExpression.invokedMethod.shortName)
                writeCallList(valueExpression)
            }
            is FunctionInvocationExpression -> {
                write(classNameForStaticFunction(valueExpression.invokedCallable.shortName))
                write(".")
                write(valueExpression.invokedCallable.shortName)
                writeCallList(valueExpression)
            }
            else -> {
                throwCannotDump(valueExpression)
            }
        }
    }

    private fun writeCallList(invocationExpression: InvocationExpression) {
        write("(")
        var first = true
        for (parameter in invocationExpression.invokedCallable.parameters) {
            if (!first) {
                write(", ")
            }
            first = false
            val argument: CodeValue? = invocationExpression.parameterToArgument[parameter]
            if (argument == null) {
                writeDefaultValueForTypeUsage(parameter.usage)
            } else {
                writeCodeValue(argument)
            }
        }
        write(")")
    }

    private fun appendLocalVariable(localVariablePresentation: LocalVariablePresentation) = appendLine {
        writeTypeUsage(localVariablePresentation.usage)
        writeSeparated(localVariablePresentation.shortName)

        val initialValue = localVariablePresentation.initialValue

        if (initialValue != null) {
            writeSeparated("=")
            writeCodeValue(initialValue)
        } else {
            writeSeparated("= null")
        }
    }

// 1. assignment + in vulnerability transit do dispatch
//
// 2. refactor part with expressions in site
// 3. correct styling
// commentaries

// statistics dump - vulnerability id, source, sink, path

// optionally - conditional paths
// optionally - kotlinx serialization for hierarchy

    private fun appendLine(block: () -> Unit) {
        try {
            tabulate()
            block()
        }
        finally {
            write(";\n")
        }
    }

    private fun appendSite(site: Site) {
        // we always dump sites in following order:
        // 1. preparation site
        // 2. call sites
        // 3. termination site
        // there are no different function for each site as their logic is connected:
        // there is always termination site in each callable.
        // so after each call site `else` is added.
        when (site) {
            is PreparationSite -> {
                for (before in site.expressionsBefore) {
                    appendCodeExpression(before)
                }
                for (after in site.expressionsAfter) {
                    appendCodeExpression(after)
                }
            }
            is CallSite -> {
                tabulate()
                writeSeparated("if (currentDispatch == ${site.graphId})")
                inScope {
                    for (before in site.expressionsBefore) {
                        appendCodeExpression(before)
                    }
                    appendCodeValue(site.invocationExpression)
                    for (after in site.expressionsAfter) {
                        appendCodeExpression(after)
                    }
                }
                tabulate()
                writeSeparated("else")
            }
            is TerminationSite -> {
                inScope {
                    for (before in site.expressionsBefore) {
                        appendCodeExpression(before)
                    }
                    for (dereference in site.dereferences) {
                        appendLine {
                            writeCodeValue(dereference)
                            write(".toString()")
                        }
                    }
                    for (after in site.expressionsAfter) {
                        appendCodeExpression(after)
                    }
                }
            }
        }
    }

    private fun appendLocalsAndSites(callable: CallablePresentation) {
        val localVariables = callable.localVariables
        for (variable in localVariables) {
            appendLocalVariable(variable)
        }
        appendSite(callable.preparationSite)
        val sites = callable.callSites
        for (site in sites) {
            appendSite(site)
        }
        appendSite(callable.terminationSite)
    }

    private fun appendConstructor(constructor: ConstructorPresentation) {
        tabulate()
        writeVisibility(constructor.visibility)
        write(constructor.containingType.shortName)
        writeParametersList(constructor)
        inScope {
            val parentCall = constructor.parentConstructorCall
            if (parentCall != null) {
                appendLine {
                    write("super")
                    writeCallList(parentCall)
                }
            }
            appendLocalsAndSites(constructor)
        }
    }

    private fun appendStaticFunction(function: FunctionPresentation) = appendStartFunction(function.shortName, function)

    private fun classNameForStaticFunction(functionName: String): String {
        return "ClassFor${functionName.capitalize()}"
    }

    private fun appendStartFunction(name: String, function: FunctionPresentation) {
        writeSeparated("public class ${classNameForStaticFunction(name)}")
        inScope {
            tabulate()
            writeVisibility(function.visibility)
            writeSeparated("static")
            writeTypeUsage(function.returnType)
            write(function.shortName)
            writeParametersList(function)
            inScope {
                appendLocalsAndSites(function)
            }
        }
    }

    private fun appendMethodSignature(methodPresentation: MethodPresentation) {
        tabulate()
        if (methodPresentation.inheritedFrom != null) {
            writeSeparated("@Override\n")
        }
        tabulate()
        writeVisibility(methodPresentation.visibility)
        when (methodPresentation.inheritanceModifier) {
            InheritanceModifier.ABSTRACT -> writeSeparated("abstract")
            InheritanceModifier.FINAL -> writeSeparated("final")
            InheritanceModifier.STATIC -> writeSeparated("static")
            else -> {
                assert(false) { "should be impossible" }
            }
        }
        writeSeparated(methodPresentation.shortName)
        writeParametersList(methodPresentation)
    }

    private fun appendMethod(methodPresentation: MethodPresentation) {
        appendMethodSignature(methodPresentation)
        inScope {
            appendLocalsAndSites(methodPresentation)
        }
    }

    override fun dumpType(type: TypePresentation, pathToSourcesDir: Path) = inFile(type.shortName, pathToSourcesDir) {
        writeTypeSignature(type)
        inScope {
            for (field in type.implementedFields) {
                appendField(field)
            }
            for (constructor in type.constructors) {
                appendConstructor(constructor)
            }
            for (method in type.implementedMethods) {
                appendMethod(method)
            }

            val staticCounterPart = type.staticCounterPart

            for (staticField in staticCounterPart.implementedFields) {
                appendField(staticField)
            }
            for (staticMethod in staticCounterPart.implementedMethods) {
                appendMethod(staticMethod)
            }
        }
    }

    override fun dumpFunction(func: FunctionPresentation, pathToSourcesDir: Path) = inFile(classNameForStaticFunction(func.shortName), pathToSourcesDir) {
        appendStaticFunction(func)
    }

    override fun dumpStartFunction(name: String, func: FunctionPresentation, pathToSourcesDir: Path) =
        inFile(classNameForStaticFunction(name), pathToSourcesDir) {
            appendStartFunction(name, func)
        }
}

interface AnalysisVulnerabilityProvider {
    fun provideInstance(codeRepresentation: CodeRepresentation): VulnerabilityInstance
    fun provideInstance(
        codeRepresentation: CodeRepresentation,
        block: VulnerabilityInstance.() -> Unit
    ): VulnerabilityInstance {
        return provideInstance(codeRepresentation).apply(block)
    }

    fun isApplicable(language: TargetLanguage): Boolean
}

class NpeProvider : AnalysisVulnerabilityProvider {
    override fun provideInstance(codeRepresentation: CodeRepresentation): VulnerabilityInstance {
        return NpeInstance(codeRepresentation)
    }

    override fun isApplicable(language: TargetLanguage): Boolean {
        return language is JavaLanguage
    }
}

class NpeInstance(private val codeRepresentation: CodeRepresentation) : VulnerabilityInstance {
    companion object {
        private var vulnerabilitiesCounter = 0
    }

    private val id = "NpeInstance${++vulnerabilitiesCounter}"
    private val variableId = "variableFor$id"
    private val typeId = "TypeFor$id"
    private val startFunctionId = "startFunctionFor$id"
    private val arrayDeque = codeRepresentation.getPredefinedType("ArrayDeque<Integer>")!!

    // все не что указано в тразите - идет в дефолт валуе. иначе берется из параметра.
    // так как рендеринг в конце - все будет ок
    // путь задаеися глобальным стейтом на доменах?))))
    // НЕТ! так как у нас проблема может быть только на одном путе, только пройдя его полностью - то нам не нужно эмулировать
    // диспатчинг в ифдс, он сам найдет только то, что нужно, а вот верификация будет за юсвм!!

    private fun addPath(targetCall: Int) {
        val startFunction = codeRepresentation.getOrCreateStartFunction(startFunctionId)
        val dispatcher = startFunction.getLocalVariable(dispatcherQueueName)!!
        val dispatcherType = (dispatcher.usage as InstanceTypeUsage).typePresentation
        val dispatcherAddMethod = dispatcherType.getMethods("add").single()
        val preparationSite = startFunction.preparationSite
        val invocationExpression = MethodInvocationExpressionImpl(dispatcherAddMethod, dispatcher.reference)
        val dispatcherAddMethodParameter = dispatcherAddMethod.parameters.single()

        invocationExpression.addInCall(dispatcherAddMethodParameter, object : DirectStringSubstitution {
            override val substitution: String = targetCall.toString()
            override val evaluatedType: TypeUsage = dispatcherAddMethodParameter.usage
        })
        preparationSite.addBefore(invocationExpression)
    }

    override fun createSource(u: Int) {
        val startFunction = codeRepresentation.getOrCreateStartFunction(startFunctionId)
        val type = codeRepresentation.getOrCreateType(typeId)

        // must be initialized here as in following transits this will be parameter
        startFunction.createLocalVariable(dispatcherQueueName, arrayDeque.instanceType, arrayDeque.defaultValue)
        // initialized as null
        startFunction.createLocalVariable(variableId, type.instanceType)
        codeRepresentation.createDispatch(startFunction)
        transitVulnerability(startFunction.graphId, u)
    }

    override fun mutateVulnerability(u: Int, v: Int) {
        // TODO currently do not mutate, enhance by time
    }

    override fun transitVulnerability(u: Int, v: Int) {
        val functionU = codeRepresentation.getOrCreateFunctionFor(u)
        val functionV = codeRepresentation.getOrCreateFunctionFor(v)

        // as it can be either variable or parameter
        val dispatchArrayInU = functionU.getLocal(dispatcherQueueName)!!
        val variableInU = functionU.getLocal(variableId)!!

        // as it can be either variable or parameter
        val dispatchParameterInV = functionV.getOrCreateParameter(dispatcherQueueName, arrayDeque.instanceType)
        val parameterInV = functionV.getOrCreateParameter(variableId, variableInU.usage)

        val uvCallSite = functionU.getOrCreateCallSite(functionV)

        uvCallSite.invocationExpression.addInCall(dispatchParameterInV, dispatchArrayInU.reference)
        uvCallSite.invocationExpression.addInCall(parameterInV, variableInU.reference)
        addPath(v)
        codeRepresentation.createDispatch(functionV)
    }

    override fun createSink(v: Int) {
        val functionV = codeRepresentation.getOrCreateFunctionFor(v)
        val variableInV = functionV.getLocal(variableId)!!
        val vTerminationSite = functionV.terminationSite

        vTerminationSite.addDereference(variableInV.reference)
        addPath(-1)
    }
}

interface VulnerabilityInstance {
    fun createSource(u: Int)
    fun mutateVulnerability(u: Int, v: Int)
    fun transitVulnerability(u: Int, v: Int)
    fun createSink(v: Int)
}

// TODO tests - generate by hands some tests, 100% cover must be
// TODO c++ implementation
// TODO enums
// TODO complex representations - list of other
// TODO ifs, cycles, arrays, assignments, lambda invokes, returns
// TODO analyses aware constructors
// TODO interfaces - DAG, abstract classes - graph DFS, implementation of interfaces - zip dag to tree
// TODO method implementation - paths in tree divisino in half, random points
// TODO each call in graph path - may be virtual invoke, provide arguments on which this call should via generated hierarchy tree
// TODO generate data flow - first only simple returns and initial values in fields + tree types generation
// TODO then do complex reassignments with conditions
// TODO after that we can think of exceptions, lambdas, generics
// TODO final boss will be unsoundiness - reflection and jni
// TODO protected modifiers
// TODO type and/or signature correlation, covariance/contrvariance - this should be part of overloading
// TODO connecting already defined code
// TODO generating IFDS false positives to test USVM
// TODO verifications - all interfaces methods are implemented, no collisions, all abstract methods are defined in non-abstract classes etc
// TODO per language features to enable/disable some generations

// can be added with minimal work, but i do not see usefulness in foreseeable future
// TODO extension methods? - should be functions/methods with additional mark
// TODO annotations? - tbh i dunno for what right now it might be required

// hard and involves much design and refactoring
// TODO accessors? - in some sense this should be method with some field reference. but not all languages support this, so skip for now
// TODO generics? templates? - oh fuk this is hard tbh


fun main(args: Array<String>) {
    assert(args.size in 5..6) {
        "vertices:Int edges:Int vulnerabilities:Int pathToProjectDir:String targetLanguage:String [clearTargetDir: Boolean]"
    }
    val n = args[0].toInt()
    val m = args[1].toInt()
    val k = args[2].toInt()

    assert(n in 2 until 1000) { "currently big graphs not supported just in case" }
    assert(m in 1 until 1000000) { "though we permit duplicated edges, do not overflow graph too much" }
    assert(k in 0 until min(listOf(255, n, m)))

    val projectPath = Paths.get(args[3]).normalize()

    assert(projectPath.notExists() || projectPath.useDirectoryEntries { it.none() }) { "Provide path to directory which either does not exists or empty" }

    val targetLanguageString = args[4]
    val targetLanguageService = ServiceLoader.load(TargetLanguage::class.java)
    val targetLanguage = targetLanguageService.single { it.javaClass.simpleName == targetLanguageString }

    val vulnerabilityProviderService = ServiceLoader.load(AnalysisVulnerabilityProvider::class.java)
    val vulnerabilityProviders = mutableListOf<AnalysisVulnerabilityProvider>()

    for (analysis in vulnerabilityProviderService) {
        if (analysis.isApplicable(targetLanguage)) {
            vulnerabilityProviders.add(analysis)
        }
    }

    logger.info { "analyses summary: " }

    val randomSeed = arrayOf(n, m, k).contentHashCode()
    val randomer = Random(randomSeed)

    logger.info { "debug seed: $randomSeed" }

    // 1. как-то задавать анализы, для которых генерируем граф
    // Это говно должено реализовывать интерфейс какой-нибудь, который должен быть положен рядом-в класспас,
    // мы его каким-нибудь рефлекшеном находим и радуемся
    // 2. как-то задавать файл путь в который че генерим
    // наверное хочу задавать путь до папки, в которую нужно класть проект. и да, туда сразу внутренности архива
    // 3. как-то завязать кеш дфс
    // просто реально держит ссылку на граф и просто мапчик да-нет и все
    // 6. нужна презентация реальных функций и че она умеет
    // функция - название, параметры, у параметра тип, и пишется он явно в формате джавы(другой язык мб потом)
    // также функция имеет понимание, в каком порядке какие вызовы в ней будут делаться, и какие у каждого вызова параметры и в каком порядке
    // изменения каждого параметра производятся перед самым вызовом в пути, тем самым гарантируем, что там не будут важны предыдущие значения
    // также из этого следует, что мы не можем двумя разными способами вызываться в одном методе.
    // !!!проблема - мы практически точно будет генерировать бесконечные рекурсии при любом цикле!!!
    // то есть мы гарантированно должны быть ациклически! для этого будет использоваться стек, в который будет положен какое ребро нужно вызвать
    // на данный момент мы поддерживаем явный путь в графе, но никак не "исполнение"(то есть как бы историю работы дфс).
    // 4. как-то сделать реализацию vulnerabilities итдитп
    // ну наверное ему нужен стартовая вершина, конечная, естественное весь путь, также функциональная репрезентация каждого говна,
    // и каждый такой анализ дожен по этому путь пройтись и сам что-то сделать так, чтобы ничего не сломать остальным

    // 5. сделать дампалку итогового состояния функций через жопу
    // просто интерфейс, который принимает функцию из репрезентации и путь, куда это говно надо написать. Дальше уже разбирается сама.
    // их тоже можно искать сервисом

    val fullClear = args.getOrElse(5) { "false" }.toBooleanStrict()
    val dezipper = DeZipper(targetLanguage, fullClear)

    dezipper.dezip(projectPath)

    val graph = mutableMapOf<Int, MutableSet<Int>>()

    var i = 0
    while(i < m) {
        val u = randomer.nextInt(n)
        val v = randomer.nextInt(n)

        if (u != v) {
            // TODO loops v->v?
            graph.getOrPut(u) { mutableSetOf() }.add(v)
            i++
        }
    }

    val accessibilityCache = AccessibilityCache(n, graph)
    val codeRepresentation = CodeRepresentation(graph, targetLanguage)
    val generatedVulnerabilitiesList = mutableListOf<VulnerabilityInstance>()

    i = 0
    while(i < k) {
        val u = randomer.nextInt(n)
        val v = randomer.nextInt(n)
        val vulnerabilityIndex = randomer.nextInt(vulnerabilityProviders.size)
        val vulnerabilityProvider = vulnerabilityProviders[vulnerabilityIndex]

        if (accessibilityCache.isAccessible(u, v)) {
            val path = accessibilityCache.getAccessPath(u, v)
            val instance = vulnerabilityProvider.provideInstance(codeRepresentation) {
                createSource(u)
                for (j in 0 until path.lastIndex) {
                    val startOfEdge = path[j]
                    val endOfEdge = path[j + 1]
                    mutateVulnerability(startOfEdge, endOfEdge)
                    transitVulnerability(startOfEdge, endOfEdge)
                }
                createSink(v)
            }
            generatedVulnerabilitiesList.add(instance)
            i++
        }
    }

    codeRepresentation.dumpTo(projectPath)
}