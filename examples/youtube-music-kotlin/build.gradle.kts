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
    kotlin("jvm") version "1.9.25" apply false
}

val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/Android/Sdk"
val androidApi = providers.gradleProperty("androidApi").getOrElse("35")
val androidJar = file("$androidHome/platforms/android-$androidApi/android.jar")
extra["androidJar"] = androidJar
if (!androidJar.isFile) error("Missing $androidJar; install platforms;android-$androidApi or set ANDROID_HOME")

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }

    // Resolve AAR artifacts (media3) by extracting classes.jar from them.
    val aarClasspath by configurations.creating {
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
        add(aarClasspath.name, "androidx.media3:media3-common:1.11.1")
        add(aarClasspath.name, "androidx.media3:media3-exoplayer:1.11.1")
    }
    val classpathDirectory = layout.buildDirectory.dir("aar-classpath")
    val extractAar by tasks.registering {
        inputs.files(aarClasspath)
        outputs.dir(classpathDirectory)
        doLast {
            val output = classpathDirectory.get().asFile
            project.delete(output)
            output.mkdirs()
            aarClasspath.incoming.artifacts.artifacts.forEach { artifact ->
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
    dependencies {
        add("compileOnly", files(androidJar))
        add("compileOnly", files(provider { fileTree(classpathDirectory).matching { include("**/*.jar") } }).builtBy(extractAar))
    }
}
