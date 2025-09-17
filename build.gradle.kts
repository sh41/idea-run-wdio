import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.text.SimpleDateFormat
import java.util.*

plugins {    // gradle-intellij-plugin - read more: https://github.com/JetBrains/gradle-intellij-plugin
	id("org.jetbrains.intellij.platform") version "2.9.0"
	id("org.jetbrains.changelog") version "2.2.0"    // Java support
	java    // Kotlin support
	kotlin("jvm") version "2.2.0"
}

val isProductionBuild = project.hasProperty("productionBuild")
val timestamp by lazy { SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()) }

fun projectProperty(key: String) = project.findProperty(key).toString()

group = projectProperty("pluginGroup")
version = if (isProductionBuild) projectProperty("pluginVersion") else "${projectProperty("pluginVersion")}-$timestamp"

repositories {
	if (!System.getenv("USE_ALI_REPO").isNullOrEmpty())
	{
		maven {
			setUrl("https://maven.aliyun.com/nexus/content/groups/public/")
		}
	}
	maven {
		url = uri("https://download.jetbrains.com/teamcity-repository/")
	}
	mavenCentral()
	intellijPlatform {
		defaultRepositories()
		intellijDependencies()
	}
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation("org.jetbrains:annotations:13.0")
	testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
	testRuntimeOnly("com.jetbrains.intellij.platform:test-framework-junit5:252.23892.409")
	testRuntimeOnly("junit:junit:4.13.2")
	intellijPlatform {
		testFramework(TestFrameworkType.JUnit5)
		bundledPlugin("com.intellij.modules.json")
		bundledPlugin("com.intellij.modules.ultimate")
		bundledPlugin("JUnit")
		intellijIdeaUltimate("2025.2")
		val bundledPlatformPlugins = projectProperty("bundledPlatformPlugins").split(',').map(String::trim).filter(String::isNotEmpty)
		if (bundledPlatformPlugins.isNotEmpty())
		{
			bundledPlugins(*bundledPlatformPlugins.toTypedArray())
		}
	}
}
intellijPlatformTesting {
	runIde
	testIde
	testIdeUi
	testIdePerformance
}

// Configure gradle-intellij-plugin plugin.
// Read more: https://github.com/JetBrains/gradle-intellij-plugin
intellijPlatform {
	pluginConfiguration {
		name.set(if (isProductionBuild) projectProperty("pluginName") else "${projectProperty("pluginName")} Test Build $timestamp")
	}
	pluginVerification {
		ides {
			val versions = projectProperty("pluginVerifierIdeVersions")
			  .split(',')
			  .map(String::trim)
			  .filter(String::isNotEmpty)
			versions.forEach { version ->
				create(IntelliJPlatformType.IntellijIdeaUltimate, version)
			}
		}

	}

}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
	groups.set(emptyList())
	version.set(projectProperty("pluginVersion"))
	repositoryUrl.set(projectProperty("pluginRepositoryUrl"))
}


// Set the JVM language level used to build the project. Use Java 11 for 2020.3+, and Java 17 for 2022.2+.
kotlin {
	jvmToolchain(21)
}

val runIdeForUiTests by intellijPlatformTesting.runIde.registering {
	task {
		jvmArgumentProviders += CommandLineArgumentProvider {
			listOf(
			  "-Drobot-server.port=8082",
			  "-Dide.mac.message.dialogs.as.sheets=false",
			  "-Djb.privacy.policy.text=<!--999.999-->",
			  "-Djb.consents.confirmation.enabled=false",
			)
		}
	}

	plugins {
		robotServerPlugin()
	}
}
tasks {
	wrapper {
		gradleVersion = "8.14"
		distributionType = Wrapper.DistributionType.ALL
	}

	withType<KotlinCompile> {
		compilerOptions {
			jvmTarget.set(JvmTarget.JVM_21)
			freeCompilerArgs.set(listOf("-Xjvm-default=all-compatibility"))
		}
	}

	patchPluginXml {
		sinceBuild.set(projectProperty("pluginSinceBuild"))
		untilBuild.set(projectProperty("pluginUntilBuild"))

		val start = "<!-- Plugin description -->"
		val end = "<!-- Plugin description end -->"
		pluginDescription.set(
		  file("README.md").readText().lines().run {
			  if (!containsAll(listOf(start, end)))
			  {
				  throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
			  }
			  subList(indexOf(start) + 1, indexOf(end))
		  }.joinToString("\n").run { markdownToHTML(this) }
		)
		// Get the latest available change notes from the changelog file
		changeNotes.set(provider {
			with(changelog) {
				renderItem(
				  getOrNull(projectProperty("pluginVersion"))
					?: kotlin.runCatching { getLatest() }.getOrElse { getUnreleased() },
				  Changelog.OutputType.HTML,
				)
			}
		})
	}
	withType<Test> {
		useJUnitPlatform()

	}


	//	runIde {
	//		plugins.set(projectProperty("bundledPlatformPlugins").split(',').map(String::trim).filter(String::isNotEmpty))
	//	}

	signPlugin {
		certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
		privateKey.set(System.getenv("PRIVATE_KEY"))
		password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
	}

	publishPlugin {
		dependsOn("patchChangelog")
		token.set(System.getenv("PUBLISH_TOKEN"))

		// pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
		// Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
		// https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
		channels.set(listOf("stable"))
	}

}
dependencyAnalysis {
	issues {
		all {
			onAny {
				severity("fail")
			}
		}
	}
}
