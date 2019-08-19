/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.buildtool

import com.intellij.build.output.BuildOutputInstantReader

class DummyBuildOutputInstantReader(parentEventId: Any) : BuildOutputInstantReader {
    private val _parentEventId: Any = parentEventId
    override fun getParentEventId(): Any = _parentEventId
    override fun readLine(): String? = null
    override fun pushBack() {}
    override fun pushBack(numberOfLines: Int) {}
}
