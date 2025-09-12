package icons

import org.junit.jupiter.api.Test
import org.zhangwenqing.jetbrains.Common
import java.lang.reflect.InvocationTargetException

class WdioIconsTest : Common<WdioIcons>()
{
	@Test
	@Throws(
	  InvocationTargetException::class,
	  NoSuchMethodException::class,
	  InstantiationException::class,
	  IllegalAccessException::class
	)
	fun testPrivateConstructor()
	{
		privateConstructor(WdioIcons::class.java)
	}
}
