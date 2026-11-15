import xyz.jpenilla.resourcefactory.bukkit.Permission
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.pluginsapi.DownloadPluginsSpec

plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.resource.factory.bukkit)
}

val minecraftVersion: String = libs.versions.minecraft.get()

// The plugin's name and base package, used below for plugin.yml, the relocation prefix and the
// smoke-test log check. When renaming the plugin, change them here (and `group` in
// gradle.properties, which the base package follows).
val pluginName = "Foliate"
val basePackage: String = project.group.toString()

// The test servers get the plugins we integrate with, so hooks are exercised by default.
// Pass -Phooks=false to start without them and check the plugin runs on its own.
val withHooks: Boolean = providers.gradleProperty("hooks").map(String::toBoolean).getOrElse(true)
val testServerPlugins: Action<DownloadPluginsSpec> = Action {
    if (withHooks) {
        modrinth("placeholderapi", libs.versions.placeholderapi.get())
        modrinth("zmenu", libs.versions.zmenu.get())
    }
}

// -PacceptEula=true accepts the Minecraft EULA (https://aka.ms/MinecraftEULA) for the test server,
// in place of editing run/eula.txt. Meant for CI: only pass it if you agree to the EULA. Anything
// but an explicit "true" leaves the EULA unaccepted.
val acceptEula: Boolean = providers.gradleProperty("acceptEula").map(String::toBoolean).getOrElse(false)

// -PselfTest=true makes the plugin test itself once the server is up, then stops the server. The
// task fails unless the self-test passed and nothing went wrong in the plugin. This is the smoke
// test.
val selfTest: Boolean = providers.gradleProperty("selfTest").map(String::toBoolean).getOrElse(false)

// The modules the self-test must find enabled. Without this, a hook that silently failed to load
// (its plugin could not download its libraries, say) would still give a green run.
val expectedModules: String =
    (listOf("config", "messages", "storage", "profiles", "sidebar", "updates") +
        if (withHooks) listOf("placeholderapi", "zmenu") else emptyList()).joinToString(",")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.helpch.at/releases/") {
        content { includeGroup("me.clip") }
    }
    maven("https://repo.groupez.dev/releases/") {
        content { includeGroupByRegex("fr\\.maxlego08.*") }
    }
}

dependencies {
    compileOnly(libs.folia.api)

    // Optional integrations: provided by the other plugin when it is installed, never bundled.
    compileOnly(libs.placeholderapi)
    compileOnly(libs.zmenu.api) { isTransitive = false }

    // Bundled in the jar and relocated by shadowJar.
    implementation(libs.configlib.yaml)
    implementation(libs.snakeyaml.engine)
    implementation(libs.fastboard)
    implementation(libs.bstats)
    implementation(libs.hikaricp) {
        // The server provides SLF4J; bundling it would relocate it away from the server's logging.
        exclude(group = "org.slf4j")
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockbukkit)
    // MockBukkit does not expose the server API to consumers, tests need it on their own classpath.
    testImplementation(libs.folia.api)
    testImplementation(libs.placeholderapi)
    testImplementation(libs.zmenu.api) { isTransitive = false }
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.sqlite.jdbc)
}

// Generates plugin.yml at build time, so the plugin metadata lives in one place.
bukkitPluginYaml {
    name = pluginName
    main = "$basePackage.FoliatePlugin"
    apiVersion = minecraftVersion
    foliaSupported = true
    description = project.description
    authors = listOf("Foliate contributors")
    website = "https://github.com/pkhemae/foliate"
    softDepend = listOf("PlaceholderAPI", "zMenu")

    // Keep in sync with dev.foliate.command.Permissions.
    permissions {
        register("foliate.command.example") {
            description = "Allows /example, the commands that demonstrate the threading rules."
            default = Permission.Default.OP
        }
        register("foliate.command.info") {
            description = "Allows /foliate info."
            default = Permission.Default.OP
        }
        register("foliate.command.menu") {
            description = "Allows /foliate menu."
            default = Permission.Default.TRUE
        }
        register("foliate.command.reload") {
            description = "Allows /foliate reload."
            default = Permission.Default.OP
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
    }

    test {
        useJUnitPlatform()
    }

    jar {
        // The shadow jar is the only artifact meant to be installed on a server.
        archiveClassifier = "plain"
    }

    shadowJar {
        archiveClassifier = ""
        // Every bundled library is moved under our own package, so it cannot clash with the
        // copy another plugin ships.
        enableAutoRelocation = true
        relocationPrefix = "$basePackage.libs"
    }

    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(minecraftVersion)
        downloadPlugins(testServerPlugins)
    }
}

// Adds the runFolia task, the one to use day to day: Paper cannot detect region violations.
runPaper.folia.registerTask {
    minecraftVersion(minecraftVersion)
    downloadPlugins(testServerPlugins)
}

tasks.withType<RunServer>().configureEach {
    if (acceptEula) {
        jvmArgs("-Dcom.mojang.eula.agree=true")
    }
    if (selfTest) {
        jvmArgs("-Dfoliate.selftest=true", "-Dfoliate.selftest.expect=$expectedModules")
        val log = runDirectory.file("logs/latest.log")
        doFirst {
            log.get().asFile.delete()
        }
        doLast {
            val lines = log.get().asFile.takeIf { it.exists() }?.readLines().orEmpty()
            // Anything that goes wrong in the plugin, whoever logs it: the plugin itself, the
            // server reporting a failed event handler or a failed enable/disable, or a stack
            // trace passing through our code.
            val errors = lines.filter {
                ("/ERROR]" in it || "/FATAL]" in it) && pluginName in it ||
                    basePackage in it && ("Exception" in it || "Error" in it) ||
                    it.trimStart().startsWith("at ") && basePackage in it
            }
            if (lines.none { "FOLIATE SELF-TEST PASSED" in it } || errors.isNotEmpty()) {
                throw GradleException(
                    "The self-test did not pass. Relevant log lines:\n" +
                        (errors + lines.filter { "SELF-TEST" in it }).distinct().joinToString("\n")
                )
            }
        }
    }
}
