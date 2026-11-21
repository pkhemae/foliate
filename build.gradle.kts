import java.util.zip.ZipFile
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import xyz.jpenilla.resourcefactory.bukkit.Permission
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.pluginsapi.DownloadPluginsSpec

plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.resource.factory.bukkit)
    alias(libs.plugins.errorprone)
}

// Adds the one-off renameTemplate task. The task removes this line when it has run.
apply(from = "gradle/rename.gradle.kts")

val minecraftVersion: String = libs.versions.minecraft.get()

// The plugin's name and base package, used below for plugin.yml, the relocation prefix and the
// smoke-test log check. When renaming the plugin, change them here (and `group` in
// gradle.properties, which the base package follows).
val pluginName = "Foliate"
val basePackage: String = project.group.toString()
val mainClass = "$basePackage.FoliatePlugin"

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

// -PserverTest=true installs the server test jar (see serverTestJar) instead of the plugin jar: the
// plugin tests itself once the server is up, then stops the server. The task fails unless the
// server test passed and nothing went wrong in the plugin. This is the smoke test.
val serverTest: Boolean = providers.gradleProperty("serverTest").map(String::toBoolean).getOrElse(false)

// -PclientTest=true (with -PserverTest=true) makes the server test wait for a real client to join and
// check what the plugin did for it, before stopping the server. The server then runs in offline
// mode so that a client without a Minecraft account can join: never for anything but CI. The CI
// job starts the client once the log says the server test is waiting.
val clientTest: Boolean = providers.gradleProperty("clientTest").map(String::toBoolean).getOrElse(false)

// -PclientLanguages=en,fr makes the connected-client test expect one visit per entry, each by a
// client set to that language: a player who comes back, and the messages of their own language.
val clientLanguages: String = providers.gradleProperty("clientLanguages").getOrElse("")

// -PclientTyped="foliate menu" makes each visit expect the client to type that command, which the
// CI job does through the client mod hmc-specifics.
val clientTyped: String = providers.gradleProperty("clientTyped").getOrElse("")

// The modules the server test must find enabled. Without this, a hook that silently failed to load
// (its plugin could not download its libraries, say) would still give a green run.
val expectedModules: String =
    (listOf("config", "messages", "storage", "profiles", "scoreboard") +
        if (withHooks) listOf("placeholderapi", "zmenu") else emptyList()).joinToString(",")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// The server test and its checks: compiled against the plugin, never part of its jar. The checks of
// a module sit in the module's package, so that they can use what it keeps package-private.
val serverTestSources: SourceSet = sourceSets.create("serverTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[serverTestSources.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
configurations[serverTestSources.implementationConfigurationName].extendsFrom(configurations.implementation.get())

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

    // Run by the compiler on every source set: Error Prone's checks, and NullAway, which holds the
    // @NullMarked packages to their annotations.
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)

    // Optional integrations: provided by the other plugin when it is installed, never bundled.
    compileOnly(libs.placeholderapi)
    compileOnly(libs.zmenu.api) { isTransitive = false }

    // Bundled in the jar and relocated by shadowJar. Each one has its notice in THIRD-PARTY-NOTICES.
    implementation(libs.configlib.yaml)
    implementation(libs.snakeyaml.engine)
    implementation(libs.fastboard)
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
    testImplementation(libs.archunit)
    // The tests of the server test's checks.
    testImplementation(serverTestSources.output)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.sqlite.jdbc)
    testRuntimeOnly(libs.mysql.connector)
}

// Generates plugin.yml at build time, so the plugin metadata lives in one place.
bukkitPluginYaml {
    name = pluginName
    main = mainClass
    apiVersion = minecraftVersion
    foliaSupported = true
    description = project.description
    authors = listOf("Foliate contributors")
    website = "https://github.com/pkhemae/foliate"
    softDepend = listOf("PlaceholderAPI", "zMenu")

    // Each node is a PERMISSION constant next to the subcommand that checks it; PluginYmlTest
    // checks both ways that the two lists agree.
    permissions {
        register("foliate.command.info") {
            description = "Allows /foliate info."
            default = Permission.Default.OP
        }
        register("foliate.command.profile") {
            description = "Allows /foliate profile, to see your own profile."
            default = Permission.Default.TRUE
        }
        register("foliate.command.profile.others") {
            description = "Allows /foliate profile <player>, to see the profile of another player."
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
        options.errorprone {
            // Every package of the plugin is @NullMarked (ArchitectureTest checks it): NullAway
            // checks exactly those, with the JSpecify semantics the annotations come from.
            check("NullAway", CheckSeverity.ERROR)
            option("NullAway:OnlyNullMarked", "true")
            option("NullAway:JSpecifyMode", "true")
            // Bukkit calls event handlers by reflection: an @EventHandler method is used, and
            // public as Bukkit's own examples write it, even in an anonymous listener.
            option("UnusedMethod:ExemptingMethodAnnotations", "org.bukkit.event.EventHandler")
            disable("EffectivelyPrivate")
            // Unit tests share the plugin's @NullMarked packages but not its contract: an assertion
            // may read a value that can be null, and failing there is what the test is for.
            if (name == "compileTestJava") {
                disable("NullAway")
            }
        }
    }

    test {
        useJUnitPlatform()
        // ArchitectureTest reads the source files: without this, Gradle would call the test up to
        // date after a change that does not alter the bytecode, such as an import.
        inputs.dir("src/main/java").withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir("src/serverTest/java").withPropertyName("serverTestSources").withPathSensitivity(PathSensitivity.RELATIVE)
        // The tests that need a MySQL or MariaDB server run only when the environment points at one:
        // without declaring it, Gradle would reuse a run (or a cached one) made without a server.
        for (name in listOf("PORT", "HOST", "DATABASE", "USER")) {
            inputs.property("mysql$name", providers.environmentVariable("FOLIATE_TEST_MYSQL_$name").orElse(""))
        }
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
        // Their licences ask for their notices to travel with them. A library added to the bundle
        // gets its entry in THIRD-PARTY-NOTICES.
        from("THIRD-PARTY-NOTICES") {
            into("META-INF")
        }
        // HikariCP's build-time generator of its proxies, which needs Javassist: never used at run time.
        exclude("com/zaxxer/hikari/util/JavassistProxyFactory*.class")
    }

    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(minecraftVersion)
        downloadPlugins(testServerPlugins)
    }
}

// The plugin with the server test: the plugin jar itself, entry for entry, plus the serverTest source
// set, and a plugin.yml whose main class is ServerTestPlugin. So the smoke test runs what is
// released. Only the test servers get it, never next to the plugin jar; it is built in its own
// folder, away from the jars to install. The server test's classes are not relocated: they may use
// the plugin and the server's API, not a bundled library directly.
val serverTestJar = tasks.register<Jar>("serverTestJar") {
    description = "Builds the plugin jar with its server test, for the smoke test."
    archiveClassifier = "server-test"
    destinationDirectory = layout.buildDirectory.dir("server-test")
    from(zipTree(tasks.shadowJar.flatMap { it.archiveFile })) {
        exclude("META-INF/MANIFEST.MF")
    }
    from(serverTestSources.output)
    // A class of the server test must not hide one of the plugin, nor the other way round.
    duplicatesStrategy = DuplicatesStrategy.FAIL
    manifest.attributes("Multi-Release" to "true")
    val mainLine = "main: $basePackage.servertest.ServerTestPlugin"
    filesMatching("plugin.yml") {
        filter { line -> if (line.startsWith("main:")) mainLine else line }
    }
    // Without its main class the server test would not start, and the smoke test would only end at
    // its timeout.
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            val pluginYml = zip.getInputStream(zip.getEntry("plugin.yml")).reader().readText()
            if (mainLine !in pluginYml.lines()) {
                throw GradleException("The server test jar's plugin.yml does not say '$mainLine'")
            }
        }
    }
}

tasks.check {
    // So that the build, and a renamed template, always package the server test.
    dependsOn(serverTestJar)
}

// The test servers get the jar named here, not whatever jar run-paper would find on its own.
runPaper {
    disablePluginJarDetection()
}

// Adds the runFolia task, the one to use day to day: Paper cannot detect region violations.
runPaper.folia.registerTask {
    minecraftVersion(minecraftVersion)
    downloadPlugins(testServerPlugins)
}

tasks.withType<RunServer>().configureEach {
    pluginJars(if (serverTest) serverTestJar else tasks.shadowJar)
    if (acceptEula) {
        jvmArgs("-Dcom.mojang.eula.agree=true")
    }
    if (serverTest) {
        jvmArgs("-Dfoliate.servertest.expect=$expectedModules")
        if (clientTest) {
            jvmArgs("-Dfoliate.servertest.client=true", "-Dfoliate.servertest.client.languages=$clientLanguages", "-Dfoliate.servertest.client.typed=$clientTyped")
            doFirst {
                // The server is switched to offline mode, which would stay on a developer's
                // machine: this is for CI runners, which are thrown away.
                if (System.getenv("CI") != "true") {
                    throw GradleException("-PclientTest switches the server to offline mode: it is for CI only")
                }
                // The server completes the other settings on its first start.
                runDirectory.file("server.properties").get().asFile.apply {
                    parentFile.mkdirs()
                    writeText("server-ip=127.0.0.1\nonline-mode=false\nenforce-secure-profile=false\n")
                }
            }
        }
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
            if (lines.none { "FOLIATE SERVER TEST PASSED" in it } || errors.isNotEmpty()) {
                throw GradleException(
                    "The server test did not pass. Relevant log lines:\n" +
                        (errors + lines.filter { "SERVER TEST" in it }).distinct().joinToString("\n")
                )
            }
        }
    }
}
