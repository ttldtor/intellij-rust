/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.configurable

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Comparing
import org.rust.ide.sdk.RsSdkAdditionalData
import org.rust.ide.sdk.RsSdkType
import java.util.*


class RsConfigurableToolchainList {
    private var _model: ProjectSdksModel? = null

    val model: ProjectSdksModel
        get() {
            if (_model == null) {
                _model = ProjectSdksModel().apply { reset(null) }
            }
            return checkNotNull(_model) { "Set to null by another thread" }
        }

    val allRustSdks: List<Sdk>
        get() = getAllRustSdks(null)

    fun disposeModel() {
        if (_model != null) {
            _model?.disposeUIResources()
            _model = null
        }
    }

    fun getAllRustSdks(project: Project?): List<Sdk> =
        model.sdks
            .filter { it.sdkType is RsSdkType }
            .sortedWith(RsToolchainComparator(project))

    private class RsToolchainComparator internal constructor(private val myProject: Project?) : Comparator<Sdk> {

        override fun compare(o1: Sdk, o2: Sdk): Int {
            if (o1.sdkType !is RsSdkType || o2.sdkType !is RsSdkType) {
                return -Comparing.compare(o1.name, o2.name)
            }

            val isRemote1 = RsSdkUtil.isRemote(o1)
            val isRemote2 = RsSdkUtil.isRemote(o2)

            return when {
                isRemote1 && !isRemote2 -> 1
                !isRemote1 && isRemote2 -> -1
                else -> o1.name.compareTo(o2.name)
            }
        }

        private fun associatedWithCurrent(o1: Sdk, project: Project): Boolean {
            val data = o1.sdkAdditionalData as? RsSdkAdditionalData
            if (data != null) {
                val path = data.getAssociatedModulePath()
                val projectBasePath = project.basePath
                if (path != null && path == projectBasePath) {
                    return true
                }
            }
            return false
        }
    }

    companion object {
        fun getInstance(project: Project?): RsConfigurableToolchainList {
            val effectiveProject = project ?: ProjectManager.getInstance().defaultProject
            val instance = effectiveProject.service<RsConfigurableToolchainList>()
            if (effectiveProject !== project) {
                instance.disposeModel()
            }
            return instance
        }
    }
}
