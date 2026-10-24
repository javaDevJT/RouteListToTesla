package com.jtdev.routelisttotesla.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PostalAreaEvidenceTest {
    private static PostalLocalityCatalog.Catalog catalog(String rows) throws Exception {
        return PostalLocalityCatalog.parse(new ByteArrayInputStream(rows.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void areaEvidenceDoesNotInventAPostalCityAlias() throws Exception {
        var catalog = catalog("12345\tNY\tPostal City\t42.0\t-74.0\t4\n");
        assertTrue(catalog.supportsArea("12345", "NY", 42.01, -74.0));
        assertFalse(catalog.supports("12345", "Different Township", "NY", 42.01, -74.0));
        assertFalse(catalog.supportsArea("12345", "NJ", 42.01, -74.0));
        assertFalse(catalog.supportsArea("54321", "NY", 42.01, -74.0));
    }

    @Test
    void areaEvidenceRetainsDistanceAndAccuracyBounds() throws Exception {
        var catalog = catalog("12345\tNY\tOne\t42.0\t-74.0\t4\n"
                + "12346\tNY\tTwo\t43.0\t-74.0\t3\n");
        assertFalse(catalog.supportsArea("12345", "NY", 43.0, -74.0));
        assertFalse(catalog.supportsArea("12346", "NY", 43.0, -74.0));
    }

    @Test
    void invalidAreaInputsAndUnavailableCatalogFailClosed() throws Exception {
        var catalog = catalog("12345\tNY\tPostal City\t42.0\t-74.0\t4\n");
        assertFalse(catalog.supportsArea(null, "NY", 42.0, -74.0));
        assertFalse(catalog.supportsArea("12345-0000", "NY", 42.0, -74.0));
        assertFalse(catalog.supportsArea("12345", null, 42.0, -74.0));
        assertFalse(catalog.supportsArea("12345", "NY", Double.NaN, -74.0));
        assertFalse(catalog.supportsArea("12345", "NY", 0.0, 0.0));
        assertFalse(PostalLocalityCatalog.load(null).supportsArea("12345", "NY", 42.0, -74.0));
    }

    @Test
    void bundledZipAreaSupportsTheReportedMunicipalityWithoutAddingACityMapping() {
        assertTrue(PostalLocalityCatalog.supportsArea("48390", "MI", 42.5678374, -83.461818));
        assertFalse(PostalLocalityCatalog.supports("48390", "Commerce Township", "MI", 42.5678374, -83.461818));
    }
}
