package org.zhangwenqing.jetbrains

import com.intellij.ide.util.PropertiesComponent
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.javascript.nodejs.util.NodePackageDescriptor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.NotNull
import org.zhangwenqing.jetbrains.WdioConstants.NODE_PACKAGE_NAME
import org.zhangwenqing.jetbrains.WdioConstants.WDIO_PACKAGE_DIR__KEY


object WdioUtil
{
	private val LOG: Logger = Logger.getInstance(WdioUtil::class.java)

	val PACKAGE_DESCRIPTOR: NodePackageDescriptor = NodePackageDescriptor(NODE_PACKAGE_NAME)


	@NotNull
	fun getWdioPackage(@NotNull project: Project): NodePackage
	{
		val packageDir = PropertiesComponent.getInstance(project).getValue(WDIO_PACKAGE_DIR__KEY)
		return PACKAGE_DESCRIPTOR.createPackage(StringUtil.notNullize(packageDir))
	}

	fun setWdioPackage(project: Project, wdioPackage: NodePackage)
	{
		PropertiesComponent.getInstance(project)
			.setValue(WDIO_PACKAGE_DIR__KEY, wdioPackage.systemIndependentPath)
	}

	fun findWdioConfig(project: Project): VirtualFile? {
		// A list of common config file names, in order of preference
		val configNames = listOf(
			"wdio.local.conf.ts", "wdio.local.conf.js",
			"wdio.ios.conf.ts", "wdio.ios.conf.js",
			"wdio.conf.ts", "wdio.conf.js"
		)

		// Search for the file in the project's content roots
		val projectFileIndex = ProjectFileIndex.getInstance(project)
		var foundConfig: VirtualFile? = null
		projectFileIndex.iterateContent { file ->
			if (!file.isDirectory && file.name in configNames) {
				foundConfig = file
				return@iterateContent false // Stop searching once found
			}
			true
		}

		if (foundConfig != null) {
			LOG.debug("Found wdio config file: ${foundConfig.path}")
		} else {
			LOG.debug("No wdio config file found in project.")
		}
		return foundConfig
	}
}
