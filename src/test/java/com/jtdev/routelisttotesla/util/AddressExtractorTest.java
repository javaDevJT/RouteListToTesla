package com.jtdev.routelisttotesla.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class AddressExtractorTest {

    @Test
    public void testAddressExtractionWithTruncatedUnits() {
        // Simulate OCR text that might have truncated unit information
        String ocrText = """
            22820 NOTTINGHAM LN UN
            IT 2315
            SOUTHFIELD
            
            23205 SUTTON DR
            SOUTHFIELD
            
            20855 LAHSER RD APT
            312
            SOUTHFIELD
            
            22950 MAPLERIDGE DR
            SOUTHFIELD
            """;

        List<String> extracted = AddressExtractor.addressLinesFromPlainJoined(ocrText);
        
        // Debug output
        System.out.println("Extracted addresses:");
        for (int i = 0; i < extracted.size(); i++) {
            System.out.println(i + ": " + extracted.get(i));
        }
        
        // Should extract 4 addresses
        assertEquals(4, extracted.size());
        
        // Check that addresses are properly reconstructed
        assertTrue(extracted.get(0).contains("22820 NOTTINGHAM LN UNIT 2315"));
        assertTrue(extracted.get(1).contains("23205 SUTTON DR"));
        assertTrue(extracted.get(2).contains("20855 LAHSER RD APT 312"));
        assertTrue(extracted.get(3).contains("22950 MAPLERIDGE DR"));
        
        // All should have city
        for (String addr : extracted) {
            assertTrue(addr.contains("SOUTHFIELD"), "Address should contain city: " + addr);
        }
    }

    @Test
    public void testCompleteAddresses() {
        // Test with complete addresses (no truncation)
        String ocrText = """
            22820 NOTTINGHAM LN UNIT 2315
            SOUTHFIELD
            
            23205 SUTTON DR
            SOUTHFIELD
            """;

        List<String> extracted = AddressExtractor.addressLinesFromPlainJoined(ocrText);
        
        // Debug output
        System.out.println("Complete addresses test - Extracted:");
        for (int i = 0; i < extracted.size(); i++) {
            System.out.println(i + ": " + extracted.get(i));
        }
        
        assertEquals(2, extracted.size());
        assertTrue(extracted.get(0).contains("22820 NOTTINGHAM LN UNIT 2315"), "First address should contain unit: " + extracted.get(0));
        assertTrue(extracted.get(1).contains("23205 SUTTON DR"), "Second address should contain sutton: " + extracted.get(1));
    }

    @Test
    public void testNormalization() {
        String input = "22820 NOTTINGHAM LN UNIT 2315, SOUTHFIELD";
        String normalized = AddressExtractor.normalize(input);
        
        // Should be uppercase and normalized
        assertEquals("22820 NOTTINGHAM LN UNIT 2315 SOUTHFIELD", normalized);
    }

    @Test
    public void testNormalizationWithProblems() {
        // Test leading stop number removal
        String input1 = "2      23673 VILLAGE HOUSE DR S APT 3B, SOUTHFIELD";
        String normalized1 = AddressExtractor.normalize(input1);
        assertEquals("23673 VILLAGE HOUSE DR S APT 3B SOUTHFIELD", normalized1);
        
        String input2 = "7 22347 ESSEX WAY CT APT 1911, SOUTHFIELD";
        String normalized2 = AddressExtractor.normalize(input2);
        assertEquals("22347 ESSEX WAY CT APT 1911 SOUTHFIELD", normalized2);
        
        // Test street abbreviation fixing
        String input3 = "19741 ALBANY AV E, SOUTHFIELD";
        String normalized3 = AddressExtractor.normalize(input3);
        assertEquals("19741 ALBANY AVE SOUTHFIELD", normalized3);
        
        String input4 = "18622 HESSEL AV E, DETROIT";
        String normalized4 = AddressExtractor.normalize(input4);
        assertEquals("18622 HESSEL AVE DETROIT", normalized4);
        
        String input5 = "17186 PLAINVIEW AV E, DETROIT";
        String normalized5 = AddressExtractor.normalize(input5);
        assertEquals("17186 PLAINVIEW AVE DETROIT", normalized5);
        
        // Test street name/type run together (OCR issue)
        String input6 = "25789 CODERD, SOUTHFIELD";
        String normalized6 = AddressExtractor.normalize(input6);
        assertEquals("25789 CODE RD SOUTHFIELD", normalized6);
    }
}
