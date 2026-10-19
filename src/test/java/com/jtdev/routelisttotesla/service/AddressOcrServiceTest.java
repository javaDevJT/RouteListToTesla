package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressOcrServiceTest {

    @Test
    void javaWrapperDeadlineLeavesTimeForConsensusWorkersToStop() {
        assertEquals(Duration.ofSeconds(75), AddressOcrService.PROCESS_TIMEOUT);
        assertTrue(AddressOcrService.PROCESS_TIMEOUT.minusSeconds(60).compareTo(Duration.ofSeconds(2)) > 0);
    }

    @Test
    void skipsItineraryCodesBeforeAddressesAndPreservesInlineAndWrappedHashUnits() throws Exception {
        String tsv = tsv(
                word(1, 1, 95, "Expected by 11:00 PM"),
                word(2, 1, 95, "# B.L28. OV"),
                word(3, 1, 95, "123 MAIN ST #212"),
                word(4, 1, 95, "FLINT"),
                word(5, 1, 95, "Deliver 1 package"),
                word(6, 1, 95, "Expected by 11:00 PM"),
                word(7, 1, 95, "FR.038"),
                word(8, 1, 95, "89 OAK RD"),
                word(9, 1, 95, "#3B"),
                word(10, 1, 95, "BURTON"));
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                arguments -> new AddressOcrService.ProcessResult(0, tsv, ""));
        assertEquals(List.of("123 MAIN ST #212, FLINT, MI", "89 OAK RD #3B, BURTON, MI"),
                service.extractAddressCandidates(png(), "route.png", "MI")
                        .stream().map(PlaceCandidate::text).toList());
    }
    private static final String TSV_HEADER =
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext";

    @Test
    void mapsAddressLinesInOrderPreservesDuplicatesAndKeepsLowConfidenceStopsEditable() throws Exception {
        byte[] image = png();
        String tsv = tsv(
                word(1, 1, 91, "24126"), word(1, 2, 86, "CIVIC"), word(1, 3, 85, "CENTER"), word(1, 4, 90, "DR"),
                word(2, 1, 90, "SOUTHFIELD"),
                word(3, 1, 12, "14020"), word(3, 2, 9, "BRAMELL"),
                word(4, 1, 90, "'"), word(4, 2, 90, "DETROIT"),
                word(5, 1, 80, "24126"), word(5, 2, 83, "CIVIC"), word(5, 3, 87, "CENTER"), word(5, 4, 92, "DR"),
                word(6, 1, 90, "4."), word(6, 2, 92, "Stops"),
                word(7, 1, 99, "Route"),
                word(8, 1, 90, "6:08"), word(8, 2, 92, "M"), word(8, 3, 93, "96%"),
                word(9, 1, 90, "Deliver"), word(9, 2, 92, "1"), word(9, 3, 92, "package"));
        AtomicReference<Path> temporaryImage = new AtomicReference<>();
        AtomicReference<List<String>> command = new AtomicReference<>();
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng", arguments -> {
            command.set(arguments);
            Path imagePath = Path.of(arguments.get(1));
            temporaryImage.set(imagePath);
            assertTrue(Files.exists(imagePath));
            assertArrayEquals(image, Files.readAllBytes(imagePath));
            return new AddressOcrService.ProcessResult(0, tsv, "");
        });

        List<PlaceCandidate> candidates = service.extractAddressCandidates(image, "route.png", "MI");

        assertEquals(List.of(
                "24126 CIVIC CENTER DR, SOUTHFIELD, MI",
                "14020 BRAMELL, DETROIT, MI",
                "24126 CIVIC CENTER DR, MI"), candidates.stream().map(PlaceCandidate::text).toList());
        assertEquals(List.of(
                "24126 CIVIC CENTER DR, SOUTHFIELD, MI",
                "14020 BRAMELL, DETROIT, MI",
                "24126 CIVIC CENTER DR, MI"), candidates.stream().map(PlaceCandidate::normalized).toList());
        assertEquals(List.of(0, 1, 2), candidates.stream().map(PlaceCandidate::lineIndex).toList());
        assertTrue(candidates.stream().allMatch(candidate -> "route.png".equals(candidate.sourceImage())));
        assertEquals(List.of(
                "ocr-binary", temporaryImage.get().toString(), "stdout", "-l", "eng", "--psm", "6", "tsv"),
                command.get());
        assertFalse(Files.exists(temporaryImage.get()), "temporary image should be removed after OCR");
    }

    @Test
    void retainsOverlappingPossibleUnitAsRequiredReviewWithoutLosingTheCity() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t100\t100\t300\t30\t90\t123 MAIN ST",
                "5\t1\t1\t1\t2\t1\t440\t105\t20\t20\t90\t3B",
                "5\t1\t1\t1\t3\t1\t100\t140\t120\t20\t90\tDETROIT");
        Map<String, Object> evidence = Map.of(
                "1", Map.of("agreement", 2, "reviewRequired", false, "alternatives", List.of(
                        reading("paddleocr", "123 MAIN ST"), reading("easyocr", "123 MAIN ST"))),
                "2", Map.of("agreement", 1, "reviewRequired", true, "alternatives", List.of(reading("tesseract", "3B"))),
                "3", Map.of("agreement", 2, "reviewRequired", false, "alternatives", List.of(
                        reading("paddleocr", "DETROIT"), reading("easyocr", "DETROIT"))));
        String output = new ObjectMapper().writeValueAsString(Map.of("schemaVersion", 1,
                "engines", List.of("tesseract", "paddleocr", "easyocr"), "tsv", tsv, "lineEvidence", evidence));
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);
        List<PlaceCandidate> candidates = service.extractAddressCandidates(png(), "route.png", "");
        assertEquals(1, candidates.size());
        assertEquals("123 MAIN ST, DETROIT", candidates.get(0).text());
        assertTrue(candidates.get(0).ocrReviewRequired());
        assertTrue(candidates.get(0).ocrAlternatives().stream().anyMatch(value -> value.endsWith(": 3B")));

        String precedingTsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t110\t100\t20\t20\t90\t3B",
                "5\t1\t1\t1\t2\t1\t100\t100\t300\t30\t90\t123 MAIN ST",
                "5\t1\t1\t1\t3\t1\t100\t140\t120\t20\t90\tDETROIT");
        String precedingOutput = new ObjectMapper().writeValueAsString(Map.of("schemaVersion", 1,
                "engines", List.of("tesseract", "paddleocr", "easyocr"), "tsv", precedingTsv,
                "lineEvidence", Map.of("1", evidence.get("2"), "2", evidence.get("1"), "3", evidence.get("3"))));
        AddressOcrService precedingService = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, precedingOutput, ""), false);
        PlaceCandidate precedingCandidate = precedingService.extractAddressCandidates(png(), "route.png", "").get(0);
        assertEquals("123 MAIN ST, DETROIT", precedingCandidate.text());
        assertTrue(precedingCandidate.ocrReviewRequired());
        assertTrue(precedingCandidate.ocrAlternatives().stream().anyMatch(value -> value.endsWith(": 3B")));
    }

    @Test
    void skipsAdjacentNonOverlappingFarBadgeAndKeepsUnitConflictAndLocality() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t100\t100\t300\t20\t90\t9876 KINGSWOOD ST APT",
                "5\t1\t1\t1\t2\t1\t850\t122\t30\t10\t90\t20",
                "5\t1\t1\t1\t3\t1\t370\t124\t30\t10\t90\t102",
                "5\t1\t1\t1\t4\t1\t100\t140\t140\t20\t90\tWARREN");
        Map<String, Object> lineEvidence = Map.of(
                "1", consensusEvidence("9876 KINGSWOOD ST APT"),
                "2", evidence(1, true, List.of(
                        reading("tesseract", "20"), reading("paddleocr", "2"), reading("easyocr", "20"))),
                "3", evidence(2, true, List.of(
                        reading("tesseract", "102"), reading("paddleocr", "102"), reading("easyocr", "103"))),
                "4", consensusEvidence("WARREN"));
        String output = consensusOutput(tsv, lineEvidence);
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);

        PlaceCandidate candidate = service.extractAddressCandidates(png(), "route.png", "MI").get(0);

        assertEquals("9876 KINGSWOOD ST APT 102, WARREN, MI", candidate.text());
        assertEquals(2, candidate.ocrAgreement());
        assertTrue(candidate.ocrReviewRequired());
        assertTrue(candidate.ocrAlternatives().contains("line 3 easyocr: 103"));
        assertTrue(candidate.ocrAlternatives().stream().noneMatch(value -> value.startsWith("line 2 ")));
    }

    @Test
    void retainsAdjacentNonOverlappingNearbyUnitFragmentAsReviewEvidence() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t100\t100\t300\t20\t90\t123 MAIN ST",
                "5\t1\t1\t1\t2\t1\t410\t122\t20\t10\t90\t3B",
                "5\t1\t1\t1\t3\t1\t100\t140\t120\t20\t90\tDETROIT");
        Map<String, Object> lineEvidence = Map.of(
                "1", consensusEvidence("123 MAIN ST"),
                "2", evidence(1, true, List.of(reading("easyocr", "3B"))),
                "3", consensusEvidence("DETROIT"));
        String output = consensusOutput(tsv, lineEvidence);
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);

        PlaceCandidate candidate = service.extractAddressCandidates(png(), "route.png", "").get(0);

        assertEquals("123 MAIN ST, DETROIT", candidate.text());
        assertEquals(1, candidate.ocrAgreement());
        assertTrue(candidate.ocrReviewRequired());
        assertTrue(candidate.ocrAlternatives().contains("line 2 easyocr: 3B"));
    }

    @Test
    void excludesAdjacentNonOverlappingHeaderFragmentsFromAddressEvidence() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t100\t70\t40\t10\t90\teG»",
                "5\t1\t1\t1\t2\t1\t100\t82\t30\t10\t90\t929",
                "5\t1\t1\t1\t3\t1\t100\t100\t300\t20\t90\t123 MAIN ST",
                "5\t1\t1\t1\t4\t1\t100\t140\t120\t20\t90\tDETROIT");
        Map<String, Object> lineEvidence = Map.of(
                "1", evidence(1, true, List.of(reading("tesseract", "eG»"))),
                "2", evidence(1, true, List.of(reading("tesseract", "929"))),
                "3", consensusEvidence("123 MAIN ST"),
                "4", consensusEvidence("DETROIT"));
        String output = consensusOutput(tsv, lineEvidence);
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);

        PlaceCandidate candidate = service.extractAddressCandidates(png(), "route.png", "").get(0);

        assertEquals("123 MAIN ST, DETROIT", candidate.text());
        assertEquals(3, candidate.ocrAgreement());
        assertFalse(candidate.ocrReviewRequired());
        assertTrue(candidate.ocrAlternatives().stream().noneMatch(value -> value.startsWith("line 1 ")
                || value.startsWith("line 2 ")));
    }

    @Test
    void retainsAdjacentNonOverlappingNearbyUnitFragmentBeforeAddressAsReviewEvidence() throws Exception {
        for (String unit : List.of("3B", "A208")) {
            String tsv = TSV_HEADER + "\n" + String.join("\n",
                    "5\t1\t1\t1\t1\t1\t410\t70\t20\t10\t90\t" + unit,
                    "5\t1\t1\t1\t2\t1\t100\t100\t300\t20\t90\t123 MAIN ST",
                    "5\t1\t1\t1\t3\t1\t100\t140\t120\t20\t90\tDETROIT");
            Map<String, Object> lineEvidence = Map.of(
                    "1", evidence(1, true, List.of(reading("easyocr", unit))),
                    "2", consensusEvidence("123 MAIN ST"),
                    "3", consensusEvidence("DETROIT"));
            String output = consensusOutput(tsv, lineEvidence);
            AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                    ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);

            PlaceCandidate candidate = service.extractAddressCandidates(png(), "route.png", "").get(0);

            assertEquals("123 MAIN ST, DETROIT", candidate.text());
            assertEquals(1, candidate.ocrAgreement());
            assertTrue(candidate.ocrReviewRequired());
            assertTrue(candidate.ocrAlternatives().contains("line 1 easyocr: " + unit));
        }
    }

    @Test
    void keepsAdjacentPlainNumericFragmentOnlyWhenAddressHasUnitMarker() throws Exception {
        for (String unit : List.of("929", "2018")) {
            String tsv = TSV_HEADER + "\n" + String.join("\n",
                    "5\t1\t1\t1\t1\t1\t100\t82\t30\t10\t90\t" + unit,
                    "5\t1\t1\t1\t2\t1\t100\t100\t300\t20\t90\t123 MAIN ST APT",
                    "5\t1\t1\t1\t3\t1\t100\t140\t120\t20\t90\tDETROIT, MI");
            Map<String, Object> lineEvidence = Map.of(
                    "1", evidence(1, true, List.of(reading("tesseract", unit))),
                    "2", consensusEvidence("123 MAIN ST APT"),
                    "3", consensusEvidence("DETROIT, MI"));
            String output = consensusOutput(tsv, lineEvidence);
            AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                    ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);

            PlaceCandidate candidate = service.extractAddressCandidates(png(), "route.png", "").get(0);

            assertEquals("123 MAIN ST APT, DETROIT, MI", candidate.text());
            assertEquals(1, candidate.ocrAgreement());
            assertTrue(candidate.ocrReviewRequired());
            assertTrue(candidate.ocrAlternatives().contains("line 1 tesseract: " + unit));
        }
    }

    @Test
    void retainsUnknownAdjacentTwoLetterFragmentSoFollowingLocalityStillJoins() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t100\t100\t300\t20\t90\t22 1/2 EASTRIDGE CT",
                "5\t1\t1\t1\t2\t1\t100\t124\t30\t10\t90\tNN",
                "5\t1\t1\t1\t3\t1\t100\t140\t120\t20\t90\tALBANY NY");
        Map<String, Object> lineEvidence = Map.of(
                "1", consensusEvidence("22 1/2 EASTRIDGE CT"),
                "2", evidence(1, true, List.of(reading("tesseract", "NN"))),
                "3", consensusEvidence("ALBANY NY"));
        String output = consensusOutput(tsv, lineEvidence);
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);

        PlaceCandidate candidate = service.extractAddressCandidates(png(), "route.png", "").get(0);

        assertEquals("22 1/2 EASTRIDGE CT, ALBANY NY", candidate.text());
        assertEquals(1, candidate.ocrAgreement());
        assertTrue(candidate.ocrReviewRequired());
        assertTrue(candidate.ocrAlternatives().contains("line 2 tesseract: NN"));
    }

    @Test
    void excludesFarRightBadgeEvidenceButKeepsAddressDisagreementAndApartmentNumbers() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t38\t159\t470\t26\t90\t12345 N PARK DR APT 208",
                "5\t1\t1\t1\t2\t1\t38\t213\t180\t23\t90\tTROY",
                "5\t1\t1\t1\t3\t1\t867\t380\t70\t34\t90\t20",
                "5\t1\t1\t1\t4\t1\t31\t401\t518\t40\t90\t9876 KINGSWOOD ST APT 102",
                "5\t1\t1\t1\t5\t1\t31\t462\t140\t23\t90\tWARREN");
        Map<String, Object> badgeEvidence = evidence(2, true, List.of(
                reading("tesseract", "20"),
                reading("paddleocr", "2"),
                reading("easyocr", "2")));
        Map<String, Object> lineEvidence = Map.of(
                "1", consensusEvidence("12345 N PARK DR APT 208"),
                "2", consensusEvidence("TROY"),
                "3", badgeEvidence,
                "4", consensusEvidence("9876 KINGSWOOD ST APT 102"),
                "5", consensusEvidence("WARREN"));
        String cleanOutput = consensusOutput(tsv, lineEvidence);
        AddressOcrService cleanService = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, cleanOutput, ""), false);

        List<PlaceCandidate> cleanCandidates = cleanService.extractAddressCandidates(png(), "route.png", "MI");

        assertEquals(List.of(
                "12345 N PARK DR APT 208, TROY, MI",
                "9876 KINGSWOOD ST APT 102, WARREN, MI"),
                cleanCandidates.stream().map(PlaceCandidate::text).toList());
        assertEquals(List.of(3, 3), cleanCandidates.stream().map(PlaceCandidate::ocrAgreement).toList());
        assertTrue(cleanCandidates.stream().noneMatch(PlaceCandidate::ocrReviewRequired));
        assertTrue(cleanCandidates.stream().flatMap(candidate -> candidate.ocrAlternatives().stream())
                .noneMatch(alternative -> alternative.startsWith("line 3 ")));

        Map<String, Object> ambiguousEvidence = new java.util.HashMap<>(lineEvidence);
        ambiguousEvidence.put("4", evidence(2, true, List.of(
                reading("tesseract", "9876 KINGSWOOD ST APT 102"),
                reading("paddleocr", "9876 KINGSWOOD ST APT 102"),
                reading("easyocr", "9876 KINGSWOOD ST APT 103"))));
        String ambiguousOutput = consensusOutput(tsv, ambiguousEvidence);
        AddressOcrService ambiguousService = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, ambiguousOutput, ""), false);

        PlaceCandidate ambiguousCandidate = ambiguousService
                .extractAddressCandidates(png(), "route.png", "MI").get(1);

        assertEquals("9876 KINGSWOOD ST APT 102, WARREN, MI", ambiguousCandidate.text());
        assertEquals(2, ambiguousCandidate.ocrAgreement());
        assertTrue(ambiguousCandidate.ocrReviewRequired());
        assertTrue(ambiguousCandidate.ocrAlternatives().contains(
                "line 4 easyocr: 9876 KINGSWOOD ST APT 103"));
    }

    @Test
    void skipsFarBadgeAndStillFindsNearbyPossibleUnitBeforeTheAddress() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t590\t380\t20\t25\t90\t3B",
                "5\t1\t1\t1\t2\t1\t867\t390\t70\t34\t90\t20",
                "5\t1\t1\t1\t3\t1\t31\t401\t518\t40\t90\t9876 KINGSWOOD ST APT 102",
                "5\t1\t1\t1\t4\t1\t31\t462\t140\t23\t90\tWARREN");
        Map<String, Object> lineEvidence = Map.of(
                "1", evidence(1, true, List.of(reading("easyocr", "3B"))),
                "2", evidence(2, true, List.of(
                        reading("tesseract", "20"),
                        reading("paddleocr", "2"),
                        reading("easyocr", "2"))),
                "3", consensusEvidence("9876 KINGSWOOD ST APT 102"),
                "4", consensusEvidence("WARREN"));
        String output = consensusOutput(tsv, lineEvidence);
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);

        PlaceCandidate candidate = service.extractAddressCandidates(png(), "route.png", "MI").get(0);

        assertEquals("9876 KINGSWOOD ST APT 102, WARREN, MI", candidate.text());
        assertEquals(1, candidate.ocrAgreement());
        assertTrue(candidate.ocrReviewRequired());
        assertTrue(candidate.ocrAlternatives().contains("line 1 easyocr: 3B"));
        assertTrue(candidate.ocrAlternatives().stream().noneMatch(alternative -> alternative.startsWith("line 2 ")));
    }

    @Test
    void skipsFarBadgeBeforeNearbyPossibleUnitAndKeepsLocalityAnchorOnTheAddress() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t31\t380\t518\t40\t90\t9876 KINGSWOOD ST APT 102",
                "5\t1\t1\t1\t2\t1\t867\t401\t70\t34\t90\t20",
                "5\t1\t1\t1\t3\t1\t590\t410\t20\t25\t90\t3B",
                "5\t1\t1\t1\t4\t1\t31\t462\t140\t23\t90\tWARREN");
        Map<String, Object> lineEvidence = Map.of(
                "1", consensusEvidence("9876 KINGSWOOD ST APT 102"),
                "2", evidence(2, true, List.of(
                        reading("tesseract", "20"),
                        reading("paddleocr", "2"),
                        reading("easyocr", "2"))),
                "3", evidence(1, true, List.of(reading("easyocr", "3B"))),
                "4", consensusEvidence("WARREN"));
        String output = consensusOutput(tsv, lineEvidence);
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);

        PlaceCandidate candidate = service.extractAddressCandidates(png(), "route.png", "MI").get(0);

        assertEquals("9876 KINGSWOOD ST APT 102, WARREN, MI", candidate.text());
        assertEquals(1, candidate.ocrAgreement());
        assertTrue(candidate.ocrReviewRequired());
        assertTrue(candidate.ocrAlternatives().contains("line 3 easyocr: 3B"));
        assertTrue(candidate.ocrAlternatives().stream().noneMatch(alternative -> alternative.startsWith("line 2 ")));
    }

    @Test
    void skipsFarBadgeInsteadOfJoiningItAsUnitValueThenKeepsNearbyWrappedUnit() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t31\t380\t300\t40\t90\t12345 N PARK DR APT",
                "5\t1\t1\t1\t2\t1\t867\t390\t70\t34\t90\t20",
                "5\t1\t1\t1\t3\t1\t340\t401\t20\t25\t90\t102",
                "5\t1\t1\t1\t4\t1\t31\t462\t140\t23\t90\tWARREN");
        Map<String, Object> lineEvidence = Map.of(
                "1", consensusEvidence("12345 N PARK DR APT"),
                "2", evidence(2, true, List.of(
                        reading("tesseract", "20"),
                        reading("paddleocr", "2"),
                        reading("easyocr", "2"))),
                "3", consensusEvidence("102"),
                "4", consensusEvidence("WARREN"));
        String output = consensusOutput(tsv, lineEvidence);
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);

        PlaceCandidate candidate = service.extractAddressCandidates(png(), "route.png", "MI").get(0);

        assertEquals("12345 N PARK DR APT 102, WARREN, MI", candidate.text());
        assertEquals(3, candidate.ocrAgreement());
        assertTrue(candidate.ocrAlternatives().stream().noneMatch(alternative -> alternative.startsWith("line 2 ")));
        assertFalse(candidate.ocrReviewRequired());
    }

    @Test
    void acceptsTwoOfThreeEngineLineEvidenceAndPreservesOnlyObservedAlternatives() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                word(1, 1, 91, "24126"),
                word(1, 2, 86, "CIVIC"),
                word(1, 3, 85, "CENTER"),
                word(1, 4, 90, "DR"));
        String wrapperOutput = consensusOutput(tsv, 2, false, List.of(
                reading("tesseract", "24126 CIVIC CENTER DR"),
                reading("paddleocr", "24126 CIVIC CENTER DR")));
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                arguments -> new AddressOcrService.ProcessResult(0, wrapperOutput, ""), false);

        PlaceCandidate candidate = service.extractAddressCandidates(png(), "route.png", "MI").get(0);

        assertEquals(2, candidate.ocrAgreement());
        assertFalse(candidate.ocrReviewRequired());
        assertEquals(List.of(
                "line 1 tesseract: 24126 CIVIC CENTER DR",
                "line 1 paddleocr: 24126 CIVIC CENTER DR"), candidate.ocrAlternatives());
    }

    @Test
    void rejectsConsensusEvidenceWithUnsupportedAgreementAlternativesOrReviewState() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                word(1, 1, 91, "24126"),
                word(1, 2, 86, "CIVIC"),
                word(1, 3, 85, "CENTER"),
                word(1, 4, 90, "DR"));

        assertInvalidConsensus(tsv, 2, true, List.of(reading("tesseract", "24126 CIVIC CENTER DR")));
        assertInvalidConsensus(tsv, 1, true, List.of(
                reading("tesseract", "24126 CIVIC CENTER DR"),
                reading("tesseract", "24126 CIVIC CENTER DR")));
        assertInvalidConsensus(tsv, 1, true, List.of(reading("unknown-engine", "24126 CIVIC CENTER DR")));
        assertInvalidConsensus(tsv, 1, false, List.of(reading("tesseract", "24126 CIVIC CENTER DR")));
    }

    private static void assertInvalidConsensus(
            String tsv, int agreement, boolean reviewRequired, List<Map<String, String>> alternatives) throws Exception {
        String wrapperOutput = consensusOutput(tsv, agreement, reviewRequired, alternatives);
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                arguments -> new AddressOcrService.ProcessResult(0, wrapperOutput, ""), false);
        assertThrows(IllegalStateException.class,
                () -> service.extractAddressCandidates(png(), "route.png", "MI"));
    }

    private static String consensusOutput(
            String tsv, int agreement, boolean reviewRequired, List<Map<String, String>> alternatives) throws Exception {
        Map<String, Object> lineEvidence = Map.of(
                "agreement", agreement,
                "reviewRequired", reviewRequired,
                "alternatives", alternatives);
        Map<String, Object> wrapper = Map.of(
                "schemaVersion", 1,
                "engines", List.of("tesseract", "paddleocr", "easyocr"),
                "tsv", tsv,
                "lineEvidence", Map.of("1", lineEvidence));
        return new ObjectMapper().writeValueAsString(wrapper);
    }

    private static String consensusOutput(String tsv, Map<String, Object> lineEvidence) throws Exception {
        Map<String, Object> wrapper = Map.of(
                "schemaVersion", 1,
                "engines", List.of("tesseract", "paddleocr", "easyocr"),
                "tsv", tsv,
                "lineEvidence", lineEvidence);
        return new ObjectMapper().writeValueAsString(wrapper);
    }

    private static Map<String, String> reading(String engine, String text) {
        return Map.of("engine", engine, "text", text);
    }

    @Test
    void keepsVisibleStateAndZipRatherThanAppendingCallerState() {
        assertEquals("123 Main St Detroit MI 48201", AddressOcrService.withDefaultState("123 Main St Detroit MI 48201", "NY"));
        assertEquals("123 Main St Washington DC 20001-1234, USA", AddressOcrService.withDefaultState("123 Main St Washington DC 20001-1234, USA", "NY"));
        assertEquals("123 Main St, Michigan", AddressOcrService.withDefaultState("123 Main St", "Michigan"));
    }

    @Test
    void removesOcrDecorationBeforeTheHouseNumberAndRejectsNonAddressUiText() {
        assertEquals("23491 TEACUP CT", AddressOcrService.addressCandidateText("© 23491 TEACUP CT"));
        assertEquals("16579 BENTLER ST", AddressOcrService.addressCandidateText("7) 16579 BENTLER ST"));
        assertNull(AddressOcrService.addressCandidateText("6:09 MM 0 mB 96%"));
        assertNull(AddressOcrService.addressCandidateText("Deliver 1 package"));
        assertNull(AddressOcrService.addressCandidateText("LOCKER 12B DOOR 4"));
        assertNull(AddressOcrService.addressCandidateText("SECTION 9 BAY 17"));
        assertEquals("123 LOCKER RD", AddressOcrService.addressCandidateText("123 LOCKER RD"));
    }

    @Test
    void preservesAddressNumberShapesAndRecognizesSingleDigitStreets() {
        assertEquals("1 N MILE RD", AddressOcrService.addressCandidateText("1 N MILE RD"));
        assertEquals("12 1/2 N MILE RD", AddressOcrService.addressCandidateText("12 1/2 N MILE RD"));
        assertEquals("123-125 O’BRIEN HIGHWAY", AddressOcrService.addressCandidateText("123-125 O’BRIEN HIGHWAY"));
        assertNull(AddressOcrService.addressCandidateText("3 APT 350"));
        assertEquals("1 MAIN ST", AddressOcrService.addressCandidateText("3. 1 MAIN ST"));
    }

    @Test
    void joinsWrappedStreetUnitsAndCityStateZipWithoutDroppingDigits() throws Exception {
        String tsv = tsv(
                word(1, 1, 95, "12"), word(1, 2, 95, "1/2"), word(1, 3, 95, "N"), word(1, 4, 95, "MILE"),
                word(2, 1, 95, "RD"),
                word(3, 1, 95, "APT"), word(3, 2, 95, "350"),
                word(4, 1, 95, "SOUTHFIELD"), word(4, 2, 95, "MI"), word(4, 3, 95, "48075"),
                word(5, 1, 95, "1"), word(5, 2, 95, "E"), word(5, 3, 95, "O’BRIEN"), word(5, 4, 95, "HIGHWAY"));
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                arguments -> new AddressOcrService.ProcessResult(0, tsv, ""));

        List<PlaceCandidate> candidates = service.extractAddressCandidates(png(), "route.png", "MI");

        assertEquals(List.of(
                "12 1/2 N MILE RD APT 350, SOUTHFIELD MI 48075",
                "1 E O’BRIEN HIGHWAY, MI"), candidates.stream().map(PlaceCandidate::text).toList());
    }

    @Test
    void joinsWrappedSuffixAndUnitEvenWhenTheStreetNameContainsASuffixWord() throws Exception {
        String tsv = tsv(word(1, 1, 95, "2316 WEST HIGHLAND MEADOWS"),
                word(2, 1, 95, "BOULEVARD APARTMENT 12B"),
                word(3, 1, 95, "COLORADO SPRINGS"), word(4, 1, 95, "75 OAK RD APT 3"),
                word(5, 1, 95, "91 ELM ST"), word(6, 1, 95, "BUILDING 9"),
                word(7, 1, 95, "EUGENE OR"));
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng", arguments ->
                new AddressOcrService.ProcessResult(0, tsv, ""));
        String expected = "2316 WEST HIGHLAND MEADOWS BOULEVARD APARTMENT 12B, COLORADO SPRINGS";
        assertEquals(List.of(expected, "75 OAK RD APT 3", "91 ELM ST BUILDING 9, EUGENE OR"), service.extractAddressCandidates(png(), "route.png", "")
                .stream().map(PlaceCandidate::text).toList());
        assertEquals(List.of(expected), com.jtdev.routelisttotesla.util.AddressExtractor
                .addressLinesFromPlainJoined("2316 WEST HIGHLAND MEADOWS\nBOULEVARD APARTMENT 12B\nCOLORADO SPRINGS"));
    }

    @Test
    void retainsApartmentComplexAndFollowingCityWithEvidenceForEveryConsumedLine() throws Exception {
        String tsv = tsv(
                word(1, 1, 95, "11570 FORDLINE ST APT 201"),
                word(2, 1, 95, "JAMESTOWN VILLAGE"),
                word(3, 1, 95, "ALLEN PARK"));
        Map<String, Object> lineEvidence = Map.of(
                "1", evidence(3, false, List.of(
                        reading("tesseract", "11570 FORDLINE ST APT 201"),
                        reading("paddleocr", "11570 FORDLINE ST APT 201"),
                        reading("easyocr", "11570 FORDLINE ST APT 201"))),
                "2", evidence(2, true, List.of(
                        reading("tesseract", "JAMESTOWN VILLAGE"),
                        reading("paddleocr", "JAMESTOWN VILLAGE"))),
                "3", evidence(3, false, List.of(
                        reading("tesseract", "ALLEN PARK"),
                        reading("paddleocr", "ALLEN PARK"),
                        reading("easyocr", "ALLEN PARK"))));
        String output = new ObjectMapper().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "engines", List.of("tesseract", "paddleocr", "easyocr"),
                "tsv", tsv,
                "lineEvidence", lineEvidence));
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, output, ""), false);

        PlaceCandidate candidate = service.extractAddressCandidates(png(), "complex.png", "MI").get(0);

        assertEquals("11570 FORDLINE ST APT 201, JAMESTOWN VILLAGE, ALLEN PARK, MI", candidate.text());
        assertEquals(2, candidate.ocrAgreement());
        assertTrue(candidate.ocrReviewRequired());
        assertTrue(candidate.ocrAlternatives().stream().anyMatch(value ->
                value.equals("line 2 tesseract: JAMESTOWN VILLAGE")));
        assertTrue(candidate.ocrAlternatives().stream().anyMatch(value ->
                value.equals("line 3 easyocr: ALLEN PARK")));
    }

    @Test
    void recognizesNoUnitComplexOnlyWhenAnotherAdjacentCityFollows() throws Exception {
        String tsv = tsv(
                word(1, 1, 95, "789 MAPLE ST"),
                word(2, 1, 95, "OAKRIDGE VILLAGE"),
                word(3, 1, 95, "ALLEN PARK"),
                word(4, 1, 95, "1450 MAPLE ST"),
                word(5, 1, 95, "ELK GROVE VILLAGE"),
                word(6, 1, 95, "MI"));
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, tsv, ""));

        assertEquals(List.of(
                        "789 MAPLE ST, OAKRIDGE VILLAGE, ALLEN PARK, MI",
                        "1450 MAPLE ST, ELK GROVE VILLAGE MI"),
                service.extractAddressCandidates(png(), "locality.png", "MI")
                        .stream().map(PlaceCandidate::text).toList());
    }

    @Test
    void extractsTheFive0624AddressesWithoutExpectedTimesBadgesOrCycleCodes() throws Exception {
        String tsv = tsv(
                word(1, 1, 95, "1560 CULVER AVE"),
                word(2, 1, 95, "DEARBORN"),
                word(3, 1, 95, "8 Expected by 11 : 40 A M"),
                word(4, 1, 95, "# CYCLE_RUSH_RAPID"),
                word(5, 1, 95, "18028 SNOW AVE"),
                word(6, 1, 95, "DEARBORN"),
                word(7, 1, 95, "9 Expected by 11 : 50 A M"),
                word(8, 1, 95, "# CYCLE RUSH RAPID"),
                word(9, 1, 95, "2061 Chestnut St"),
                word(10, 1, 95, "DEARBORN"),
                word(11, 1, 95, "10 Expected by 11:50 AM"),
                word(12, 1, 95, "# CYCIE RUSH RAPI0"),
                word(13, 1, 95, "17851 SNOW AVE"),
                word(14, 1, 95, "DEARBORN"),
                word(15, 1, 95, "11 Expected by 11:50 AM"),
                word(16, 1, 95, "CYCLE RUSH RAPID"),
                word(17, 1, 95, "10728 MELBOURNE AVE"),
                word(18, 1, 95, "ALLEN PARK"));
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, tsv, ""));

        assertEquals(List.of(
                "1560 CULVER AVE, DEARBORN, MI",
                "18028 SNOW AVE, DEARBORN, MI",
                "2061 Chestnut St, DEARBORN, MI",
                "17851 SNOW AVE, DEARBORN, MI",
                "10728 MELBOURNE AVE, ALLEN PARK, MI"),
                service.extractAddressCandidates(png(), "itinerary.png", "MI")
                        .stream().map(PlaceCandidate::text).toList());
    }

    @Test
    void retainsWrappedUnitAndDoesNotPromoteStateOnlyOrUiLinesToComplexCities() throws Exception {
        String tsv = tsv(
                word(1, 1, 95, "789 MAPLE ST"),
                word(2, 1, 95, "APT 4"),
                word(3, 1, 95, "OAKRIDGE VILLAGE"),
                word(4, 1, 95, "MICHIGAN"),
                word(5, 1, 95, "987 PINE ST"),
                word(6, 1, 95, "APT 2"),
                word(7, 1, 95, "OAKRIDGE VILLAS"),
                word(8, 1, 95, "PACKAGES"));
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng",
                ignored -> new AddressOcrService.ProcessResult(0, tsv, ""));

        assertEquals(List.of(
                        "789 MAPLE ST APT 4, OAKRIDGE VILLAGE MICHIGAN",
                        "987 PINE ST APT 2, OAKRIDGE VILLAS, MI"),
                service.extractAddressCandidates(png(), "wrapped-unit.png", "MI")
                        .stream().map(PlaceCandidate::text).toList());
    }

    private static Map<String, Object> evidence(
            int agreement, boolean reviewRequired, List<Map<String, String>> alternatives) {
        return Map.of("agreement", agreement, "reviewRequired", reviewRequired, "alternatives", alternatives);
    }

    private static Map<String, Object> consensusEvidence(String text) {
        return evidence(3, false, List.of(
                reading("tesseract", text),
                reading("paddleocr", text),
                reading("easyocr", text)));
    }

    @Test
    void attachesCitiesWhenDetectorPaddingSlightlyOverlapsConsecutiveSmallTextLines() throws Exception {
        String tsv = TSV_HEADER + "\n" + String.join("\n",
                "5\t1\t1\t1\t1\t1\t99\t230\t78\t22\t90\t5 E 4TH ST",
                "5\t1\t1\t1\t2\t1\t98\t247\t64\t25\t90\tDAYTON",
                "5\t1\t1\t1\t3\t1\t46\t350\t159\t26\t90\t29 NW 8TH AVE",
                "5\t1\t1\t1\t4\t1\t99\t373\t50\t21\t90\tBOISE",
                "5\t1\t1\t1\t5\t1\t45\t493\t146\t26\t90\t116 S 2ND ST",
                "5\t1\t1\t1\t6\t1\t99\t515\t48\t23\t90\tRENO");
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng", arguments ->
                new AddressOcrService.ProcessResult(0, tsv, ""));
        assertEquals(List.of("5 E 4TH ST, DAYTON", "29 NW 8TH AVE, BOISE", "116 S 2ND ST, RENO"),
                service.extractAddressCandidates(png(), "route.png", "").stream().map(PlaceCandidate::text).toList());
    }

    @Test
    void admitsTwoOcrJobsAndReturns429ForTheThirdImage() throws Exception {
        byte[] image = png();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng", arguments -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IOException("timed out waiting for test release");
            }
            return new AddressOcrService.ProcessResult(0, TSV_HEADER + "\n", "");
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<List<PlaceCandidate>> first = workers.submit(() -> service.extractAddressCandidates(image, "first.png", "MI"));
        Future<List<PlaceCandidate>> second = workers.submit(() -> service.extractAddressCandidates(image, "second.png", "MI"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "both admitted OCR jobs should enter the executor");
            ResponseStatusException busy = assertThrows(
                    ResponseStatusException.class,
                    () -> service.extractAddressCandidates(image, "third.png", "MI"));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), busy.getStatusCode().value());
            release.countDown();
            assertEquals(List.of(), first.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(), second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void killsOcrDescendantsWhenTheProcessTimesOut() throws Exception {
        Path pidFile = Files.createTempFile("ocr-child-", ".pid");
        Files.delete(pidFile);
        long started = System.nanoTime();
        try {
            IOException error = assertThrows(IOException.class, () ->
                    AddressOcrService.runCommand(shellWithChild(pidFile, "wait"), Duration.ofMillis(200)));

            assertTrue(error.getMessage().contains("timed out"));
            assertChildStopped(pidFile);
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos());
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    @Test
    void killsOcrDescendantsWhenReadingOutputFails() throws Exception {
        Path pidFile = Files.createTempFile("ocr-child-", ".pid");
        Files.delete(pidFile);
        try {
            IOException error = assertThrows(IOException.class, () ->
                    AddressOcrService.runCommand(
                            shellWithChild(pidFile, "printf '0123456789'; wait"),
                            Duration.ofSeconds(3),
                            4));

            assertTrue(error.getMessage().contains("Unable to read OCR process output"));
            assertChildStopped(pidFile);
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    @Test
    void killsOcrDescendantsWhenTheCallingThreadIsInterrupted() throws Exception {
        Path pidFile = Files.createTempFile("ocr-child-", ".pid");
        Files.delete(pidFile);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                AddressOcrService.runCommand(shellWithChild(pidFile, "wait"), Duration.ofSeconds(10));
                failure.set(new AssertionError("interrupted OCR process returned successfully"));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        worker.setDaemon(true);
        try {
            worker.start();
            long pid = awaitChildPid(pidFile);
            worker.interrupt();
            worker.join(3000);

            assertFalse(worker.isAlive(), "interrupted OCR worker should finish cleanup promptly");
            assertTrue(failure.get() instanceof InterruptedException);
            assertChildStopped(pid);
        } finally {
            worker.interrupt();
            worker.join(1000);
            Files.deleteIfExists(pidFile);
        }
    }

    @Test
    void boundsOutputDrainWhenADescendantKeepsThePipeOpen() throws Exception {
        Path pidFile = Files.createTempFile("ocr-child-", ".pid");
        Files.delete(pidFile);
        long started = System.nanoTime();
        try {
            IOException error = assertThrows(IOException.class, () ->
                    AddressOcrService.runCommand(
                            shellWithChild(pidFile, "sleep 0.2; exit 0"),
                            Duration.ofSeconds(3)));

            assertTrue(error.getMessage().contains("output stream did not close"));
            assertChildStopped(pidFile);
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos());
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    private static List<String> shellWithChild(Path pidFile, String afterSpawn) {
        String quotedPath = "'" + pidFile.toAbsolutePath().toString().replace("'", "'\\''") + "'";
        String script = "sleep 30 & child=$!; printf '%s' \"$child\" > " + quotedPath + "; " + afterSpawn;
        return List.of("/bin/sh", "-c", script);
    }

    private static long awaitChildPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                String value = Files.readString(pidFile).trim();
                if (!value.isEmpty()) {
                    return Long.parseLong(value);
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("OCR child process did not publish its pid");
    }

    private static void assertChildStopped(Path pidFile) throws Exception {
        assertChildStopped(awaitChildPid(pidFile));
    }

    private static void assertChildStopped(long pid) throws Exception {
        ProcessHandle child = ProcessHandle.of(pid).orElse(null);
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (child != null && child.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(child != null && child.isAlive(), "OCR child process should be terminated");
    }

    @Test
    void rejectsInvalidImagesBeforeInvokingTesseract() throws Exception {
        AddressOcrService service = new AddressOcrService("tesseract", "eng", arguments -> {
            throw new AssertionError("invalid images must not start OCR");
        });

        assertThrows(IllegalArgumentException.class, () -> service.extractAddressCandidates(new byte[0], "empty.png", ""));
        assertThrows(IllegalArgumentException.class, () -> service.extractAddressCandidates(new byte[]{1, 2, 3}, "fake.png", ""));
    }

    @Test
    void reportsOcrProcessFailureWithoutReturningPartialAddresses() throws Exception {
        AddressOcrService service = new AddressOcrService("tesseract", "eng", arguments ->
                new AddressOcrService.ProcessResult(1, "", "Error opening data file"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.extractAddressCandidates(png(), "route.png", "MI"));

        assertTrue(error.getMessage().contains("exit code 1"));
        assertTrue(error.getMessage().contains("local OCR process failed"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void retainsStderrForDiagnosisWithoutLoggingOrReturningItsContents(CapturedOutput output) throws Exception {
        String privateDiagnostic = "123 Main St SECRET_TOKEN";
        AddressOcrService.ProcessResult process = AddressOcrService.runCommand(
                List.of("/bin/sh", "-c", "printf '%s' '" + privateDiagnostic + "' >&2"), Duration.ofSeconds(3));
        assertEquals(privateDiagnostic, process.stderr());

        AddressOcrService service = new AddressOcrService("ocr-binary", "eng", arguments ->
                new AddressOcrService.ProcessResult(2, "", privateDiagnostic));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.extractAddressCandidates(png(), "route.png", "MI"));

        assertTrue(error.getMessage().contains("exit code 2"));
        assertFalse(error.getMessage().contains(privateDiagnostic));
        assertTrue(output.toString().contains("category=engine_failure"));
        assertFalse(output.toString().contains(privateDiagnostic));

        AddressOcrService timeout = new AddressOcrService("ocr-binary", "eng", arguments ->
                new AddressOcrService.ProcessResult(
                        2, "", privateDiagnostic + "\nConsensus OCR failed: TimeoutExpired\n"));
        IllegalStateException timeoutError = assertThrows(IllegalStateException.class,
                () -> timeout.extractAddressCandidates(png(), "route.png", "MI"));
        assertTrue(timeoutError.getMessage().contains("timed out"));
        assertTrue(output.toString().contains("category=engine_timeout"));
        assertTrue(output.toString().contains("timeoutScope=shared_deadline"));
        assertFalse(output.toString().contains(privateDiagnostic));

        AddressOcrService engineTimeout = new AddressOcrService("ocr-binary", "eng", arguments ->
                new AddressOcrService.ProcessResult(2, "",
                        privateDiagnostic + "\nConsensus OCR failed: Timeout scope=engine engines=tesseract\n"));
        IllegalStateException engineTimeoutError = assertThrows(IllegalStateException.class,
                () -> engineTimeout.extractAddressCandidates(png(), "route.png", "MI"));
        assertTrue(engineTimeoutError.getMessage().contains("timed out"));
        assertTrue(output.toString().contains("timeoutScope=engine timeoutEngines=tesseract"));
        assertFalse(output.toString().contains(privateDiagnostic));

        AddressOcrService sharedEngineTimeout = new AddressOcrService("ocr-binary", "eng", arguments ->
                new AddressOcrService.ProcessResult(2, "",
                        privateDiagnostic + "\nConsensus OCR failed: Timeout scope=shared_deadline engines=paddleocr,easyocr\n"));
        assertThrows(IllegalStateException.class,
                () -> sharedEngineTimeout.extractAddressCandidates(png(), "route.png", "MI"));
        assertTrue(output.toString().contains("timeoutEngines=paddleocr,easyocr"));
        assertFalse(output.toString().contains(privateDiagnostic));
    }

    @Test
    void turnsProcessStartErrorsIntoActionableLocalOcrErrors() throws Exception {
        AddressOcrService service = new AddressOcrService("missing-tesseract", "eng", arguments -> {
            throw new IOException("executable not found");
        });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.extractAddressCandidates(png(), "route.png", ""));

        assertTrue(error.getMessage().contains("local OCR process could not be started or completed"));
    }

    private static String tsv(String... wordRows) {
        return TSV_HEADER + "\n" + String.join("\n", wordRows) + "\n";
    }

    private static String word(int line, int number, int confidence, String text) {
        int top = (line - 1) * 20;
        return "5\t1\t1\t1\t" + line + "\t" + number + "\t0\t" + top + "\t20\t10\t" + confidence + "\t" + text;
    }

    @Test
    void detectsHeifByBytesAndRemovesAllJobFilesOnEveryOutcome() throws Exception {
        for (int exitCode : List.of(0, 65, 2, -1)) {
            AtomicReference<Path> job = new AtomicReference<>();
            byte[] heif = heifHeader("mif1", "heic");
            AddressOcrService service = new AddressOcrService("ocr-binary", "eng", arguments -> {
                Path input = Path.of(arguments.get(1));
                assertTrue(input.toString().endsWith(".heic"));
                assertArrayEquals(heif, Files.readAllBytes(input));
                job.set(input.getParent());
                Path normalized = Files.createDirectory(input.getParent().resolve("normalized-test"));
                Files.writeString(normalized.resolve("image.png"), "temporary decoded pixels");
                if (exitCode == -1) throw new IOException("simulated timeout");
                return new AddressOcrService.ProcessResult(exitCode, TSV_HEADER + "\n", "");
            });
            if (exitCode == 0) {
                assertEquals(List.of(), service.extractAddressCandidates(heif, "untrusted.jpg", "MI"));
            } else if (exitCode == 65) {
                assertThrows(IllegalArgumentException.class,
                        () -> service.extractAddressCandidates(heif, "untrusted.jpg", "MI"));
            } else {
                assertThrows(IllegalStateException.class,
                        () -> service.extractAddressCandidates(heif, "untrusted.jpg", "MI"));
            }
            assertFalse(Files.exists(job.get()), "entire job directory must be deleted");
        }
    }

    @Test
    void rejectsUnrecognizedAndMalformedHeifHeadersBeforeSubprocess() {
        AddressOcrService service = new AddressOcrService("ocr-binary", "eng", arguments -> {
            throw new AssertionError("invalid header reached decoder");
        });
        byte[] badSize = heifHeader("heic", "mif1");
        ByteBuffer.wrap(badSize).putInt(4096);
        byte[] brandInMinorVersion = heifHeader("avif", "av01");
        System.arraycopy("heic".getBytes(StandardCharsets.US_ASCII), 0, brandInMinorVersion, 12, 4);
        for (byte[] bytes : List.of(badSize, brandInMinorVersion, heifHeader("avif", "av01"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.extractAddressCandidates(bytes, "misleading.HEIC", "MI"));
        }
    }

    @Test
    void doesNotPassApplicationEnvironmentToOcrProcess() throws Exception {
        var output = AddressOcrService.runCommand(List.of("/usr/bin/env"), Duration.ofSeconds(3));
        var allowed = java.util.Set.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "TMP", "TEMP",
                "OMP_THREAD_LIMIT", "OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS",
                "OCR_MODEL_MANIFEST", "OCR_TESSERACT_BINARY", "TESSDATA_PREFIX");
        for (String line : output.stdout().split("\n")) {
            if (!line.isBlank()) assertTrue(allowed.contains(line.substring(0, line.indexOf('='))));
        }
    }

    private static byte[] heifHeader(String majorBrand, String compatibleBrand) {
        return ByteBuffer.allocate(20).putInt(20)
                .put("ftyp".getBytes(StandardCharsets.US_ASCII))
                .put(majorBrand.getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put(compatibleBrand.getBytes(StandardCharsets.US_ASCII)).array();
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }
}
