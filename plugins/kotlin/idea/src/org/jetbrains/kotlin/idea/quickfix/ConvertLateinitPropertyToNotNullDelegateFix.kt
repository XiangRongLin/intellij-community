// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.codeinsight.api.classic.quickfixes.KotlinQuickFixAction
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

class ConvertLateinitPropertyToNotNullDelegateFix(
    property: KtProperty,
    private val type: String
) : KotlinQuickFixAction<KtProperty>(property) {
    override fun getText() = KotlinBundle.message("convert.to.notnull.delegate")

    override fun getFamilyName() = text

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val property = element ?: return
        val typeReference = property.typeReference ?: return
        val psiFactory = KtPsiFactory(property)
        property.removeModifier(KtTokens.LATEINIT_KEYWORD)
        val propertyDelegate = psiFactory.createPropertyDelegate(
            psiFactory.createExpression("kotlin.properties.Delegates.notNull<$type>()")
        )
        property.addAfter(propertyDelegate, typeReference)
        property.typeReference = null
        ShortenReferences.DEFAULT.process(property)
    }

    companion object : KotlinSingleIntentionActionFactory() {
        override fun createAction(diagnostic: Diagnostic): KotlinQuickFixAction<KtProperty>? {
            val property = diagnostic.psiElement as? KtProperty ?: return null
            if (!property.hasModifier(KtTokens.LATEINIT_KEYWORD) || !property.isVar || property.hasInitializer()) return null
            val typeReference = property.typeReference ?: return null
            val type = property.analyze(BodyResolveMode.PARTIAL)[BindingContext.TYPE, typeReference] ?: return null
            if (!KotlinBuiltIns.isPrimitiveType(type)) return null
            return ConvertLateinitPropertyToNotNullDelegateFix(property, type.toString())
        }
    }
}
