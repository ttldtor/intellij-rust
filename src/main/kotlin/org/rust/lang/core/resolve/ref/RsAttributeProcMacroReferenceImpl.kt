/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve.ref

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsPath
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.resolve.collectResolveVariants
import org.rust.lang.core.resolve.processAttributeProcMacroResolveVariants

class RsAttributeProcMacroReferenceImpl(element: RsPath) : RsPathReferenceBase(element) {
    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> =
        cachedMultiResolve().toTypedArray()

    override fun isReferenceTo(element: PsiElement): Boolean =
        element is RsFunction && super.isReferenceTo(element)

    override fun multiResolve(): List<RsElement> =
        cachedMultiResolve().mapNotNull { it.element as? RsElement }

    private fun cachedMultiResolve(): List<PsiElementResolveResult> {
        return RsResolveCache.getInstance(element.project)
            .resolveWithCaching(element, ResolveCacheDependency.LOCAL_AND_RUST_STRUCTURE, Resolver).orEmpty()
    }

    private object Resolver : (RsPath) -> List<PsiElementResolveResult> {
        override fun invoke(ref: RsPath): List<PsiElementResolveResult> {
            return resolve(ref).map { PsiElementResolveResult(it) }
        }

        private fun resolve(element: RsPath): List<RsElement> {
            val attributeMacroName = element.referenceName
            return collectResolveVariants(attributeMacroName) { processAttributeProcMacroResolveVariants(element, it) }
        }
    }
}
