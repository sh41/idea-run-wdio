package org.zhangwenqing.jetbrains

import org.junit.jupiter.api.Assertions
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

const val NEWLINE = "\n"

@Suppress("UnnecessaryAbstractClass")

abstract class Common<T> {
	@Throws(
		NoSuchMethodException::class,
		IllegalAccessException::class,
		InvocationTargetException::class,
		InstantiationException::class
	)
	protected fun privateConstructor(clz: Class<T>) {
		val constructor = clz.getDeclaredConstructor()
		Assertions.assertTrue(Modifier.isPrivate(constructor.modifiers))
		constructor.isAccessible = true
		constructor.newInstance()
	}
}
