/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve.ref

import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.RsPath
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.BoundElement

interface RsPathReference : RsReference {
    override fun getElement(): RsPath

    fun advancedResolve(): BoundElement<RsElement>? =
        resolve()?.let { BoundElement(it) }
}

abstract class RsPathReferenceBase(element: RsPath): RsReferenceBase<RsPath>(element), RsPathReference {
    override val RsPath.referenceAnchor: PsiElement get() = referenceNameElement
}

class RsEmptyPathReference(element: RsPath) : RsPathReferenceBase(element) {
    override fun multiResolve(): List<RsElement> = emptyList()
}
