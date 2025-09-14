package com.jtdev.routelisttotesla.model;

public record PlaceCandidate(String text, String normalized, String sourceImage, int lineIndex, double lat, double lon, String pid) {
    public PlaceCandidate withLatLonPid(double lat, double lon, String pid) { return new PlaceCandidate(text, normalized, sourceImage, lineIndex, lat, lon, pid); }
}
