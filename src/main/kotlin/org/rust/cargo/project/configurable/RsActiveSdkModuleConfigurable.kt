/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.configurable

import com.intellij.application.options.ModuleAwareProjectConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.util.PlatformUtils

class RsActiveSdkModuleConfigurable(project: Project)
    : ModuleAwareProjectConfigurable<*>(project, "Project Toolchain", if (PlatformUtils.isCLion()) "rustsupport" else null) {

    override fun createModuleConfigurable(module: Module): UnnamedConfigurable {
        return PyActiveSdkConfigurable(module)
    }

    override fun createDefaultProjectConfigurable(): UnnamedConfigurable {
        return PyActiveSdkConfigurable(project)
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        super.apply()
        val sdk = ModuleManager.getInstance(project).modules.mapNotNull { ModuleRootManager.getInstance(it).sdk }.firstOrNull()
        if (sdk != null) {
            ApplicationManager.getApplication().executeOnPooledThread { VFSTestFrameworkListener.getInstance().updateAllTestFrameworks(sdk) }
        }
    }
}
