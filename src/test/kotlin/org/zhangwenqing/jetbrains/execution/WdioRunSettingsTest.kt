package org.zhangwenqing.jetbrains.execution

import com.intellij.execution.configuration.EnvironmentVariablesData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.zhangwenqing.jetbrains.Common

class WdioRunSettingsTest : Common<WdioRunSettings>()
{
	@Test
	fun testWdioRunSettingDefaultValue()
	{
		val builder = WdioRunSettings.Builder()
		val settings = builder.build()
		assertNotNull(settings.interpreterRef)
		assertEquals("", settings.nodeOptions)
		assertNull(settings.wdioPackage)
		assertEquals("", settings.workingDir)
		assertEquals(EnvironmentVariablesData.DEFAULT, settings.envData)
		assertEquals("", settings.wdioConfigFilePath)
		assertEquals("", settings.testFilePath)
		assertTrue(settings.testNames.isEmpty())
		assertTrue(settings.testLineNumbers.isEmpty())
	}
}
