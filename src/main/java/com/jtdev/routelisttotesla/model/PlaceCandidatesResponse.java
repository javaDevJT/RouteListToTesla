package com.jtdev.routelisttotesla.model;

import java.util.List;

public record PlaceCandidatesResponse(List<PlaceCandidate> candidates,
                                      List<List<PlaceCandidate>> imageCandidates) {
    public PlaceCandidatesResponse(List<PlaceCandidate> candidates) {
        this(candidates, null);
    }
}
