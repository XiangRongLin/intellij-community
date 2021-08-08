// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.refactoring.introduce.introduceConstant

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.refactoring.RefactoringActionHandler
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.core.util.CodeInsightUtils
import org.jetbrains.kotlin.idea.refactoring.chooseContainerElement
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.*
import org.jetbrains.kotlin.idea.refactoring.introduce.introduceProperty.KotlinInplacePropertyIntroducer
import org.jetbrains.kotlin.idea.refactoring.introduce.showErrorHint
import org.jetbrains.kotlin.idea.refactoring.introduce.showErrorHintByKey
import org.jetbrains.kotlin.idea.refactoring.introduce.validateExpressionElements
import org.jetbrains.kotlin.idea.refactoring.selectElement
import org.jetbrains.kotlin.idea.util.psi.patternMatching.toRange
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.stubs.elements.KtClassElementType
import org.jetbrains.kotlin.psi.stubs.elements.KtConstantExpressionElementType
import org.jetbrains.kotlin.psi.stubs.elements.KtFileElementType
import org.jetbrains.kotlin.psi.stubs.elements.KtStringTemplateExpressionElementType

class KotlinIntroduceConstantHandler(
    val helper: ExtractionEngineHelper = InteractiveExtractionHelper
) : RefactoringActionHandler {
    object InteractiveExtractionHelper : ExtractionEngineHelper(INTRODUCE_CONSTANT) {
        private fun getExtractionTarget(descriptor: ExtractableCodeDescriptor) =
            propertyTargets.firstOrNull { it.isAvailable(descriptor) } //TODO constantTargets companion object

        override fun validate(descriptor: ExtractableCodeDescriptor) =
            descriptor.validate(getExtractionTarget(descriptor) ?: ExtractionTarget.FUNCTION)

        override fun configureAndRun(
            project: Project,
            editor: Editor,
            descriptorWithConflicts: ExtractableCodeDescriptorWithConflicts,
            onFinish: (ExtractionResult) -> Unit
        ) {
            val descriptor = descriptorWithConflicts.descriptor
            val target = getExtractionTarget(descriptor)
            if (target != null) {
                val options = ExtractionGeneratorOptions(target = target, delayInitialOccurrenceReplacement = true, isConst = true)
                doRefactor(ExtractionGeneratorConfiguration(descriptor, options), onFinish)
            } else {
                showErrorHint(
                    project,
                    editor,
                    KotlinBundle.message("error.text.can.t.introduce.constant.for.this.expression"),
                    INTRODUCE_CONSTANT
                )
            }
        }
    }

    fun doInvoke(project: Project, editor: Editor, file: KtFile, elements: List<PsiElement>, target: PsiElement) {
        val adjustedElements = (elements.singleOrNull() as? KtBlockExpression)?.statements ?: elements
        when {
            adjustedElements.isEmpty() -> {
                showErrorHintByKey(
                    project, editor, "cannot.refactor.no.expression",
                    INTRODUCE_CONSTANT
                )
            }
            else -> {
                val options = ExtractionOptions(extractAsProperty = true)
                val extractionData = ExtractionData(file, adjustedElements.toRange(), target, null, options)
                ExtractionEngine(helper).run(editor, extractionData) {
                    val property = it.declaration as KtProperty
                    val descriptor = it.config.descriptor

                    editor.caretModel.moveToOffset(property.textOffset)
                    editor.selectionModel.removeSelection()
                    if (editor.settings.isVariableInplaceRenameEnabled && !ApplicationManager.getApplication().isUnitTestMode) {
                        with(PsiDocumentManager.getInstance(project)) {
                            commitDocument(editor.document)
                            doPostponedOperationsAndUnblockDocument(editor.document)
                        }

                        val introducer = KotlinInplacePropertyIntroducer(
                            property = property,
                            editor = editor,
                            project = project,
                            title = INTRODUCE_CONSTANT,
                            doNotChangeVar = false,
                            exprType = descriptor.returnType,
                            extractionResult = it,
                            availableTargets = propertyTargets.filter { target -> target.isAvailable(descriptor) }
                        )
                        introducer.performInplaceRefactoring(LinkedHashSet(descriptor.suggestedNames))
                    } else {
                        processDuplicatesSilently(it.duplicateReplacers, project)
                    }
                }
            }
        }
    }

    private fun validateElement(element: PsiElement): String? {
        val errorMessage = validateExpressionElements(listOf(element))
        return when {
            errorMessage != null -> errorMessage
            element.isNotConstant() -> KotlinBundle.message("error.text.can.t.introduce.constant.for.this.expression.because.not.constant")
            else -> null
        }
    }

    private fun PsiElement.isNotConstant(): Boolean {
        return this.elementType !is KtConstantExpressionElementType && this.elementType !is KtStringTemplateExpressionElementType
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        if (file !is KtFile) return
        selectElements(editor, file) { elements, targets -> doInvoke(project, editor, file, elements, targets) }
    }

    private fun selectElements(
        editor: Editor,
        file: KtFile,
        continuation: (elements: List<PsiElement>, targets: PsiElement) -> Unit
    ) {
        selectElement(editor, file, listOf(CodeInsightUtils.ElementKind.EXPRESSION)) { element ->
            if (element == null) throw RuntimeException("TODO, null element")
            validateElement(element)?.let {
                showErrorHint(file.project, editor, it, INTRODUCE_CONSTANT)
                return@let
            }

            val fileElement = PsiTreeUtil.findFirstParent(element) {
                it.elementType is KtFileElementType
            }

            val classElement = PsiTreeUtil.findFirstParent(element) { it.elementType is KtClassElementType }
            val companionObject = PsiTreeUtil.findChildOfType(classElement, KtObjectDeclaration::class.java)

            val potentialContainer = listOfNotNull(fileElement, companionObject)

            if (potentialContainer.size == 1) {
                continuation(listOf(element), potentialContainer.first().firstChild)
            } else {
                chooseContainerElement(
                    potentialContainer,
                    editor,
                    KotlinBundle.message("title.select.target.code.block"),
                    highlightSelection = true
                ) {
                    continuation(listOf(element), it.firstChild)
                }
            }
        }
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        throw AssertionError("$INTRODUCE_CONSTANT can only be invoked from editor")
    }
}

val INTRODUCE_CONSTANT: String = KotlinBundle.message("introduce.constant")
