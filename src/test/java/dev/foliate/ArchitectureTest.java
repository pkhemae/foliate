package dev.foliate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.foliate.core.command.Subcommand;
import dev.foliate.core.module.Module;
import dev.foliate.servertest.ServerTestPlugin;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
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
     * The roles of a feature: what the server and the database call, each in a package of its own
     * whichever part of the feature it serves. A new role is added here on purpose, and to the
     * layout described in CLAUDE.md, not by creating a folder.
     */
    private static final Set<String> ROLES = Set.of("command", "listener", "storage");

    /**
     * Names that say what kind of code a package holds, not which part of the domain: a feature's
     * other packages are its concepts, named after what they are about ({@code course}, {@code race}).
     */
    private static final Set<String> NOT_CONCEPTS = Set.of(
            "api", "base", "commands", "common", "config", "configuration", "constants", "core", "dao", "data",
            "database", "db", "domain", "dto", "enums", "exceptions", "handler", "handlers", "helper", "helpers",
            "impl", "interfaces", "internal", "listeners", "logic", "manager", "managers", "misc", "model",
            "models", "other", "persistence", "records", "repositories", "repository", "service", "services",
            "shared", "sql", "types", "util", "utilities", "utility", "utils");

    /** The most classes a feature's own package holds: past it, a part of the feature becomes a concept. */
    private static final int FEATURE_PACKAGE_LIMIT = 10;

    /** The most classes a concept holds: past it, the concept is two. */
    private static final int CONCEPT_LIMIT = 15;

    /** A concept's name: one lowercase word, as a part of the domain is named. */
    private static final Pattern CONCEPT_NAME = Pattern.compile("[a-z]+");

    /** What a feature's entry point is called after, in each group: {@code ProfileModule}, {@code ZMenuHook}. */
    private static final Map<String, String> ENTRY_SUFFIX = Map.of("modules", "Module", "hooks", "Hook");

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
     * Outside a feature, only its own package is known: its entry point and its settings, for the
     * composition root. What lies below (commands, listeners, SQL) is the feature's business, even
     * when public so that its own package can reach it.
     */
    @Test
    void outsideAFeatureOnlyItsOwnPackageIsUsed() {
        for (String group : GROUPS) {
            noClasses().that().resideOutsideOfPackage(BASE + "." + group + "..")
                    .should().dependOnClassesThat().resideInAPackage(BASE + "." + group + ".*.*..")
                    .allowEmptyShould(true)
                    .because("a feature is reached through its own package, see the layout in CLAUDE.md")
                    .check(plugin);
        }
    }

    /**
     * Below a feature's own package come its roles and its concepts, one level deep: a concept is
     * named after a part of the domain, never after a kind of code, so that every feature reads
     * the same way however it grows.
     */
    @Test
    void aFeaturesPackagesAreItsRolesAndItsConcepts() {
        Set<String> wrong = new TreeSet<>();
        for (String name : featurePackages().keySet()) {
            String[] parts = name.split("\\.", -1);
            if (parts.length == 2) {
                continue; // the feature's own package
            }
            String below = parts[2];
            if (parts.length > 3) {
                wrong.add(name + ": nothing goes below a role or a concept");
            } else if (!ROLES.contains(below) && (NOT_CONCEPTS.contains(below) || !CONCEPT_NAME.matcher(below).matches())) {
                wrong.add(name + ": a concept is one lowercase word for a part of the domain, not " + new TreeSet<>(NOT_CONCEPTS));
            }
        }
        assertEquals(Set.of(), wrong, "See \"Laying out a feature\" in CLAUDE.md");
    }

    /**
     * A package that keeps growing is a heap: the feature's own past {@value #FEATURE_PACKAGE_LIMIT}
     * classes needs a concept, and a concept past {@value #CONCEPT_LIMIT} is two. Roles hold one
     * class per subcommand, listener or repository, and are not limited.
     */
    @Test
    void aFeatureKeepsEachOfItsPackagesSmall() {
        Set<String> crowded = new TreeSet<>();
        featurePackages().forEach((name, classes) -> {
            String[] parts = name.split("\\.", -1);
            if (parts.length > 2 && ROLES.contains(parts[2])) {
                return;
            }
            int limit = parts.length == 2 ? FEATURE_PACKAGE_LIMIT : CONCEPT_LIMIT;
            if (classes.size() > limit) {
                crowded.add(name + " holds " + classes.size() + " classes, " + limit + " at most");
            }
        });
        assertEquals(Set.of(), crowded, "Group a part of the feature into a concept, see CLAUDE.md");
    }

    /**
     * The concepts of a feature are layered: when {@code race} uses {@code course}, {@code course}
     * does not use {@code race}, directly or through another concept. The feature's own package and
     * its roles may use any of them. Read from the bytecode and, for a constant javac copies into
     * the class that uses it, from the imports.
     */
    @Test
    void aFeaturesConceptsNeverDependOnEachOtherInACircle() throws IOException {
        Map<String, Set<String>> uses = new TreeMap<>();
        for (JavaClass type : plugin) {
            String from = conceptOf(type);
            if (from == null) {
                continue;
            }
            uses.computeIfAbsent(from, f -> new TreeSet<>());
            type.getDirectDependenciesFromSelf().forEach(dependency -> {
                String to = conceptOf(dependency.getTargetClass());
                if (to != null && !to.equals(from) && featureOf(to).equals(featureOf(from))) {
                    uses.get(from).add(to);
                }
            });
        }
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String from = conceptOfFolder(MAIN.relativize(file).getParent());
                if (from == null) {
                    continue;
                }
                for (String line : Files.readAllLines(file)) {
                    Matcher imported = IMPORT.matcher(line);
                    if (imported.matches()) {
                        String to = conceptOfImport(imported.group(1));
                        if (to != null && !to.equals(from) && featureOf(to).equals(featureOf(from))) {
                            uses.computeIfAbsent(from, f -> new TreeSet<>()).add(to);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), circles(uses), "Concepts of a feature that use each other in a circle are one concept, or need a third");
    }

    /** Each circle of the graph, once, from its smallest concept: {@code a -> b -> a}. */
    static List<String> circles(Map<String, Set<String>> uses) {
        List<String> circles = new ArrayList<>();
        for (String start : new TreeSet<>(uses.keySet())) {
            List<String> path = circleFrom(start, start, uses, new ArrayList<>(List.of(start)), new HashSet<>());
            if (path != null && start.equals(path.stream().sorted().findFirst().orElseThrow())) {
                circles.add(String.join(" -> ", path));
            }
        }
        return circles;
    }

    @Test
    void circlesAreFoundOnceEachAndOnlyWhenThereAreSome() {
        assertEquals(List.of(), circles(Map.of("a", Set.of("b"), "b", Set.of("c"), "c", Set.of())));
        assertEquals(List.of("a -> b -> a"), circles(Map.of("a", Set.of("b"), "b", Set.of("a"))));
        assertEquals(List.of("a -> b -> c -> a"), circles(Map.of("a", Set.of("b"), "b", Set.of("c"), "c", Set.of("a"))));
        assertEquals(
                List.of("a -> b -> a", "x -> y -> x"),
                circles(Map.of("a", Set.of("b"), "b", Set.of("a"), "x", Set.of("y"), "y", Set.of("x"), "z", Set.of("x"))));
    }

    /** The concept of a folder of the plugin ({@code modules/elytra/course}), or null. */
    private static @Nullable String conceptOfFolder(@Nullable Path folder) {
        if (folder == null || folder.getNameCount() != 3 || !GROUPS.contains(folder.getName(0).toString())) {
            return null;
        }
        String role = folder.getName(2).toString();
        return ROLES.contains(role) ? null : folder.getName(0) + "." + folder.getName(1) + "." + role;
    }

    /** The concept an imported name lies in ({@code modules.elytra.course.Course}), or null. */
    private static @Nullable String conceptOfImport(String name) {
        String[] parts = name.split("\\.", -1);
        boolean inAConcept = parts.length > 3 && GROUPS.contains(parts[0]) && Character.isLowerCase(parts[2].charAt(0))
                && !ROLES.contains(parts[2]);
        return inAConcept ? parts[0] + "." + parts[1] + "." + parts[2] : null;
    }

    /** A path back to {@code start} through the concepts {@code at} uses, or null. */
    private static @Nullable List<String> circleFrom(
            String start, String at, Map<String, Set<String>> uses, List<String> path, Set<String> seen) {
        for (String next : uses.getOrDefault(at, Set.of())) {
            if (next.equals(start)) {
                List<String> circle = new ArrayList<>(path);
                circle.add(start);
                return circle;
            }
            if (seen.add(next)) {
                path.add(next);
                List<String> found = circleFrom(start, next, uses, path, seen);
                if (found != null) {
                    return found;
                }
                path.removeLast();
            }
        }
        return null;
    }

    /** The concept a class belongs to ({@code modules.elytra.course}), or null for a role, a feature's own package or the rest. */
    private static @Nullable String conceptOf(JavaClass type) {
        String name = relative(type.getPackageName());
        if (name == null) {
            return null;
        }
        String[] parts = name.split("\\.", -1);
        return parts.length == 3 && GROUPS.contains(parts[0]) && !ROLES.contains(parts[2]) ? name : null;
    }

    /** {@code modules.elytra} for {@code modules.elytra.course}. */
    private static String featureOf(String concept) {
        return concept.substring(0, concept.lastIndexOf('.'));
    }

    /** A package of the plugin without the base package before it, or null when it is not one. */
    private static @Nullable String relative(String packageName) {
        return packageName.startsWith(BASE + ".") ? packageName.substring(BASE.length() + 1) : null;
    }

    /**
     * Every package of every feature, from its own ({@code modules.elytra}) to its roles and
     * concepts ({@code modules.elytra.course}), with the classes written in it: nested classes and
     * {@code package-info} left out.
     */
    private static Map<String, List<String>> featurePackages() {
        Map<String, List<String>> packages = new TreeMap<>();
        for (JavaClass type : plugin) {
            String name = relative(type.getPackageName());
            if (name == null) {
                continue;
            }
            String[] parts = name.split("\\.", -1);
            if (parts.length < 2 || !GROUPS.contains(parts[0])) {
                continue;
            }
            List<String> classes = packages.computeIfAbsent(name, n -> new ArrayList<>());
            if (type.isTopLevelClass() && !type.getSimpleName().equals("package-info")) {
                classes.add(type.getSimpleName());
            }
        }
        return packages;
    }

    /**
     * Commands and listeners are what the server calls: nothing depends on them but the feature's
     * own package, where the entry point creates them, and others of the same role. The imports
     * are held to the same rule, see {@link #theImportsFollowTheLayoutToo}.
     */
    @Test
    void commandsAndListenersAreReachedFromTheFeaturesPackageOnly() {
        for (String group : GROUPS) {
            for (String role : List.of("command", "listener")) {
                classes().that().resideInAPackage(BASE + "." + group + ".*." + role)
                        .should().onlyHaveDependentClassesThat()
                        .resideInAnyPackage(BASE + "." + group + ".*", BASE + "." + group + ".*." + role)
                        .allowEmptyShould(true)
                        .because("a feature's " + role + "s only turn what the server calls into calls on the feature")
                        .check(plugin);
            }
        }
    }

    /** Found by their name and their place, in every feature alike. */
    @Test
    void commandsAndListenersAreNamedAndPlacedByTheirRole() {
        classes().that().implement(Subcommand.class).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Command")
                .because("a subcommand is named after what it does: ProfileCommand, ReloadCommand")
                .check(plugin);
        for (String group : GROUPS) {
            classes().that().implement(Subcommand.class).and().resideInAPackage(BASE + "." + group + "..")
                    .should().resideInAPackage(BASE + "." + group + ".*.command")
                    .allowEmptyShould(true)
                    .because("a feature's subcommands live in its command package")
                    .check(plugin);
            classes().that().implement(Listener.class).and().resideInAPackage(BASE + "." + group + "..")
                    .should().resideInAPackage(BASE + "." + group + ".*.listener")
                    .andShould().haveSimpleNameEndingWith("Listener")
                    .allowEmptyShould(true)
                    .because("a feature's listeners live in its listener package, apart from the logic they call")
                    .check(plugin);
        }
    }

    /**
     * Each feature has one entry point, in its own package, named after it: what the composition
     * root registers, and where a reader starts.
     */
    @Test
    void everyFeatureHasOneEntryPointInItsOwnPackage() {
        Map<String, List<String>> entries = new TreeMap<>();
        int depth = BASE.split("\\.", -1).length;
        for (JavaClass type : plugin) {
            String[] parts = type.getPackageName().split("\\.", -1);
            if (parts.length > depth + 1 && GROUPS.contains(parts[depth])) {
                String feature = parts[depth] + "." + parts[depth + 1];
                entries.computeIfAbsent(feature, f -> new ArrayList<>());
                if (type.isAssignableTo(Module.class) && type.isTopLevelClass()) {
                    entries.get(feature).add(type.getName());
                }
            }
        }
        assertFalse(entries.isEmpty(), "no feature found");
        entries.forEach((feature, found) -> {
            String group = feature.substring(0, feature.indexOf('.'));
            assertEquals(1, found.size(), feature + " needs exactly one Module, its entry point: " + found);
            String entry = found.getFirst();
            assertEquals(BASE + "." + feature, entry.substring(0, entry.lastIndexOf('.')), entry + " belongs in the feature's own package");
            // ProfileModule for modules.profile, ZMenuHook for hooks.zmenu.
            String expected = feature.substring(group.length() + 1) + ENTRY_SUFFIX.get(group);
            assertTrue(
                    entry.substring(entry.lastIndexOf('.') + 1).equalsIgnoreCase(expected),
                    entry + " is named after its feature: " + expected + ", whatever the case");
        });
    }

    /**
     * What the bytecode cannot show: a constant of another part, copied into the class by javac,
     * as in {@code import static ...PlaceholderApiHook.PLUGIN_NAME}. The imports of every source
     * file of the plugin follow the same layout, the roles inside a feature included.
     */
    @Test
    void theImportsFollowTheLayoutToo() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                // Null for the composition root, which may know every part, but not inside a feature.
                String unit = unitOf(MAIN.relativize(file).getParent());
                for (String line : Files.readAllLines(file)) {
                    Matcher imported = IMPORT.matcher(line);
                    if (!imported.matches()) {
                        continue;
                    }
                    String target = unitOfImport(imported.group(1));
                    boolean outsideTheLayout = unit != null && !mayDependOn(unit, target);
                    boolean insideAnotherFeature = !target.equals(unit) && isBelowAFeature(imported.group(1));
                    boolean intoAnotherRole = target.equals(unit) && reachesAnotherRole(MAIN.relativize(file), imported.group(1));
                    if (outsideTheLayout || insideAnotherFeature || intoAnotherRole) {
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

    /** Whether an imported name lies in a package below a feature's own: {@code modules.profile.command.ProfileCommand}. */
    private static boolean isBelowAFeature(String name) {
        String[] parts = name.split("\\.", -1);
        return GROUPS.contains(parts[0]) && parts.length > 3 && Character.isLowerCase(parts[2].charAt(0));
    }

    /**
     * Whether a file in a role of its feature, {@code storage} say, imports from the commands or the
     * listeners of the same feature: only the feature's own package and the same role may.
     */
    private static boolean reachesAnotherRole(Path file, String name) {
        String[] parts = name.split("\\.", -1);
        if (file.getNameCount() < 4 || parts.length < 4) {
            return false; // the file is in the feature's own package, or the import is not in a role
        }
        String role = file.getName(2).toString();
        return List.of("command", "listener").contains(parts[2]) && !parts[2].equals(role);
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
