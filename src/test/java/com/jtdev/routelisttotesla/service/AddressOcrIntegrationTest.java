package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AddressOcrIntegrationTest {
    @Test
    void readsVisibleStopsFromScreenshotUsingLocalTesseract() throws Exception {
        assumeTrue(tesseractIsAvailable(), "Tesseract is optional for test hosts without the local OCR executable");

        String filename = "synthetic-route.png";
        byte[] screenshot = syntheticScreenshot();
        // This checks the individual engine; final-image tests exercise strict three-engine consensus.
        AddressOcrService service = new AddressOcrService("tesseract", "eng", true);

        List<PlaceCandidate> candidates = service.extractAddressCandidates(screenshot, filename, "MI");

        assertEquals(List.of(
                "123 MAIN ST, DETROIT, MI",
                "456 OAK RD, DETROIT, MI"), candidates.stream().map(PlaceCandidate::text).toList());
        assertEquals(List.of(0, 1), candidates.stream().map(PlaceCandidate::lineIndex).toList());
        assertEquals(List.of(filename, filename), candidates.stream().map(PlaceCandidate::sourceImage).toList());
    }

    private static byte[] syntheticScreenshot() throws IOException {
        BufferedImage image = new BufferedImage(900, 280, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 32));
            String[] lines = {"123 MAIN ST", "DETROIT", "456 OAK RD", "DETROIT"};
            for (int i = 0; i < lines.length; i++) graphics.drawString(lines[i], 40, 55 + 50 * i);
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } finally {
            graphics.dispose();
            image.flush();
        }
    }

    private static boolean tesseractIsAvailable() {
        try {
            Process process = new ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
