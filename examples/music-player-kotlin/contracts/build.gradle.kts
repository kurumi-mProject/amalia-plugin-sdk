plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One checked-in stub source describes two host JVM facades. Split it before compilation
// so invokestatic owners match AmaliaScaffoldKt and GlassCardKt exactly.
val hostUiSource = rootProject.file("../../sdk/api/player-ui/HostUi.kt")
val generatedUi = layout.buildDirectory.dir("generated/host-ui")
val generateHostUi by tasks.registering {
    inputs.file(hostUiSource)
    outputs.dir(generatedUi)
    doLast {
        val source = hostUiSource.readText()
        val marker = "// GLASS_CARD_FACADE"
        val imports = source.substringBefore("@Composable\nfun AmaliaScreen")
        val directory = generatedUi.get().asFile.apply { mkdirs() }
        directory.resolve("AmaliaScaffold.kt").writeText(source.substringBefore(marker))
        directory.resolve("GlassCard.kt").writeText(
            imports.replace("AmaliaScaffoldKt", "GlassCardKt") + source.substringAfter(marker)
        )
    }
}
sourceSets.main {
    kotlin.srcDir(rootProject.file("../../sdk/api/minimal"))
    kotlin.srcDir(rootProject.file("../../sdk/api/player-ui"))
    // player-ui supplies the screen contracts; use the real Compose annotation from hostUi.
    kotlin.exclude("Screen.kt", "ComposeStubs.kt", "HostUi.kt")
    kotlin.srcDir(files(generatedUi).builtBy(generateHostUi))
}
