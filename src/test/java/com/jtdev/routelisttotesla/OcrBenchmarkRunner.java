package com.jtdev.routelisttotesla;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.service.AddressOcrService;
import com.jtdev.routelisttotesla.util.AddressExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Test-only bridge from fixture manifests to the production OCR service. */
public class OcrBenchmarkRunner {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String manifestValue = System.getProperty("ocr.benchmark.manifest");
        if (manifestValue == null || manifestValue.isBlank()) {
            throw new IllegalArgumentException("Set -Docr.benchmark.manifest to a fixture manifest.");
        }

        Path manifest = Path.of(manifestValue).toAbsolutePath().normalize();
        JsonNode cases = JSON.readTree(manifest.toFile()).path("cases");
        if (!cases.isArray()) {
            throw new IllegalArgumentException("OCR benchmark manifest must contain a cases array.");
        }

        String executable = System.getProperty("ocr.tesseract.executable", "tesseract");
        String language = System.getProperty("ocr.language", "eng");
        AddressOcrService ocr = new AddressOcrService(executable, language, false);
        ObjectNode output = JSON.createObjectNode();
        output.put("schemaVersion", 2);
        output.put("runner", "AddressOcrService.extractAddressCandidates");
        output.put("ocrStrictMode", true);
        output.put("ocrExecutable", executable);
        output.put("ocrLanguage", language);
        ObjectNode engineContract = output.putObject("engineContract");
        engineContract.put("schemaVersion", 1);
        ArrayNode engines = engineContract.putArray("engines");
        engines.add("tesseract");
        engines.add("paddleocr");
        engines.add("easyocr");
        output.set("cases", runCases(cases, manifest, ocr, 1));
        if (Boolean.getBoolean("ocr.benchmark.compareParallelism")) {
            int repeats = Integer.getInteger("ocr.benchmark.repeats", 5);
            if (repeats < 1 || repeats > 6 || cases.size() > 4) {
                throw new IllegalArgumentException("Use 1–6 trials and at most four speed fixtures");
            }
            ArrayNode trials = output.putArray("parallelTrials");
            for (int repeat = 0; repeat < repeats; repeat++) {
                for (int position = 0; position < 2; position++) {
                    int parallelism = (repeat + position) % 2 + 1;
                    ObjectNode trial = trials.addObject();
                    trial.put("repeat", repeat);
                    trial.put("parallelism", parallelism);
                    long started = System.nanoTime();
                    trial.set("cases", runCases(cases, manifest, ocr, parallelism));
                    trial.put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0);
                }
            }
        }

        String outputValue = System.getProperty("ocr.benchmark.output", "target/ocr-benchmark-results.json");
        Path outputPath = Path.of(outputValue).toAbsolutePath().normalize();
        Files.createDirectories(outputPath.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), output);
    }

    private static ArrayNode runCases(JsonNode fixtures, Path manifest, AddressOcrService ocr,
                                      int parallelism) throws Exception {
        ArrayNode results = JSON.createArrayNode();
        try (var executor = Executors.newFixedThreadPool(parallelism)) {
            List<Future<ObjectNode>> pending = new ArrayList<>();
            for (JsonNode fixture : fixtures) {
                pending.add(executor.submit(() -> readCase(fixture, manifest, ocr)));
            }
            // Future completion order must never reorder selected screenshots.
            for (Future<ObjectNode> result : pending) results.add(result.get());
        }
        return results;
    }

    private static ObjectNode readCase(JsonNode fixture, Path manifest, AddressOcrService ocr) {
            String id = fixture.path("id").asString();
            String imageValue = fixture.path("image").asString();
            Path image = Path.of(imageValue);
            if (!image.isAbsolute()) {
                image = manifest.getParent().resolve(image).normalize();
            }

            ObjectNode result = JSON.createObjectNode();
            result.put("id", id);
            result.put("image", image.toString());
            ArrayNode candidates = result.putArray("candidates");
            long started = System.nanoTime();
            try {
                String defaultState = fixture.path("defaultState").asString("");
                for (PlaceCandidate candidate : ocr.extractAddressCandidates(
                        Files.readAllBytes(image), image.getFileName().toString(), defaultState)) {
                    ObjectNode row = candidates.addObject();
                    row.put("text", candidate.text());
                    row.put("normalized", candidate.normalized());
                    row.put("extractorNormalized", AddressExtractor.normalize(candidate.text()));
                    row.put("normalizationConsistent", Objects.equals(
                            candidate.normalized(), AddressExtractor.normalize(candidate.text())));
                    row.put("lineIndex", candidate.lineIndex());
                    row.put("ocrAgreement", candidate.ocrAgreement());
                    row.put("ocrReviewRequired", candidate.ocrReviewRequired());
                    ArrayNode alternatives = row.putArray("ocrAlternatives");
                    for (String alternative : candidate.ocrAlternatives()) {
                        alternatives.add(alternative);
                    }
                }
            } catch (Exception e) {
                result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                result.put("errorCause", Objects.toString(e.getCause(), ""));
            } finally {
                result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0);
            }
            return result;
    }
}
