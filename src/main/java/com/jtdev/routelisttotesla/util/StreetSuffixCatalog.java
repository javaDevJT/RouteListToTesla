package com.jtdev.routelisttotesla.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Token-level USPS street-suffix normalization backed by the checked-in Pub 28 C1 catalog.
 *
 * <p>Callers must identify a suffix token by its position before using this class. It deliberately
 * does not rewrite complete street names.</p>
 */
public final class StreetSuffixCatalog {
    private static final String RESOURCE = "/address/street-suffixes.tsv";
    private static final List<Entry> ENTRIES = readEntries();
    private static final CatalogIndex INDEX = buildIndex(ENTRIES);

    private StreetSuffixCatalog() {
    }

    /**
     * Returns the official primary full suffix name for a recognized token.
     * Case and terminal punctuation are normalized. Unknown and ambiguous aliases are returned
     * in normalized form.
     */
    public static String canonical(String token) {
        String normalized = normalize(token);
        if (normalized == null) return null;
        return INDEX.canonicalByAlias().getOrDefault(normalized, normalized);
    }

    /**
     * Reports whether a token appears in the checked-in catalog. Ambiguous spellings are known
     * suffix forms even though {@link #canonical(String)} deliberately leaves them unresolved.
     */
    public static boolean isKnown(String token) {
        String normalized = normalize(token);
        return normalized != null && INDEX.allAliases().contains(normalized);
    }

    /**
     * Returns all normalized primary names, USPS forms, and compatibility aliases in sorted,
     * immutable order. Ambiguous forms remain included for suffix recognition.
     */
    public static Set<String> aliases() {
        return INDEX.allAliases();
    }

    /**
     * Compares two suffix tokens without erasing distinct explicit primary names. A non-primary
     * ambiguous abbreviation can match each primary form that USPS lists for it; this method is
     * intended only for suffix-position comparisons, never for whole-address or destination checks.
     */
    public static boolean equivalent(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft == null || normalizedRight == null) return false;
        if (normalizedLeft.equals(normalizedRight)) return true;

        String canonicalLeft = canonical(normalizedLeft);
        String canonicalRight = canonical(normalizedRight);
        if (canonicalLeft.equals(canonicalRight)) return true;
        return ambiguousAbbreviationMatches(normalizedLeft, canonicalRight)
                || ambiguousAbbreviationMatches(normalizedRight, canonicalLeft);
    }

    private static boolean ambiguousAbbreviationMatches(String spelling, String otherCanonical) {
        if (INDEX.entriesByPrimary().containsKey(spelling)) return false;
        Set<String> possiblePrimaries = INDEX.ambiguousSpellings().get(spelling);
        return possiblePrimaries != null && possiblePrimaries.contains(otherCanonical);
    }

    /**
     * Returns the deterministic provider query spellings for a suffix token: primary full name,
     * then the USPS standard abbreviation when that abbreviation resolves to the same primary.
     * Unknown and ambiguous tokens retain just their normalized spelling.
     */
    public static List<String> preferredSpellings(String token) {
        String normalized = normalize(token);
        if (normalized == null || normalized.isEmpty()) return List.of();
        String primary = INDEX.canonicalByAlias().get(normalized);
        Entry entry = primary == null ? null : INDEX.entriesByPrimary().get(primary);
        if (entry == null) return List.of(normalized);
        if (entry.primary().equals(entry.standard())) return List.of(entry.primary());
        if (entry.primary().equals(INDEX.canonicalByAlias().get(entry.standard()))) {
            return List.of(entry.primary(), entry.standard());
        }
        return List.of(entry.primary());
    }

    static List<Entry> entries() {
        return ENTRIES;
    }

    static Map<String, Set<String>> ambiguousSpellings() {
        return INDEX.ambiguousSpellings();
    }

    private static CatalogIndex buildIndex(List<Entry> entries) {
        Map<String, Entry> entriesByPrimary = new LinkedHashMap<>();
        Map<String, Set<String>> ownersBySpelling = new TreeMap<>();

        for (Entry entry : entries) {
            Entry duplicate = entriesByPrimary.putIfAbsent(entry.primary(), entry);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate primary suffix in " + RESOURCE + ": " + entry.primary());
            }
            for (String spelling : entry.allSpellings()) {
                ownersBySpelling.computeIfAbsent(spelling, ignored -> new TreeSet<>()).add(entry.primary());
            }
        }

        Map<String, Set<String>> ambiguous = new LinkedHashMap<>();
        ownersBySpelling.forEach((spelling, owners) -> {
            if (owners.size() > 1) ambiguous.put(spelling, immutableSortedSet(owners));
        });

        Map<String, String> canonicalByAlias = new LinkedHashMap<>();
        entriesByPrimary.keySet().forEach(primary -> canonicalByAlias.put(primary, primary));
        ownersBySpelling.forEach((spelling, owners) -> {
            // Keep full primary names self-identifying, even if another C1 row uses the same
            // spelling as an abbreviation. Other ambiguous aliases remain unresolved.
            if (owners.size() == 1) canonicalByAlias.put(spelling, owners.iterator().next());
        });

        return new CatalogIndex(
                Collections.unmodifiableMap(entriesByPrimary),
                Collections.unmodifiableMap(canonicalByAlias),
                Collections.unmodifiableMap(ambiguous),
                Collections.unmodifiableSet(new LinkedHashSet<>(ownersBySpelling.keySet())));
    }

    private static Set<String> immutableSortedSet(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(values)));
    }

    private static List<Entry> readEntries() {
        InputStream stream = StreetSuffixCatalog.class.getResourceAsStream(RESOURCE);
        if (stream == null) throw new IllegalStateException("Missing street suffix catalog resource: " + RESOURCE);

        List<Entry> entries = new ArrayList<>();
        boolean headerSeen = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) continue;
                if (!headerSeen) {
                    if (!line.equals("PRIMARY\tSTANDARD\tUSPS_ALIASES\tCOMPATIBILITY_ALIASES")) {
                        throw malformed(lineNumber, "unexpected header");
                    }
                    headerSeen = true;
                    continue;
                }

                String[] columns = line.split("\\t", -1);
                if (columns.length != 4) throw malformed(lineNumber, "expected four tab-separated columns");
                String primary = requireToken(columns[0], lineNumber, "primary suffix");
                String standard = requireToken(columns[1], lineNumber, "standard abbreviation");
                List<String> aliases = readTokenList(columns[2], lineNumber, "USPS aliases");
                List<String> compatibility = readTokenList(columns[3], lineNumber, "compatibility aliases");
                if (aliases.isEmpty()) throw malformed(lineNumber, "USPS aliases must not be empty");
                entries.add(new Entry(primary, standard, aliases, compatibility));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read street suffix catalog resource: " + RESOURCE, exception);
        }
        if (!headerSeen || entries.isEmpty()) throw new IllegalStateException("Empty or invalid street suffix catalog: " + RESOURCE);
        return List.copyOf(entries);
    }

    private static List<String> readTokenList(String column, int lineNumber, String description) {
        if (column.isBlank() || column.equals("-")) return List.of();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String value : column.split("\\|", -1)) {
            tokens.add(requireToken(value, lineNumber, description));
        }
        return List.copyOf(tokens);
    }

    private static String requireToken(String value, int lineNumber, String description) {
        String token = normalize(value);
        if (token == null || token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
            throw malformed(lineNumber, description + " must be one non-empty token");
        }
        return token;
    }

    private static IllegalStateException malformed(int lineNumber, String reason) {
        return new IllegalStateException("Malformed " + RESOURCE + " line " + lineNumber + ": " + reason);
    }

    private static String normalize(String token) {
        if (token == null) return null;
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        int end = normalized.length();
        while (end > 0) {
            int codePoint = normalized.codePointBefore(end);
            if (!isPunctuation(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return normalized.substring(0, end).trim();
    }

    private static boolean isPunctuation(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    record Entry(String primary, String standard, List<String> aliases, List<String> compatibilityAliases) {
        Entry {
            aliases = List.copyOf(aliases);
            compatibilityAliases = List.copyOf(compatibilityAliases);
        }

        List<String> allSpellings() {
            LinkedHashSet<String> spellings = new LinkedHashSet<>();
            spellings.add(primary);
            spellings.add(standard);
            spellings.addAll(aliases);
            spellings.addAll(compatibilityAliases);
            return List.copyOf(spellings);
        }
    }

    private record CatalogIndex(
            Map<String, Entry> entriesByPrimary,
            Map<String, String> canonicalByAlias,
            Map<String, Set<String>> ambiguousSpellings,
            Set<String> allAliases) {
    }
}
