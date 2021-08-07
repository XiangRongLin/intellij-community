// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.idea.refactoring.introduce.introduceConstant

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.HelpID
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.core.util.CodeInsightUtils
import org.jetbrains.kotlin.idea.refactoring.getExtractionContainers
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.ExtractionData
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.ExtractionEngine
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.ExtractionEngineHelper
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.processDuplicatesSilently
import org.jetbrains.kotlin.idea.refactoring.introduce.introduceProperty.INTRODUCE_PROPERTY
import org.jetbrains.kotlin.idea.refactoring.introduce.introduceProperty.KotlinIntroducePropertyHandler
import org.jetbrains.kotlin.idea.refactoring.introduce.introduceVariable.KotlinIntroduceVariableHandler
import org.jetbrains.kotlin.idea.refactoring.introduce.selectElementsWithTargetParent
import org.jetbrains.kotlin.idea.refactoring.introduce.showErrorHintByKey
import org.jetbrains.kotlin.idea.refactoring.introduce.validateExpressionElements
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.util.psi.patternMatching.toRange
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext

val INTRODUCE_CONSTANT2 = KotlinBundle.message("introduce.constant")

open class Kotlin2IntroduceConstantHandler(
    val helper: ExtractionEngineHelper = KotlinIntroducePropertyHandler.InteractiveExtractionHelper
) : RefactoringActionHandler {

    //object InteractiveExtractionHelper : ExtractionEngineHelper(INTRODUCE_CONSTANT) {

    //}


    private val LOG = Logger.getInstance(Kotlin2IntroduceConstantHandler::class.java)

    private fun showErrorHint(project: Project, editor: Editor?, @NlsContexts.DialogMessage message: String) {
        CommonRefactoringUtil.showErrorHint(
            project,
            editor,
            message,
            KotlinIntroduceVariableHandler.INTRODUCE_VARIABLE,
            HelpID.INTRODUCE_VARIABLE
        )
    }


    private fun KtExpression.getCandidateContainers(
        resolutionFacade: ResolutionFacade,
        originalContext: BindingContext
    ): List<Pair<KtElement, KtElement>> {

        return emptyList()
    }

    fun selectElements(editor: Editor, file: KtFile, continuation: (elements: List<PsiElement>, targetParent: PsiElement) -> Unit) {
        selectElementsWithTargetParent(
            INTRODUCE_CONSTANT2,
            editor,
            file,
            KotlinBundle.message("title.select.target.code.block"),
            listOf(CodeInsightUtils.ElementKind.TYPE_ELEMENT, CodeInsightUtils.ElementKind.EXPRESSION),
            ::validateExpressionElements,
            { _, parent ->
                parent.getExtractionContainers(strict = true, includeAll = true)
                //.filter { it is KtFile && !it.isScript() }
            },
            continuation
        )
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        LOG.warn("invoke with single")

        if (file !is KtFile) return

        selectElements(editor, file) { elements, targetParent ->
            doRefactoring(project, editor, file, elements, targetParent)
            LOG.warn(elements.toString())
            LOG.warn(targetParent.toString())
        }
    }

    private fun doRefactoring(project: Project, editor: Editor, file: KtFile, elements: List<PsiElement>, targetParent: PsiElement) {
        val adjustedElements = (elements.singleOrNull() as? KtBlockExpression)?.statements ?: elements
        if (adjustedElements.isNotEmpty()) {
            val extractionData = ExtractionData(file, adjustedElements.toRange(), targetParent, null)
            ExtractionEngine(helper).run(editor, extractionData) {
                val property = it.declaration as KtProperty
                val descriptor = it.config.descriptor

                //editor.caretModel.moveToOffset(property.textOffset)
                editor.selectionModel.removeSelection()
                if (editor.settings.isVariableInplaceRenameEnabled && !ApplicationManager.getApplication().isUnitTestMode) {
                    with(PsiDocumentManager.getInstance(project)) {
                        commitDocument(editor.document)
                        doPostponedOperationsAndUnblockDocument(editor.document)
                    }
                } else {
                    processDuplicatesSilently(it.duplicateReplacers, project)
                }
            }
        } else {
            showErrorHintByKey(project, editor, "cannot.refactor.no.expression", INTRODUCE_PROPERTY)
        }

    }

    override fun invoke(project: Project, elements: Array<PsiElement>, dataContext: DataContext) {
        LOG.warn("invoke with multiple")
        //do nothing
    }
}


