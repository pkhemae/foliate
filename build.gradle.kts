plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.resource.factory.bukkit)
}

val minecraftVersion: String = libs.versions.minecraft.get()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.folia.api)

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
    }
}

// Adds the runFolia task, the one to use day to day: Paper cannot detect region violations.
runPaper.folia.registerTask {
    minecraftVersion(minecraftVersion)
}
