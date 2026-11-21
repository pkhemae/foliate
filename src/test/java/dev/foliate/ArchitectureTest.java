package dev.foliate;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The layout of the code is a rule, not a habit: {@code core} knows nothing of the plugin;
 * {@code contracts} holds what features offer each other, and depends on {@code core} and itself;
 * the modules, hooks and commands depend on {@code core} and {@code contracts}, never on each other.
 * Only {@code FoliatePlugin} and {@code PluginConfig}, which compose everything, may know it all.
 *
 * <p>Checked with ArchUnit on the compiled classes of the plugin, so every real dependency counts
 * (an import, a qualified name, a constant, a lambda) and a mention in a comment does not. The
 * server test's classes are left out: they test everything, and the plugin cannot depend on them,
 * since they are not on its classpath.
 */
class ArchitectureTest {

    /** The base package: taken from here, so that renaming the template cannot leave this test looking for the old one. */
    private static final String BASE = ArchitectureTest.class.getPackageName();

    private static final Path MAIN = Path.of("src/main/java", BASE.split("\\.", -1));
    private static final Path SERVER_TEST = Path.of("src/serverTest/java", BASE.split("\\.", -1));

    private static JavaClasses plugin;

    /** The plugin's classes only: the folder FoliatePlugin was compiled to, without the tests'. */
    @BeforeAll
    static void importThePlugin() throws URISyntaxException {
        plugin = new ClassFileImporter().importPath(
                Path.of(FoliatePlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
    }

    @Test
    void everyPartDependsOnlyOnWhatTheLayoutAllows() {
        layeredArchitecture()
                .consideringOnlyDependenciesInAnyPackage(BASE + "..")
                // A part with no class, as contracts in the template, is a rule waiting for code.
                .withOptionalLayers(true)
                .layer("composition root").definedBy(BASE)
                .layer("core").definedBy(BASE + ".core..")
                .layer("contracts").definedBy(BASE + ".contracts..")
                .layer("modules").definedBy(BASE + ".modules..")
                .layer("hooks").definedBy(BASE + ".hooks..")
                .layer("commands").definedBy(BASE + ".commands..")
                .whereLayer("composition root").mayNotBeAccessedByAnyLayer()
                .whereLayer("modules").mayOnlyBeAccessedByLayers("composition root")
                .whereLayer("hooks").mayOnlyBeAccessedByLayers("composition root")
                .whereLayer("commands").mayOnlyBeAccessedByLayers("composition root")
                .whereLayer("contracts").mayOnlyBeAccessedByLayers("composition root", "modules", "hooks", "commands")
                .because("see the Architecture page of the wiki")
                .check(plugin);
    }

    @Test
    void aFeatureNeverDependsOnAnother() {
        for (String group : List.of("modules", "hooks")) {
            slices().matching(BASE + "." + group + ".(*)..")
                    .should().notDependOnEachOther()
                    .allowEmptyShould(true)
                    .because("a feature reaches another one through contracts only")
                    .check(plugin);
        }
    }

    @Test
    void theRulesSeeTheCodeTheyGuard() {
        // A guard that imports nothing would pass forever.
        assertTrue(plugin.containPackage(BASE + ".core.module"));
        assertTrue(plugin.size() > 50, plugin.size() + " classes");
    }

    /**
     * In the server test's source set, a package the plugin also has is covered by the plugin's
     * package-info: a second one would be a second {@code package-info.class} in the same jar.
     */
    @Test
    void everyPackageHasANullMarkedPackageInfo() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path root : List.of(MAIN, SERVER_TEST)) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path folder : files.filter(Files::isDirectory).toList()) {
                    try (Stream<Path> inside = Files.list(folder)) {
                        boolean holdsClasses = inside.anyMatch(path -> path.toString().endsWith(".java")
                                && !path.getFileName().toString().equals("package-info.java"));
                        Path info = folder.resolve("package-info.java");
                        if (root.equals(SERVER_TEST) && !Files.exists(info)) {
                            info = MAIN.resolve(SERVER_TEST.relativize(folder)).resolve("package-info.java");
                        }
                        if (holdsClasses && (!Files.exists(info) || !Files.readString(info).contains("@NullMarked"))) {
                            missing.add(root.getParent().relativize(folder).toString());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "These packages need a package-info.java annotated with @NullMarked");
    }

    @Test
    void theServerTestAddsNoPackageInfoToAPackageOfThePlugin() throws IOException {
        try (Stream<Path> files = Files.walk(SERVER_TEST)) {
            List<String> doubled = files.filter(path -> path.getFileName().toString().equals("package-info.java"))
                    .filter(path -> Files.exists(MAIN.resolve(SERVER_TEST.relativize(path))))
                    .map(path -> SERVER_TEST.relativize(path).toString())
                    .toList();
            assertEquals(List.of(), doubled, "The plugin already has these package-info files");
        }
    }
}
