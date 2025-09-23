package org.zhangwenqing.jetbrains

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.execution.TestStateStorage
import com.intellij.execution.testframework.TestIconMapper
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import java.net.URI

class WdioTestGutterIconProvider : LineMarkerProvider, DumbAware {

	override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
		// Only run in JavaScript or TypeScript files
		if (element.containingFile !is JSFile) {
			return null
		}
		// We only care about the name of the function being called, e.g., 'it' or 'describe'
		val callExpression = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return null
		val methodExpression = callExpression.methodExpression ?: return null

		if (element.node !== methodExpression.node.firstChildNode) return null

		val testName = methodExpression.text
		if (testName != "it" && testName != "describe") {
			return null
		}

		val locationUrl = buildLocationUrl(callExpression, element.project) ?: return null
		val stateStorage = TestStateStorage.getInstance(element.project)

		val testRecord = stateStorage.getState(locationUrl) ?: return null

		// This is the correct, pragmatic way to get the icon.
		// We use the unstable 'getMagnitude' and suppress the warning, as there is no stable public alternative.
		val magnitude = TestIconMapper.getMagnitude(testRecord.magnitude) ?: return null
		val icon = TestIconMapper.getIcon(magnitude) ?: return null

		// The annotation is removed as it's not applicable to a local variable.
		val tooltip = "Test status: ${magnitude.title}"

		// Use the modern constructor which includes an accessible name provider for screen readers.
		return LineMarkerInfo(
			element,
			element.textRange,
			icon,
			{ _ -> tooltip },
			null,
			GutterIconRenderer.Alignment.CENTER,
			{ tooltip } // Accessible Name Provider
		)
	}

	// Modify the function to accept the project and calculate a relative path
	private fun buildLocationUrl(callExpression: JSCallExpression, project: Project): String? {
		val virtualFile = callExpression.containingFile?.virtualFile ?: return null
		val projectBasePath = project.basePath ?: return null

		// Calculate the relative path from the project's base directory
		val relativePath = FileUtil.getRelativePath(projectBasePath, virtualFile.path, '/') ?: return null

		val fullTitle = buildFullTitle(callExpression) ?: return null

		// Use the URI class to correctly encode the fragment
		val encodedFragment = URI(null, null, null, null, fullTitle).rawFragment

		return "wdio://$relativePath#$encodedFragment"
	}


	private fun getTitleFromCallExpression(callExpression: JSCallExpression): String? {
		val methodExpr = callExpression.methodExpression ?: return null
		if (methodExpr.text != "it" && methodExpr.text != "describe") {
			return null
		}

		val firstArg = callExpression.arguments.getOrNull(0)
		if (firstArg is JSLiteralExpression && firstArg.isQuotedLiteral) {
			return firstArg.stringValue
		}
		return null
	}

	private fun buildFullTitle(element: JSCallExpression): String? {
		val titles = mutableListOf<String>()
		var current: JSCallExpression? = element

		while (current != null) {
			val titlePart = getTitleFromCallExpression(current) ?: return null
			titles.add(titlePart)
			current = PsiTreeUtil.getParentOfType(current, JSCallExpression::class.java, true)
		}

		if (titles.isEmpty()) return null
		return titles.reversed().joinToString(".")
	}
}
