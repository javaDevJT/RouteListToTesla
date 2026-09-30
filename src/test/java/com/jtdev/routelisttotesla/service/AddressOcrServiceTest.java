package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
                "5\t1\t1\t1\t2\t1\t40\t105\t20\t20\t90\t3B",
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
                "5\t1\t1\t1\t1\t1\t40\t100\t20\t20\t90\t3B",
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
    void admitsOneOcrJobAndReturns429ForTheNextImage() throws Exception {
        byte[] image = png();
        CountDownLatch entered = new CountDownLatch(1);
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
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the admitted OCR job should enter the executor");
            ResponseStatusException busy = assertThrows(
                    ResponseStatusException.class,
                    () -> service.extractAddressCandidates(image, "second.png", "MI"));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), busy.getStatusCode().value());
            release.countDown();
            assertEquals(List.of(), first.get(5, TimeUnit.SECONDS));
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
