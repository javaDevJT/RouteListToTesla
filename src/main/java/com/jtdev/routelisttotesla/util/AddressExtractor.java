package com.jtdev.routelisttotesla.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class AddressExtractor {
    private static final String STREET_TYPES = "(?:ALLEY|ALLY|ALY|ANEX|ANNEX|ANNX|ARC|ARCADE|AV|AVE|AVENUE|BAYOO|BAYOU|BEACH|BEND|BLUFF|BLF|BLUFFS|BOT|BOTTM|BOTTOM|BOULEVARD|BLVD|BRANCH|BR|BRNCH|BRIDGE|BRG|BROOK|BRK|BROOKS|BURG|BG|BURGS|BYPASS|BYP|BYPA|BYPAS|BYPS|CAMP|CP|CMP|CANYON|CYN|CAPE|CAUSEWAY|CAUSWA|CSWY|CENTER|CENT|CEN|CNTER|CNTR|CTR|CENTERS|CIR|CIRC|CIRCL|CIRCLE|CRCL|CRCLE|CIRCLES|CLF|CLIFF|CLFS|CLIFFS|CLUB|COMMON|COMMONS|CORNER|COR|CORNERS|CORS|COURSE|CRSE|COURT|CT|COURTS|CTS|COVE|CV|COVES|CREEK|CRK|CRESCENT|CRES|CRSENT|CRSNT|CREST|CROSSING|XING|CRSSNG|CROSSROAD|CROSSROADS|CURVE|DALE|DL|DAM|DM|DIVIDE|DV|DVD|DRIVE|DR|DRIVES|DRS|ESTATE|EST|ESTATES|ESTS|EXPRESSWAY|EXPW|EXPY|EXTENSION|EXT|EXTN|EXTNSN|FALL|FALLS|FLS|FERRY|FRRY|FIELD|FLD|FIELDS|FLDS|FLAT|FLT|FLATS|FLTS|FORD|FRD|FORDS|FOREST|FRST|FORGE|FRG|FORGES|FRGS|FORK|FRK|FORKS|FRKS|FORT|FT|FREEWAY|FWY|GARDEN|GARDN|GRDEN|GRDN|GARDENS|GDNS|GATEWAY|GATEWY|GATWAY|GTWAY|GTWY|GLEN|GLN|GLENS|GREEN|GRN|GREENS|GROVE|GRV|GROVES|HARBOR|HBR|HARBR|HRBOR|HARBORS|HAVEN|HVN|HEIGHTS|HTS|HIGHWAY|HWY|HILL|HL|HILLS|HLS|HOLLOW|HOLW|HOLLOWS|HOLWS|INLET|INLT|ISLAND|IS|ISLND|ISLANDS|ISS|ISLS|JUNCTION|JCT|JCTION|JCTN|JUNCTN|JUNCTON|JUNCTIONS|JCTS|KEY|KY|KEYS|KYS|KNOLL|KNL|KNOLLS|KNLS|LAKE|LK|LAKES|LKS|LAND|LANDING|LNDG|LNDNG|LANE|LN|LIGHT|LGT|LIGHTS|LGTS|LOAF|LF|LOCK|LCK|LOCKS|LCKS|LODGE|LDG|LDGE|LODG|LOOP|LOOPS|MALL|MANOR|MNR|MANORS|MNRS|MEADOW|MDW|MDWS|MEADOWS|MEDOWS|MEWS|MILL|ML|MILLS|MLS|MISSION|MISSN|MSSN|MOTORWAY|MTWY|MOUNT|MT|MOUNTAIN|MTN|MNTN|MOUNTAINS|MTNS|NECK|NCK|ORCHARD|ORCHRD|OVAL|OVL|OVERPASS|PARK|PARKS|PARKWAY|PKWY|PARKWAYS|PKWYS|PASS|PASSAGE|PATH|PIKE|PIKES|PINE|PNE|PINES|PNES|PLACE|PL|PLAIN|PLN|PLAINS|PLNS|PLAZA|PLZ|PLZA|POINT|PT|POINTS|PTS|PORT|PRT|PORTS|PRTS|PRAIRIE|PR|PRR|RADIAL|RADIEL|RADL|RAMP|RANCH|RNCH|RANCHES|RNCHS|RAPID|RPD|RAPIDS|RPDS|REST|RST|RIDGE|RDG|RDGE|RIDGES|RDGS|RIVER|RIV|RVR|ROAD|RD|ROADS|RDS|ROUTE|RTE|ROW|RUE|RUN|SHOAL|SHL|SHOALS|SHLS|SHORE|SHR|SHORES|SHRS|SKYWAY|SPG|SPNG|SPRING|SPRNG|SPRINGS|SPGS|SPUR|SPURS|SQUARE|SQ|SQR|SQRE|SQU|SQUARES|STA|STATION|STATN|STN|STRAVENUE|STRAV|STRAVEN|STRAVN|STRVN|STRVNUE|STREAM|STRM|STREET|ST|STREETS|STS|SUMMIT|SMT|TERRACE|TER|TERR|THROUGHWAY|TRWY|TRACE|TRACES|TRACK|TRACKS|TRAFFICWAY|TRFY|TRAIL|TRL|TRAILER|TRLR|TRLRS|TUNNEL|TUNL|TUNLS|TUNNL|TURNPIKE|TPKE|UNDERPASS|UN|UNP|UNPS|UNION|UNIONS|VALLEY|VALLY|VLLY|VALLEYS|VLYS|VIADUCT|VIA|VIADCT|VIADUCT|VIEW|VW|VIEWS|VWS|VILLAGE|VILL|VILLAG|VILLG|VILLIAGE|VLG|VILLAGES|VLGS|VILLE|VL|VISTA|VIS|VST|VSTA|WALK|WALKS|WALL|WAY|WAYS|WELL|WL|WELLS|WLS)";

    // Search for a street address anywhere in the line (captures the canonical street starting at house number)
    private static final Pattern STREET_SEARCH = Pattern.compile(
            "(?i)(\\d{1,6}[A-Za-z]?\\s+[A-Za-z0-9' .-]+\\s+(?:" + STREET_TYPES + ")(?:\\.)?(?:\\s+#?[A-Za-z0-9-]+|\\s+(?:APT|UNIT|STE|SUITE)\\s+[A-Za-z0-9-]+)?)");

    // Strict whole-line street matcher (unused in main flow but kept for reference)
    private static final Pattern STREET_LINE = Pattern.compile(
            "(?i)^\\s*\\d{1,6}[A-Za-z]?\\s+[A-Za-z0-9' .-]+\\s+(?:" + STREET_TYPES + ")(?:\\.)?(?:\\s+#?[A-Za-z0-9-]+|\\s+(?:APT|UNIT|STE|SUITE)\\s+[A-Za-z0-9-]+)?\\s*$");

    private static final Pattern CITY_LINE = Pattern.compile("(?i)^\\s*[A-Za-z][A-Za-z\\s.-]{1,40}$");

    public static List<String> addressLinesFromPlainJoined(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("[\\r\\n]+")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            lines.add(line);
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String a = lines.get(i);
            java.util.regex.Matcher m = STREET_SEARCH.matcher(a);
            if (!m.find()) continue;
            String street = m.group(1).trim();
            String candidate = street;
            if (i + 1 < lines.size() && CITY_LINE.matcher(lines.get(i + 1)).matches()) {
                candidate = candidate + ", " + lines.get(i + 1);
                i++; // consume city/state line
            }

            out.add(candidate);
        }
        return out;
    }

    public static String normalize(String input) {
        String s = input;
        s = s.toUpperCase();
        s = s.replaceAll("[,]+", " ");
        s = s.replaceAll("[\\s]+", " ");

        // Drop common UI-leading tokens before the house number, e.g., "Z/ ", "N / "
        s = s.replaceFirst("(?i)^(?:[A-Z]{1,3}\\s*[\\/:|>-]\\s*)+(?=\\d)", "");
        s = s.replaceFirst("^(?:(?!\\d).){1,4}(?=\\d)", "");

        s = s.replaceAll("[\\s]+", " ").trim();
        return s;
    }
}