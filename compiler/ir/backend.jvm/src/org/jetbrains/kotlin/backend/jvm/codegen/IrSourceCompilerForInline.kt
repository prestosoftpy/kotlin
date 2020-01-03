/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.codegen

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.backend.common.CodegenUtil
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.codegen.BaseExpressionCodegen
import org.jetbrains.kotlin.codegen.ClassBuilder
import org.jetbrains.kotlin.codegen.OwnerKind
import org.jetbrains.kotlin.codegen.coroutines.INVOKE_SUSPEND_METHOD_NAME
import org.jetbrains.kotlin.codegen.inline.*
import org.jetbrains.kotlin.codegen.inline.coroutines.FOR_INLINE_SUFFIX
import org.jetbrains.kotlin.codegen.serialization.JvmSerializationBindings
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.declarations.IrAttributeContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOrigin
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodGenericSignature
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodSignature
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import org.jetbrains.org.objectweb.asm.commons.Method
import org.jetbrains.org.objectweb.asm.tree.MethodNode

class IrSourceCompilerForInline(
    override val state: GenerationState,
    override val callElement: IrMemberAccessExpression,
    private val callee: IrFunction,
    internal val codegen: ExpressionCodegen,
    private val data: BlockInfo
) : SourceCompilerForInline {

    //TODO: KotlinLookupLocation(callElement)
    override val lookupLocation: LookupLocation
        get() = NoLookupLocation.FROM_BACKEND

    override val callElementText: String
        get() = ir2string(callElement)

    override val callsiteFile: PsiFile?
        get() = codegen.context.psiSourceManager.getKtFile(codegen.irFunction.fileParent)

    override val contextKind: OwnerKind
        get() = OwnerKind.getMemberOwnerKind(callElement.symbol.descriptor.containingDeclaration!!)

    override val inlineCallSiteInfo: InlineCallSiteInfo
        get() {
            val root = generateSequence(codegen) { it.inlinedInto }.last()
            return InlineCallSiteInfo(
                root.classCodegen.type.internalName,
                root.signature.asmMethod.name,
                root.signature.asmMethod.descriptor,
                compilationContextFunctionDescriptor.isInlineOrInsideInline(),
                compilationContextFunctionDescriptor.isSuspend,
                findElement()?.let { CodegenUtil.getLineNumberForElement(it, false) } ?: 0
            )
        }

    override val lazySourceMapper: DefaultSourceMapper
        get() = codegen.classCodegen.getOrCreateSourceMapper()

    private fun makeInlineNode(function: IrFunction, classCodegen: ClassCodegen, isLambda: Boolean): SMAPAndMethodNode {
        var node: MethodNode? = null
        // Do not inline the generated state-machine, which was generated to support java interop of inline suspend functions.
        // Instead, find its $$forInline companion (they share the same attributeOwnerId), which is generated for the inliner to use.
        val forInlineFunction =
            if (function.isSuspend)
                function.parentAsClass.functions.find {
                    it.name.asString() == function.name.asString() + FOR_INLINE_SUFFIX &&
                            it.attributeOwnerId == (function as? IrAttributeContainer)?.attributeOwnerId
                } ?: function
            else function
        val functionCodegen = object : FunctionCodegen(forInlineFunction, classCodegen, codegen.takeIf { isLambda }) {
            override fun createMethod(flags: Int, signature: JvmMethodGenericSignature): MethodVisitor {
                val asmMethod = signature.asmMethod
                node = MethodNode(
                    Opcodes.API_VERSION,
                    flags,
                    asmMethod.name.removeSuffix(FOR_INLINE_SUFFIX),
                    asmMethod.descriptor,
                    signature.genericsSignature,
                    null
                )
                return wrapWithMaxLocalCalc(node!!)
            }
        }
        functionCodegen.generate()
        return SMAPAndMethodNode(node!!, SMAP(classCodegen.getOrCreateSourceMapper().resultMappings))
    }

    override fun generateLambdaBody(lambdaInfo: ExpressionLambda): SMAPAndMethodNode =
        makeInlineNode((lambdaInfo as IrExpressionLambdaImpl).function, codegen.classCodegen, true)

    override fun doCreateMethodNodeFromSource(
        callableDescriptor: FunctionDescriptor,
        jvmSignature: JvmMethodSignature,
        callDefault: Boolean,
        asmMethod: Method
    ): SMAPAndMethodNode {
        assert(callableDescriptor == callee.symbol.descriptor.original) { "Expected $callableDescriptor got ${callee.descriptor.original}" }
        return makeInlineNode(callee, FakeClassCodegen(callee, codegen.classCodegen), false)
    }

    override fun hasFinallyBlocks() = data.hasFinallyBlocks()

    override fun generateFinallyBlocksIfNeeded(codegen: BaseExpressionCodegen, returnType: Type, afterReturnLabel: Label, target: Label?) {
        require(codegen is ExpressionCodegen)
        codegen.generateFinallyBlocksIfNeeded(returnType, afterReturnLabel, data, target)
    }

    override fun createCodegenForExternalFinallyBlockGenerationOnNonLocalReturn(finallyNode: MethodNode, curFinallyDepth: Int) =
        ExpressionCodegen(
            codegen.irFunction, codegen.signature, codegen.frameMap, InstructionAdapter(finallyNode), codegen.classCodegen,
            codegen.inlinedInto
        ).also {
            it.finallyDepth = curFinallyDepth
        }

    override fun isCallInsideSameModuleAsDeclared(functionDescriptor: FunctionDescriptor): Boolean {
        // TODO port to IR structures
        return DescriptorUtils.areInSameModule(DescriptorUtils.getDirectMember(functionDescriptor), codegen.irFunction.descriptor)
    }

    override fun isFinallyMarkerRequired(): Boolean {
        return codegen.isFinallyMarkerRequired()
    }

    override val compilationContextDescriptor: DeclarationDescriptor
        get() = callElement.symbol.descriptor

    override val compilationContextFunctionDescriptor: FunctionDescriptor
        get() = callElement.symbol.descriptor as FunctionDescriptor

    override fun getContextLabels(): Set<String> {
        val result = mutableSetOf<String>()
        for (info in data.infos) {
            if (info !is LoopInfo)
                continue
            result += info.loop.nonLocalReturnLabel(false)
            result += info.loop.nonLocalReturnLabel(true)
        }

        var name = codegen.irFunction.name.asString()
        if (name == INVOKE_SUSPEND_METHOD_NAME) {
            codegen.context.suspendLambdaToOriginalFunctionMap[codegen.irFunction.parentAsClass.attributeOwnerId]?.let {
                name = it.name.asString()
            }
        }
        result += name
        return result
    }

    override fun getJumpTarget(label: String): Label? {
        for (info in data.infos) {
            when {
                info is LoopInfo && info.loop.nonLocalReturnLabel(false) == label -> return info.continueLabel
                info is LoopInfo && info.loop.nonLocalReturnLabel(true) == label -> return info.breakLabel
            }
        }
        return null
    }

    // TODO: Find a way to avoid using PSI here
    override fun reportSuspensionPointInsideMonitor(stackTraceElement: String) {
        org.jetbrains.kotlin.codegen.coroutines.reportSuspensionPointInsideMonitor(findElement()!!, state, stackTraceElement)
    }

    private fun findElement() = (callElement.symbol.descriptor.original as? DeclarationDescriptorWithSource)?.source?.getPsi() as? KtElement

    internal val isPrimaryCopy: Boolean
        get() = codegen.classCodegen !is FakeClassCodegen

    private class FakeClassCodegen(irFunction: IrFunction, codegen: ClassCodegen) :
        ClassCodegen(irFunction.parent as IrClass, codegen.context) {

        override fun createClassBuilder(): ClassBuilder {
            return FakeBuilder
        }

        companion object {
            val FakeBuilder = object : ClassBuilder {
                override fun newField(
                    origin: JvmDeclarationOrigin,
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    value: Any?
                ): FieldVisitor {
                    TODO("not implemented")
                }

                override fun newMethod(
                    origin: JvmDeclarationOrigin,
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor {
                    TODO("not implemented")
                }

                override fun getSerializationBindings(): JvmSerializationBindings {
                    return JvmSerializationBindings()
                }

                override fun newAnnotation(desc: String, visible: Boolean): AnnotationVisitor {
                    TODO("not implemented")
                }

                override fun done() {
                    TODO("not implemented")
                }

                override fun getVisitor(): ClassVisitor {
                    TODO("not implemented")
                }

                override fun defineClass(
                    origin: PsiElement?,
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String,
                    interfaces: Array<out String>
                ) {
                    TODO("not implemented")
                }

                override fun visitSource(name: String, debug: String?) {
                    TODO("not implemented")
                }

                override fun visitOuterClass(owner: String, name: String?, desc: String?) {
                    TODO("not implemented")
                }

                override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                    TODO("not implemented")
                }

                override fun getThisName(): String {
                    TODO("not implemented")
                }

                override fun addSMAP(mapping: FileMapping?) {
                    TODO("not implemented")
                }
            }
        }
    }
}

// TODO generate better labels; this is unique (includes the object's address), but not very descriptive
internal fun IrLoop.nonLocalReturnLabel(forBreak: Boolean): String = "$this\$${if (forBreak) "break" else "continue"}"
