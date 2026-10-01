package com.jtdev.routelisttotesla.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.util.AddressExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Service
public class AddressOcrService {
    public static final String EXTRACTION_VERSION = "ocr-consensus-v5";

    static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    static final long MAX_IMAGE_PIXELS = 20_000_000L;
    static final int MAX_TSV_BYTES = 8 * 1024 * 1024;
    static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(45);
    static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration PROCESS_TERMINATION_GRACE = Duration.ofMillis(150);
    private static final long PROCESS_POLL_INTERVAL_MILLIS = 10;
    private static final Semaphore OCR_SLOTS = new Semaphore(1, true);
    private static final Logger LOG = LoggerFactory.getLogger(AddressOcrService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CONSENSUS_ENGINES = List.of("tesseract", "paddleocr", "easyocr");
    private static final Set<String> CONSENSUS_ENGINE_SET = Set.copyOf(CONSENSUS_ENGINES);
    private static final String TSV_HEADER =
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext";

    private static final Pattern STATE_SUFFIX = Pattern.compile(
            "(?i)(?:^|[,\\s])(?:AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY|PR|GU|VI|AS|MP|ALABAMA|ALASKA|ARIZONA|ARKANSAS|CALIFORNIA|COLORADO|CONNECTICUT|DELAWARE|FLORIDA|GEORGIA|HAWAII|IDAHO|ILLINOIS|INDIANA|IOWA|KANSAS|KENTUCKY|LOUISIANA|MAINE|MARYLAND|MASSACHUSETTS|MICHIGAN|MINNESOTA|MISSISSIPPI|MISSOURI|MONTANA|NEBRASKA|NEVADA|NEW\\s+HAMPSHIRE|NEW\\s+JERSEY|NEW\\s+MEXICO|NEW\\s+YORK|NORTH\\s+CAROLINA|NORTH\\s+DAKOTA|OHIO|OKLAHOMA|OREGON|PENNSYLVANIA|RHODE\\s+ISLAND|SOUTH\\s+CAROLINA|SOUTH\\s+DAKOTA|TENNESSEE|TEXAS|UTAH|VERMONT|VIRGINIA|WASHINGTON|WEST\\s+VIRGINIA|WISCONSIN|WYOMING)(?:[,\\s]+\\d{5}(?:-\\d{4})?)?(?:[,\\s]+(?:USA|UNITED\\s+STATES))?\\s*$");
    private static final Pattern ZIP_SUFFIX = Pattern.compile("\\b\\d{5}(?:-\\d{4})?\\s*$");
    private static final Pattern STATE_ONLY_LINE = Pattern.compile("(?i)^\\s*[A-Z]{2}(?:\\s+\\d{5}(?:-\\d{4})?)?\\s*$");
    private static final Pattern UNIT_LINE = Pattern.compile("(?i)^" + AddressExtractor.UNIT_LABELS + "\\s*#?\\s*[\\p{L}\\p{N}-]*\\s*$");
    private static final Pattern UNIT_MARKER_END = Pattern.compile("(?i)(?:^|\\s)" + AddressExtractor.UNIT_LABELS + "\\s*$");
    private static final Pattern UNIT_VALUE = Pattern.compile("(?i)^#?[\\p{L}\\p{N}][\\p{L}\\p{N}-]*$");

    private final String executable;
    private final String language;
    private final CommandExecutor commandExecutor;
    private final boolean allowLegacyTsv;

    @Autowired
    public AddressOcrService(
            @Value("${ocr.tesseract.executable:tesseract}") String executable,
            @Value("${ocr.language:eng}") String language) {
        this(executable, language, AddressOcrService::runCommand, false);
    }

    /** Explicit compatibility path for offline legacy-TSV tests and benchmarks. */
    public AddressOcrService(String executable, String language, boolean allowLegacyTsv) {
        this(executable, language, AddressOcrService::runCommand, allowLegacyTsv);
    }

    AddressOcrService(String executable, String language, CommandExecutor commandExecutor) {
        this(executable, language, commandExecutor, true);
    }

    AddressOcrService(String executable, String language, CommandExecutor commandExecutor, boolean allowLegacyTsv) {
        this.executable = executable == null || executable.isBlank() ? "tesseract" : executable.trim();
        this.language = language == null || language.isBlank() ? "eng" : language.trim();
        this.commandExecutor = commandExecutor;
        this.allowLegacyTsv = allowLegacyTsv;
    }

    public List<PlaceCandidate> extractAddressCandidates(byte[] imageBytes, String name, String defaultState) throws Exception {
        long startedAt = System.nanoTime();
        try {
            if (!OCR_SLOTS.tryAcquire(0, TimeUnit.MILLISECONDS)) {
                logFailure("admission_busy", null, null, startedAt);
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "OCR is busy; try again");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logFailure("admission_interrupted", e.getClass(), null, startedAt);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OCR admission was interrupted", e);
        }

        Path jobDirectory = null;
        String failureCategory = "image_validation";
        Integer exitCode = null;
        try {
            String imageExtension = validateImage(imageBytes);
            failureCategory = "image_setup";
            jobDirectory = Files.createTempDirectory("routelist-ocr-");
            Path imageFile = jobDirectory.resolve("input" + imageExtension);
            Files.write(imageFile, imageBytes);
            List<String> command = List.of(
                    executable,
                    imageFile.toString(),
                    "stdout",
                    "-l", language,
                    "--psm", "6",
                    "tsv");

            ProcessResult result;
            failureCategory = "ocr_process";
            try {
                result = commandExecutor.execute(command);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failureCategory = "process_interrupted";
                throw new IllegalStateException("Local OCR was interrupted.", e);
            } catch (IOException e) {
                boolean timedOut = "OCR process timed out.".equals(e.getMessage());
                failureCategory = timedOut ? "process_timeout" : "process_io_failure";
                if (timedOut) {
                    throw new IllegalStateException("The local OCR process timed out. Please retry.", e);
                }
                throw new IllegalStateException(
                        "The local OCR process could not be started or completed. Check the OCR wrapper configuration and installed engine dependencies.", e);
            }

            exitCode = result.exitCode();
            if (result.exitCode() == 65) {
                failureCategory = "invalid_image";
                throw new IllegalArgumentException(
                        "The image could not be decoded or exceeds the 20 megapixel limit. Choose a valid HEIC, PNG, or JPEG image.");
            }
            if (result.exitCode() != 0) {
                if (isWrapperTimeout(result.stderr())) {
                    failureCategory = "engine_timeout";
                    throw new IllegalStateException("The local OCR engines timed out. Please retry.");
                }
                failureCategory = "engine_failure";
                throw new IllegalStateException(
                        "The local OCR process failed with exit code " + result.exitCode()
                                + ". Check the OCR wrapper configuration and installed engine dependencies.");
            }

            failureCategory = "output_processing";
            ParsedOcrOutput parsedOutput = parseOutput(result.stdout(), allowLegacyTsv);
            List<OcrLine> lines = readLines(parsedOutput.tsv());
            if (parsedOutput.consensus()) {
                validateLineEvidence(parsedOutput.lineEvidence(), lines.size());
            }
            List<PlaceCandidate> candidates = new ArrayList<>(lines.size());
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                OcrLine line = lines.get(lineIndex);
                String addressText = addressCandidateText(line.text());
                if (addressText == null) {
                    continue;
                }

                int lastLineIndex = lineIndex;
                boolean overlappingFragment = false;
                int firstLineIndex = lineIndex;
                while (firstLineIndex > 0) {
                    OcrLine previous = lines.get(firstLineIndex - 1);
                    if (previous.bottom() <= line.top() || previous.text().trim().length() > 3
                            || addressCandidateText(previous.text()) != null) break;
                    overlappingFragment = true;
                    firstLineIndex--;
                }
                while (lastLineIndex + 1 < lines.size()) {
                    OcrLine previousLine = lines.get(lastLineIndex);
                    OcrLine nextLine = lines.get(lastLineIndex + 1);
                    String continuation = nextLine.text().trim();
                    boolean smallOverlap = continuation.length() <= 3 && nextLine.top() < line.bottom()
                            && addressCandidateText(continuation) == null;
                    if (!linesAreAdjacent(previousLine, nextLine) && !smallOverlap) {
                        break;
                    }
                    if (AddressExtractor.continuesStreet(addressText, continuation)
                            || UNIT_LINE.matcher(continuation).matches()
                            || (UNIT_MARKER_END.matcher(addressText).find()
                                    && UNIT_VALUE.matcher(continuation).matches())
                            || (addressText.endsWith(" UN") && continuation.matches("(?i)^IT\\s+\\d+.*"))) {
                        addressText += " " + continuation;
                        lastLineIndex++;
                    } else if (smallOverlap) {
                        // Retain possible units and stop badges as evidence that requires review.
                        overlappingFragment = true;
                        lastLineIndex++;
                    } else {
                        break;
                    }
                }

                if (lastLineIndex + 1 < lines.size()) {
                    OcrLine lastAddressLine = lines.get(lastLineIndex);
                    OcrLine followingLine = lines.get(lastLineIndex + 1);
                    String locality = adjacentLocality(lastAddressLine, followingLine);
                    if (locality != null) {
                        addressText += ", " + locality;
                        lastLineIndex++;
                        if (lastLineIndex + 1 < lines.size()) {
                            OcrLine stateLine = lines.get(lastLineIndex + 1);
                            if (linesAreAdjacent(lines.get(lastLineIndex), stateLine)
                                    && STATE_ONLY_LINE.matcher(stateLine.text().trim()).matches()
                                    && STATE_SUFFIX.matcher(stateLine.text()).find()) {
                                addressText += " " + stateLine.text().trim();
                                lastLineIndex++;
                            }
                        }
                    }
                }
                // Do not apply a confidence cutoff: ambiguous address-like rows stay in the editable review list.
                String text = withDefaultState(addressText, defaultState);
                CandidateOcrEvidence ocrEvidence = candidateEvidence(
                        parsedOutput.lineEvidence(), parsedOutput.consensus(), firstLineIndex, lastLineIndex);
                candidates.add(new PlaceCandidate(
                        text,
                        text.toUpperCase(Locale.ROOT),
                        name,
                        candidates.size(),
                        0,
                        0,
                        null,
                        ocrEvidence.agreement(),
                        overlappingFragment || ocrEvidence.reviewRequired(),
                        ocrEvidence.alternatives()));
                lineIndex = lastLineIndex;
            }
            return candidates;
        } catch (IllegalArgumentException | IllegalStateException e) {
            logFailure(failureCategory, e.getClass(), exitCode, startedAt);
            throw e;
        } catch (IOException e) {
            logFailure(failureCategory, e.getClass(), exitCode, startedAt);
            throw e;
        } finally {
            try {
                if (jobDirectory != null) {
                    // Also remove converted pixels after an interrupted or killed subprocess.
                    try (var files = Files.walk(jobDirectory)) {
                        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(file);
                        }
                    }
                }
            } finally {
                OCR_SLOTS.release();
            }
        }
    }

    private static void logFailure(String category, Class<?> exceptionClass, Integer exitCode, long startedAt) {
        LOG.warn("OCR failed category={} exceptionClass={} exitCode={} elapsedMs={}",
                category,
                exceptionClass == null ? "none" : exceptionClass.getSimpleName(),
                exitCode == null ? "none" : exitCode.toString(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private static boolean isWrapperTimeout(String stderr) {
        return stderr != null && stderr.stripTrailing().endsWith("Consensus OCR failed: TimeoutExpired");
    }

    static String withDefaultState(String address, String defaultState) {
        String state = defaultState == null ? "" : defaultState.trim().replaceAll("\\s+", " ");
        if (state.isEmpty() || STATE_SUFFIX.matcher(address).find()) {
            return address;
        }
        return address + ", " + state;
    }

    private static String validateImage(byte[] imageBytes) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Image must not be empty.");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image exceeds the 12 MB OCR limit.");
        }

        // The local HEIF decoder checks dimensions before allocating pixels.
        if (isHeif(imageBytes)) return ".heic";
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (input == null) {
                throw new IllegalArgumentException("Image bytes are not a supported image.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Image bytes are not a supported image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_IMAGE_PIXELS) {
                    throw new IllegalArgumentException("Image dimensions exceed the 20 megapixel OCR limit.");
                }
                return extensionFor(reader.getFormatName());
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Image bytes are not a supported image.", e);
        }
    }

    private static boolean isHeif(byte[] bytes) {
        if (bytes.length < 16 || !"ftyp".equals(new String(bytes, 4, 4, StandardCharsets.US_ASCII))) return false;
        long size = Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt());
        if (size < 16 || size > Math.min(bytes.length, 4096) || size % 4 != 0) return false;
        Set<String> brands = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1");
        for (int offset = 8; offset < size; offset += 4) {
            if (offset != 12 && brands.contains(new String(bytes, offset, 4, StandardCharsets.US_ASCII))) return true;
        }
        return false;
    }

    private static String extensionFor(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> ".jpg";
            case "tif", "tiff" -> ".tif";
            case "bmp" -> ".bmp";
            case "gif" -> ".gif";
            case "png" -> ".png";
            default -> ".img";
        };
    }

    private static ParsedOcrOutput parseOutput(String stdout, boolean allowLegacyTsv) {
        if (stdout == null) {
            throw invalidConsensusOutput();
        }
        String trimmed = stdout.stripLeading();
        if (!trimmed.startsWith("{")) {
            if (allowLegacyTsv && hasStandardTsvHeader(stdout)) {
                return new ParsedOcrOutput(stdout, Map.of(), false);
            }
            throw new IllegalStateException("The OCR consensus wrapper output is required.");
        }

        try {
            JsonNode root = JSON.readTree(trimmed);
            if (root == null || !root.isObject()
                    || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").asInt() != 1
                    || !root.path("engines").isArray()
                    || !root.path("tsv").isString()
                    || !hasStandardTsvHeader(root.path("tsv").asString())
                    || !root.path("lineEvidence").isObject()) {
                throw invalidConsensusOutput();
            }

            LinkedHashSet<String> engines = new LinkedHashSet<>();
            for (JsonNode engine : root.path("engines")) {
                if (!engine.isString() || !engines.add(engine.asString())) {
                    throw invalidConsensusOutput();
                }
            }
            if (!engines.equals(CONSENSUS_ENGINE_SET)) {
                throw invalidConsensusOutput();
            }

            Map<Integer, LineEvidence> lineEvidence = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.path("lineEvidence").properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                int lineNumber;
                try {
                    lineNumber = Integer.parseInt(field.getKey());
                } catch (NumberFormatException e) {
                    throw invalidConsensusOutput();
                }
                if (lineNumber < 1 || !Integer.toString(lineNumber).equals(field.getKey())) {
                    throw invalidConsensusOutput();
                }

                JsonNode evidence = field.getValue();
                JsonNode agreement = evidence.path("agreement");
                JsonNode reviewRequired = evidence.path("reviewRequired");
                JsonNode alternativesNode = evidence.path("alternatives");
                if (!evidence.isObject() || !agreement.isIntegralNumber()
                    || agreement.asInt() < 1 || agreement.asInt() > CONSENSUS_ENGINES.size()
                    || !reviewRequired.isBoolean() || !alternativesNode.isArray()
                    || (agreement.asInt() == 1 && !reviewRequired.asBoolean())
                    || alternativesNode.size() < 1
                    || alternativesNode.size() > CONSENSUS_ENGINES.size()) {
                    throw invalidConsensusOutput();
                }

                Map<String, String> alternativesByEngine = new LinkedHashMap<>();
                for (JsonNode alternative : alternativesNode) {
                    JsonNode engine = alternative.path("engine");
                    JsonNode text = alternative.path("text");
                    if (!alternative.isObject() || !engine.isString() || !text.isString()
                            || !CONSENSUS_ENGINE_SET.contains(engine.asString())
                            || alternativesByEngine.putIfAbsent(engine.asString(), text.asString()) != null) {
                        throw invalidConsensusOutput();
                    }
                }
            if (agreement.asInt() > alternativesByEngine.size()
                    || lineEvidence.containsKey(lineNumber)) {
                    throw invalidConsensusOutput();
                }

            List<LineAlternative> alternatives = CONSENSUS_ENGINES.stream()
                    .filter(alternativesByEngine::containsKey)
                    .map(engine -> new LineAlternative(engine, alternativesByEngine.get(engine)))
                        .toList();
                lineEvidence.put(lineNumber, new LineEvidence(
                        agreement.asInt(), reviewRequired.asBoolean(), alternatives));
            }

            return new ParsedOcrOutput(root.path("tsv").asString(), Map.copyOf(lineEvidence), true);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException invalid) {
                throw invalid;
            }
            throw invalidConsensusOutput();
        }
    }

    private static boolean hasStandardTsvHeader(String tsv) {
        if (tsv == null || tsv.isEmpty()) {
            return false;
        }
        int lineEnd = tsv.indexOf('\n');
        String header = lineEnd < 0 ? tsv : tsv.substring(0, lineEnd);
        if (header.endsWith("\r")) {
            header = header.substring(0, header.length() - 1);
        }
        return TSV_HEADER.equals(header);
    }

    private static void validateLineEvidence(Map<Integer, LineEvidence> lineEvidence, int lineCount) {
        if (lineEvidence.size() != lineCount) {
            throw invalidConsensusOutput();
        }
        for (int lineNumber = 1; lineNumber <= lineCount; lineNumber++) {
            if (!lineEvidence.containsKey(lineNumber)) {
                throw invalidConsensusOutput();
            }
        }
    }

    private static CandidateOcrEvidence candidateEvidence(
            Map<Integer, LineEvidence> lineEvidence,
            boolean consensus,
            int firstLineIndex,
            int lastLineIndex) {
        if (!consensus) {
            return new CandidateOcrEvidence(0, false, List.of());
        }

        int agreement = CONSENSUS_ENGINES.size();
        boolean reviewRequired = false;
        List<String> alternatives = new ArrayList<>();
        for (int index = firstLineIndex; index <= lastLineIndex; index++) {
            int lineNumber = index + 1;
            LineEvidence evidence = lineEvidence.get(lineNumber);
            if (evidence == null) {
                throw invalidConsensusOutput();
            }
            agreement = Math.min(agreement, evidence.agreement());
            reviewRequired |= evidence.reviewRequired();
            for (LineAlternative alternative : evidence.alternatives()) {
                alternatives.add("line " + lineNumber + " " + alternative.engine() + ": " + alternative.text());
            }
        }
        return new CandidateOcrEvidence(agreement, reviewRequired, alternatives);
    }

    private static IllegalStateException invalidConsensusOutput() {
        return new IllegalStateException("The OCR consensus wrapper returned invalid output.");
    }

    private static List<OcrLine> readLines(String tsv) {
        Map<LineKey, LineBuilder> lines = new LinkedHashMap<>();
        String[] rows = tsv.split("\\R");
        for (int rowIndex = 1; rowIndex < rows.length; rowIndex++) {
            String[] columns = rows[rowIndex].split("\\t", 12);
            if (columns.length < 12 || !"5".equals(columns[0])) {
                continue;
            }
            String word = columns[11].trim().replaceAll("\\s+", " ");
            if (word.isEmpty()) {
                continue;
            }
            try {
                LineKey key = new LineKey(
                        Integer.parseInt(columns[1]),
                        Integer.parseInt(columns[2]),
                        Integer.parseInt(columns[3]),
                        Integer.parseInt(columns[4]));
                int wordNumber = Integer.parseInt(columns[5]);
                double confidence = Double.parseDouble(columns[10]);
                lines.computeIfAbsent(key, ignored -> new LineBuilder())
                        .add(wordNumber, word, confidence,
                                Integer.parseInt(columns[7]),
                                Integer.parseInt(columns[7]) + Integer.parseInt(columns[9]));
            } catch (NumberFormatException ignored) {
                // Ignore malformed TSV rows; valid OCR words in other rows remain usable.
            }
        }

        return lines.values().stream()
                .map(LineBuilder::build)
                .sorted(java.util.Comparator.comparingInt(OcrLine::top))
                .toList();
    }

    private static String adjacentLocality(OcrLine addressLine, OcrLine followingLine) {
        String rawLocality = followingLine.text().trim();
        if (rawLocality.matches("(?i)^" + AddressExtractor.UNIT_LABELS + "(?:\\s|#|$).*")) {
            return null;
        }
        boolean stateOrZip = STATE_SUFFIX.matcher(rawLocality).find() || ZIP_SUFFIX.matcher(rawLocality).find();
        if (rawLocality.chars().anyMatch(Character::isDigit) && !stateOrZip) {
            return null;
        }
        String locality = rawLocality
                .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "")
                .replaceAll("\\s+", " ");
        if (locality.length() < 3
                || !locality.matches("[\\p{L}][\\p{L}.'’,\\-]*(?:\\s+[\\p{L}][\\p{L}.'’,\\-]*){0,4}(?:\\s+\\d{5}(?:-\\d{4})?)?")) {
            return null;
        }
        return linesAreAdjacent(addressLine, followingLine) ? locality : null;
    }

    static String addressCandidateText(String text) {
        return AddressExtractor.addressCandidateText(text);
    }

    private static boolean linesAreAdjacent(OcrLine first, OcrLine second) {
        int gap = second.top() - first.bottom();
        int lineHeight = Math.max(first.bottom() - first.top(), second.bottom() - second.top());
        // Detector padding can overlap consecutive small-text rows without sharing their baseline.
        int overlap = Math.max(2, (int) Math.ceil(Math.min(
                first.bottom() - first.top(), second.bottom() - second.top()) * 0.3));
        return gap >= -overlap && gap <= Math.max(12, (int) Math.ceil(lineHeight * 1.5));
    }

    private static ProcessResult runCommand(List<String> command) throws IOException, InterruptedException {
        return runCommand(command, PROCESS_TIMEOUT, MAX_TSV_BYTES);
    }

    static ProcessResult runCommand(List<String> command, Duration timeout) throws IOException, InterruptedException {
        return runCommand(command, timeout, MAX_TSV_BYTES);
    }

    static ProcessResult runCommand(List<String> command, Duration timeout, int maximumOutputBytes)
            throws IOException, InterruptedException {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || maximumOutputBytes < 1) {
            throw new IllegalArgumentException("OCR process limits must be positive.");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        // OCR needs runtime paths and resource limits, never application credentials.
        builder.environment().keySet().retainAll(Set.of(
                "PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "TMP", "TEMP",
                "OMP_THREAD_LIMIT", "OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS",
                "OCR_MODEL_MANIFEST", "OCR_TESSERACT_BINARY", "TESSDATA_PREFIX"));
        Process process = builder.start();
        Set<ProcessHandle> observedDescendants = ConcurrentHashMap.newKeySet();
        CompletableFuture<byte[]> stdout = null;
        CompletableFuture<byte[]> stderr = null;
        boolean interrupted = false;
        try {
            process.getOutputStream().close();
            stdout = readAsync(process.getInputStream(), maximumOutputBytes);
            stderr = readAsync(process.getErrorStream(), 64 * 1024);

            long deadline = System.nanoTime() + timeout.toNanos();
            while (process.isAlive()) {
                recordDescendants(process.toHandle(), observedDescendants);
                throwIfStreamFailed(stdout);
                throwIfStreamFailed(stderr);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("OCR process timed out.");
                }
                long waitMillis = Math.max(1, Math.min(
                        PROCESS_POLL_INTERVAL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining)));
                process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
            }
            recordDescendants(process.toHandle(), observedDescendants);

            String output = new String(awaitStream(stdout), StandardCharsets.UTF_8);
            String errorOutput = new String(awaitStream(stderr), StandardCharsets.UTF_8);
            return new ProcessResult(process.exitValue(), output, errorOutput);
        } catch (InterruptedException e) {
            interrupted = true;
            throw e;
        } finally {
            boolean interruptedDuringCleanup = terminateProcessTree(process, observedDescendants);
            closeProcessStreams(process);
            if (stdout != null) {
                stdout.cancel(true);
            }
            if (stderr != null) {
                stderr.cancel(true);
            }
            if (interrupted || interruptedDuringCleanup) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static CompletableFuture<byte[]> readAsync(InputStream input, int maximumBytes) {
        return CompletableFuture.supplyAsync(() -> {
            try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > maximumBytes) {
                        throw new IOException("OCR process output exceeded allowed size.");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static byte[] awaitStream(CompletableFuture<byte[]> stream) throws IOException, InterruptedException {
        try {
            return stream.get(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw streamReadFailure(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("OCR output stream did not close after the process exited.", e);
        }
    }

    private static void throwIfStreamFailed(CompletableFuture<byte[]> stream) throws IOException {
        if (!stream.isCompletedExceptionally()) {
            return;
        }
        try {
            stream.join();
        } catch (CompletionException e) {
            throw streamReadFailure(e.getCause());
        }
    }

    private static IOException streamReadFailure(Throwable cause) {
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return new IOException("Unable to read OCR process output.", cause);
    }

    private static void recordDescendants(ProcessHandle root, Set<ProcessHandle> observedDescendants) {
        try {
            root.descendants().forEach(observedDescendants::add);
        } catch (RuntimeException ignored) {
            // Process exit can race with a descendant snapshot; previously observed handles remain usable.
        }
    }

    private static boolean terminateProcessTree(Process process, Set<ProcessHandle> observedDescendants) {
        ProcessHandle root = process.toHandle();
        recordDescendants(root, observedDescendants);
        Set<ProcessHandle> tree = new HashSet<>(observedDescendants);
        tree.add(root);
        if (tree.stream().noneMatch(ProcessHandle::isAlive)) {
            return false;
        }

        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + PROCESS_TERMINATION_GRACE.toNanos();
        boolean interrupted = false;
        while (tree.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            recordDescendants(root, observedDescendants);
            tree.addAll(observedDescendants);
            tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
            try {
                Thread.sleep(PROCESS_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        recordDescendants(root, observedDescendants);
        tree.addAll(observedDescendants);
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        long forceDeadline = System.nanoTime() + PROCESS_TERMINATION_GRACE.toNanos();
        while (tree.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < forceDeadline) {
            try {
                Thread.sleep(PROCESS_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static void closeProcessStreams(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
        }
    }


    @FunctionalInterface
    interface CommandExecutor {
        ProcessResult execute(List<String> command) throws IOException, InterruptedException;
    }

    record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    private record ParsedOcrOutput(String tsv, Map<Integer, LineEvidence> lineEvidence, boolean consensus) {
    }

    private record LineEvidence(int agreement, boolean reviewRequired, List<LineAlternative> alternatives) {
        private LineEvidence {
            alternatives = List.copyOf(alternatives);
        }
    }

    private record LineAlternative(String engine, String text) {
    }

    private record CandidateOcrEvidence(int agreement, boolean reviewRequired, List<String> alternatives) {
        private CandidateOcrEvidence {
            alternatives = List.copyOf(alternatives);
        }
    }

    private record LineKey(int page, int block, int paragraph, int line) {
    }

    private static final class LineBuilder {
        private final Map<Integer, OcrWord> words = new TreeMap<>();
        private double confidenceTotal;
        private int confidenceWords;
        private int top = Integer.MAX_VALUE;
        private int bottom = Integer.MIN_VALUE;

        private void add(int wordNumber, String word, double confidence, int wordTop, int wordBottom) {
            words.put(wordNumber, new OcrWord(word, confidence));
            top = Math.min(top, wordTop);
            bottom = Math.max(bottom, wordBottom);
            if (confidence >= 0) {
                confidenceTotal += confidence;
                confidenceWords++;
            }
        }

        private OcrLine build() {
            double confidence = confidenceWords == 0 ? 0 : confidenceTotal / confidenceWords;
            String text = words.values().stream().map(OcrWord::text).collect(java.util.stream.Collectors.joining(" "));
            return new OcrLine(text, confidence, top, bottom);
        }
    }

    private record OcrWord(String text, double confidence) {
    }

    private record OcrLine(String text, double confidence, int top, int bottom) {
    }
}
