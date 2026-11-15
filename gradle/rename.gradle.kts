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
    setOf("java", "kts", "toml", "properties", "yml", "yaml", "md", "json", "sql", "txt", "gitignore", "editorconfig")
val textFileNames = setOf("LICENSE", ".gitignore", ".gitattributes", ".editorconfig")

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

        // The repository is replaced first and parked behind a token: its second half is the
        // template's lowercase name, which the last replacement would otherwise rewrite.
        val repositoryToken = "\u0000repository\u0000"
        val replacements = listOf(
            templateRepository to repositoryToken,
            templateGroup to basePackage,
            templateGroup.replace('.', '/') to basePackage.replace('.', '/'),
            templateName to name,
            templateName.uppercase() to name.uppercase(),
            templateName.lowercase() to name.lowercase(),
            repositoryToken to repository,
        )

        val files = root.walkTopDown()
            .onEnter { it == root || it.name !in skippedDirectories }
            .filter { it.isFile && (it.extension in textExtensions || it.name in textFileNames) }
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
        for (sources in listOf("src/main/java", "src/test/java")) {
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
        )
        File(root, "gradle/rename.gradle.kts").delete()

        println("Renamed to $name ($basePackage): $rewritten files rewritten, $moved files renamed.")
        println("Next: ./gradlew build, then review the diff. The README still describes the template.")
    }
}
