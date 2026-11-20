package dev.foliate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The layout of the code is a rule, not a habit: {@code core} knows nothing of the plugin, and the
 * modules, hooks and commands depend on {@code core} only, never on each other. Only
 * {@code FoliatePlugin} and {@code PluginConfig}, which compose everything, may know it all.
 *
 * <p>It reads every mention of the plugin's packages in the source files (imports, wildcard or
 * static, and qualified names), so a violation is reported with the file before it can spread.
 */
class ArchitectureTest {

    /** The base package: taken from here, so that renaming the template cannot leave this test looking for the old one. */
    private static final String BASE = ArchitectureTest.class.getPackageName();

    private static final Path MAIN = Path.of("src/main/java", BASE.split("\\."));
    /** Any mention of a plugin class or package: an import (wildcard or static) or a qualified name. */
    private static final Pattern REFERENCE = Pattern.compile("\\b" + Pattern.quote(BASE) + "\\.([\\w.]*[\\w*])");

    /** Parents whose children are independent of one another: {@code modules.alpha}, ... */
    private static final Set<String> GROUPS = Set.of("modules", "hooks");

    @Test
    void everyPartDependsOnCoreAndItselfOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String unit = unitOfFile(MAIN.relativize(file));
                if (unit == null) {
                    continue; // the composition root
                }
                for (String reference : foreignReferences(unit, Files.readString(file))) {
                    violations.add(file + " (" + unit + ") refers to dev.foliate." + reference);
                }
            }
        }
        assertEquals(List.of(), violations, "These imports break the layout, see the Architecture page of the wiki");
    }

    @Test
    void theRuleCatchesEveryWayToReachAnotherPart() {
        String source = """
                package dev.foliate.modules.alpha;

                import dev.foliate.core.message.MessageService;
                import dev.foliate.modules.beta.*;
                import static dev.foliate.hooks.gamma.GammaHook.NAME;
                import dev.foliate.PluginConfig;

                class Example {
                    dev.foliate.commands.FoliateCommand command;
                    // see dev.foliate.modules
                }
                """;

        assertEquals(
                List.of(
                        "modules.beta.*",
                        "hooks.gamma.GammaHook.NAME",
                        "PluginConfig",
                        "commands.FoliateCommand",
                        "modules"),
                foreignReferences("modules.alpha", source));
    }

    @Test
    void theRuleSeesTheCodeItGuards() throws IOException {
        // A guard that scans nothing would pass forever.
        try (Stream<Path> files = Files.walk(MAIN.resolve("core"))) {
            assertTrue(files.filter(path -> path.toString().endsWith(".java")).count() > 10);
        }
    }

    /** What a source file of {@code unit} mentions of the plugin outside {@code core} and itself. */
    private static List<String> foreignReferences(String unit, String source) {
        List<String> foreign = new ArrayList<>();
        Matcher references = REFERENCE.matcher(source);
        while (references.find()) {
            String target = unitOfImport(references.group(1));
            if (!target.equals(unit) && !target.equals("core")) {
                foreign.add(references.group(1));
            }
        }
        return foreign;
    }

    /** The unit a source file belongs to, or null for the classes of the root package. */
    private static String unitOfFile(Path relative) {
        if (relative.getNameCount() == 1) {
            return null;
        }
        String first = relative.getName(0).toString();
        return GROUPS.contains(first) ? first + "." + relative.getName(1) : first;
    }

    /** The unit an imported name belongs to; "root" for a class of the root package. */
    private static String unitOfImport(String name) {
        String[] parts = name.split("\\.");
        if (Character.isUpperCase(parts[0].charAt(0)) || parts[0].equals("*")) {
            return "root";
        }
        // A bare "modules" or "hooks", in a comment for instance, names no part in particular.
        return GROUPS.contains(parts[0]) && parts.length > 1 ? parts[0] + "." + parts[1] : parts[0];
    }
}
