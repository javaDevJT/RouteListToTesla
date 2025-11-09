package com.jtdev.routelisttotesla.service;


import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.util.AddressExtractor;
import org.bytedeco.leptonica.PIX;
import org.bytedeco.tesseract.TessBaseAPI;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.bytedeco.leptonica.global.leptonica.*;

@Service
public class AddressOcrService {
    // English; provide your own tessdata on classpath if you want custom.
    private static final String LANG = "eng";
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(AddressOcrService.class);



    public List<PlaceCandidate> extractAddressCandidates(byte[] imageBytes, String name, String defaultState) throws Exception {
        log.info("Processing image {} for OCR", name);

        BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (src == null) return List.of();

        try (TessBaseAPI api = new TessBaseAPI()) {
            // Resolve tessdata path from classpath (/tessdata) or env fallback
            String dataPath = System.getenv("TESSDATA_PREFIX");
            if (dataPath == null || dataPath.isBlank()) {
                var url = getClass().getResource("/");
                if (url != null) {
                    dataPath = new java.io.File(url.toURI()).getPath();
                }
            }
            if (api.Init((dataPath == null || dataPath.isBlank()) ? null : dataPath, LANG) != 0)
                throw new IllegalStateException("Tesseract init failed");

            PIX pix = pixReadMem(imageBytes, imageBytes.length);

            // 1) Upscale for small UI text (back to original scale)
            PIX scaled = pixScale(pix, 3.0F, 3.0F);

            // 2) Grayscale
            PIX gray = pixConvertRGBToGray(scaled, 0.2126f, 0.7152f, 0.0722f);

            // 3) Unsharp mask (original settings)
            PIX sharp = pixUnsharpMasking(gray, 2, 0.5f);

            // 4) Threshold
            PIX bin = pixThresholdToBinary(sharp, 140);

            api.SetImage(bin);
            api.SetPageSegMode(11);              // sparse text - better for structured layouts
            api.SetVariable("user_defined_dpi", "300");
            api.SetVariable("preserve_interword_spaces", "1");
            // remove the whitelist; it suppressed lowercase and symbols

            String plain = api.GetUTF8Text().getString();

            // Fallback if OCR is junk
            if (plain.replaceAll("[^A-Za-z0-9]", "").length() < 20) {
                api.SetPageSegMode(11);          // SPARSE_TEXT
                plain = api.GetUTF8Text().getString();
            }

            // Post-process OCR text to fix common spacing issues before address extraction
            plain = postProcessOcrText(plain);

            // cleanup
            pixDestroy(pix);
            pixDestroy(scaled);
            pixDestroy(gray);
            pixDestroy(sharp);
            pixDestroy(bin);

            List<PlaceCandidate> out = new ArrayList<>();
            int idx = 0;
            log.info("Raw OCR text from {}: {}", name, plain.replace("\n", "\\n").replace("\r", "\\r"));
            for (String line : AddressExtractor.addressLinesFromPlainJoined(plain, defaultState)) {
                String normalized = AddressExtractor.normalize(line);
                log.info("Extracted address - Raw: {} | Normalized: {}", line, normalized);
                out.add(new PlaceCandidate(line, normalized, name, idx++, 0, 0, null));
            }

            return out;
        }
    }

    /**
     * Post-process OCR text to fix common character spacing issues
     * that affect address extraction quality.
     */
    private String postProcessOcrText(String text) {
        // Process each line separately to preserve line breaks
        String[] lines = text.split("\\n");
        List<String> processedLines = new ArrayList<>();
        
        for (String line : lines) {
            String processed = repairConcatenatedAddresses(line.trim());
            if (!processed.trim().isEmpty()) {
                processedLines.add(processed);
            }
        }
        
        return String.join("\n", processedLines);
    }
    
    /**
     * Advanced address parsing to repair concatenated street types.
     * Based on structured parsing approach with two phases.
     */
    private String repairConcatenatedAddresses(String line) {
        if (line == null || line.trim().isEmpty()) {
            return line;
        }
        
        String normalized = line.trim().toUpperCase().replaceAll("\\s+", " ");
        List<String> tokens = new ArrayList<>(Arrays.asList(normalized.split(" ")));
        
        // Must start with a house number to be considered an address
        if (tokens.isEmpty() || !tokens.get(0).matches("\\d+[A-Z]?")) {
            return line; // Not an address format
        }
        
        Set<String> streetTypes = Set.of("RD", "ST", "AVE", "BLVD", "LN", "DR", "CT", "TRL", "TER", "WAY", "PL", "PKWY", "HWY", "CIR");
        Pattern unitPattern = Pattern.compile("(APT|UNIT|STE|SUITE|#)");
        
        // Phase 1: Look for properly spaced street type early in sequence
        int typeIndex = findStreetTypeIndex(tokens, streetTypes);
        if (typeIndex > 0) {
            return normalized; // Already properly formatted
        }
        
        // Phase 2: Look for concatenated street type and repair
        int concatIndex = findConcatenationCandidate(tokens, streetTypes, unitPattern);
        if (concatIndex == -1) {
            return line; // No clear concatenation candidate
        }
        
        String token = tokens.get(concatIndex);
        String streetType = getEndingStreetType(token, streetTypes);
        if (streetType == null) {
            return line; // No street type suffix found
        }
        
        String stem = token.substring(0, token.length() - streetType.length());
        
        // Don't split compound words that contain common street name endings
        if (containsCompoundPattern(stem)) {
            return line; // This looks like a compound street name, don't split
        }
        
        if (!isValidBoundary(stem, streetType)) {
            return line; // Boundary doesn't look right for splitting
        }
        
        // Special case: avoid splitting numbered streets like "1ST", "2ND"
        if (streetType.equals("ST") && stem.chars().allMatch(Character::isDigit)) {
            return line;
        }
        
        // Perform the repair
        tokens.set(concatIndex, stem);
        tokens.add(concatIndex + 1, streetType);
        
        return String.join(" ", tokens);
    }
    
    private int findStreetTypeIndex(List<String> tokens, Set<String> streetTypes) {
        for (int i = 1; i < Math.min(tokens.size(), 6); i++) {
            if (streetTypes.contains(tokens.get(i))) {
                return i;
            }
        }
        return -1;
    }
    
    private int findConcatenationCandidate(List<String> tokens, Set<String> streetTypes, Pattern unitPattern) {
        int hits = 0;
        int candidateIndex = -1;
        
        for (int i = 1; i < Math.min(tokens.size(), 6); i++) {
            // Stop at unit indicators
            if (unitPattern.matcher(tokens.get(i)).matches()) {
                break;
            }
            
            String endingType = getEndingStreetType(tokens.get(i), streetTypes);
            if (endingType != null) {
                hits++;
                candidateIndex = i;
            }
        }
        
        // Only repair if there's exactly one candidate (avoid ambiguity)
        return hits == 1 ? candidateIndex : -1;
    }
    
    private String getEndingStreetType(String token, Set<String> streetTypes) {
        for (String type : streetTypes) {
            if (token.length() > type.length() && token.endsWith(type)) {
                return type;
            }
        }
        return null;
    }
    
    private boolean containsCompoundPattern(String stem) {
        // Check if stem contains common street name components that should not be split
        Set<String> compoundPatterns = Set.of(
            "FORD", "WOOD", "FIELD", "LAND", "BROOK", "CREEK", 
            "HILL", "DALE", "GLEN", "RIDGE", "VIEW", "GROVE"
        );
        
        for (String pattern : compoundPatterns) {
            if (stem.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isValidBoundary(String stem, String streetType) {
        if (stem.length() < 3) {
            return false; // Stem too short
        }
        
        char lastChar = stem.charAt(stem.length() - 1);
        Set<Character> vowels = Set.of('A', 'E', 'I', 'O', 'U');
        
        // For common street types, be more lenient but still prefer consonants
        if (Set.of("RD", "ST", "DR", "LN", "CT").contains(streetType)) {
            // Allow vowel boundaries for common words that end in vowels
            if (vowels.contains(lastChar)) {
                // Allow specific common patterns like CODE, LAKE, PINE, etc.
                return stem.length() <= 6; // Short words ending in vowels are often valid
            }
            return true; // Consonant ending is always good
        }
        
        // For other types, just ensure it's a letter
        return Character.isLetter(lastChar);
    }


}
