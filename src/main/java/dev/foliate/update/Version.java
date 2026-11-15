package dev.foliate.update;

import java.util.ArrayList;
import java.util.List;

/**
 * A version such as {@code 1.4.0} or {@code v1.4.0-SNAPSHOT}, compared the way people expect:
 * number by number, and a version with a suffix (a snapshot or pre-release) comes before the same
 * version without one. Two pre-releases of the same version count as equal, which is enough here:
 * GitHub's latest release is never a pre-release.
 */
record Version(List<Long> numbers, boolean preRelease) implements Comparable<Version> {

    static Version parse(String text) {
        String cleaned = text.strip();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        // Build metadata ("+build.5") says nothing about order.
        int metadata = cleaned.indexOf('+');
        if (metadata >= 0) {
            cleaned = cleaned.substring(0, metadata);
        }
        int suffix = cleaned.indexOf('-');
        String core = suffix < 0 ? cleaned : cleaned.substring(0, suffix);
        List<Long> numbers = new ArrayList<>();
        for (String part : core.split("\\.")) {
            try {
                numbers.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + text + "' is not a version such as 1.4.0", e);
            }
        }
        return new Version(List.copyOf(numbers), suffix >= 0);
    }

    @Override
    public int compareTo(Version other) {
        for (int i = 0; i < Math.max(numbers.size(), other.numbers.size()); i++) {
            long mine = i < numbers.size() ? numbers.get(i) : 0;
            long theirs = i < other.numbers.size() ? other.numbers.get(i) : 0;
            if (mine != theirs) {
                return Long.compare(mine, theirs);
            }
        }
        return Boolean.compare(other.preRelease, preRelease);
    }
}
