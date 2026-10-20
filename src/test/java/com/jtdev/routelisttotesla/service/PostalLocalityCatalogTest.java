package com.jtdev.routelisttotesla.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostalLocalityCatalogTest {
    @Test
    void matchesExactZipCityAndStateAcrossMultipleLocalities() throws IOException {
        PostalLocalityCatalog.Catalog catalog = parse(
                "#ZIP5\tstate2\tcity\tlatitude\tlongitude\taccuracy\n"
                        + "01234\tMI\tRose City\t42.0000\t-83.0000\t4\n"
                        + "01234\tMI\tBay City\t42.0000\t-83.0000\t5\n"
                        + "01234\tOH\tRose City\t42.0000\t-83.0000\t4\n"
                        + "12345\tMI\tRoseville\t42.0000\t-83.0000\t4\n");

        assertTrue(catalog.supports("01234", "rose city", "mi", 42.0, -83.0));
        assertTrue(catalog.supports("01234", "Bay City", "MI", 42.0, -83.0));
        assertTrue(catalog.supports("01234", "Rose City", "OH", 42.0, -83.0));
        assertFalse(catalog.supports("12345", "Rose City", "MI", 42.0, -83.0));
        assertFalse(catalog.supports("01234", "Roseville", "MI", 42.0, -83.0));
        assertFalse(catalog.supports("01234", "Rose City", "OHIO", 42.0, -83.0));
        assertFalse(catalog.supports("01234", "Rose City Center", "MI", 42.0, -83.0));
        assertFalse(catalog.supports("01234", "City", "MI", 42.0, -83.0));
    }

    @Test
    void preservesLeadingZeroZipAndRequiresExactlyFiveAsciiDigits() throws IOException {
        PostalLocalityCatalog.Catalog catalog = parse("01234\tMI\tExample\t42.0\t-83.0\t4\n");

        assertTrue(catalog.supports("01234", "Example", "MI", 42.0, -83.0));
        assertFalse(catalog.supports("1234", "Example", "MI", 42.0, -83.0));
        assertFalse(catalog.supports("012340", "Example", "MI", 42.0, -83.0));
        assertFalse(catalog.supports("０１２３４", "Example", "MI", 42.0, -83.0));
    }

    @Test
    void normalizesNfkcCaseAndUnicodePunctuationWithoutFuzzyMatching() throws IOException {
        PostalLocalityCatalog.Catalog catalog = parse("48101\tMI\tSt. Louis\t42.0\t-83.0\t4\n");

        assertTrue(catalog.supports("48101", "ＳＴ—ＬＯＵＩＳ", "ＭＩ", 42.0, -83.0));
        assertTrue(catalog.supports("48101", "st / louis", "M.I.", 42.0, -83.0));
        assertFalse(catalog.supports("48101", "St. Louis City", "MI", 42.0, -83.0));
        assertFalse(catalog.supports("48101", "Saint Louis", "MI", 42.0, -83.0));
    }

    @Test
    void requiresHighAccuracyAndPointWithinTenKilometers() throws IOException {
        PostalLocalityCatalog.Catalog catalog = parse(
                "48101\tMI\tAccurate\t42.0\t-83.0\t4\n"
                        + "48102\tMI\tLow Accuracy\t42.0\t-83.0\t3\n"
                        + "48103\tMI\tFar Away\t42.0\t-83.0\t5\n");

        assertTrue(catalog.supports("48101", "Accurate", "MI", 42.08, -83.0));
        assertFalse(catalog.supports("48101", "Accurate", "MI", 42.10, -83.0));
        assertFalse(catalog.supports("48102", "Low Accuracy", "MI", 42.0, -83.0));
        assertFalse(catalog.supports("48103", "Far Away", "MI", 42.0, -82.8));
    }

    @Test
    void rejectsInvalidQueryCoordinates() throws IOException {
        PostalLocalityCatalog.Catalog catalog = parse("48101\tMI\tExample\t42.0\t-83.0\t4\n");

        assertFalse(catalog.supports("48101", "Example", "MI", Double.NaN, -83.0));
        assertFalse(catalog.supports("48101", "Example", "MI", 42.0, Double.POSITIVE_INFINITY));
        assertFalse(catalog.supports("48101", "Example", "MI", 90.1, 0.0));
        assertFalse(catalog.supports("48101", "Example", "MI", 0.0, 181.0));
        assertFalse(catalog.supports("48101", "Example", "MI", 0.0, 0.0));
        assertFalse(catalog.supports(null, "Example", "MI", 42.0, -83.0));
        assertFalse(catalog.supports("48101", null, "MI", 42.0, -83.0));
    }

    @Test
    void malformedOrEmptyResourceFailsAsAWhole() {
        assertThrows(IllegalArgumentException.class, () -> parse(""));
        assertThrows(IllegalArgumentException.class,
                () -> parse("48101\tMI\tExample\t42.0\t-83.0\t4\nmalformed\trow\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("48101\tMI\tExample\t42.0\t-83.0\t4\n"
                        + "4810\tMI\tPartial Bad Row\t42.0\t-83.0\t4\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("48101\tMI\tExample\t91.0\t-83.0\t4\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("48101\tMI\tExample\tNaN\t-83.0\t4\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("48101\tMI\tExample\t0.0\t0.0\t4\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("48101\tMI\tExample\t42.0\t-83.0\t-1\n"));
    }

    @Test
    void rejectsMalformedUtf8InsteadOfUsingPartialText() {
        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        assertThrows(IOException.class, () -> PostalLocalityCatalog.parse(new ByteArrayInputStream(invalidUtf8)));
    }

    @Test
    void missingOrInvalidCatalogDisablesEvidence() {
        assertFalse(PostalLocalityCatalog.load(null).supports("48101", "Example", "MI", 42.0, -83.0));
        assertFalse(PostalLocalityCatalog.load(new ByteArrayInputStream(
                "48101\tMI\tExample\t42.0\t-83.0\t4\ninvalid\trow\n".getBytes(StandardCharsets.UTF_8)))
                .supports("48101", "Example", "MI", 42.0, -83.0));
    }

    @Test
    void packagedCatalogMatchesExpectedHashAndSnapshot() throws IOException, NoSuchAlgorithmException {
        byte[] resourceBytes;
        try (InputStream resource = PostalLocalityCatalog.class.getResourceAsStream("/postal/us-localities.tsv")) {
            assertNotNull(resource);
            resourceBytes = resource.readAllBytes();
        }

        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resourceBytes));
        assertEquals("d4bde326697f50264137016827e3760fe6c70cbd39607d747c424b42815b4880", sha256);
        assertTrue(PostalLocalityCatalog.supports("48230", "Grosse Pointe", "MI", 42.3847, -82.9244));
    }

    private static PostalLocalityCatalog.Catalog parse(String content) throws IOException {
        return PostalLocalityCatalog.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}
