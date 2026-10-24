package com.jtdev.routelisttotesla.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Offline evidence associating a postal city/state with a ZIP near a geocoded point.
 * The provider's street-address proof must establish the point's ZIP. The distance
 * bound is not a ZIP polygon or a legal municipal boundary. Catalog coordinates
 * are evidence only and are never returned as navigation targets.
 */
final class PostalLocalityCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostalLocalityCatalog.class);
    private static final String RESOURCE = "/postal/us-localities.tsv";
    private static final int MAX_RESOURCE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RECORDS = 100_000;
    private static final double MAX_DISTANCE_KM = 10.0;
    private static final double EARTH_RADIUS_KM = 6_371.0088;
    private static final Pattern ZIP5 = Pattern.compile("[0-9]{5}");
    private static final Pattern STATE2 = Pattern.compile("[A-Z]{2}");

    private static final Catalog DEFAULT_CATALOG = loadDefault();

    private PostalLocalityCatalog() {
    }

    static boolean supports(String zip, String city, String state, double lat, double lon) {
        return DEFAULT_CATALOG.supports(zip, city, state, lat, lon);
    }

    /** Nearby ZIP/state evidence; the caller must independently prove the municipal name. */
    static boolean supportsArea(String zip, String state, double lat, double lon) {
        return DEFAULT_CATALOG.supportsArea(zip, state, lat, lon);
    }

    /** Parses a bounded UTF-8 TSV catalog, rejecting the entire file on any malformed record. */
    static Catalog parse(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Catalog input is required");
        }

        byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
        if (bytes.length > MAX_RESOURCE_BYTES) {
            throw new IllegalArgumentException("Catalog exceeds the byte limit");
        }

        String content = decodeUtf8(bytes);
        Map<LocalityKey, List<Locality>> records = new HashMap<>();
        int recordCount = 0;
        String[] lines = content.split("\n", -1);
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = lines[lineNumber];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (++recordCount > MAX_RECORDS) {
                throw new IllegalArgumentException("Catalog exceeds the record limit");
            }

            String[] fields = line.split("\t", -1);
            if (fields.length != 6) {
                throw malformedRecord(lineNumber);
            }

            String zip = fields[0];
            String state = fields[1];
            String city = fields[2];
            if (!ZIP5.matcher(zip).matches() || !STATE2.matcher(state).matches()
                    || normalize(city).isEmpty() || city.codePoints().anyMatch(Character::isISOControl)) {
                throw malformedRecord(lineNumber);
            }

            double lat;
            double lon;
            int accuracy;
            try {
                lat = Double.parseDouble(fields[3]);
                lon = Double.parseDouble(fields[4]);
                accuracy = Integer.parseInt(fields[5]);
            } catch (NumberFormatException exception) {
                throw malformedRecord(lineNumber);
            }
            if (!validPoint(lat, lon) || (lat == 0.0 && lon == 0.0) || accuracy < 0) {
                throw malformedRecord(lineNumber);
            }

            LocalityKey key = new LocalityKey(zip, normalize(city), normalize(state));
            records.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new Locality(lat, lon, accuracy));
        }

        if (recordCount == 0) {
            throw new IllegalArgumentException("Catalog contains no locality records");
        }

        Map<LocalityKey, List<Locality>> immutableRecords = new HashMap<>();
        records.forEach((key, values) -> immutableRecords.put(key, List.copyOf(values)));
        return new Catalog(Collections.unmodifiableMap(immutableRecords));
    }

    private static Catalog loadDefault() {
        return load(PostalLocalityCatalog.class.getResourceAsStream(RESOURCE));
    }

    static Catalog load(InputStream input) {
        if (input == null) {
            return unavailable();
        }
        try (InputStream resource = input) {
            return parse(resource);
        } catch (IOException | RuntimeException exception) {
            return unavailable();
        }
    }

    private static Catalog unavailable() {
        LOGGER.warn("Postal locality catalog unavailable or invalid; ZIP/locality evidence is disabled");
        return Catalog.EMPTY;
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static IllegalArgumentException malformedRecord(int zeroBasedLineNumber) {
        return new IllegalArgumentException("Malformed catalog record at line " + (zeroBasedLineNumber + 1));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);
        return result.toString();
    }

    private static boolean validPoint(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double latitudeDelta = Math.toRadians(lat2 - lat1);
        double longitudeDelta = Math.toRadians(lon2 - lon1);
        double firstLatitude = Math.toRadians(lat1);
        double secondLatitude = Math.toRadians(lat2);
        double haversine = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(firstLatitude) * Math.cos(secondLatitude)
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        double boundedHaversine = Math.max(0.0, Math.min(1.0, haversine));
        return EARTH_RADIUS_KM * 2.0 * Math.atan2(Math.sqrt(boundedHaversine),
                Math.sqrt(1.0 - boundedHaversine));
    }

    private record LocalityKey(String zip, String city, String state) {
    }

    private record PostalAreaKey(String zip, String state) {
    }

    private record Locality(double lat, double lon, int accuracy) {
    }

    static final class Catalog {
        private static final Catalog EMPTY = new Catalog(Map.of());
        private final Map<LocalityKey, List<Locality>> records;
        private final Map<PostalAreaKey, List<Locality>> areas;

        private Catalog(Map<LocalityKey, List<Locality>> records) {
            this.records = records;
            Map<PostalAreaKey, List<Locality>> indexed = new HashMap<>();
            records.forEach((key, values) -> indexed
                    .computeIfAbsent(new PostalAreaKey(key.zip(), key.state()), ignored -> new ArrayList<>())
                    .addAll(values));
            Map<PostalAreaKey, List<Locality>> immutable = new HashMap<>();
            indexed.forEach((key, values) -> immutable.put(key, values.stream().distinct().toList()));
            this.areas = Collections.unmodifiableMap(immutable);
        }

        boolean supportsArea(String zip, String state, double lat, double lon) {
            String normalizedState = normalize(state);
            if (zip == null || !ZIP5.matcher(zip).matches() || !STATE2.matcher(normalizedState).matches()
                    || !validPoint(lat, lon) || lat == 0.0 && lon == 0.0) return false;
            List<Locality> matches = areas.get(new PostalAreaKey(zip, normalizedState));
            return matches != null && matches.stream().anyMatch(locality -> locality.accuracy() >= 4
                    && distanceKm(lat, lon, locality.lat(), locality.lon()) <= MAX_DISTANCE_KM);
        }

        boolean supports(String zip, String city, String state, double lat, double lon) {
            if (zip == null || !ZIP5.matcher(zip).matches() || !validPoint(lat, lon)
                    || (lat == 0.0 && lon == 0.0)) {
                return false;
            }
            String normalizedCity = normalize(city);
            String normalizedState = normalize(state);
            if (normalizedCity.isEmpty() || normalizedState.isEmpty()) {
                return false;
            }

            List<Locality> matches = records.get(new LocalityKey(zip, normalizedCity, normalizedState));
            if (matches == null) {
                return false;
            }
            return matches.stream().anyMatch(locality -> locality.accuracy() >= 4
                    && distanceKm(lat, lon, locality.lat(), locality.lon()) <= MAX_DISTANCE_KM);
        }
    }
}
