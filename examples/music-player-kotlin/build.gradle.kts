import org.gradle.api.attributes.Attribute
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

abstract class AndroidHostCompatibility : AttributeCompatibilityRule<String> {
    override fun execute(details: CompatibilityCheckDetails<String>) {
        if (details.consumerValue == "androidJvm" && details.producerValue == "jvm") details.compatible()
    }
}
abstract class AndroidHostDisambiguation : AttributeDisambiguationRule<String> {
    override fun execute(details: MultipleCandidatesDetails<String>) {
        if ("androidJvm" in details.candidateValues) details.closestMatch("androidJvm")
    }
}

plugins {
    kotlin("jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/Android/Sdk"
val androidApi = providers.gradleProperty("androidApi").getOrElse("35")
val androidJar = file("$androidHome/platforms/android-$androidApi/android.jar")
extra["androidJar"] = androidJar
if (!androidJar.isFile) error("Missing $androidJar; install platforms;android-$androidApi or set ANDROID_HOME")

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.compose")

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            )
        }
    }

    // Resolve Android variants transitively, then expose only bytecode to the JVM compiler.
    val hostUi by configurations.creating {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java), "androidJvm")
        }
    }
    dependencies.attributesSchema.attribute(Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java)) {
        compatibilityRules.add(AndroidHostCompatibility::class.java)
        disambiguationRules.add(AndroidHostDisambiguation::class.java)
    }
    dependencies {
        add(hostUi.name, platform("androidx.compose:compose-bom:2026.08.00"))
        listOf(
            "androidx.compose.runtime:runtime",
            "androidx.compose.runtime:runtime-saveable",
            "androidx.compose.ui:ui",
            "androidx.compose.ui:ui-graphics",
            "androidx.compose.foundation:foundation",
            "androidx.compose.material3:material3",
            "androidx.compose.material:material-icons-core:1.6.8",
            "androidx.compose.material:material-icons-extended:1.6.8",
            "androidx.activity:activity-compose:1.13.0",
            "androidx.lifecycle:lifecycle-runtime-compose:2.11.0",
            "androidx.lifecycle:lifecycle-runtime:2.11.0",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1",
        ).forEach { add(hostUi.name, it) }
    }
    val classpathDirectory = layout.buildDirectory.dir("host-ui-classpath")
    val extractHostUi by tasks.registering {
        inputs.files(hostUi)
        outputs.dir(classpathDirectory)
        doLast {
            val output = classpathDirectory.get().asFile
            project.delete(output)
            output.mkdirs()
            hostUi.incoming.artifacts.artifacts.forEach { artifact ->
                val input = artifact.file
                val directory = output.resolve(input.nameWithoutExtension).apply { mkdirs() }
                when (input.extension) {
                    "jar" -> input.copyTo(directory.resolve(input.name), overwrite = true)
                    "aar" -> java.util.zip.ZipFile(input).use { archive ->
                        archive.entries().asSequence().filter {
                            it.name == "classes.jar" || (it.name.startsWith("libs/") && it.name.endsWith(".jar"))
                        }.forEach { entry ->
                            val target = directory.resolve(entry.name)
                            require(target.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                            target.parentFile.mkdirs()
                            archive.getInputStream(entry).use { source -> target.outputStream().use { source.copyTo(it) } }
                        }
                    }
                }
            }
        }
    }
    if (name == "plugin") {
        val dexCompiler by configurations.creating {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
        }
        dependencies.add(dexCompiler.name, "com.android.tools:r8:8.10.21")
        tasks.register<JavaExec>("dex") {
            dependsOn(tasks.named("jar"))
            classpath = dexCompiler
            mainClass.set("com.android.tools.r8.D8")
            val output = rootProject.layout.projectDirectory.dir("dex")
            inputs.file(tasks.named<Jar>("jar").flatMap { it.archiveFile })
            outputs.dir(output)
            doFirst { output.asFile.mkdirs() }
            args("--min-api", "26", "--lib", androidJar.absolutePath,
                "--output", output.asFile.absolutePath,
                tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
        }
    }
    dependencies {
        add("compileOnly", files(androidJar))
        add("compileOnly", files(provider { fileTree(classpathDirectory).matching { include("**/*.jar") } }).builtBy(extractHostUi))
    }
}
