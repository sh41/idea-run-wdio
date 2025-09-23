package org.zhangwenqing.jetbrains.quickfix


import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessNotCreatedException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreter
import com.intellij.lang.javascript.JavaScriptBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.webcore.packaging.PackageManagementService
import org.zhangwenqing.jetbrains.WdioBundle
import org.zhangwenqing.jetbrains.WdioUtil
import java.nio.file.Path

const val NOTIFICATION_GROUP_ID = "WebdriverIO.NotificationGroup"

/**
 * A custom exception that is thrown when the wdio-teamcity-reporter is not found.
 * It implements HyperlinkInfo to provide a clickable "install" link in the run console.
 * By subclassing ProcessNotCreatedException, we signal to the IDE that this is a user-correctable
 * environment issue, not a plugin crash, thus preventing the "report error" notification.
 */
class MissingReporterException(
	private val interpreter: NodeJsInterpreter,
	private val env: ExecutionEnvironment,
	private val installWorkingDirectory: String, // The pre-verified directory to run 'npm install' in.
	commandLine: GeneralCommandLine // The command that would have been executed.
) : ProcessNotCreatedException(
	WdioBundle.message(
		"wdio.quickfix.reporter.missing.message",
		HtmlChunk.link("", WdioBundle.message("wdio.quickfix.install.reporter.link.text"))
	), commandLine
), HyperlinkInfo {

	override fun navigate(project: Project) {
		val installerService = com.intellij.lang.javascript.modules.NpmPackageInstallerService.getInstance()

		installerService.installPackage(
			project,
			interpreter,
			WdioUtil.TEAMCITY_REPORTER_PACKAGE,
			null, // version
			Path.of(installWorkingDirectory),
			object : PackageManagementService.Listener {
				override fun operationStarted(packageName: String) {}

				override fun operationFinished(
					packageName: String,
					errorDescription: PackageManagementService.ErrorDescription?
				) {
					ApplicationManager.getApplication().invokeLater {
						if (errorDescription == null) {
							// On successful installation, restart the original run configuration.
							ExecutionUtil.restart(env)
						} else {
							// On failure, show an error notification balloon.
							val html = HtmlBuilder()
								.append(
									HtmlChunk.text(
										JavaScriptBundle.message(
											"npm.failed_to_install_package.title.message",
											WdioUtil.TEAMCITY_REPORTER_PACKAGE
										) + ":"
									)
								)
								.br()
								.append(HtmlChunk.text(errorDescription.message))
								.toString()
							Notification(NOTIFICATION_GROUP_ID, "Installation failed", html, NotificationType.ERROR)
								.notify(project)
						}
					}
				}
			},
			"--save-dev" // Install as a dev dependency
		)
	}
}
