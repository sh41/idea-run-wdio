package org.zhangwenqing.jetbrains.execution

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.Filter
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.ui.ConsoleView
import com.intellij.javascript.debugger.CommandLineDebugConfigurator
import com.intellij.javascript.nodejs.NodeCommandLineUtil
import com.intellij.javascript.nodejs.NodeConsoleAdditionalFilter
import com.intellij.javascript.nodejs.NodeStackTraceFilter
import com.intellij.javascript.nodejs.debug.NodeDebuggableRunProfileState
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreter
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.javascript.nodejs.util.NodePackageDescriptor
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.execution.ParametersListUtil
import org.jetbrains.annotations.NotNull
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import org.zhangwenqing.jetbrains.WdioBundle
import org.zhangwenqing.jetbrains.WdioConstants.FRAMEWORK_CUCUMBER
import org.zhangwenqing.jetbrains.WdioConstants.FRAMEWORK_JASMINE
import org.zhangwenqing.jetbrains.WdioConstants.FRAMEWORK_MOCHA
import org.zhangwenqing.jetbrains.WdioConstants.TEAMCITY_REPORTER_PACKAGE
import org.zhangwenqing.jetbrains.quickfix.MissingReporterException
import org.zhangwenqing.jetbrains.quickfix.NOTIFICATION_GROUP_ID
import java.io.File
import java.nio.charset.StandardCharsets


class WdioRunProfileState(
	@param:NotNull private val project: Project,
	@param:NotNull private val runConfiguration: WdioRunConfiguration,
	@param:NotNull private val env: ExecutionEnvironment,
	@param:NotNull private val wdioPackage: NodePackage,
	@param:NotNull val runSettings: WdioRunSettings
) : NodeDebuggableRunProfileState
{
	private var myRerunActionFailedTests: List<List<String>>? = null

	override fun execute(configurator: CommandLineDebugConfigurator?): Promise<ExecutionResult> {
		val promise = AsyncPromise<ExecutionResult>()

		ApplicationManager.getApplication().executeOnPooledThread {
			try {
				val interpreter = runSettings.interpreterRef.resolveNotNull(project)
				val commandLine = createCommandLine(interpreter, configurator)

				// Proactively check for the reporter package before starting the process.
				val reporterPackage = NodePackageDescriptor(TEAMCITY_REPORTER_PACKAGE).findFirstDirectDependencyPackage(
					project,
					interpreter,
					runConfiguration.getContextFile()
				)

				if (reporterPackage.isEmptyPath) {
					// Intelligently find the best directory to run 'npm install' in.
					val installDir = findInstallWorkingDirectory()
						?: throw ExecutionException("Cannot find a package.json to install the reporter into.")
					// If the reporter is not found, fail early with our custom exception.
					throw MissingReporterException(
						interpreter = interpreter,
						env = env,
						installWorkingDirectory = installDir,
						commandLine = commandLine
					)
				}
				val processHandler = NodeCommandLineUtil.createProcessHandler(commandLine, false)
				ProcessTerminatedListener.attach(processHandler)

				ApplicationManager.getApplication().invokeLater {
					try {
						val executionResult = createExecutionResult(processHandler)
						promise.setResult(executionResult)
					} catch (e: Exception) {
						promise.setError(e)
					}
				}
			} catch (e: ExecutionException) {
				// Any exception before the process starts is handled by showing a notification.
				ApplicationManager.getApplication().invokeLater {
					handleSetupError(e, promise)
				}
			}
		}
		return promise
	}

	private fun handleSetupError(e: ExecutionException, promise: AsyncPromise<ExecutionResult>) {
		val notification = Notification(
			NOTIFICATION_GROUP_ID,
			"WebdriverIO run error",
			e.message ?: "An unknown error occurred.",
			NotificationType.ERROR
		)

		// If it's our specific exception for the missing reporter, add the "Install" action.
		if (e is MissingReporterException) {
			notification.addAction(object : AnAction(WdioBundle.message("wdio.quickfix.install.reporter.link.text")) {
				override fun actionPerformed(event: AnActionEvent) {
					e.navigate(project)
					notification.expire() // Hide notification after click
				}
			})
		}

		notification.notify(project)

		// Fulfill the promise with null. This signals to the IDE that the run has
		// failed to start, so no test runner or console should be shown.
		promise.setResult(null)
	}

	private fun createExecutionResult(processHandler: ProcessHandler): ExecutionResult {
		val consoleProperties = runConfiguration.createTestConsoleProperties(
			env.executor,
			NodeCommandLineUtil.shouldUseTerminalConsole(processHandler)
		)
		val consoleView = createSMTRunnerConsoleView(consoleProperties)
		consoleView.attachToProcess(processHandler)

		val rerunAction = consoleProperties.createRerunFailedTestsAction(consoleView)
		val actions = if (rerunAction != null) arrayOf(rerunAction) else emptyArray()

		return DefaultExecutionResult(consoleView, processHandler, *actions)
	}

	/**
	 * Intelligently finds the best directory to run 'npm install' in by checking for a package.json file.
	 * It first checks the run configuration's working directory, then falls back to the project's base directory.
	 */
	private fun findInstallWorkingDirectory(): String? {
		val workingDir = runSettings.workingDir
		if (workingDir.isNotBlank()) {
			val packageJsonInWorkingDir = File(workingDir, "package.json")
			if (packageJsonInWorkingDir.isFile) {
				return workingDir
			}
		}
		val projectBaseDir = project.basePath
		if (projectBaseDir != null) {
			val packageJsonInProjectRoot = File(projectBaseDir, "package.json")
			if (packageJsonInProjectRoot.isFile) {
				return projectBaseDir
			}
		}
		return null
	}

	@Throws(ExecutionException::class)
	private fun createCommandLine(
		interpreter: NodeJsInterpreter,
		configurator: CommandLineDebugConfigurator?
	): GeneralCommandLine {
		val commandLine = NodeCommandLineUtil.createCommandLineForTestTools()
		NodeCommandLineUtil.configureCommandLine(commandLine, configurator, interpreter) {
			configureCommandLine(commandLine, interpreter, it)
		}
		return commandLine
	}

	private fun createSMTRunnerConsoleView(consoleProperties: WdioConsoleProperties): ConsoleView
	{
		val baseTestsOutputConsoleView = SMTestRunnerConnectionUtil.createConsole(
			consoleProperties.testFrameworkName,
			(consoleProperties as TestConsoleProperties)
		)
		val workDir = runSettings.workingDir.ifBlank { project.basePath ?: "" }
		consoleProperties.addStackTraceFilter(NodeStackTraceFilter(this.project, workDir) as Filter)
		for (filter in consoleProperties.stackTrackFilters)
		{
			baseTestsOutputConsoleView.addMessageFilter(filter)
		}
		baseTestsOutputConsoleView.addMessageFilter(
			NodeConsoleAdditionalFilter(this.project, workDir) as Filter
		)
		return baseTestsOutputConsoleView
	}

	private fun configureCommandLine(
		commandLine: GeneralCommandLine,
		interpreter: NodeJsInterpreter,
		debugMode: Boolean
	)
	{
		val nodeOptions: List<String> = ArrayList(commandLine.parametersList.parameters)
		commandLine.parametersList.clearAll()
		commandLine.charset = StandardCharsets.UTF_8
		if (!StringUtil.isEmptyOrSpaces(this.runSettings.workingDir))
		{
			commandLine.withWorkDirectory(this.runSettings.workingDir)
		}
		NodeCommandLineUtil.configureUsefulEnvironment(commandLine)
		NodeCommandLineUtil.prependNodeDirToPATH(commandLine, interpreter)
		this.runSettings.envData.configureCommandLine(commandLine, true)
		val wdioMainFile = this.wdioPackage.findBinFilePath("wdio", null, interpreter)
			?: throw ExecutionException("Cannot find 'wdio' binary in '${this.wdioPackage.name}' package")

		commandLine.addParameter(wdioMainFile.toString())
		commandLine.addParameters(nodeOptions)
		commandLine.addParameters(ParametersListUtil.parse(this.runSettings.nodeOptions.trim()))

		commandLine.addParameter("run")
		var wdioConfigFilePath = this.runSettings.wdioConfigFilePath.trim()
		wdioConfigFilePath = wdioConfigFilePath.ifBlank { "wdio.conf.js" }
		val extraWdioOptionList = ParametersListUtil.parse(wdioConfigFilePath)
		commandLine.addParameters(extraWdioOptionList)

		// Ensure that the teamcity reporter is the loaded so that the test tree populates correctly.
		commandLine.addParameter("--reporters")
		commandLine.addParameter("teamcity")

		commandLine.addParameter("--framework")
		commandLine.addParameter(this.runSettings.framework)

		if (debugMode) {
			when (this.runSettings.framework) {
				FRAMEWORK_MOCHA ->
				{
					commandLine.addParameter("--mochaOpts.timeout")
				}
				FRAMEWORK_JASMINE ->
				{
					commandLine.addParameter("--jasmineOpts.defaultTimeoutInterval")
				}

				FRAMEWORK_CUCUMBER ->
				{
					commandLine.addParameter("--cucumberOpts.timeout")
				}
			}
			commandLine.addParameter("0")
		}

		if (!StringUtil.isEmptyOrSpaces(this.runSettings.testFilePath))
		{
			commandLine.addParameter("--spec")
			if (this.runSettings.testLineNumbers.isEmpty())
			{
				commandLine.addParameter(FileUtil.toSystemDependentName(this.runSettings.testFilePath))
			}
			else
			{
				commandLine.addParameter(
					"${
						FileUtil.toSystemDependentName(this.runSettings.testFilePath)
					}:${runSettings.testLineNumbers[0]}"
				)
			}
		}
	}

	fun setFailedTests(rerunActionFailedTests: List<List<String>>?)
	{
		myRerunActionFailedTests = rerunActionFailedTests
	}
}
