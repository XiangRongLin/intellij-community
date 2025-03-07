// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.codeinsights.impl.base.applicators

import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.KotlinApplicatorInput
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.applicator
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.startOffset

object AddAccessorApplicator {
    fun applicator(addGetter: Boolean, addSetter: Boolean) = applicator<KtProperty, KotlinApplicatorInput.Empty> {
        familyAndActionName {
            when {
                addGetter && addSetter -> KotlinBundle.message("text.add.getter.and.setter")
                addGetter -> KotlinBundle.message("text.add.getter")
                addSetter -> KotlinBundle.message("text.add.setter")
                else -> throw AssertionError("At least one from (addGetter, addSetter) should be true")
            }
        }

        applyTo { element, _, _, editor ->
            val hasInitializer = element.hasInitializer()
            val psiFactory = KtPsiFactory(element)
            if (addGetter) {
                val expression = if (hasInitializer) psiFactory.createExpression("field") else psiFactory.createBlock("TODO()")
                val getter = psiFactory.createPropertyGetter(expression)
                val added = if (element.setter != null) {
                    element.addBefore(getter, element.setter)
                } else {
                    element.add(getter)
                }
                if (!hasInitializer) {
                    (added as? KtPropertyAccessor)?.bodyBlockExpression?.statements?.firstOrNull()?.let {
                        editor?.caretModel?.moveToOffset(it.startOffset)
                    }
                }
            }
            if (addSetter) {
                val expression = if (hasInitializer) psiFactory.createBlock("field = value") else psiFactory.createEmptyBody()
                val setter = psiFactory.createPropertySetter(expression)
                val added = element.add(setter)
                if (!hasInitializer && !addGetter) {
                    (added as? KtPropertyAccessor)?.bodyBlockExpression?.lBrace?.let {
                        editor?.caretModel?.moveToOffset(it.startOffset + 1)
                    }
                }
            }
        }
    }
}