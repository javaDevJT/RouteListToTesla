package com.jtdev.routelisttotesla.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AddressExtractor {
    public static final String UNIT_LABELS = "(?:APT|APARTMENT|UNIT|STE|SUITE|BLDG|BUILDING|FLOOR|ROOM|RM|DEPT|#)";
    private static final String STREET_TYPES = "(?:ALLEY|ALLY|ALY|ANEX|ANNEX|ANNX|ARC|ARCADE|AV|AVE|AVENUE|BAYOO|BAYOU|BEACH|BEND|BLUFF|BLF|BLUFFS|BOT|BOTTM|BOTTOM|BOULEVARD|BLVD|BRANCH|BR|BRNCH|BRIDGE|BRG|BROOK|BRK|BROOKS|BURG|BG|BURGS|BYPASS|BYP|BYPA|BYPAS|BYPS|CAMP|CP|CMP|CANYON|CYN|CAPE|CAUSEWAY|CAUSWA|CSWY|CENTER|CENT|CEN|CNTER|CNTR|CTR|CENTERS|CIR|CIRC|CIRCL|CIRCLE|CRCL|CRCLE|CIRCLES|CLF|CLIFF|CLFS|CLIFFS|CLUB|COMMON|COMMONS|CORNER|COR|CORNERS|CORS|COURSE|CRSE|COURT|CT|COURTS|CTS|COVE|CV|COVES|CREEK|CRK|CRESCENT|CRES|CRSENT|CRSNT|CREST|CROSSING|XING|CRSSNG|CROSSROAD|CROSSROADS|CURVE|DALE|DL|DAM|DM|DIVIDE|DV|DVD|DRIVE|DR|DRIVES|DRS|ESTATE|EST|ESTATES|ESTS|EXPRESSWAY|EXPW|EXPY|EXTENSION|EXT|EXTN|EXTNSN|FALL|FALLS|FLS|FERRY|FRRY|FIELD|FLD|FIELDS|FLDS|FLAT|FLT|FLATS|FLTS|FORD|FRD|FORDS|FOREST|FRST|FORGE|FRG|FORGES|FRGS|FORK|FRK|FORKS|FRKS|FORT|FT|FREEWAY|FWY|GARDEN|GARDN|GRDEN|GRDN|GARDENS|GDNS|GATEWAY|GATEWY|GATWAY|GTWAY|GTWY|GLEN|GLN|GLENS|GREEN|GRN|GREENS|GROVE|GRV|GROVES|HARBOR|HBR|HARBR|HRBOR|HARBORS|HAVEN|HVN|HEIGHTS|HTS|HIGHWAY|HWY|HILL|HL|HILLS|HLS|HOLLOW|HOLW|HOLLOWS|HOLWS|INLET|INLT|ISLAND|IS|ISLND|ISLANDS|ISS|ISLS|JUNCTION|JCT|JCTION|JCTN|JUNCTN|JUNCTON|JUNCTIONS|JCTS|KEY|KY|KEYS|KYS|KNOLL|KNL|KNOLLS|KNLS|LAKE|LK|LAKES|LKS|LAND|LANDING|LNDG|LNDNG|LANE|LN|LIGHT|LGT|LIGHTS|LGTS|LOAF|LF|LOCK|LCK|LOCKS|LCKS|LODGE|LDG|LDGE|LODG|LOOP|LOOPS|MALL|MANOR|MNR|MANORS|MNRS|MEADOW|MDW|MDWS|MEADOWS|MEDOWS|MEWS|MILL|ML|MILLS|MLS|MISSION|MISSN|MSSN|MOTORWAY|MTWY|MOUNT|MT|MOUNTAIN|MTN|MNTN|MOUNTAINS|MTNS|NECK|NCK|ORCHARD|ORCHRD|OVAL|OVL|OVERPASS|PARK|PARKS|PARKWAY|PKWY|PARKWAYS|PKWYS|PASS|PASSAGE|PATH|PIKE|PIKES|PINE|PNE|PINES|PNES|PLACE|PL|PLAIN|PLN|PLAINS|PLNS|PLAZA|PLZ|PLZA|POINT|PT|POINTS|PTS|PORT|PRT|PORTS|PRTS|PRAIRIE|PR|PRR|RADIAL|RADIEL|RADL|RAMP|RANCH|RNCH|RANCHES|RNCHS|RAPID|RPD|RAPIDS|RPDS|REST|RST|RIDGE|RDG|RDGE|RIDGES|RDGS|RIVER|RIV|RVR|ROAD|RD|ROADS|RDS|ROUTE|RTE|ROW|RUE|RUN|SHOAL|SHL|SHOALS|SHLS|SHORE|SHR|SHORES|SHRS|SKYWAY|SPG|SPNG|SPRING|SPRNG|SPRINGS|SPGS|SPUR|SPURS|SQUARE|SQ|SQR|SQRE|SQU|SQUARES|STA|STATION|STATN|STN|STRAVENUE|STRAV|STRAVEN|STRAVN|STRVN|STRVNUE|STREAM|STRM|STREET|ST|STREETS|STS|SUMMIT|SMT|TERRACE|TER|TERR|THROUGHWAY|TRWY|TRACE|TRACES|TRACK|TRACKS|TRAFFICWAY|TRFY|TRAIL|TRL|TRAILER|TRLR|TRLRS|TUNNEL|TUNL|TUNLS|TUNNL|TURNPIKE|TPKE|UNDERPASS|UN|UNP|UNPS|UNION|UNIONS|VALLEY|VALLY|VLLY|VALLEYS|VLYS|VIADUCT|VIA|VIADCT|VIADUCT|VIEW|VW|VIEWS|VWS|VILLAGE|VILL|VILLAG|VILLG|VILLIAGE|VLG|VILLAGES|VLGS|VILLE|VL|VISTA|VIS|VST|VSTA|WALK|WALKS|WALL|WAY|WAYS|WELL|WL|WELLS|WLS)";

    private static final String FRACTION_GLYPH = "[¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞]";
    private static final String HOUSE_NUMBER_TEXT = "(?:\\d{1,6}[A-Za-z]?(?:-\\d{1,6}[A-Za-z]?)?(?:\\s+\\d+/\\d+|" + FRACTION_GLYPH + ")?)";
    private static final String STATE_CODES = "(?:AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY|PR|GU|VI|AS|MP)";

    private static final Pattern STREET_SEARCH = Pattern.compile(
            "(?i)(" + HOUSE_NUMBER_TEXT + "\\s+[\\p{L}\\p{M}\\p{N}'’ .-]+?)\\s+((?:" + STREET_TYPES + ")(?:\\.)?)(.*)");

    private static final Pattern STREET_TYPE_TOKEN = Pattern.compile(
            "(?i)(?:^|\\s)(?:" + STREET_TYPES + ")(?:\\.)?(?=\\s|,|$)");
    private static final Pattern STREET_TYPE_SUFFIX = Pattern.compile(
            "(?i)(?:^|\\s)(?:" + STREET_TYPES + ")(?:\\.)?\\s*$");
    private static final Pattern HOUSE_NUMBER = Pattern.compile(
            "(?i)^(?=[^\\s]*[0-9])(?:[0-9OQDIILSZB]{2,}(?:-[0-9A-Z]{1,6})?|[0-9][A-Z]?(?:-[0-9A-Z]{1,6})?)(?:" + FRACTION_GLYPH + ")?$");
    private static final Pattern LEADING_OCR_DECORATION = Pattern.compile("^(?:[\\p{P}\\p{S}]\\s*)+(?=\\d)");
    private static final Pattern COMPACT_DIRECTIONAL_MILE_ROAD = Pattern.compile(
            "(?i)^(\\d{1,6})\\s*([NSEW])\\s*(\\d{1,2})(?=\\s*MILE\\b)");
    private static final Pattern COMPACT_DIRECTIONAL_PREFIX = Pattern.compile("(?i)^(\\d{1,6})([NSEW])(?=\\s|$)");
    private static final Pattern COMPACT_FRACTION = Pattern.compile("^(\\d{2,7})(\\d/\\d+)(?=\\s|$)");
    private static final Pattern ROUTE_UI_TEXT = Pattern.compile(
            "(?i)\\b(?:STOPS?|COMPLETED|PACKAGES?|PICKUP|SCHEDULED|DELIVER(?:Y|ING)?|ITINERARY|MPH|VOLTE\\d*|CBBLTE)\\b|\\d\\s*%");
    private static final Pattern LEADING_CLOCK = Pattern.compile("^\\d{1,2}\\s*[:.]\\s*\\d{2}(?!\\d)");
    private static final Pattern CLOCK_FRAGMENT = Pattern.compile("(?i)^\\d{1,2}\\s*[AP]\\.?M\\.?$");
    private static final Pattern UNIT_LINE = Pattern.compile("(?i)^" + UNIT_LABELS + "\\s*#?\\s*[\\p{L}\\p{N}-]*\\s*$");
    private static final Pattern UNIT_MARKER_END = Pattern.compile("(?i)(?:^|\\s)" + UNIT_LABELS + "\\s*$");
    private static final Pattern UNIT_VALUE = Pattern.compile("(?i)^#?[\\p{L}\\p{N}][\\p{L}\\p{N}-]*$");
    private static final Pattern UNIT_SUFFIX = Pattern.compile(
            "(?i)(?:^|\\s)" + UNIT_LABELS + "\\s*#?\\s*[\\p{L}\\p{N}-]+\\s*$");
    private static final Pattern CITY_LINE = Pattern.compile(
            "(?i)^\\s*[\\p{L}][\\p{L}\\p{M} .’'-]{1,48}(?:,?\\s+" + STATE_CODES + ")?(?:,?\\s+\\d{5}(?:-\\d{4})?)?\\s*$");

    private static final Pattern STATE_PATTERN = Pattern.compile(
            "(?i)(?:^|[,\\s])" + STATE_CODES + "(?:[,\\s]+\\d{5}(?:-\\d{4})?)?\\s*$");

    public static List<String> addressLinesFromPlainJoined(String text) {
        return addressLinesFromPlainJoined(text, "");
    }

    public static List<String> addressLinesFromPlainJoined(String text, String defaultState) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("[\\r\\n]+")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            lines.add(line);
        }
        
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String a = lines.get(i);
            String addressText = addressCandidateText(a);
            if (addressText == null) continue;
            java.util.regex.Matcher m = STREET_SEARCH.matcher(addressText);
            
            if (!m.find()) continue;
            
            String street = (m.group(1) + " " + m.group(2) + m.group(3)).trim();

            while (i + 1 < lines.size()) {
                String nextLine = lines.get(i + 1).trim();
            if (street.endsWith(" UN") && nextLine.matches("(?i)^IT\\s+\\d+.*")) {
                String unitNumber = nextLine.replaceFirst("(?i)^IT\\s+", "");
                street = street.substring(0, street.length() - 3) + " UNIT " + unitNumber;
                i++;
            } else if (continuesStreet(street, nextLine) || UNIT_LINE.matcher(nextLine).matches()
                    || (UNIT_MARKER_END.matcher(street).find() && UNIT_VALUE.matcher(nextLine).matches())
                    ) {
                street += " " + nextLine;
                i++;
                } else {
                    break;
                }
            }
            
            String candidate = street;

            // Look for city line after address (potentially after merged line)
            if (i + 1 < lines.size() && CITY_LINE.matcher(lines.get(i + 1)).matches()) {
            candidate = candidate + ", " + lines.get(i + 1);
            i++; // consume city/state line
            }

            // Append default state if no state found and defaultState provided
            if (defaultState != null && !defaultState.trim().isEmpty() && !STATE_PATTERN.matcher(candidate).find()) {
                candidate = candidate + ", " + defaultState.trim();
            }

            out.add(candidate);
        }
        return out;
    }

    public static String addressCandidateText(String text) {
        if (text == null || text.isBlank()) return null;
        String candidateText = LEADING_OCR_DECORATION.matcher(text.trim()).replaceFirst("");
        if (LEADING_CLOCK.matcher(candidateText).find()) return null;
        Matcher compactDirectional = COMPACT_DIRECTIONAL_MILE_ROAD.matcher(candidateText);
        if (compactDirectional.find() && containsStreetType(candidateText)) {
            candidateText = compactDirectional.replaceFirst("$1 $2 $3 ");
        } else {
            Matcher attachedDirectional = COMPACT_DIRECTIONAL_PREFIX.matcher(candidateText);
            if (attachedDirectional.find() && containsStreetType(candidateText)) {
                candidateText = attachedDirectional.replaceFirst("$1 $2");
            }
        }
        Matcher compactFraction = COMPACT_FRACTION.matcher(candidateText);
        if (compactFraction.find()) {
            candidateText = compactFraction.replaceFirst("$1 $2");
        }
        String[] words = candidateText.trim().split("\\s+");
        int searchEnd = Math.min(words.length - 1, 3);
        for (int index = 0; index <= searchEnd; index++) {
            String word = words[index];
            if (word.matches("\\d{1,2}[.)]")) continue;
            String houseNumber = word.replaceFirst("[,.;]$", "").toUpperCase(java.util.Locale.ROOT);
            if (!HOUSE_NUMBER.matcher(houseNumber).matches()) continue;
            if (index > 0 && words[index - 1].matches(
                    "(?i)(?:" + UNIT_LABELS + "|LOCKER|DOOR|SECTION|BAY)")) continue;

            boolean singleDigit = houseNumber.matches("\\d");
            if (singleDigit && index + 1 < words.length
                    && HOUSE_NUMBER.matcher(words[index + 1].replaceFirst("[,.;]$", "").toUpperCase(java.util.Locale.ROOT)).matches()) {
                continue;
            }

            String candidate = String.join(" ", java.util.Arrays.copyOfRange(words, index, words.length));
            // Split OCR clocks can leave only the minute and meridiem, e.g. "10 PM".
            if (CLOCK_FRAGMENT.matcher(candidate).matches()) continue;
            boolean hasLettersAfterNumber = candidate.substring(word.length()).chars().anyMatch(Character::isLetter);
            if (ROUTE_UI_TEXT.matcher(candidate).find() && !containsStreetType(candidate)) {
                continue;
            }
            if (hasLettersAfterNumber && (!singleDigit || containsStreetType(candidate))) return candidate;
        }
        return null;
    }

    public static boolean containsStreetType(String text) {
        return text != null && STREET_TYPE_TOKEN.matcher(text).find();
    }

    public static boolean endsWithStreetType(String text) {
        return text != null && STREET_TYPE_SUFFIX.matcher(text).find();
    }

    public static boolean continuesStreet(String address, String nextLine) {
        // A name may itself end in a suffix word, e.g. MEADOWS, before BOULEVARD APT 12B wraps.
        return addressCandidateText(nextLine) == null
                && ((!containsStreetType(address) && endsWithStreetType(nextLine))
                || (containsStreetType(nextLine) && UNIT_SUFFIX.matcher(nextLine).find()));
    }

    public static String normalize(String input) {
        String s = input;
        s = s.toUpperCase();
        s = s.replaceAll("[,]+", " ");
        s = s.replaceAll("[\\s]+", " ");

        // Drop common UI-leading tokens before the house number, e.g., "Z/ ", "N / "
        s = s.replaceFirst("(?i)^(?:[A-Z]{1,3}\\s*[\\/:|>-]\\s*)+(?=\\d)", "");
        s = s.replaceFirst("^(?:(?!\\d).){1,4}(?=\\d)", "");
        
        // Remove leading stop numbers (1-2 digits) followed by spaces before house numbers
        // Pattern: "2      23673 VILLAGE HOUSE" or "7 22347 ESSEX" -> "23673 VILLAGE HOUSE" / "22347 ESSEX"
        s = s.replaceFirst("^\\d{1,2}\\s+(?=\\d{3,})", "");
        
        // Fix common street abbreviation spacing issues from OCR
        s = s.replaceAll("\\bAV E\\b", "AVE");
        s = s.replaceAll("\\bST E\\b", "STE"); 
        s = s.replaceAll("\\bDR E\\b", "DRE");  // Less common but possible
        s = s.replaceAll("\\bLN E\\b", "LNE");  // Less common but possible
        
        // Keep ambiguous run-on endings intact; guessing can corrupt valid street names.

        s = s.replaceAll("[\\s]+", " ").trim();
        return s;
    }
}
