package com.jtdev.routelisttotesla.model;

import java.util.List;

public record PlaceCandidate(
        String text,
        String normalized,
        String sourceImage,
        int lineIndex,
        double lat,
        double lon,
        String pid,
        int ocrAgreement,
        boolean ocrReviewRequired,
        List<String> ocrAlternatives) {

    public PlaceCandidate {
        if (ocrAgreement < 0 || ocrAgreement > 3) {
            throw new IllegalArgumentException("OCR agreement must be between zero and three");
        }
        ocrAlternatives = ocrAlternatives == null ? List.of() : List.copyOf(ocrAlternatives);
    }

    public PlaceCandidate(
            String text,
            String normalized,
            String sourceImage,
            int lineIndex,
            double lat,
            double lon,
            String pid) {
        this(text, normalized, sourceImage, lineIndex, lat, lon, pid, 0, false, List.of());
    }

    public PlaceCandidate withLatLonPid(double lat, double lon, String pid) {
        return new PlaceCandidate(
                text, normalized, sourceImage, lineIndex, lat, lon, pid,
                ocrAgreement, ocrReviewRequired, ocrAlternatives);
    }
}
