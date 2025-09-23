import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory

fun latestEapBuild(): String
{
	// 1) Prefer JetBrains Releases API to get a concrete numeric EAP build (e.g., 253.20558.101)
	val apiUris = listOf(
	  URI("https://data.services.jetbrains.com/products/releases?code=IIU&type=eap&latest=true&fields=build"),
	  URI("https://data.services.jetbrains.com/products/releases?code=IIU&type=eap&latest=true")
	)
	for (uri in apiUris)
	{
		val json = kotlin.runCatching {
			uri.toURL().openStream().bufferedReader().use { it.readText() }
		}.getOrNull()
		if (json != null)
		{
			val regex = """"build"\s*:\s*"([0-9.]+)"""".toRegex()
			val match = regex.find(json)
			if (match != null)
			{
				return match.groupValues[1] // e.g., 253.20558.101
			}
		}
	}

	// 2) Fallback: parse snapshots metadata and pick the last 3-part numeric EAP snapshot, then strip the suffix
	val snapshotsUri =
	  URI("https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/snapshots/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml")
	val versions = kotlin.runCatching {
		val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(snapshotsUri.toURL().openStream())
		doc.documentElement.normalize()
		val versioning = doc.getElementsByTagName("versioning").item(0)
		val versionsNode = versioning?.childNodes
		buildList {
			if (versionsNode != null)
			{
				for (i in 0 until versionsNode.length)
				{
					val n = versionsNode.item(i)
					if (n.nodeName == "versions")
					{
						val children = n.childNodes
						for (j in 0 until children.length)
						{
							val v = children.item(j)
							if (v.nodeName == "version") add(v.textContent)
						}
					}
				}
			}
		}
	}.getOrNull().orEmpty()

	// Look for versions like 253.17525.95-EAP-SNAPSHOT and convert to 253.17525.95
	val numericEap = versions.asSequence()
	  .mapNotNull { v ->
		  val m = Regex("""^(\d+\.\d+\.\d+)-EAP-SNAPSHOT$""").matchEntire(v)
		  m?.groupValues?.get(1)
	  }
	  .lastOrNull()

	if (numericEap != null) return numericEap

	throw IllegalStateException("No numeric EAP build found from JetBrains API or snapshots metadata")
}

val latestIdeaIuEap: String by lazy { latestEapBuild() }

fun isAtLeast2025_3(version: String): Boolean
{
	// Supports "YYYY.M[.patch]" and "BBB.xxx" (e.g., 253.17525.95)
	val yearMatch = Regex("""^(\d{4})\.(\d+)""").find(version)
	if (yearMatch != null)
	{
		val (year, minor) = yearMatch.destructured
		return year.toInt() > 2025 || (year.toInt() == 2025 && minor.toInt() >= 3)
	}
	val branchMatch = Regex("""^(\d{3})""").find(version)
	if (branchMatch != null)
	{
		return branchMatch.groupValues[1].toInt() >= 253
	}
	return false
}

plugins {    // gradle-intellij-plugin - read more: https://github.com/JetBrains/gradle-intellij-plugin
	id("org.jetbrains.intellij.platform") version "2.9.0"
	id("org.jetbrains.changelog") version "2.2.0"    // Java support
	java    // Kotlin support
	kotlin("jvm") version "2.2.0"
}

val isProductionBuild = project.hasProperty("productionBuild")
val timestamp by lazy { SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()) }

fun projectProperty(key: String) = project.findProperty(key).toString()

val pluginBaseVersion = projectProperty("ideDependencyVersion")

group = projectProperty("pluginGroup")
version = if (isProductionBuild)
{
	pluginBaseVersion
}
else "$pluginBaseVersion-$timestamp"

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
		val ideVersion = projectProperty("ideDependencyVersion")
		if (isAtLeast2025_3(ideVersion))
		{
			intellijIdea(ideVersion)
		}
		else
		{
			intellijIdeaUltimate(ideVersion)
		}
		val bundledPlatformPlugins =
		  projectProperty("bundledPlatformPlugins").split(',').map(String::trim).filter(String::isNotEmpty)
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
			// Read configured versions from gradle.properties
			val configured = projectProperty("pluginVerifierIdeVersions")
			  .split(',')
			  .map(String::trim)
			  .filter(String::isNotEmpty)

			// Resolve latest EAP build, but don't fail the build if it can't be fetched
			val latest = kotlin.runCatching { latestIdeaIuEap }.getOrNull()

			// Support optional "LATEST-EAP" token and also append latest if not explicitly present
			val normalized = configured.map { v ->
				if (v.equals("LATEST-EAP", ignoreCase = true)) latest ?: v else v
			}

			val combined = buildList {
				addAll(normalized)
				if (latest != null && normalized.none { it == latest }) add(latest)
			}
			  .filter { it != "LATEST-EAP" }
			  .distinct()

			if (latest == null)
			{
				logger.warn("Plugin Verifier: could not resolve latest EAP build; proceeding without it.")
			}
			else
			{
				logger.lifecycle("Plugin Verifier: including latest EAP $latest")
			}
			logger.lifecycle("Plugin Verifier IDEs: ${combined.joinToString(", ")}")

			combined.forEach { version ->
				val type =
				  if (isAtLeast2025_3(version)) IntelliJPlatformType.IntellijIdea else IntelliJPlatformType.IntellijIdeaUltimate
				create(type, version)
			}
		}

	}

}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
	groups.set(emptyList())
	version.set(pluginBaseVersion)
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
				  getOrNull(pluginBaseVersion)
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
