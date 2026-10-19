package com.jtdev.routelisttotesla.support;

import java.util.Locale;

public final class SourceCommit {
    private SourceCommit() {}

    public static String normalize(String candidate) {
        return candidate != null && candidate.matches("(?i)[0-9a-f]{40}")
                ? candidate.toLowerCase(Locale.ROOT)
                : "unknown";
    }
}
