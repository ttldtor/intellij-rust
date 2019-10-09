/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.sdk

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.projectRoots.SdkType

object RsSdkType : SdkType {
    private val LOG = Logger.getInstance(RsSdkType::class.java)

    const val SDK_TYPE_ID: String = "Rust Toolchain"
}
