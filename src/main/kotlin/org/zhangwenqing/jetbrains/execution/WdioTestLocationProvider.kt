package org.zhangwenqing.jetbrains.execution

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.javascript.testFramework.JsTestSelector
import com.intellij.javascript.testFramework.util.EscapeUtils
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
import java.io.File
import java.net.URI


private const val WDIO_PROTOCOL_ID = "wdio"
private const val SPLIT_CHAR = '.'

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
		// Check for the custom 'wdio' protocol
		if (WDIO_PROTOCOL_ID == protocol)
		{
			try {
				// The 'path' from the IDE is the full locationHint string
				val uri = URI.create(path)
				val testFilePath = File(project.basePath, uri.path).absolutePath
				val testName = uri.fragment // The part after '#'

				if (testFilePath == null || testName == null) {
					return emptyList()
				}
				val location: Location<PsiElement>? = getTestLocation(project, testName, testFilePath)
			return ContainerUtil.createMaybeSingletonList(location)
			} catch (_: Exception) {
				// Log or handle malformed URI
				return emptyList()
			}
		}
		return emptyList()
	}

	private fun getTestLocation(
	  project: Project,
	  locationData: String,
	  testFilePath: String?
	): Location<PsiElement>?
	{
		var psiElement: PsiElement?
		val path = EscapeUtils.split(locationData, SPLIT_CHAR)
		if (path.isEmpty())
		{
			return null
		}

		psiElement = findJasmineElement(project, path, testFilePath)

		if (psiElement == null)
		{
			psiElement = findQUnitElement(project, path, testFilePath)
		}

		if (psiElement == null)
		{
			psiElement = findExportsElement(project, path, testFilePath)
		}

		if (psiElement == null)
		{
			psiElement = findTddElement(project, path, testFilePath)
		}

		return if (psiElement != null)
		{
			PsiLocation.fromPsiElement(psiElement)
		}
		else null
	}

	companion object
	{
		private fun findElement(
			project: Project,
			location: List<String>,
			testFilePath: String?,
			interfaceName: String
		): PsiElement? {
			if (location.isEmpty()) return null
			val suites = location.subList(0, location.size - 1)
			val testName = location.last()
			val testSelector = JsTestSelector(suites, testName)

			val virtualFiles = if (testFilePath != null) {
				listOfNotNull(findFile(testFilePath))
			} else {
				MochaDetector.instance.findTestFilesInIndexesBySelector(project, testSelector)
			}

			for (file in virtualFiles) {
				val jsFile = PsiManager.getInstance(project).findFile(file) as? JSFile ?: continue
				val element =
					MochaDetector.instance.findPsiElementByProbableInterface(jsFile, interfaceName, testSelector)
				if (element != null && element.isValid) {
					return element
				}
			}
			return null
		}

		private fun findJasmineElement(project: Project, location: List<String>, testFilePath: String?): PsiElement? {
			return findElement(project, location, testFilePath, "jasmine")
		}

		private fun findQUnitElement(project: Project, location: List<String>, testFilePath: String?): PsiElement?
		{
			if (testFilePath == null) return null
			val newLocation = when
			{
				location.size > 1 -> location
				else -> listOf("Default Module", location[0])
			}
			return findElement(project, newLocation, testFilePath, "qunit")
		}

		private fun findExportsElement(project: Project, location: List<String>, testFilePath: String?): PsiElement?
		{
			return findElement(project, location, testFilePath, "exports")
		}

		private fun findTddElement(project: Project, location: List<String>, testFilePath: String?): PsiElement?
		{
			return findElement(project, location, testFilePath, "mocha-tdd")
		}

		private fun findFile(filePath: String): VirtualFile?
		{
			return if (StringUtil.isEmptyOrSpaces(filePath)) null
			else LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(filePath))
		}
	}
}
