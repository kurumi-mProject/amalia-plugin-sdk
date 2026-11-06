#!/usr/bin/env python3
"""SDK3 authoring CLI. Only the existing signed android-dex format is emitted."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SDK = "0.2.0-SNAPSHOT"
DECLARATION_MODULES = {"media", "background", "notifications", "browser", "device", "secrets", "screen"}
RESERVED = ("com/my/amali/", "org/amalia/platform/", "tech/amalia/platform/",
            "java/", "javax/", "android/", "androidx/", "dalvik/", "sun/",
            "com/android/", "kotlin/", "kotlinx/")
ID = r"[a-z0-9]+(?:[.-][a-z0-9]+)+"
JAVA = r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+"
NETWORK_WARNING = ("Network domains narrow the compiled SDK facade, but grant no authority. "
                   "No grants are provisioned: the host independently approves each exact domain. "
                   "Signed capability review in the Android host is required before any network request.")


class CliError(Exception):
    pass


def fail(message):
    raise CliError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path):
    if path.stat().st_size > 1024 * 1024:
        fail(f"JSON exceeds 1 MiB: {path}")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def domain(value):
    if not isinstance(value, str) or len(value) > 253 or not re.fullmatch(
        r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+"
        r"[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?", value
    ):
        fail(f"Expected a lowercase DNS hostname (no URL, wildcard, port or path): {value!r}")
    return value


def validate(project):
    project = Path(project).resolve()
    manifest = read_json(project / "amalia.json")
    fields = {"schemaVersion", "id", "name", "version", "publisher", "entryPoint", "capabilities"}
    if not isinstance(manifest, dict) or set(manifest) != fields:
        fail("amalia.json must contain exactly: " + ", ".join(sorted(fields)))
    if type(manifest["schemaVersion"]) is not int or manifest["schemaVersion"] != 1:
        fail("Unsupported author manifest schemaVersion (expected 1)")
    for field in ("id", "publisher"):
        value = manifest[field]
        if not isinstance(value, str) or len(value) > 190 or not re.fullmatch(ID, value):
            fail(f"Invalid {field}: expected a lowercase dotted/hyphenated ID, at most 190 characters")
    if not manifest["id"].startswith(manifest["publisher"] + "."):
        fail("id must be within the publisher namespace (publisher + '.')")
    name = manifest["name"]
    if not isinstance(name, str) or not name.strip() or len(name) > 128 or any(ord(c) < 32 for c in name):
        fail("name must be nonempty printable text, at most 128 characters")
    version = manifest["version"]
    if not isinstance(version, str) or not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        fail("version must be a stable major.minor.patch version")
    if any(int(part) > 2147483647 for part in version.split(".")):
        fail("Version components must fit a signed 32-bit integer")
    entry = manifest["entryPoint"]
    if not isinstance(entry, str) or len(entry) > 512 or not re.fullmatch(JAVA, entry):
        fail("entryPoint must be a fully qualified Java class name")
    if (entry.replace(".", "/") + "/").startswith(RESERVED):
        fail("entryPoint uses a reserved host/shared namespace")
    capabilities = manifest["capabilities"]
    if not isinstance(capabilities, dict) or set(capabilities) - ({"network"} | DECLARATION_MODULES):
        fail("Unknown capability; supported declarations: network, " + ", ".join(sorted(DECLARATION_MODULES)))
    for module in DECLARATION_MODULES & set(capabilities):
        if not isinstance(capabilities[module], dict) or capabilities[module]:
            fail(f"{module} is a declaration-only capability and must be an empty object")
    if "network" in capabilities:
        network = capabilities["network"]
        if not isinstance(network, dict) or set(network) != {"domains"}:
            fail("network must contain only domains")
        domains = network["domains"]
        if not isinstance(domains, list) or not 1 <= len(domains) <= 128:
            fail("network.domains must contain 1–128 DNS hostnames")
        for item in domains:
            domain(item)
        if len(set(domains)) != len(domains):
            fail("Duplicate network domains")
    for filename in ("settings.gradle.kts", "build.gradle.kts"):
        if not (project / filename).is_file():
            fail(f"Missing {filename}: expected a single-project Kotlin/JVM plugin")
    if (project / "platform.json").exists():
        fail("Do not author platform.json; amalia.json is the only author manifest")
    sources = project / "src/main"
    if not sources.is_dir() or not any(sources.rglob("*.kt")):
        fail("No Kotlin source files under src/main")
    reject_source_payloads(project)
    return project, manifest


def reject_source_payloads(project):
    for root in (project / "src/main/resources", project / "src/main/res", project / "src/main/assets",
                 project / "src/main/jniLibs", project / "src/main/jni"):
        if root.exists() and any(p.is_file() for p in root.rglob("*")):
            fail(f"Resources/native payloads are not supported by the single-DEX format: {root}")
    if (project / "src/main/AndroidManifest.xml").exists():
        fail("Android projects/AARs are unsupported; use a pure Kotlin/JVM plugin")


SETTINGS = '''pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins { id("org.jetbrains.kotlin.jvm") version "2.2.10" }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(providers.gradleProperty("amaliaSdkRepository").getOrElse("sdk-repository")) }
        mavenCentral()
    }
}
rootProject.name = "amalia-plugin"
'''

BUILD = r'''import groovy.json.JsonSlurper
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
'''

SOURCE = '''package {namespace}

import org.amalia.platform.protocol.sdk.AmaliaPlugin
import org.amalia.platform.protocol.sdk.AmaliaApi
import amalia.generated.GeneratedManifest
import kotlinx.serialization.json.JsonPrimitive

class Plugin : AmaliaPlugin() {{
    override val manifest = GeneratedManifest.value

    override suspend fun install(api: AmaliaApi) {{
        api.tools.register("echo", "Return a greeting") {{
            string("query", description = "Text to echo")
            execute {{ JsonPrimitive("Hello from " + manifest.name + ": " + string("query")) }}
        }}
    }}
}}
'''

# Resolve the actual runtimeClasspath through Gradle, not by guessing build/libs names.
# The init script is temporary and never becomes another author configuration file.
INIT = r'''import groovy.json.JsonOutput
gradle.projectsEvaluated {
    def p = gradle.rootProject
    p.tasks.register('amaliaCliInputs') {
        dependsOn p.tasks.named('jar')
        doLast {
            def artifacts = { configuration ->
                configuration.resolvedConfiguration.resolvedArtifacts.collect { a ->
                    [path: a.file.absolutePath, group: a.moduleVersion.id.group, name: a.name]
                }
            }
            def jar = p.tasks.named('jar').get().archiveFile.get().asFile
            def data = [jar: jar.absolutePath,
                runtime: artifacts(p.configurations.getByName('runtimeClasspath')),
                compile: artifacts(p.configurations.getByName('compileClasspath'))]
            new File(System.getProperty('amalia.cli.inputs')).text = JsonOutput.toJson(data)
        }
    }
}
'''


def create(args):
    if not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", args.name) or len(args.name) > 80:
        fail("NAME must be a lowercase slug, e.g. weather-plugin (at most 80 characters)")
    target = Path(args.directory or args.name).resolve()
    if target.exists():
        fail(f"Refusing to overwrite existing path: {target}")
    namespace = "org.example.plugin_" + args.name.replace("-", "_")
    plugin_id = "org.example." + args.name
    manifest = dict(schemaVersion=1, id=plugin_id, name=args.name, version="1.0.0",
                    publisher="org.example", entryPoint=namespace + ".Plugin", capabilities={})
    target.mkdir(parents=True)
    write_json(target / "amalia.json", manifest)
    (target / "settings.gradle.kts").write_text(SETTINGS, encoding="utf-8")
    (target / "build.gradle.kts").write_text(BUILD, encoding="utf-8")
    source = target / "src/main/kotlin" / namespace.replace(".", "/") / "Plugin.kt"
    source.parent.mkdir(parents=True)
    source.write_text(SOURCE.format(namespace=namespace), encoding="utf-8")
    print(f"Created {target}\nSet publisher/id in amalia.json before distribution. "
          f"Set -PamaliaSdkRepository to an SDK Maven repository containing the SDK3 facade ({SDK}).")


def gradle_command(project):
    wrapper = project / "gradlew"
    if wrapper.is_file():
        return [str(wrapper)] if os.access(wrapper, os.X_OK) else ["bash", str(wrapper)]
    gradle = shutil.which("gradle")
    if not gradle:
        fail("Gradle not found: install Gradle 9.7.1 or supply the project's Gradle wrapper")
    return [gradle]


def run(command, cwd=None):
    subprocess.run([str(arg) for arg in command], cwd=cwd, check=True)


def find_d8(explicit):
    if explicit:
        path = Path(explicit).expanduser().resolve()
        if not path.is_file() or not os.access(path, os.X_OK):
            fail(f"--d8 must name an executable D8 file: {path}")
        return path
    installed = shutil.which("d8")
    if installed:
        return Path(installed).resolve()
    homes = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
             str(Path.home() / "Android/Sdk")]
    local = ROOT / "local.properties"
    if local.is_file():
        for line in local.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                homes.append(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    candidates = []
    for home in homes:
        if home:
            for path in (Path(home) / "build-tools").glob("*/d8"):
                if path.is_file() and os.access(path, os.X_OK):
                    numbers = tuple(int(n) for n in re.findall(r"\d+", path.parent.name))
                    candidates.append((numbers, "-" not in path.parent.name, str(path)))
    if not candidates:
        fail("D8 not found; install Android SDK build-tools or pass --d8 /path/to/d8")
    return Path(max(candidates)[2])


def shared(artifact):
    group = artifact["group"]
    return (group in {"org.jetbrains.kotlin", "org.jetbrains.kotlinx"}
            or (group == "org.amalia.platform" and artifact["name"] == "amalia-protocol"))


def inspect_jars(paths, entrypoint):
    definitions = set()
    for path in paths:
        if path.suffix.lower() != ".jar" or not path.is_file():
            fail(f"Only JVM JAR program inputs are supported (no AAR/directories): {path}")
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                name = info.filename
                if info.is_dir():
                    continue
                if name.endswith(".class"):
                    if name.startswith(RESERVED):
                        fail(f"Reserved host/shared class in {path.name}: {name}")
                    if name.startswith("META-INF/versions/") or name == "module-info.class":
                        fail(f"Modular/multi-release classes are unsupported: {path.name}: {name}")
                    if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)*\.class", name):
                        fail(f"Unsupported class name: {path.name}: {name}")
                    if name in definitions:
                        fail(f"Duplicate class definition: {name}")
                    definitions.add(name)
                else:
                    # JAR bookkeeping is not runtime data; all other payloads would be lost in DEX.
                    bookkeeping = (name.upper() == "META-INF/MANIFEST.MF"
                        or re.fullmatch(r"META-INF/[^/]+\.kotlin_module", name)
                        or re.fullmatch(r"(?i)(?:META-INF/)?(?:LICENSE|NOTICE|COPYING)(?:[._-][^/]*)?", name))
                    if not bookkeeping:
                        fail(f"Resource/native/unsupported payload would be lost: {path.name}: {name}")
    if entrypoint.replace(".", "/") + ".class" not in definitions:
        fail("Manifest entryPoint is not defined in the program JARs")


def package(args):
    project, manifest = validate(args.project)
    key = Path(args.private_key).expanduser().resolve()
    if not key.is_file():
        fail(f"Private key not found: {key}")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", args.key_id):
        fail("Invalid --key-id")
    output = Path(args.output).resolve() if args.output else project / "dist" / f"{manifest['id']}-{manifest['version']}.amaliaplug"
    if output.exists():
        fail(f"Output already exists: {output}")
    d8 = find_d8(args.d8)
    gradle = gradle_command(project)
    if not shutil.which("openssl"):
        fail("OpenSSL is required by PackagePlatformPlugin.py")
    public_key = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-passin", "pass:"],
                                check=True, capture_output=True).stdout
    key_info = subprocess.run(["openssl", "pkey", "-pubin", "-text", "-noout"], input=public_key,
                              check=True, capture_output=True).stdout
    if b"ASN1 OID: prime256v1" not in key_info:
        fail("Host requires a P-256 (prime256v1) publisher key")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".amalia-package-", dir=project) as directory:
        temp = Path(directory)
        init = temp / "inputs.gradle"
        inputs = temp / "inputs.json"
        init.write_text(INIT, encoding="utf-8")
        run(gradle + ["--no-daemon", "--console=plain", "-I", str(init),
                      f"-Damalia.cli.inputs={inputs}", *args.gradle_arg, "jar", "amaliaCliInputs"], project)
        resolved = read_json(inputs)
        programs = [Path(resolved["jar"])]
        libraries = []
        for artifact in resolved["runtime"]:
            (libraries if shared(artifact) else programs).append(Path(artifact["path"]))
        # compileOnly host libraries help D8 resolve references but never become definitions.
        for artifact in resolved["compile"]:
            path = Path(artifact["path"])
            if path not in programs:
                libraries.append(path)
        programs = list(dict.fromkeys(programs))
        libraries = list(dict.fromkeys(libraries))
        inspect_jars(programs, manifest["entryPoint"])
        for path in libraries:
            if path.suffix.lower() != ".jar" or not path.is_file():
                fail(f"D8 classpath must contain JARs only (AARs unsupported): {path}")
        dex_dir = temp / "dex"
        dex_dir.mkdir()
        command = [d8, "--min-api", "26", "--output", dex_dir]
        for path in libraries:
            command.extend(["--classpath", path])
        run(command + programs, project)
        files = sorted(p.name for p in dex_dir.iterdir())
        if files != ["classes.dex"]:
            fail("D8 must emit exactly one classes.dex; multidex and extra payloads are unsupported")
        dex = dex_dir / "classes.dex"
        if dex.read_bytes()[:8] not in (b"dex\n035\0", b"dex\n037\0"):
            fail("Host accepts only DEX 035/037; select compatible D8/min-api")
        offers = temp / "offers.json"
        write_json(offers, [{"providerId": manifest["id"] + ".local", "contracts": [
            {"contractId": manifest["id"] + ".tools", "version": manifest["version"], "features": []}]}])
        modules = {"media": ["background.media"], "background": ["background.short", "scheduler"],
                   "notifications": ["notifications"], "browser": ["browser"],
                   "device": ["device.read", "device.volume", "device.vibrate"], "secrets": ["secrets"], "screen": ["ui"]}
        tokens = ["network:" + host for host in manifest["capabilities"].get("network", {}).get("domains", [])]
        tokens += [token for module, values in modules.items() if module in manifest["capabilities"] for token in values]
        capabilities_file = temp / "capabilities.json"
        write_json(capabilities_file, sorted(set(tokens)))
        staged_output = temp / "verified.amaliaplug"
        run([sys.executable, ROOT / "tools/PackagePlatformPlugin.py", "--dex", dex,
             "--package-id", manifest["id"], "--publisher", manifest["publisher"],
             "--version", manifest["version"], "--entrypoint", manifest["entryPoint"],
             "--key-id", args.key_id, "--private-key", key, "--offers", offers, "--capabilities", capabilities_file, "--output", staged_output])
        import base64
        with zipfile.ZipFile(staged_output) as archive:
            signed_metadata = archive.read("platform.json")
            signature = json.loads(archive.read("package.sig"))
            payload = archive.read("classes.dex")
        public_file, signature_file, metadata_file = temp / "public.pem", temp / "signature.der", temp / "metadata.json"
        public_file.write_bytes(public_key)
        signature_file.write_bytes(base64.b64decode(signature["signature"], validate=True))
        metadata_file.write_bytes(signed_metadata)
        import hashlib
        if json.loads(signed_metadata)["sha256"]["classes.dex"] != hashlib.sha256(payload).hexdigest():
            fail("Packaged DEX inventory mismatch")
        run(["openssl", "dgst", "-sha256", "-verify", public_file, "-signature", signature_file, metadata_file])
        # Publish only after inventory and signature verification, without clobbering an existing file.
        with output.open("xb") as target:
            target.write(staged_output.read_bytes())
        print(f"Packaged and verified {output}")
    if manifest["capabilities"]:
        print_capabilities(manifest, file=sys.stderr)



def print_capabilities(manifest, file=None):
    if "network" in manifest["capabilities"]:
        print(NETWORK_WARNING, file=file)
    pending = DECLARATION_MODULES & set(manifest["capabilities"])
    if pending:
        print("Requested host modules (no implicit grants): " + ", ".join(sorted(pending)), file=file)


DEV_INIT = r"""gradle.projectsEvaluated {
    def p = gradle.rootProject
    def main = p.sourceSets.main
    def console = p.sourceSets.create('amaliaDev')
    p.extensions.getByName('kotlin').sourceSets.getByName('amaliaDev').kotlin.srcDir(System.getProperty('amalia.dev.sources'))
    console.compileClasspath += main.output + main.compileClasspath
    p.configurations.getByName('amaliaDevImplementation').extendsFrom(p.configurations.getByName('implementation'))
    p.configurations.getByName('amaliaDevRuntimeOnly').extendsFrom(p.configurations.getByName('runtimeOnly'))
    console.runtimeClasspath += main.output + main.runtimeClasspath
    p.dependencies.add('amaliaDevImplementation', 'org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT')
    p.tasks.register('amaliaDevConsole', JavaExec) {
        dependsOn console.classesTaskName
        classpath = console.runtimeClasspath
        mainClass.set('amalia.console.ConsoleKt')
        standardInput = System.in
        args System.getProperty('amalia.dev.config')
    }
}
"""

DEV_SOURCE = r"""package amalia.console

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.amalia.platform.protocol.*
import org.amalia.platform.protocol.sdk.AmaliaPlugin
import org.amalia.platform.runtime.RuntimeGrant
import org.amalia.platform.runtime.testing.FakePluginHost

fun main(args: Array<String>) = runBlocking {
    val config = Json.parseToJsonElement(File(args.single()).readText()).jsonObject
    val author = config.getValue("manifest").jsonObject
    val plugin = Class.forName(author.getValue("entryPoint").jsonPrimitive.content)
        .getDeclaredConstructor().newInstance() as AmaliaPlugin
    require(plugin.manifest.id == author.getValue("id").jsonPrimitive.content)
    require(plugin.manifest.version == author.getValue("version").jsonPrimitive.content)
    val host = FakePluginHost()
    val packageId = plugin.manifest.id
    val descriptors = mutableListOf<ContractDescriptor>()
    val storageKeys = config.getValue("storage").jsonObject.keys.toMutableSet()
    val networkGrants = config.getValue("networkGrants").jsonArray.map { it.jsonPrimitive.content }.toMutableSet()
    var storageGranted = config.getValue("grantStorage").jsonPrimitive.boolean
    var invocationGranted = config.getValue("grantInvoke").jsonPrimitive.boolean
    val featureTokens = config["featureGrants"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    var pluginContext: PluginContext? = null
    val fixtures = mutableMapOf<String, JsonObject>()
    fun fixture(value: JsonObject) {
        val url = value.getValue("url").jsonPrimitive.content
        val headers = value["headers"]?.jsonObject?.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } } ?: emptyMap()
        host.networkFor(packageId).respond(url, NetworkResponse(
            value["status"]?.jsonPrimitive?.int ?: 200, headers,
            value.getValue("body").jsonPrimitive.content.toByteArray(Charsets.UTF_8)))
        fixtures[url] = value
    }
    fun invocationGrant() {
        host.grant(RuntimeGrant("console", host.caller, "$packageId.tools", providerId = "$packageId.local",
            expiresAtEpochMillis = System.currentTimeMillis() + 3_600_000))
    }
    fun permissions() = buildJsonObject {
        put("storage", storageGranted)
        put("invoke", invocationGranted)
        put("invokeLease", "1 hour; grant invoke renews")
        put("network", JsonArray(networkGrants.sorted().map(::JsonPrimitive)))
        put("note", "Fake-host authority only; declarations never imply grants")
    }
    var activation: org.amalia.platform.runtime.CoreRuntime.Activation? = null
    try {
        if (storageGranted) host.grantStorage(packageId)
        featureTokens.forEach { host.grantFeature(packageId, it) }
        networkGrants.forEach { host.grantNetwork(packageId, it) }
        config.getValue("fixtures").jsonArray.forEach { fixture(it.jsonObject) }
        val identity = CallerIdentity(author.getValue("publisher").jsonPrimitive.content, packageId, "plugin", host.deviceId)
        activation = host.runtime.activate(identity, object : PluginEntrypoint {
            override suspend fun start(context: PluginContext) {
                pluginContext = context
                val observed = object : PluginContext by context {
                    override suspend fun registerContract(contract: ContractDescriptor): Registration {
                        val registration = context.registerContract(contract)
                        descriptors += contract
                        return registration
                    }
                }
                config.getValue("storage").jsonObject.forEach { (key, value) ->
                    context.storage.data.write(key, value.jsonPrimitive.content)
                }
                plugin.start(observed)
            }
            override suspend fun stop() = plugin.stop()
        })
        if (invocationGranted) invocationGrant()
        println("Amalia local JVM host. No device connection, real network, or hot reload. Type help.")
        println(permissions())
        while (true) {
            print("amalia> "); System.out.flush()
            val line = readlnOrNull()?.trim() ?: break
            if (line.isEmpty()) continue
            val command = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "").trim()
            try {
                when (command) {
                    "help" -> println("tools | manifest | permissions | storage KEY | invoke TOOL JSON_OBJECT | fixtures | fixture JSON | grant/revoke storage|invoke|network DOMAIN | quit")
                    "quit", "exit" -> break
                    "manifest" -> println(author)
                    "permissions" -> println(permissions())
                    "tools" -> println(JsonArray(descriptors.flatMap { it.operations }.map {
                        buildJsonObject {
                            put("name", it.id); put("description", it.description)
                            put("input", it.input.definition); put("output", it.output.definition)
                            put("effect", it.effect.name)
                        }
                    }))
                    "storage" -> {
                        require(rest.isNotBlank()) { "storage KEY (keys known from fixtures: $storageKeys)" }
                        val value = checkNotNull(pluginContext).storage.data.read(rest)
                        println(buildJsonObject { put("key", rest); put("value", value?.let(::JsonPrimitive) ?: JsonNull) })
                    }
                    "invoke" -> {
                        val name = rest.substringBefore(' ')
                        require(name.isNotBlank()) { "invoke TOOL JSON_OBJECT" }
                        val input = Json.parseToJsonElement(rest.substringAfter(' ', "{}"))
                        require(input is JsonObject) { "Tool arguments must be a JSON object" }
                        println(Json.encodeToString(InvocationResponse.serializer(), host.invoke(plugin, name, input)))
                    }
                    "fixtures" -> println(JsonArray(fixtures.values.toList()))
                    "fixture" -> { fixture(Json.parseToJsonElement(rest).jsonObject); println("Fixture installed; no network grant issued") }
                    "grant", "revoke" -> {
                        val allow = command == "grant"
                        when (rest.substringBefore(' ')) {
                            "storage" -> {
                                if (allow) host.grantStorage(packageId) else host.revokeStorage(packageId)
                                storageGranted = allow
                            }
                            "invoke" -> {
                                if (allow) invocationGrant() else host.revoke("console")
                                invocationGranted = allow
                            }
                            "network" -> {
                                val domain = rest.substringAfter(' ', "")
                                require(domain.isNotBlank()) { "grant/revoke network DOMAIN" }
                                if (allow) { host.grantNetwork(packageId, domain); networkGrants += domain }
                                else { host.revokeNetwork(packageId, domain); networkGrants -= domain }
                            }
                            else -> error("Unknown permission")
                        }
                        println(permissions())
                    }
                    else -> error("Unknown command; type help")
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                // Do not print exception messages: they can contain network URLs or secret fixture values.
                System.err.println("Command failed (${error.javaClass.simpleName}); check input, schema and explicit grants")
            }
        }
    } finally {
        try { activation?.stop() } finally { host.close() }
    }
}
"""


def dev(args):
    project, manifest = validate(args.project)
    config = read_json(Path(args.config).resolve()) if args.config else {}
    fields = {"grantStorage", "grantInvoke", "networkGrants", "storage", "fixtures", "featureGrants"}
    if not isinstance(config, dict) or set(config) - fields:
        fail("Dev config supports only: " + ", ".join(sorted(fields)))
    config = dict(grantStorage=False, grantInvoke=False, networkGrants=[], storage={}, fixtures=[]) | config
    for field in ("grantStorage", "grantInvoke"):
        if type(config[field]) is not bool:
            fail(field + " must be boolean")
    if not isinstance(config["networkGrants"], list):
        fail("networkGrants must be a list")
    for value in config["networkGrants"]:
        domain(value)
    if not isinstance(config["storage"], dict) or any(not isinstance(v, str) for v in config["storage"].values()):
        fail("storage must map keys to string values")
    if config["storage"] and not config["grantStorage"]:
        fail("Storage fixtures require explicit grantStorage=true")
    if not isinstance(config["fixtures"], list) or len(config["fixtures"]) > 64:
        fail("fixtures must be a list of at most 64 responses")
    for fixture in config["fixtures"]:
        if (not isinstance(fixture, dict) or set(fixture) - {"url", "status", "headers", "body"}
                or not isinstance(fixture.get("url"), str) or not isinstance(fixture.get("body"), str)):
            fail("Each fixture requires url/body strings and optional status/headers")
    if not isinstance(config.get("featureGrants", []), list) or any(not isinstance(t, str) for t in config.get("featureGrants", [])):
        fail("featureGrants must be an explicit list of capability tokens")
    config["manifest"] = manifest
    print_capabilities(manifest)
    print("Trusted local code executes in this JVM, not a sandbox. All grants default to denied.", flush=True)
    with tempfile.TemporaryDirectory(prefix=".amalia-dev-", dir=project) as directory:
        temp = Path(directory)
        source = temp / "sources/amalia/console/Console.kt"
        source.parent.mkdir(parents=True)
        source.write_text(DEV_SOURCE, encoding="utf-8")
        init = temp / "dev.gradle"
        init.write_text(DEV_INIT, encoding="utf-8")
        config_file = temp / "config.json"
        write_json(config_file, config)
        os.chmod(config_file, 0o600)
        run(gradle_command(project) + ["--no-daemon", "--console=plain", "-q", "-I", str(init),
            f"-Damalia.dev.sources={temp / 'sources'}", f"-Damalia.dev.config={config_file}",
            *args.gradle_arg, "amaliaDevConsole"], project)

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    new = sub.add_parser("create", help="Create a Kotlin/JVM SDK3 plugin")
    kinds = new.add_subparsers(dest="kind", required=True)
    plugin = kinds.add_parser("plugin")
    plugin.add_argument("name", metavar="NAME")
    plugin.add_argument("--directory")
    for name in ("validate", "package", "test", "dev"):
        command = sub.add_parser(name)
        command.add_argument("project", nargs="?", default=".")
        if name in {"package", "test", "dev"}:
            command.add_argument("--gradle-arg", action="append", default=[],
                                 help="Repeatable Gradle option, e.g. --gradle-arg=-PamaliaSdkRepository=/path")
        if name == "dev":
            command.add_argument("--config", help="Fake-host JSON configuration (grants, storage, fixtures)")
        if name == "package":
            command.add_argument("--private-key", required=True)
            command.add_argument("--key-id", required=True)
            command.add_argument("--output")
            command.add_argument("--d8")
    add = sub.add_parser("add", help="Declare a capability; declarations do not grant authority")
    add.add_argument("module")
    add.add_argument("project", nargs="?", default=".")
    add.add_argument("--domain", action="append", default=[], help="Repeat for each allowed hostname")
    args = parser.parse_args(argv)
    try:
        if args.command == "create":
            create(args)
        elif args.command == "dev":
            dev(args)
        elif args.command == "package":
            package(args)
        elif args.command == "add":
            if args.module not in {"network"} | DECLARATION_MODULES:
                fail("Unknown module: " + args.module)
            project, manifest = validate(args.project)
            if args.module == "network":
                if not args.domain:
                    fail("add network requires at least one --domain example.com")
                domains = [domain(value) for value in args.domain]
                current = manifest["capabilities"].get("network", {}).get("domains", [])
                combined = sorted(set(current + domains))
                if len(combined) > 128:
                    fail("At most 128 network domains are supported")
                manifest["capabilities"]["network"] = {"domains": combined}
                print(NETWORK_WARNING)
            else:
                if args.domain:
                    fail("--domain is only valid for network")
                manifest["capabilities"][args.module] = {}
                print(f"Declared {args.module}; no facade implementation, adapter or permission is installed.")
            write_json(project / "amalia.json", manifest)
        else:
            project, manifest = validate(args.project)
            if args.command == "test":
                print("Compiling main classes and running Gradle test; no tests are generated and NO-SOURCE is not test coverage.", flush=True)
                run(gradle_command(project) + ["--no-daemon", "--console=plain", *args.gradle_arg, "classes", "test"], project)
            else:
                print(f"Valid author project: {manifest['id']} (static checks only; not compiled or host-validated)")
                if manifest["capabilities"]:
                    print_capabilities(manifest)
        return 0
    except (CliError, OSError, ValueError, zipfile.BadZipFile, KeyError, TypeError) as error:
        print(f"amalia: {error}", file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as error:
        print(f"amalia: command failed (exit {error.returncode}); see output above", file=sys.stderr)
        return error.returncode if 0 < error.returncode < 126 else 1
    except KeyboardInterrupt:
        print("amalia: interrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
