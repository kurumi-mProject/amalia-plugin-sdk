import groovy.json.JsonSlurper
import groovy.json.JsonOutput

plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
dependencies { compileOnly("org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT") }

// amalia.json is the single author source, including for the compiled SDK manifest.
val authorManifest = layout.projectDirectory.file("amalia.json")
val generated = layout.buildDirectory.dir("generated/amalia")
val generateAmaliaManifest by tasks.registering {
    inputs.file(authorManifest)
    outputs.dir(generated)
    doLast {
        val manifest = JsonSlurper().parse(authorManifest.asFile) as Map<*, *>
        fun literal(key: String) = JsonOutput.toJson(manifest[key]).replace("$", "\\$")
        val capabilityMap = manifest["capabilities"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val network = capabilityMap["network"] as? Map<*, *>
        val domains = network?.get("domains") as? List<*> ?: emptyList<Any>()
        val capabilityItems = mutableListOf<String>()
        if (domains.isNotEmpty()) capabilityItems += "Capability.Network(setOf(" + domains.joinToString(",") { JsonOutput.toJson(it).replace("$", "\\$") } + "))"
        val modules = mapOf("media" to listOf("background.media"), "background" to listOf("background.short", "scheduler"),
            "notifications" to listOf("notifications"), "browser" to listOf("browser"), "device" to listOf("device.read", "device.volume", "device.vibrate"),
            "secrets" to listOf("secrets"), "screen" to listOf("ui"))
        modules.forEach { (module, tokens) -> if (module in capabilityMap) tokens.forEach { token ->
            capabilityItems += "Capability.Host(" + JsonOutput.toJson(token) + "," + JsonOutput.toJson("Requested by " + module) + ")"
        } }
        val capabilityCode = "setOf(" + capabilityItems.joinToString(",") + ")"
        val target = generated.get().file("amalia/generated/GeneratedManifest.kt").asFile
        target.parentFile.mkdirs()
        target.writeText("""
            package amalia.generated
            import org.amalia.platform.protocol.sdk.PluginManifest
            import org.amalia.platform.protocol.sdk.Capability
            internal object GeneratedManifest {
                val value = PluginManifest(id = ${literal("id")}, name = ${literal("name")}, version = ${literal("version")}, capabilities = $capabilityCode)
            }
        """.trimIndent() + "\n")
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generated) }
tasks.named("compileKotlin") { dependsOn(generateAmaliaManifest) }
