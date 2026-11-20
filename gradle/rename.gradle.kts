import java.io.File
import java.nio.file.Files

// One-off task that turns the template into your own plugin:
//
//   ./gradlew renameTemplate -PnewName=Skylands -PnewGroup=com.example.skylands -PnewRepository=you/skylands
//
// It rewrites every name derived from the template's (plugin name, base package, command,
// permission nodes, table names, placeholder identifier...), moves the sources to the new package,
// and then removes itself. Run it once, on a clean checkout, before writing any code of your own.

val templateName = "Foliate"
val templateGroup = "dev.foliate"
val templateRepository = "pkhemae/foliate"

val skippedDirectories = setOf(".git", ".gradle", ".idea", ".kotlin", "build", "run", "out", "wrapper")
val textExtensions =
    setOf("java", "kts", "toml", "properties", "yml", "yaml", "md", "json", "sql", "txt", "sh", "gitignore", "editorconfig")
val textFileNames = setOf("LICENSE", ".gitignore", ".gitattributes", ".editorconfig")
val javaKeywords = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
    "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
    "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
    "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
)

tasks.register("renameTemplate") {
    group = "template"
    description = "Renames the template. Needs -PnewName, -PnewGroup and -PnewRepository."

    val newName = providers.gradleProperty("newName")
    val newGroup = providers.gradleProperty("newGroup")
    val newRepository = providers.gradleProperty("newRepository")
    val root = layout.projectDirectory.asFile

    doLast {
        val name = newName.orNull ?: throw GradleException("Pass the plugin name: -PnewName=Skylands")
        val basePackage = newGroup.orNull ?: throw GradleException("Pass the base package: -PnewGroup=com.example.skylands")
        val repository = newRepository.orNull ?: throw GradleException("Pass the GitHub repository: -PnewRepository=you/skylands")
        if (!Regex("[A-Z][A-Za-z0-9]*").matches(name)) {
            throw GradleException("newName must be a class-like name such as Skylands, got '$name'")
        }
        if (!Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(basePackage)) {
            throw GradleException("newGroup must be a lowercase package such as com.example.skylands, got '$basePackage'")
        }
        if (!Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository)) {
            throw GradleException("newRepository must look like owner/name, got '$repository'")
        }
        // The replacements are plain text, applied one after the other: a new name that contains
        // the template's would be rewritten a second time.
        for ((label, value) in listOf("newName" to name, "newGroup" to basePackage, "newRepository" to repository)) {
            if (templateName.lowercase() in value.lowercase()) {
                throw GradleException("$label must not contain '${templateName.lowercase()}', got '$value'")
            }
        }
        basePackage.split('.').firstOrNull { it in javaKeywords }?.let {
            throw GradleException("newGroup cannot use the Java keyword '$it' as a package name")
        }
        val sourceFiles = root.walkTopDown()
            .onEnter { it == root || it.name !in skippedDirectories }
            .filter { it.isFile }
            .toList()
        // A class named after the template must not land on the name of an existing one.
        val existingNames = sourceFiles.map { it.name }.filterNot { templateName in it }.toSet()
        sourceFiles.map { it.name }
            .filter { templateName in it && it.replace(templateName, name) in existingNames }
            .firstOrNull()
            ?.let { throw GradleException("newName '$name' would turn $it into ${it.replace(templateName, name)}, which already exists") }
        // Nothing can be undone by this task: start from a tree git can restore.
        if (File(root, ".git").exists()) {
            val status = providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.get()
            if (status.isNotBlank()) {
                throw GradleException(
                    "Commit or stash your changes first. If a rename goes wrong, " +
                        "'git checkout . && git clean -fd' brings the tree back."
                )
            }
        }

        // The repository is replaced first and parked behind a token: its second half is the
        // template's lowercase name, which the last replacement would otherwise rewrite.
        val repositoryToken = "\u0000repository\u0000"
        // The developer guide stays where it is, in the template's wiki: its links are kept.
        val guideUrl = "https://github.com/$templateRepository/wiki"
        val guideToken = "\u0000guide\u0000"
        val replacements = listOf(
            guideUrl to guideToken,
            templateRepository to repositoryToken,
            templateGroup to basePackage,
            templateGroup.replace('.', '/') to basePackage.replace('.', '/'),
            templateName to name,
            templateName.uppercase() to name.uppercase(),
            templateName.lowercase() to name.lowercase(),
            repositoryToken to repository,
            guideToken to guideUrl,
        )

        val files = root.walkTopDown()
            .onEnter { it == root || it.name !in skippedDirectories }
            .filter { it.isFile && (it.extension in textExtensions || it.name in textFileNames) }
            // Not this script: it is deleted at the end, and must stay intact until then. Not the
            // licence either: the template's copyright notice must stay in it (see below).
            .filterNot { it.name == "rename.gradle.kts" || it.name == "LICENSE" }
            .toList()
        var rewritten = 0
        for (file in files) {
            val before = file.readText()
            val after = replacements.fold(before) { text, (from, to) -> text.replace(from, to) }
            if (after != before) {
                file.writeText(after)
                rewritten++
            }
        }

        // Classes named after the template, then the package directories.
        var moved = 0
        root.walkTopDown()
            .onEnter { it == root || it.name !in skippedDirectories }
            .filter { it.isFile && templateName in it.name }
            .toList()
            .forEach { file ->
                Files.move(file.toPath(), file.resolveSibling(file.name.replace(templateName, name)).toPath())
                moved++
            }
        for (sources in listOf("src/main/java", "src/selftest/java", "src/test/java")) {
            val from = File(root, "$sources/${templateGroup.replace('.', '/')}")
            val to = File(root, "$sources/${basePackage.replace('.', '/')}")
            if (from.isDirectory && from != to) {
                to.parentFile.mkdirs()
                Files.move(from.toPath(), to.toPath())
                // Remove what is left of the old package path, as long as it is empty.
                var parent = from.parentFile
                while (parent != File(root, sources) && parent.list()?.isEmpty() == true) {
                    parent.delete()
                    parent = parent.parentFile
                }
            }
        }

        // The task has done its job: take it out of the build.
        val buildFile = File(root, "build.gradle.kts")
        buildFile.writeText(
            buildFile.readLines()
                .filterNot { "rename.gradle.kts" in it || "renameTemplate" in it }
                .joinToString("\n", postfix = "\n")
                // The line was followed by a blank one: do not leave two in a row.
                .replace("\n\n\n", "\n\n")
        )
        File(root, "gradle/rename.gradle.kts").delete()

        // The licence keeps the template's copyright notice, as the MIT licence requires, with the
        // new plugin's above it.
        val licence = File(root, "LICENSE")
        if (licence.exists()) {
            licence.writeText(
                licence.readText().replaceFirst(
                    Regex("(?m)^Copyright \\(c\\)"),
                    "Copyright (c) ${java.time.Year.now()} $name contributors\n\nCopyright (c)",
                )
            )
        }
        // The guidance for assistants keeps its rules but not the template's introduction.
        val assistant = File(root, "CLAUDE.md")
        if (assistant.exists()) {
            val rules = assistant.readText().substringAfter("## Development workflow", "")
            if (rules.isNotEmpty()) {
                assistant.writeText(
                    "# $name\n\nA Folia plugin made from the [Foliate template](https://github.com/$templateRepository). " +
                        "The code is the source of truth; this file only holds what the code cannot tell you. The\n" +
                        "template's developer guide ($guideUrl) explains how the code is organised.\n\n" +
                        "## Development workflow" + rules
                )
            }
        }

        // The README and the changelog describe the template and its history, not this plugin:
        // leave honest ones to write in.
        File(root, "README.md").writeText(
            """
            |# $name
            |
            |Describe your plugin here: what it does, who it is for, and how to install it.
            |
            |## Build
            |
            |```bash
            |./gradlew build
            |```
            |
            |The plugin jar is in `build/libs/` (the one without `-plain` in its name is the one to install).
            |`./gradlew runFolia` starts a Folia test server with it,
            |and `./gradlew runFolia -PselfTest=true` runs the smoke test.
            |
            |## Developer guide
            |
            |$name started from the [Foliate template](https://github.com/$templateRepository). How the
            |code is organised and how to extend it is in the template's
            |[developer guide]($guideUrl).
            |
            |## License
            |
            |[MIT](LICENSE)
            |
            """.trimMargin()
        )
        File(root, "CHANGELOG.md").writeText(
            """
            |# Changelog
            |
            |All notable changes to this project are listed here. The format follows
            |[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
            |[Semantic Versioning](https://semver.org/).
            |
            |## [Unreleased]
            |
            """.trimMargin()
        )

        println("Renamed to $name ($basePackage): $rewritten files rewritten, $moved files renamed.")
        println("Next:")
        println("  1. ./gradlew build, then review the diff.")
        println("  2. If you cloned the template: git remote set-url origin <your repository>, or the next push")
        println("     goes to the template.")
        println("  3. Write your README.md (a skeleton was generated), and set the description in gradle.properties")
        println("     and the authors in build.gradle.kts.")
        println("  4. LICENSE: your line was added above the template's copyright notice, which the MIT licence")
        println("     requires you to keep. The logo assets/logo.svg is the template's: replace it or delete it.")
        println("  5. The developer guide stays in the template's wiki: $guideUrl")
        println("  6. Commit the result.")
    }
}
