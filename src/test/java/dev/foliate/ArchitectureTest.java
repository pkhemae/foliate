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
 * <p>It reads the imports of the source files, so a violation is reported with the file and the
 * line, before it can spread.
 */
class ArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java/dev/foliate");
    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?dev\\.foliate\\.([\\w.]+);", Pattern.MULTILINE);

    /** Parents whose children are independent of one another: {@code modules.sidebar}, ... */
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
                Matcher imports = IMPORT.matcher(Files.readString(file));
                while (imports.find()) {
                    String target = unitOfImport(imports.group(1));
                    if (!target.equals(unit) && !target.equals("core")) {
                        violations.add(file + " (" + unit + ") imports dev.foliate." + imports.group(1));
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "These imports break the layout, see the Architecture page of the wiki");
    }

    @Test
    void theRuleSeesTheCodeItGuards() throws IOException {
        // A guard that scans nothing would pass forever.
        try (Stream<Path> files = Files.walk(MAIN.resolve("core"))) {
            assertTrue(files.filter(path -> path.toString().endsWith(".java")).count() > 10);
        }
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
        if (Character.isUpperCase(parts[0].charAt(0))) {
            return "root";
        }
        return GROUPS.contains(parts[0]) ? parts[0] + "." + parts[1] : parts[0];
    }
}
