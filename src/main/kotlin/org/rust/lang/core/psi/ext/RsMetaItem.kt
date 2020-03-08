/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import org.rust.lang.core.psi.RsMetaItem
import org.rust.lang.core.psi.RsTraitItem

val RsMetaItem.name: String? get() {
    val stub = greenStub
    return if (stub != null) {
        stub.name
    } else {
        val path = path ?: return null
        if (path.hasColonColon) return null
        path.referenceName
    }
}

val RsMetaItem.value: String? get() = litExpr?.stringValue

val RsMetaItem.hasEq: Boolean get() = greenStub?.hasEq ?: (eq != null)

fun RsMetaItem.resolveToDerivedTrait(): RsTraitItem? =
    path?.reference?.resolve() as? RsTraitItem
