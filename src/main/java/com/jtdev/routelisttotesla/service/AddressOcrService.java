package com.jtdev.routelisttotesla.service;


import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.util.AddressExtractor;
import org.bytedeco.leptonica.PIX;
import org.bytedeco.tesseract.TessBaseAPI;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.leptonica.global.leptonica.*;

@Service
public class AddressOcrService {
    // English; provide your own tessdata on classpath if you want custom.
    private static final String LANG = "eng";

    public List<PlaceCandidate> extractAddressCandidates(byte[] imageBytes, String name) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (src == null) return List.of();

        try (TessBaseAPI api = new TessBaseAPI()) {
            // Resolve tessdata path from classpath (/tessdata) or env fallback
            String dataPath = null;
            try {
                var url = getClass().getResource("/");
                if (url != null) {
                    dataPath = new java.io.File(url.toURI()).getPath();
                }
            } catch (Exception ignore) {}
            if (dataPath == null || dataPath.isBlank()) {
                dataPath = System.getenv("TESSDATA_PREFIX");
            }
            if (api.Init((dataPath == null || dataPath.isBlank()) ? null : dataPath, LANG) != 0)
                throw new IllegalStateException("Tesseract init failed");

            PIX pix    = pixReadMem(imageBytes, imageBytes.length);

            // 1) Upscale for small UI text
            PIX scaled = pixScale(pix, 3.0F, 3.0F);

            // 2) Grayscale
            PIX gray   = pixConvertRGBToGray(scaled, 0.2126f, 0.7152f, 0.0722f);

            // 3) Unsharp mask (radius=2, amount=0.5)
            PIX sharp  = pixUnsharpMasking(gray, 2, 0.5f);

            // 4) Fixed threshold (works on 1.85.0)
            PIX bin    = pixThresholdToBinary(sharp, 140); // try 120–160 if needed

            api.SetImage(bin);
            api.SetPageSegMode(6);               // uniform block
            api.SetVariable("user_defined_dpi", "300");
            api.SetVariable("preserve_interword_spaces", "1");
            // remove the whitelist; it suppressed lowercase and symbols

            String plain = api.GetUTF8Text().getString();

            // Fallback if OCR is junk
            if (plain.replaceAll("[^A-Za-z0-9]", "").length() < 20) {
                api.SetPageSegMode(11);          // SPARSE_TEXT
                plain = api.GetUTF8Text().getString();
            }

            // cleanup
            pixDestroy(pix);
            pixDestroy(scaled);
            pixDestroy(gray);
            pixDestroy(sharp);
            pixDestroy(bin);

            List<PlaceCandidate> out = new ArrayList<>();
            int idx = 0;
            for (String line : AddressExtractor.addressLinesFromPlainJoined(plain)) {
                out.add(new PlaceCandidate(line, AddressExtractor.normalize(line), name, idx++, 0, 0, null));
            }
            return out;
        }
    }
}
