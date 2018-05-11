/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.refactoring

import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtElement

class KotlinVetoRenameCondition: Condition<PsiElement> {
    override fun value(t: PsiElement?): Boolean =
            t is KtElement && t is PsiNameIdentifierOwner && t.nameIdentifier == null && t !is KtConstructor<*>
}