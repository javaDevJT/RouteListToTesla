package com.jtdev.routelisttotesla.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StreetNumberWordsTest {
    private List<String> tokens(String value) { return List.of(value.toUpperCase().split("[ -]+")); }
    private boolean equivalent(String a, String b) { return StreetNumberWords.equivalent(tokens(a), tokens(b)); }

    @Test void wholeCardinalAndOrdinalExpressionsWorkInEitherDirection() {
        for (String[] pair : List.of(new String[]{"11 MILE RD", "ELEVEN MILE RD"},
                new String[]{"N NINETEEN OAKS AVE", "N 19 OAKS AVE"},
                new String[]{"21ST ST", "TWENTY FIRST ST"},
                new String[]{"101ST AVE", "ONE HUNDRED AND FIRST AVE"},
                new String[]{"2105 HILLS DR", "TWO THOUSAND ONE HUNDRED FIVE HILLS DR"},
                new String[]{"1000000 WAY", "ONE MILLION WAY"},
                new String[]{"999999999 ST", "NINE HUNDRED NINETY NINE MILLION NINE HUNDRED NINETY NINE THOUSAND NINE HUNDRED NINETY NINE ST"})) {
            assertTrue(equivalent(pair[0], pair[1]), List.of(pair).toString());
            assertTrue(equivalent(pair[1], pair[0]));
        }
    }

    @Test void identityAndGrammarAreNotGuessed() {
        for (String[] pair : List.of(new String[]{"11 MILE RD", "TWELVE MILE RD"},
                new String[]{"12 MILE RD", "ONE TWO MILE RD"},
                new String[]{"120 MILE RD", "ONE TWENTY MILE RD"},
                new String[]{"11ST ST", "ELEVENTH ST"},
                new String[]{"11 ST", "ELEVENTH ST"},
                new String[]{"011 MILE RD", "ELEVEN MILE RD"},
                new String[]{"11 MILE RD", "I1 MILE RD"},
                new String[]{"1 HILL RD", "I HILL RD"},
                new String[]{"N 11 MILE RD", "S ELEVEN MILE RD"},
                new String[]{"11 OAK RD", "ELEVEN OAKS RD"},
                new String[]{"11 MILE RD", "ELEVEN MILE ST"},
                new String[]{"1000 ST", "ONE HUNDRED THOUSAND ST"})) {
            assertFalse(equivalent(pair[0], pair[1]), List.of(pair).toString());
        }
        assertFalse(equivalent("11 MILE RD", "11 MILE RD"), "Unchanged forms are not a new recovery rule");
    }
}
