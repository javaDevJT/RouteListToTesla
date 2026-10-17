package com.jtdev.routelisttotesla.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreetSuffixCatalogTest {
    @Test
    void everyUspsPrimaryAndRecordedAliasUsesItsExpectedIdentity() {
        List<StreetSuffixCatalog.Entry> entries = StreetSuffixCatalog.entries();
        assertEquals(206, entries.size(), "The checked-in source is the complete Pub 28 C1 table.");

        Map<String, Set<String>> ownersBySpelling = new LinkedHashMap<>();
        Set<String> primaryNames = new LinkedHashSet<>();
        int perPrimaryAliasClaims = 0;
        for (StreetSuffixCatalog.Entry entry : entries) {
            primaryNames.add(entry.primary());
            Set<String> uspsSpellings = new LinkedHashSet<>();
            uspsSpellings.add(entry.primary());
            uspsSpellings.add(entry.standard());
            uspsSpellings.addAll(entry.aliases());
            perPrimaryAliasClaims += uspsSpellings.size();
            for (String spelling : entry.allSpellings()) {
                ownersBySpelling.computeIfAbsent(spelling, ignored -> new LinkedHashSet<>()).add(entry.primary());
            }
        }
        assertEquals(554, perPrimaryAliasClaims);

        for (StreetSuffixCatalog.Entry entry : entries) {
            assertEquals(entry.primary(), StreetSuffixCatalog.canonical(entry.primary()));
            assertEquals(expectedPreferred(entry), StreetSuffixCatalog.preferredSpellings(entry.primary()));
            for (String spelling : entry.allSpellings()) {
                Set<String> owners = ownersBySpelling.get(spelling);
                String expected = primaryNames.contains(spelling) || owners.size() > 1
                        ? spelling
                        : entry.primary();
                assertEquals(expected, StreetSuffixCatalog.canonical(spelling),
                        spelling + " should preserve its source identity");
            }
        }
    }

    @Test
    void collisionsAreExplicitAndNeverResolvedByResourceOrder() {
        assertEquals(Map.of(
                "MDW", Set.of("MEADOW", "MEADOWS"),
                "PARK", Set.of("PARK", "PARKS"),
                "PKWY", Set.of("PARKWAY", "PARKWAYS"),
                "SPUR", Set.of("SPUR", "SPURS"),
                "WALK", Set.of("WALK", "WALKS")),
                StreetSuffixCatalog.ambiguousSpellings());

        assertEquals("MDW", StreetSuffixCatalog.canonical("mdw"));
        assertEquals("PKWY", StreetSuffixCatalog.canonical("PKWY."));
        assertEquals("PARK", StreetSuffixCatalog.canonical("park"));
        assertEquals("PARKS", StreetSuffixCatalog.canonical("parks"));
        assertEquals("PARKWAY", StreetSuffixCatalog.canonical("parkway"));
        assertEquals("PARKWAYS", StreetSuffixCatalog.canonical("parkways"));
        assertEquals(List.of("PARKWAY"), StreetSuffixCatalog.preferredSpellings("PARKWAY"));
        assertEquals(List.of("PARKWAYS"), StreetSuffixCatalog.preferredSpellings("PARKWAYS"));
        assertEquals(List.of("MEADOW"), StreetSuffixCatalog.preferredSpellings("MEADOW"));
        assertEquals(List.of("MEADOWS", "MDWS"), StreetSuffixCatalog.preferredSpellings("MEADOWS"));
        assertEquals(List.of("PARKS"), StreetSuffixCatalog.preferredSpellings("PARKS"));
        assertEquals(List.of("SPURS"), StreetSuffixCatalog.preferredSpellings("SPURS"));
        assertEquals(List.of("WALKS"), StreetSuffixCatalog.preferredSpellings("WALKS"));
        assertEquals(List.of("MDW"), StreetSuffixCatalog.preferredSpellings("MDW"));
        assertEquals(List.of("PKWY"), StreetSuffixCatalog.preferredSpellings("PKWY"));

        assertTrue(StreetSuffixCatalog.equivalent("MDW", "MEADOW"));
        assertTrue(StreetSuffixCatalog.equivalent("MEADOWS", "MDW"));
        assertFalse(StreetSuffixCatalog.equivalent("MEADOW", "MEADOWS"));
        assertTrue(StreetSuffixCatalog.equivalent("PKWY", "PARKWAY"));
        assertTrue(StreetSuffixCatalog.equivalent("PARKWAYS", "PKWY"));
        assertFalse(StreetSuffixCatalog.equivalent("PARKWAY", "PARKWAYS"));
        assertFalse(StreetSuffixCatalog.equivalent("PARK", "PARKS"));
        assertFalse(StreetSuffixCatalog.equivalent("SPUR", "SPURS"));
        assertFalse(StreetSuffixCatalog.equivalent("WALK", "WALKS"));
        assertTrue(StreetSuffixCatalog.equivalent("RTE.", "ROUTE"));
        assertTrue(StreetSuffixCatalog.equivalent("unknown.", "UNKNOWN"));
        assertFalse(StreetSuffixCatalog.equivalent("UNKNOWN", "OTHER"));
    }

    @Test
    void recognizesUncommonSuffixesWithoutCollapsingPluralIdentities() {
        assertEquals("POINT", StreetSuffixCatalog.canonical("pt"));
        assertEquals("POINTS", StreetSuffixCatalog.canonical("pts"));
        assertEquals("POINT", StreetSuffixCatalog.canonical("POINT"));
        assertEquals("POINTS", StreetSuffixCatalog.canonical("POINTS"));
        assertEquals(List.of("POINT", "PT"), StreetSuffixCatalog.preferredSpellings("POINT"));
        assertEquals(List.of("POINTS", "PTS"), StreetSuffixCatalog.preferredSpellings("POINTS"));

        assertEquals("CROSSING", StreetSuffixCatalog.canonical("xing"));
        assertEquals("JUNCTION", StreetSuffixCatalog.canonical("jct"));
        assertEquals("HEIGHTS", StreetSuffixCatalog.canonical("hts"));
        assertEquals("PARKWAY", StreetSuffixCatalog.canonical("PARKWAY"));
        assertEquals("PKWY", StreetSuffixCatalog.canonical("pkwy"), "PKWY is shared by PARKWAY and PARKWAYS.");
    }

    @Test
    void preservesExistingCompatibilitySpellingsAndNormalizesOnlyTheToken() {
        assertEquals("TRAIL", StreetSuffixCatalog.canonical("tr"));
        assertEquals("AVENUE", StreetSuffixCatalog.canonical("av."));
        assertEquals("ROUTE", StreetSuffixCatalog.canonical("rte"));
        assertEquals(List.of("TRAIL", "TRL"), StreetSuffixCatalog.preferredSpellings("Tr."));
        assertEquals(List.of("AVENUE", "AVE"), StreetSuffixCatalog.preferredSpellings("av"));
        assertEquals(List.of("ROUTE", "RTE"), StreetSuffixCatalog.preferredSpellings("RTE."));

        assertEquals("ALLEY", StreetSuffixCatalog.canonical(" allee. "));
        assertEquals("UNKNOWN", StreetSuffixCatalog.canonical(" unknown!? "));
        assertEquals(List.of("UNKNOWN"), StreetSuffixCatalog.preferredSpellings("unknown."));
        assertEquals("UNKNOWN SUFFIX", StreetSuffixCatalog.canonical(" unknown suffix. "));
        assertNull(StreetSuffixCatalog.canonical(null));
        assertTrue(StreetSuffixCatalog.preferredSpellings(null).isEmpty());
        assertTrue(StreetSuffixCatalog.isKnown(" alley."));
        assertTrue(StreetSuffixCatalog.isKnown("mdw"), "Catalog membership includes ambiguous aliases.");
        assertFalse(StreetSuffixCatalog.isKnown("unknown"));
        assertTrue(StreetSuffixCatalog.aliases().containsAll(List.of("TR", "AV", "RTE", "MDW", "PKWY")));
        assertEquals(StreetSuffixCatalog.aliases().stream().sorted().toList(),
                List.copyOf(StreetSuffixCatalog.aliases()));
        assertThrows(UnsupportedOperationException.class,
                () -> StreetSuffixCatalog.aliases().add("EXTRA"));
        assertThrows(UnsupportedOperationException.class,
                () -> StreetSuffixCatalog.preferredSpellings("STREET").add("EXTRA"));
        assertFalse(StreetSuffixCatalog.preferredSpellings("STREET").contains("STREETS"));
    }

    private static List<String> expectedPreferred(StreetSuffixCatalog.Entry entry) {
        if (!entry.primary().equals(StreetSuffixCatalog.canonical(entry.standard()))) {
            return List.of(entry.primary());
        }
        return entry.primary().equals(entry.standard())
                ? List.of(entry.primary())
                : List.of(entry.primary(), entry.standard());
    }
}
