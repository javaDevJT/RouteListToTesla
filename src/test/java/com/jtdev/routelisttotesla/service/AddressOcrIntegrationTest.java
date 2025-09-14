package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the complete OCR pipeline using real delivery route images.
 * Tests the full flow from image bytes to extracted and validated addresses.
 */
public class AddressOcrIntegrationTest {
    
    private AddressOcrService ocrService;
    private Map<String, Set<String>> expectedAddresses;
    
    @BeforeEach
    void setUp() {
        ocrService = new AddressOcrService();
        setupExpectedResults();
    }
    
    /**
     * Define expected addresses for each test image.
     * These are based on manual inspection of the delivery route screenshots.
     */
    private void setupExpectedResults() {
        expectedAddresses = new HashMap<>();
        
        // Based on the image you showed, these should contain addresses like:
        // 22820 NOTTINGHAM LN UNIT 2315, SOUTHFIELD
        // 23205 SUTTON DR, SOUTHFIELD  
        // 20855 LAHSER RD APT 312, SOUTHFIELD
        // 22950 MAPLERIDGE DR, SOUTHFIELD
        
        expectedAddresses.put("Screenshot_20250920-060848.png", Set.of(
            "CIVIC CENTER DR", "VILLAGE HOUSE DR", "STONYCROFT DR", "SOUTHFIELD"  // Key terms we expect to find
        ));
        
        expectedAddresses.put("Screenshot_20250920-060902.png", Set.of(
            "LARGES DR", "BERG RD", "ESSEX WAY CT", "SOUTHFIELD"
        ));
        
        expectedAddresses.put("Screenshot_20250920-060917.png", Set.of(
            "LAHSER", "SOUTHFIELD"
        ));
        
        expectedAddresses.put("Screenshot_20250920-060927.png", Set.of(
            "MAPLERIDGE DR", "SOUTHFIELD"
        ));
        
        // Add more expected results for other images as needed
        expectedAddresses.put("Screenshot_20250920-060937.png", Set.of(
            "SOUTHFIELD"  // At minimum expect city
        ));
        
        expectedAddresses.put("Screenshot_20250920-060948.png", Set.of(
            "DETROIT", "PLAINVIEW AVE", "BLACKSTONE ST"
        ));
        
        expectedAddresses.put("Screenshot_20250920-061009.png", Set.of(
            "DETROIT"
        ));
    }
    
    @Test
    void testOcrExtractionOnAllImages(TestInfo testInfo) throws Exception {
        Path resourcesDir = Paths.get("src/test/resources");
        
        // Get all PNG files in test resources
        List<Path> imageFiles = Files.list(resourcesDir)
                .filter(path -> path.toString().toLowerCase().endsWith(".png"))
                .filter(path -> path.getFileName().toString().startsWith("Screenshot_"))
                .sorted()
                .toList();
        
        assertTrue(imageFiles.size() > 0, "Should have test images in src/test/resources");
        System.out.println("Found " + imageFiles.size() + " test images");
        
        for (Path imagePath : imageFiles) {
            String fileName = imagePath.getFileName().toString();
            System.out.println("\n=== Testing OCR on: " + fileName + " ===");
            
            testSingleImage(imagePath, fileName);
        }
    }
    
    private void testSingleImage(Path imagePath, String fileName) throws Exception {
        // Read image bytes
        byte[] imageBytes = Files.readAllBytes(imagePath);
        assertNotNull(imageBytes, "Image bytes should not be null");
        assertTrue(imageBytes.length > 0, "Image should have content");
        
        // Extract addresses using OCR
        List<PlaceCandidate> candidates = ocrService.extractAddressCandidates(imageBytes, fileName, "");
        
        // Log results for debugging
        System.out.println("Extracted " + candidates.size() + " candidates:");
        for (int i = 0; i < candidates.size(); i++) {
            PlaceCandidate candidate = candidates.get(i);
            System.out.println("  " + i + ": " + candidate.text());
            System.out.println("      Normalized: " + candidate.normalized());
            System.out.println("      Source: " + candidate.sourceImage());
        }
        
        // Basic validation - should extract at least one candidate
        assertFalse(candidates.isEmpty(), "Should extract at least one address candidate from " + fileName);
        
        // Validate against expected results
        Set<String> expectedTerms = expectedAddresses.get(fileName);
        if (expectedTerms != null) {
            validateExpectedTerms(candidates, expectedTerms, fileName);
        }
        
        // Validate candidate structure
        for (PlaceCandidate candidate : candidates) {
            assertNotNull(candidate.text(), "Candidate text should not be null");
            assertFalse(candidate.text().trim().isEmpty(), "Candidate text should not be empty");
            assertNotNull(candidate.normalized(), "Normalized text should not be null");
            assertEquals(fileName, candidate.sourceImage(), "Source image should match");
            
            // Address should contain at least a number (house number)
            assertTrue(candidate.normalized().matches(".*\\d+.*"),
                "Address should contain at least one number: " + candidate.text());
        }
    }
    
    private void validateExpectedTerms(List<PlaceCandidate> candidates, Set<String> expectedTerms, String fileName) {
        // Combine all extracted text for searching
        String allExtractedText = candidates.stream()
                .map(PlaceCandidate::normalized)
                .map(String::toUpperCase)
                .reduce("", (a, b) -> a + " " + b);
        
        System.out.println("All extracted text: " + allExtractedText);
        System.out.println("Looking for terms: " + expectedTerms);
        
        // Check that expected terms appear in extracted text
        for (String expectedTerm : expectedTerms) {
            assertTrue(allExtractedText.contains(expectedTerm.toUpperCase()), 
                "Expected term '" + expectedTerm + "' not found in extracted text from " + fileName + ". " +
                "Extracted: " + allExtractedText);
        }
    }
    
    @Test
    void testSpecificImageWithKnownResults() throws Exception {
        // Test the specific image from your example if it exists
        Path imagePath = Paths.get("src/test/resources/Screenshot_20250920-060848.png");
        
        if (Files.exists(imagePath)) {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            List<PlaceCandidate> candidates = ocrService.extractAddressCandidates(imageBytes,
                imagePath.getFileName().toString(), "");

            System.out.println("\n=== Detailed test of Screenshot_20250920-060848.png ===");
            
            // Based on your description, we expect addresses like:
            // 22820 NOTTINGHAM LN UNIT 2315, SOUTHFIELD
            String allText = candidates.stream()
                .map(PlaceCandidate::text)
                .map(String::toUpperCase)
                .reduce("", (a, b) -> a + " " + b);
            
            System.out.println("Combined extracted text: " + allText);
            
            // Should contain key elements
            assertTrue(allText.contains("CIVIC CENTER") || allText.contains("VILLAGE HOUSE") || allText.contains("STONYCROFT"), 
                "Should contain address elements from the delivery route");
            
            // Should not contain leading numbers from stop indicators (like "10", "11", "12", "13")
            for (PlaceCandidate candidate : candidates) {
                String text = candidate.text().trim();
                
                // Address should not start with just a small number (stop numbers)
                assertFalse(text.matches("^[0-9]{1,2}\\s*$"), 
                    "Should not extract stop numbers as addresses: " + text);
                
                // Should contain actual street address elements
                if (text.length() > 10) { // Only check longer candidates
                    assertTrue(text.matches(".*\\d{3,}.*") || text.contains("CIVIC CENTER") || 
                              text.contains("VILLAGE HOUSE") || text.contains("STONYCROFT"),
                        "Longer candidates should contain street addresses, not just stop info: " + text);
                }
            }
        } else {
            System.out.println("Specific test image not found, skipping detailed test");
        }
    }
    
    @Test
    void testOcrPerformanceAndResilience() throws Exception {
        Path resourcesDir = Paths.get("src/test/resources");
        
        List<Path> imageFiles = Files.list(resourcesDir)
                .filter(path -> path.toString().toLowerCase().endsWith(".png"))
                .limit(3) // Test first 3 images for performance
                .toList();
        
        if (imageFiles.isEmpty()) {
            System.out.println("No images found for performance test");
            return;
        }
        
        for (Path imagePath : imageFiles) {
            long startTime = System.currentTimeMillis();
            
            byte[] imageBytes = Files.readAllBytes(imagePath);
            List<PlaceCandidate> candidates = ocrService.extractAddressCandidates(imageBytes,
                imagePath.getFileName().toString(), "");

            long duration = System.currentTimeMillis() - startTime;
            
            System.out.println("OCR processing time for " + imagePath.getFileName() + ": " + duration + "ms");
            System.out.println("Extracted " + candidates.size() + " candidates");
            
            // Performance assertion - OCR should complete within reasonable time
            assertTrue(duration < 30000, "OCR should complete within 30 seconds"); // Generous timeout
            
            // Should not crash on any image
            assertNotNull(candidates, "OCR should return a result list (even if empty)");
        }
    }
}
