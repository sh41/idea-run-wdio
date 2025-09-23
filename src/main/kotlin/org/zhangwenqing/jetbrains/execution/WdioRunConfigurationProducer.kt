package org.zhangwenqing.jetbrains.execution

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.util.NodePackageDescriptor
import com.intellij.javascript.testFramework.JsTestElementPath
import com.intellij.javascript.testFramework.PreferableRunConfiguration
import com.intellij.javascript.testFramework.interfaces.mochaTdd.MochaTddFileStructureBuilder
import com.intellij.javascript.testFramework.jasmine.JasmineFileStructureBuilder
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.ObjectUtils
import com.jetbrains.nodejs.mocha.execution.MochaRunConfiguration

class WdioRunConfigurationProducer : LazyRunConfigurationProducer<WdioRunConfiguration>() {
	companion object {
		private val LOG = Logger.getInstance(WdioRunConfigurationProducer::class.java)
	}

	override fun getConfigurationFactory(): ConfigurationFactory =
		WdioConfigurationType.getInstance().configurationFactories[0]

	private fun getElementInfoFromContext(
		configuration: WdioRunConfiguration,
		context: ConfigurationContext
	): TestElementInfo? {
		val element = context.psiLocation ?: return null

		val karmaPluginId = PluginId.getId("Karma")
		if (!PluginManagerCore.isDisabled(karmaPluginId)) {
			val project = context.project
			val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
			val contextFile = element.containingFile?.virtualFile
			val karmaPackage =
				NodePackageDescriptor("karma").findFirstDirectDependencyPackage(project, interpreter, contextFile)
			if (!karmaPackage.isEmptyPath) return null
		}

		val virtualFile = PsiUtilCore.getVirtualFile(element) ?: return null

		if (!(virtualFile.name.endsWith(".e2e.ts") || virtualFile.name.endsWith(".e2e.js"))) {
			return null
		}
		return createTestElementRunInfo(element, configuration.getRunSettings())
	}

	override fun setupConfigurationFromContext(
		configuration: WdioRunConfiguration,
		context: ConfigurationContext,
		sourceElement: Ref<PsiElement>
	): Boolean {
		val elementRunInfo = getElementInfoFromContext(configuration, context) ?: return false

		val project = context.project
		val workingDir = project.basePath ?: ""
		val wdioConfig = findWdioConfig(context)

		val runSettingsBuilder = elementRunInfo.runSettings.builder()
			.setWorkingDir(workingDir)

		if (wdioConfig != null) {
			runSettingsBuilder.setWdioConfigFilePath(wdioConfig.path)
		} else if (elementRunInfo.runSettings.wdioConfigFilePath.isEmpty()) {
			runSettingsBuilder.setWdioConfigFilePath("wdio.conf.ts")
		}

		configuration.setRunSettings(runSettingsBuilder.build())
		sourceElement.set(elementRunInfo.enclosingTestElement)
		configuration.setGeneratedName()
		return true
	}

	override fun isConfigurationFromContext(
		configuration: WdioRunConfiguration,
		context: ConfigurationContext
	): Boolean {
		val elementRunInfo = getElementInfoFromContext(configuration, context) ?: return false
		val thisRunSettings = elementRunInfo.runSettings
		val thatRunSettings = configuration.getRunSettings()

		return thisRunSettings.testFilePath == thatRunSettings.testFilePath &&
				thisRunSettings.testNames == thatRunSettings.testNames
	}

	override fun isPreferredConfiguration(self: ConfigurationFromContext, other: ConfigurationFromContext?): Boolean {
		if (other != null) {
			val otherRc = ObjectUtils.tryCast(
				other.configuration,
				PreferableRunConfiguration::class.java
			)

			return (otherRc == null
					|| otherRc is MochaRunConfiguration
					|| !otherRc.isPreferredOver(self.configuration, self.sourceElement))
		}
		return true
	}

	private fun createSuiteOrTestData(element: PsiElement): Pair<String, JsTestElementPath>? {
		if (element is PsiFileSystemItem) return null
		val jsFile = ObjectUtils.tryCast(element.containingFile, JSFile::class.java)
		val textRange = element.textRange
		if (jsFile == null || textRange == null) return null

		var path = JasmineFileStructureBuilder.getInstance()
			.fetchCachedTestFileStructure(jsFile)
			.findTestElementPath(textRange)
		if (path != null) return Pair("bdd", path)

		val tddStructure = MochaTddFileStructureBuilder.getInstance().fetchCachedTestFileStructure(jsFile)
		path = tddStructure.findTestElementPath(textRange)
		return if (path != null) {
			if (tddStructure.hasMochaTypeScriptDeclarations()) Pair("mocha-typescript", path)
			else Pair("tdd", path)
		} else null
	}

	private fun findWdioConfig(context: ConfigurationContext): VirtualFile? {
		val project = context.project
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

	private fun createTestElementRunInfo(
		element: PsiElement,
		templateRunSettings: WdioRunSettings
	): TestElementInfo? {
		val virtualFile = PsiUtilCore.getVirtualFile(element) ?: return null
		val pair = createSuiteOrTestData(element)

		if (pair == null) {
			return createFileInfo(element, virtualFile, templateRunSettings)
		}

		val builder = templateRunSettings.builder()
		builder.setTestFilePath(virtualFile.path)
		val testElementPath = pair.second
		val testName = testElementPath.testName
		if (testName != null) {
			val names = ArrayList(testElementPath.suiteNames)
			names.add(testName)
			builder.setTestNames(names)

			val psiFile = ObjectUtils.tryCast(element.containingFile, JSFile::class.java)
			if (psiFile != null) {
				val lineNumbers = mutableListOf<Int>()
				PsiDocumentManager.getInstance(element.project)
					.getDocument(psiFile)?.getLineNumber(element.textOffset)?.let {
						lineNumbers.add(it + 1)
					}
				builder.setTestLineNumbers(lineNumbers)
			}
		}
		return TestElementInfo(builder.build(), testElementPath.testElement)
	}

	private fun createFileInfo(
		element: PsiElement,
		virtualFile: VirtualFile,
		templateRunSettings: WdioRunSettings
	): TestElementInfo? {
		if (virtualFile.isDirectory) {
			return null
		}

		val psiFile = element.containingFile
		if (psiFile != null) {
			val builder = templateRunSettings.builder()
			builder.setTestFilePath(virtualFile.path)
			return TestElementInfo(builder.build(), psiFile)
		}
		return null
	}

	private class TestElementInfo(
		val runSettings: WdioRunSettings,
		val enclosingTestElement: PsiElement
	)
}
