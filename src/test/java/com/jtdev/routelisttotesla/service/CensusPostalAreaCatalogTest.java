package com.jtdev.routelisttotesla.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class CensusPostalAreaCatalogTest {
    private static final String HEADER = "ZIP\tINTPTLAT\tINTPTLONG\n";

    @Test
    void packagedCatalogMatchesExpectedHashAndNationalRecordCount() throws IOException, NoSuchAlgorithmException {
        byte[] resourceBytes;
        try (InputStream resource = CensusPostalAreaCatalog.class.getResourceAsStream("/postal/us-zcta-areas.tsv")) {
            assertNotNull(resource);
            resourceBytes = resource.readAllBytes();
        }
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resourceBytes));
        assertEquals("d51c1f5e925d80e957746db20217f9bda090b8053773f7d2b135d55507ef3ebb", sha256);

        CensusPostalAreaCatalog.Catalog catalog = CensusPostalAreaCatalog.parse(
                new ByteArrayInputStream(resourceBytes));
        assertEquals(33_791, catalog.size());
        assertTrue(CensusPostalAreaCatalog.supports("48382", 42.583069, -83.511996));
    }

    @Test
    void supportsOnlyExactZipNearItsRepresentativePoint() throws IOException {
        CensusPostalAreaCatalog.Catalog catalog = parse(HEADER
                + "48382\t42.583069\t-83.511996\n"
                + "00501\t40.815400\t-73.045200\n");
        assertTrue(catalog.supports("48382", 42.583069, -83.511996));
        assertTrue(catalog.supports("48382", 42.633069, -83.511996));
        assertFalse(catalog.supports("48382", 42.683069, -83.511996));
        assertFalse(catalog.supports("48381", 42.583069, -83.511996));
    }

    @Test
    void preservesLeadingZeroZipCodes() throws IOException {
        CensusPostalAreaCatalog.Catalog catalog = parse(HEADER + "00501\t40.815400\t-73.045200\n");
        assertTrue(catalog.supports("00501", 40.815400, -73.045200));
        assertFalse(catalog.supports("501", 40.815400, -73.045200));
        assertFalse(catalog.supports("00502", 40.815400, -73.045200));
    }

    @Test
    void includesTenKilometerBoundaryAndRejectsPointsBeyondIt() throws IOException {
        CensusPostalAreaCatalog.Catalog catalog = parse(HEADER + "48101\t42.000000\t-83.000000\n");
        double boundaryLatitude = 42.0 + Math.toDegrees(10.0 / 6_371.0088);
        double outsideLatitude = 42.0 + Math.toDegrees(10.001 / 6_371.0088);
        assertTrue(catalog.supports("48101", boundaryLatitude, -83.0));
        assertFalse(catalog.supports("48101", outsideLatitude, -83.0));
    }

    @Test
    void rejectsInvalidZipAndCoordinateQueries() throws IOException {
        CensusPostalAreaCatalog.Catalog catalog = parse(HEADER + "48101\t42.000000\t-83.000000\n");
        assertFalse(catalog.supports(null, 42.0, -83.0));
        assertFalse(catalog.supports("4810", 42.0, -83.0));
        assertFalse(catalog.supports("4810A", 42.0, -83.0));
        assertFalse(catalog.supports(" 48101", 42.0, -83.0));
        assertFalse(catalog.supports("48101", Double.NaN, -83.0));
        assertFalse(catalog.supports("48101", 42.0, Double.POSITIVE_INFINITY));
        assertFalse(catalog.supports("48101", 90.1, 0.0));
        assertFalse(catalog.supports("48101", 42.0, -180.1));
        assertFalse(catalog.supports("48101", 0.0, 0.0));
    }

    @Test
    void malformedEmptyDuplicateAndOversizedResourcesFailAsAWhole() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> parse(""));
        assertThrows(IllegalArgumentException.class, () -> parse("bad\n48101\t42.0\t-83.0\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(HEADER + "bad\trow\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parse(HEADER + "48101\t42.0\t-83.0\n48101\t42.1\t-83.0\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(HEADER + "48101\t91.0\t-83.0\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(HEADER + "48101\tNaN\t-83.0\n"));
        String truncated = "# row_count=2\n" + HEADER + "48101\t42.0\t-83.0\n";
        assertThrows(IllegalArgumentException.class,
                () -> CensusPostalAreaCatalog.parse(input(truncated)));
        assertFalse(CensusPostalAreaCatalog.load(input(truncated)).supports("48101", 42.0, -83.0));
        assertFalse(CensusPostalAreaCatalog.load(null).supports("48101", 42.0, -83.0));
        assertFalse(CensusPostalAreaCatalog.load(input(HEADER + "bad\trow\n"))
                .supports("48101", 42.0, -83.0));
        assertFalse(CensusPostalAreaCatalog.load(input(HEADER
                + "48101\t42.0\t-83.0\n48101\t42.1\t-83.0\n"))
                .supports("48101", 42.0, -83.0));

        byte[] oversized = new byte[CensusPostalAreaCatalog.MAX_RESOURCE_BYTES + 1];
        assertFalse(CensusPostalAreaCatalog.load(new ByteArrayInputStream(oversized))
                .supports("48101", 42.0, -83.0));
    }

    @Test
    void invalidUtf8DisablesTheEntireCatalog() throws IOException {
        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        assertThrows(IOException.class,
                () -> CensusPostalAreaCatalog.parse(new ByteArrayInputStream(invalidUtf8)));
        assertFalse(CensusPostalAreaCatalog.load(new ByteArrayInputStream(invalidUtf8))
                .supports("48101", 42.0, -83.0));
    }

    private static CensusPostalAreaCatalog.Catalog parse(String content) throws IOException {
        long rowCount = content.lines()
                .filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.equals("ZIP\tINTPTLAT\tINTPTLONG"))
                .count();
        return CensusPostalAreaCatalog.parse(input("# row_count=" + rowCount + "\n" + content));
    }

    private static ByteArrayInputStream input(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
