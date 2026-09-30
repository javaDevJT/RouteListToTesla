package com.jtdev.routelisttotesla.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class AddressExtractorTest {
    @Test
    void routeCodesAreNotHouseNumbersAndHashUnitsRemainAddresses() {
        for (String code : List.of("# FR.038", "# B.L28.OV", "# B.L28.0V",
                "# B L28 OV", "# 8 L28 OV", "# UNASSIGNED_SORT_LOCATION",
                "＃ B L28 OV", "# B.L28. OV", "B.L28.OV", "B . L28 . OV", "L28. OV", "FR.038")) {
            assertNull(AddressExtractor.addressCandidateText(code), code);
        }
        assertEquals("123 MAIN ST #212", AddressExtractor.addressCandidateText("123 MAIN ST #212"));
        assertEquals("23491 TEACUP CT", AddressExtractor.addressCandidateText("# 23491 TEACUP CT"));
        assertEquals("12 1/2 MAIN RD", AddressExtractor.addressCandidateText("＃ 12 1/2 MAIN RD"));
        assertEquals("123 MAIN ST APT #212", AddressExtractor.addressCandidateText("123 MAIN ST APT #212"));
        assertEquals(List.of("123 MAIN ST #212, FLINT, MI"), AddressExtractor.addressLinesFromPlainJoined("""
                Expected by 11:00 PM
                # B.L28.OV
                123 MAIN ST
                #212
                FLINT
                """, "MI"));
    }


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
    public void testFractionsRangesUnicodeStreetNamesUnitsAndCityZip() {
        String ocrText = """
                12 1/2 N MILE RD
                APT 3B
                SOUTHFIELD MI 48075
                123-125 O’BRIEN HIGHWAY
                DETROIT MI 48201
                """;

        assertEquals(List.of(
                "12 1/2 N MILE RD APT 3B, SOUTHFIELD MI 48075",
                "123-125 O’BRIEN HIGHWAY, DETROIT MI 48201"),
                AddressExtractor.addressLinesFromPlainJoined(ocrText));
    }

    @Test
    public void testAddressCandidatesRepairOcrDecorationAndCompactDirectionalPrefixes() {
        assertEquals("8 W 8TH AVE", AddressExtractor.addressCandidateText("8 W 8TH AVE"));
        assertEquals("5½ W GRAND BLVD", AddressExtractor.addressCandidateText("5½ W GRAND BLVD"));
        assertEquals("51¾ HARBOR ST", AddressExtractor.addressCandidateText("51¾ HARBOR ST"));
        assertEquals("123 ELM ST", AddressExtractor.addressCandidateText("‘123 ELM ST"));
        assertEquals("1 N MAIN ST", AddressExtractor.addressCandidateText("1N MAIN ST"));
        assertEquals("4 W 8 MILE RD", AddressExtractor.addressCandidateText("4W8 MILE RD"));
        assertEquals("74 W 9 MILE RD", AddressExtractor.addressCandidateText("74W9MILE RD"));
        assertEquals("58 W 12 MILE RD", AddressExtractor.addressCandidateText("58W12 MILE RD"));
        assertEquals("51 3/4 HARBOR ST", AddressExtractor.addressCandidateText("‘513/4 HARBOR ST"));
    }

    @Test
    public void testAddressCandidatesRejectNumericRouteStatusAndPackageUiText() {
        assertNull(AddressExtractor.addressCandidateText("Stop 14 completed"));
        for (String text : List.of("6: 08 M X BM", "6.09 M X LTE 96%",
                "08 VOLTE4 96 %", "% 0 MPH 96 %", "09 M X BM ITINERARY")) {
            assertNull(AddressExtractor.addressCandidateText(text), text);
        }
        assertEquals("123 MAIN ST", AddressExtractor.addressCandidateText("6. 123 MAIN ST"));
        assertNull(AddressExtractor.addressCandidateText("12 4 stops remaining"));
        assertNull(AddressExtractor.addressCandidateText("1234 PACKAGE PICKUP"));
        assertEquals("1234 PACKAGE RD", AddressExtractor.addressCandidateText("1234 PACKAGE RD"));
        assertEquals("7 ELM ST", AddressExtractor.addressCandidateText("7 ELM ST"));
    }

    @Test
    public void testClockFragmentsAreNotAddressesButStreetNamesRemainValid() {
        for (String text : List.of("10 PM", "00 PM", "12 AM", "05 a.m.",
                "Expected by 4 : 10 PM", "5 4 : 00 PM")) {
            assertNull(AddressExtractor.addressCandidateText(text), text);
        }
        assertEquals("10 AM ST", AddressExtractor.addressCandidateText("10 AM ST"));
        assertEquals("10 PM ROAD", AddressExtractor.addressCandidateText("10 PM ROAD"));
        assertEquals("1641 PINEWOOD", AddressExtractor.addressCandidateText("1641 PINEWOOD"));
    }

    @Test
    public void testHouseNumbersRequireAnObservedDigitWithoutCorrectingCharacters() {
        assertNull(AddressExtractor.addressCandidateText("yp cmon ooo. ooo eso"));
        assertNull(AddressExtractor.addressCandidateText("OOO MAIN ST"));
        assertNull(AddressExtractor.addressCandidateText("II OLD MILL ROAD"));
        assertEquals("5 OLD MILL ROAD", AddressExtractor.addressCandidateText("5 OLD MILL ROAD"));
        assertEquals("24O11 CIVIC CENTER DR APT 330",
                AddressExtractor.addressCandidateText("24O11 CIVIC CENTER DR APT 330"));
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
        
        // Ambiguous terminal suffixes must not split words into false street types.
        String input6 = "25789 CODERD, SOUTHFIELD";
        String normalized6 = AddressExtractor.normalize(input6);
        assertEquals("25789 CODERD SOUTHFIELD", normalized6);
        assertEquals("25789 FOREST SOUTHFIELD", AddressExtractor.normalize("25789 FOREST, SOUTHFIELD"));
        assertEquals("25789 FIRST AVE SOUTHFIELD", AddressExtractor.normalize("25789 FIRST AVE, SOUTHFIELD"));
    }
}
