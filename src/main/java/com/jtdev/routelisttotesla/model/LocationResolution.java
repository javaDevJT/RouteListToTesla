package com.jtdev.routelisttotesla.model;

/** Safe outcome details; command paths must obtain these from server validation. */
public record LocationResolution(
        Status status,
        String strategy,
        String reasonCode,
        int attempts,
        String policyVersion,
        Integer retryAfterSeconds,
        String matchedAddress) {

    public LocationResolution(Status status, String strategy, String reasonCode, int attempts,
                              String policyVersion, Integer retryAfterSeconds) {
        this(status, strategy, reasonCode, attempts, policyVersion, retryAfterSeconds, null);
    }

    public enum Status {
        VERIFIED_UNIT, VERIFIED_BUILDING, NO_MATCH, AMBIGUOUS, DEFERRED, LOOKUP_UNAVAILABLE
    }

    public boolean verified() {
        return status == Status.VERIFIED_UNIT || status == Status.VERIFIED_BUILDING;
    }
}
