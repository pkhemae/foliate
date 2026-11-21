package dev.foliate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.foliate.servertest.ServerTestPlugin;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The layout of the code is a rule, not a habit: {@code core} knows nothing of the plugin;
 * {@code contracts} holds what features offer each other, and depends on {@code core} and itself;
 * the modules, hooks and commands depend on {@code core} and {@code contracts}, never on each other.
 * Only {@code FoliatePlugin} and {@code PluginConfig}, which compose everything, may know it all.
 *
 * <p>Checked with ArchUnit on the compiled classes of the plugin, so every real dependency counts
 * (a type, a method, a lambda) and a mention in a comment does not. One kind of dependency leaves
 * no trace in a class file: javac copies a {@code static final} constant into the class that uses
 * it. The imports of the sources are therefore checked against the same layout too. The server
 * test's classes are left out of the layout: they test everything, and the plugin cannot depend on
 * them, since they are not on its classpath. They have one rule of their own: no bundled library.
 */
class ArchitectureTest {

    /** The base package: taken from here, so that renaming the template cannot leave this test looking for the old one. */
    private static final String BASE = ArchitectureTest.class.getPackageName();

    private static final Path MAIN = Path.of("src/main/java", BASE.split("\\.", -1));
    private static final Path SERVER_TEST = Path.of("src/serverTest/java", BASE.split("\\.", -1));

    /** An import of the plugin, wildcard or static: what follows the base package. */
    private static final Pattern IMPORT =
            Pattern.compile("\\s*import\\s+(?:static\\s+)?" + Pattern.quote(BASE) + "\\.([\\w.]*[\\w*])\\s*;.*");

    /** Parents whose children are parts of their own: {@code modules.profile}, {@code hooks.zmenu}. */
    private static final Set<String> GROUPS = Set.of("modules", "hooks");

    /**
     * The packages of the libraries the plugin bundles (see {@code shadowJar}). In the plugin's jar
     * they are relocated under its own package, and the plugin's classes with them; the server
     * test's classes are not, so a reference to one of them would compile and then fail on the
     * server, where the library is only found at its relocated name.
     */
    private static final String[] BUNDLED_LIBRARIES = {
        "fr.mrmicky.fastboard..", "com.zaxxer.hikari..", "de.exlll.configlib..", "org.snakeyaml.engine..",
    };

    private static JavaClasses plugin;

    private static JavaClasses serverTest;

    /**
     * The plugin's classes only: the folder FoliatePlugin was compiled to, without the tests'. The
     * server test's, the same way, from the folder ServerTestPlugin was compiled to.
     */
    @BeforeAll
    static void importTheClasses() throws URISyntaxException {
        plugin = new ClassFileImporter().importPath(classesFolderOf(FoliatePlugin.class));
        serverTest = new ClassFileImporter().importPath(classesFolderOf(ServerTestPlugin.class));
    }

    private static Path classesFolderOf(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @Test
    void everyPartDependsOnlyOnWhatTheLayoutAllows() {
        layeredArchitecture()
                .consideringOnlyDependenciesInAnyPackage(BASE + "..")
                // A part with no class, as contracts in the template, is a rule waiting for code.
                .withOptionalLayers(true)
                // A new top-level package must find its place here before it can be used.
                .ensureAllClassesAreContainedInArchitecture()
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
        for (String group : GROUPS) {
            slices().matching(BASE + "." + group + ".(*)..")
                    .should().notDependOnEachOther()
                    .allowEmptyShould(true)
                    .because("a feature reaches another one through contracts only")
                    .check(plugin);
        }
    }

    /**
     * What the bytecode cannot show: a constant of another part, copied into the class by javac,
     * as in {@code import static ...PlaceholderApiHook.PLUGIN_NAME}. The imports of every source
     * file of the plugin follow the same layout.
     */
    @Test
    void theImportsFollowTheLayoutToo() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String unit = unitOf(MAIN.relativize(file).getParent());
                if (unit == null) {
                    continue; // the composition root may know everything
                }
                for (String line : Files.readAllLines(file)) {
                    Matcher imported = IMPORT.matcher(line);
                    if (imported.matches() && !mayDependOn(unit, unitOfImport(imported.group(1)))) {
                        violations.add(MAIN.relativize(file) + ": " + line.strip());
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "These imports break the layout, see the Architecture page of the wiki");
    }

    /** The server test reaches a bundled library through the plugin only, whose references the relocation follows. */
    @Test
    void theServerTestNeverUsesABundledLibraryDirectly() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(BUNDLED_LIBRARIES)
                .because("the server test jar does not relocate the server test's classes, see serverTestJar")
                .check(serverTest);
    }

    @Test
    void theRulesSeeTheCodeTheyGuard() {
        // A guard that imports nothing would pass forever: from a jar instead of the classes
        // folder, the importer finds nothing.
        assertTrue(plugin.containPackage(BASE + ".core.module"));
        assertTrue(plugin.size() > 10, plugin.size() + " classes");
        // The server test's folder holds only its classes, not the plugin's next to them.
        assertTrue(serverTest.contain(ServerTestPlugin.class));
        assertFalse(serverTest.contain(FoliatePlugin.class));
    }

    /** What a part of {@code unit} may depend on: itself, core, and the contracts unless it is core. */
    private static boolean mayDependOn(String unit, String target) {
        return target.equals(unit) || target.equals("core") || (target.equals("contracts") && !unit.equals("core"));
    }

    /** The part a folder of the plugin belongs to ({@code modules.profile}, {@code core}), or null for the root. */
    private static @Nullable String unitOf(@Nullable Path folder) {
        if (folder == null) {
            return null;
        }
        String first = folder.getName(0).toString();
        return GROUPS.contains(first) && folder.getNameCount() > 1 ? first + "." + folder.getName(1) : first;
    }

    /** The part an imported name belongs to; "root" for a class of the root package. */
    private static String unitOfImport(String name) {
        String[] parts = name.split("\\.", -1);
        if (Character.isUpperCase(parts[0].charAt(0))) {
            return "root";
        }
        return GROUPS.contains(parts[0]) && parts.length > 1 ? parts[0] + "." + parts[1] : parts[0];
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
