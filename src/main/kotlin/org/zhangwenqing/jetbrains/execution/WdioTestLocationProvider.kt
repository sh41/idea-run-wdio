package org.zhangwenqing.jetbrains.execution

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.javascript.testFramework.JsTestSelector
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.containers.ContainerUtil
import com.jetbrains.nodejs.mocha.execution.MochaDetector
import org.jetbrains.annotations.Nullable
import org.zhangwenqing.jetbrains.WdioConstants.WDIO_PROTOCOL_ID
import org.zhangwenqing.jetbrains.WdioConstants.WDIO_PROTOCOL_QUERY_PARAM_LOCATION
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets


class WdioTestLocationProvider : SMTestLocator
{
	override fun getLocation(
		protocol: String,
		path: String,
		project: Project,
		scope: GlobalSearchScope
	): List<Location<PsiElement>>
	{
		throw IllegalStateException("Should not be called")
	}

	override fun getLocation(
		protocol: String,
		path: String,
		@Nullable metaInfo: String?,
		project: Project,
		scope: GlobalSearchScope
	): List<Location<PsiElement>>
	{
		if (WDIO_PROTOCOL_ID != protocol) return emptyList()

		val location = runCatching {
			// The 'path' from the IDE is the full locationHint string
			val uri = URI.create(path)
			val testFilePath = File(project.basePath, uri.path).absolutePath
			val testSelectorPath =
				parseMultiValueQueryString(uri.query)[WDIO_PROTOCOL_QUERY_PARAM_LOCATION] ?: return emptyList()
			getTestLocation(project, testSelectorPath, testFilePath)
		}.getOrNull()

		return ContainerUtil.createMaybeSingletonList(location)
	}

	private fun getTestLocation(
		project: Project,
		testSelectorPath: List<String>,
		testFilePath: String?
	): Location<PsiElement>?
	{
		if (testSelectorPath.isEmpty()) return null

		val psiElement =
			findTest(project, testSelectorPath, testFilePath) ?: findSuite(project, testSelectorPath, testFilePath)

		return psiElement?.let { PsiLocation.fromPsiElement(it) }
	}

	companion object
	{
		private fun findTest(project: Project, location: List<String>, testFilePath: String?): PsiElement? {
			if (location.isEmpty()) return null
			val suites = location.subList(0, location.size - 1)
			val testName = location.last()
			val selector = JsTestSelector(suites, testName)
			return findElementBySelector(project, testFilePath, selector)
		}

		private fun findSuite(project: Project, location: List<String>, testFilePath: String?): PsiElement? {
			if (location.isEmpty()) return null
			val selector = JsTestSelector(location, null)
			return findElementBySelector(project, testFilePath, selector)
		}

		private fun findElementBySelector(
			project: Project,
			testFilePath: String?,
			selector: JsTestSelector
		): PsiElement? {
			val virtualFiles = if (testFilePath != null) {
				listOfNotNull(findFile(testFilePath))
			} else {
				MochaDetector.instance.findTestFilesInIndexesBySelector(project, selector)
			}

			for (file in virtualFiles) {
				val jsFile = PsiManager.getInstance(project).findFile(file) as? JSFile ?: continue
				// Check against different test framework styles
				for (interfaceName in listOf("jasmine", "qunit", "exports", "mocha-tdd")) {
					val element =
						MochaDetector.instance.findPsiElementByProbableInterface(jsFile, interfaceName, selector)
					if (element != null && element.isValid) {
						return element
					}
				}
			}
			return null
		}

		private fun findFile(filePath: String): VirtualFile?
		{
			return if (StringUtil.isEmptyOrSpaces(filePath)) null
			else LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(filePath))
		}

		private fun parseMultiValueQueryString(query: String): Map<String, List<String>> {
			val result = mutableMapOf<String, MutableList<String>>()
			if (query.isBlank()) {
				return result
			}

			query.split('&').forEach { pair ->
				val parts = pair.split('=', limit = 2)
				val encodedKey = parts[0]
				val encodedValue = if (parts.size > 1) parts[1] else ""

				// Manually replace '+' with its percent-encoded equivalent before decoding
				val decodedKey = URLDecoder.decode(encodedKey, StandardCharsets.UTF_8.toString())
				val decodedValue = URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.toString())

				result.getOrPut(decodedKey) { mutableListOf() }.add(decodedValue)
			}

			return result
		}
	}
}
