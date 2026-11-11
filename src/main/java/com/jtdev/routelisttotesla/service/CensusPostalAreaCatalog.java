package com.jtdev.routelisttotesla.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded evidence that a typed ZIP is close to the Census ZCTA representative point.
 * This catalog never supplies a geocoding result or navigation coordinate.
 */
final class CensusPostalAreaCatalog {
    static final int MAX_RESOURCE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RECORDS = 100_000;
    private static final int MAX_ROW_CHARS = 128;
    private static final double MAX_DISTANCE_KM = 10.0;
    private static final double EARTH_RADIUS_KM = 6_371.0088;
    private static final String RESOURCE = "/postal/us-zcta-areas.tsv";
    private static final String HEADER = "ZIP\tINTPTLAT\tINTPTLONG";
    private static final Pattern ZIP5 = Pattern.compile("[0-9]{5}");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)");
    private static final Logger LOGGER = LoggerFactory.getLogger(CensusPostalAreaCatalog.class);
    private static final Catalog DEFAULT_CATALOG = loadDefault();

    private CensusPostalAreaCatalog() {
    }

    /** Returns only whether a valid, exact five-digit ZIP is near its Census point. */
    static boolean supports(String zip, double lat, double lon) {
        return DEFAULT_CATALOG.supports(zip, lat, lon);
    }

    /** Parses a bounded UTF-8 TSV resource, rejecting the entire file on any malformed record. */
    static Catalog parse(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Catalog input is required");
        }

        byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
        if (bytes.length > MAX_RESOURCE_BYTES) {
            throw new IllegalArgumentException("Catalog exceeds the byte limit");
        }

        String text = decodeUtf8(bytes);
        if (text.indexOf('\r') >= 0) {
            text = text.replace("\r\n", "\n");
            if (text.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Catalog contains an invalid line ending");
            }
        }

        Map<String, Point> points = new HashMap<>();
        boolean headerSeen = false;
        int declaredRecordCount = -1;
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty() && index == lines.length - 1 && text.endsWith("\n")) {
                continue;
            }
            if (line.isEmpty()) {
                throw malformedRecord(index);
            }
            if (line.startsWith("#")) {
                if (line.startsWith("# row_count=")) {
                    if (declaredRecordCount >= 0 || !line.substring("# row_count=".length()).matches("[0-9]+")) {
                        throw new IllegalArgumentException("Catalog row-count metadata is invalid");
                    }
                    try {
                        declaredRecordCount = Integer.parseInt(line.substring("# row_count=".length()));
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("Catalog row-count metadata is invalid", exception);
                    }
                    if (declaredRecordCount < 1 || declaredRecordCount > MAX_RECORDS) {
                        throw new IllegalArgumentException("Catalog row-count metadata is out of bounds");
                    }
                }
                continue;
            }
            if (!headerSeen) {
                if (!HEADER.equals(line)) {
                    throw new IllegalArgumentException("Catalog schema header is missing or invalid");
                }
                headerSeen = true;
                continue;
            }
            if (line.length() > MAX_ROW_CHARS) {
                throw malformedRecord(index);
            }

            String[] columns = line.split("\t", -1);
            if (columns.length != 3 || !ZIP5.matcher(columns[0]).matches()
                    || !DECIMAL.matcher(columns[1]).matches() || !DECIMAL.matcher(columns[2]).matches()) {
                throw malformedRecord(index);
            }

            double latitude;
            double longitude;
            try {
                latitude = Double.parseDouble(columns[1]);
                longitude = Double.parseDouble(columns[2]);
            } catch (NumberFormatException exception) {
                throw malformedRecord(index);
            }
            if (!validPoint(latitude, longitude) || (latitude == 0.0 && longitude == 0.0)) {
                throw malformedRecord(index);
            }
            if (points.putIfAbsent(columns[0], new Point(latitude, longitude)) != null) {
                throw new IllegalArgumentException("Catalog contains a duplicate ZIP code");
            }
            if (points.size() > MAX_RECORDS) {
                throw new IllegalArgumentException("Catalog exceeds the record limit");
            }
        }

        if (!headerSeen || points.isEmpty() || declaredRecordCount != points.size()) {
            throw new IllegalArgumentException("Catalog is empty, incomplete, or has invalid metadata");
        }
        return new Catalog(Map.copyOf(points));
    }

    /** Loads the supplied resource, disabling this evidence path on any error. */
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

    private static Catalog loadDefault() {
        return load(CensusPostalAreaCatalog.class.getResourceAsStream(RESOURCE));
    }

    private static Catalog unavailable() {
        LOGGER.warn("Census ZCTA catalog unavailable or invalid; ZIP-area evidence is disabled");
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
        return new IllegalArgumentException("Malformed Census ZCTA record at line " + (zeroBasedLineNumber + 1));
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
        double haversine = Math.sin(latitudeDelta / 2.0) * Math.sin(latitudeDelta / 2.0)
                + Math.cos(firstLatitude) * Math.cos(secondLatitude)
                * Math.sin(longitudeDelta / 2.0) * Math.sin(longitudeDelta / 2.0);
        double boundedHaversine = Math.min(1.0, Math.max(0.0, haversine));
        return EARTH_RADIUS_KM * 2.0 * Math.atan2(Math.sqrt(boundedHaversine),
                Math.sqrt(1.0 - boundedHaversine));
    }

    private record Point(double lat, double lon) {
    }

    static final class Catalog {
        private static final Catalog EMPTY = new Catalog(Map.of());
        private final Map<String, Point> points;

        private Catalog(Map<String, Point> points) {
            this.points = points;
        }

        boolean supports(String zip, double lat, double lon) {
            if (zip == null || !ZIP5.matcher(zip).matches() || !validPoint(lat, lon)
                    || (lat == 0.0 && lon == 0.0)) {
                return false;
            }
            Point point = points.get(zip);
            return point != null && distanceKm(lat, lon, point.lat(), point.lon()) <= MAX_DISTANCE_KM;
        }

        int size() {
            return points.size();
        }
    }
}
