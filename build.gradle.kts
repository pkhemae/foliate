import xyz.jpenilla.resourcefactory.bukkit.Permission
import xyz.jpenilla.runtask.pluginsapi.DownloadPluginsSpec

plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.resource.factory.bukkit)
}

val minecraftVersion: String = libs.versions.minecraft.get()

// The test servers get the plugins we integrate with, so hooks are exercised by default.
// Pass -Phooks=false to start without them and check the plugin runs on its own.
val withHooks: Boolean = providers.gradleProperty("hooks").map(String::toBoolean).getOrElse(true)
val testServerPlugins: Action<DownloadPluginsSpec> = Action {
    if (withHooks) {
        modrinth("placeholderapi", libs.versions.placeholderapi.get())
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.helpch.at/releases/") {
        content { includeGroup("me.clip") }
    }
}

dependencies {
    compileOnly(libs.folia.api)

    // Optional integrations: provided by the other plugin when it is installed, never bundled.
    compileOnly(libs.placeholderapi)

    // Bundled in the jar and relocated by shadowJar.
    implementation(libs.configlib.yaml)
    implementation(libs.snakeyaml.engine)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockbukkit)
    // MockBukkit does not expose the server API to consumers, tests need it on their own classpath.
    testImplementation(libs.folia.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Generates plugin.yml at build time, so the plugin metadata lives in one place.
bukkitPluginYaml {
    name = "Foliate"
    main = "dev.foliate.FoliatePlugin"
    apiVersion = minecraftVersion
    foliaSupported = true
    description = project.description
    website = "https://github.com/pkhemae/foliate"
    softDepend = listOf("PlaceholderAPI")

    // Keep in sync with dev.foliate.command.Permissions.
    permissions {
        register("foliate.command.info") {
            description = "Allows /foliate info."
            default = Permission.Default.OP
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
        relocationPrefix = "dev.foliate.libs"
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
