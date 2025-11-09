package com.jtdev.routelisttotesla.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class AddressExtractor {
    private static final String STREET_TYPES = "(?:ALLEY|ALLY|ALY|ANEX|ANNEX|ANNX|ARC|ARCADE|AV|AVE|AVENUE|BAYOO|BAYOU|BEACH|BEND|BLUFF|BLF|BLUFFS|BOT|BOTTM|BOTTOM|BOULEVARD|BLVD|BRANCH|BR|BRNCH|BRIDGE|BRG|BROOK|BRK|BROOKS|BURG|BG|BURGS|BYPASS|BYP|BYPA|BYPAS|BYPS|CAMP|CP|CMP|CANYON|CYN|CAPE|CAUSEWAY|CAUSWA|CSWY|CENTER|CENT|CEN|CNTER|CNTR|CTR|CENTERS|CIR|CIRC|CIRCL|CIRCLE|CRCL|CRCLE|CIRCLES|CLF|CLIFF|CLFS|CLIFFS|CLUB|COMMON|COMMONS|CORNER|COR|CORNERS|CORS|COURSE|CRSE|COURT|CT|COURTS|CTS|COVE|CV|COVES|CREEK|CRK|CRESCENT|CRES|CRSENT|CRSNT|CREST|CROSSING|XING|CRSSNG|CROSSROAD|CROSSROADS|CURVE|DALE|DL|DAM|DM|DIVIDE|DV|DVD|DRIVE|DR|DRIVES|DRS|ESTATE|EST|ESTATES|ESTS|EXPRESSWAY|EXPW|EXPY|EXTENSION|EXT|EXTN|EXTNSN|FALL|FALLS|FLS|FERRY|FRRY|FIELD|FLD|FIELDS|FLDS|FLAT|FLT|FLATS|FLTS|FORD|FRD|FORDS|FOREST|FRST|FORGE|FRG|FORGES|FRGS|FORK|FRK|FORKS|FRKS|FORT|FT|FREEWAY|FWY|GARDEN|GARDN|GRDEN|GRDN|GARDENS|GDNS|GATEWAY|GATEWY|GATWAY|GTWAY|GTWY|GLEN|GLN|GLENS|GREEN|GRN|GREENS|GROVE|GRV|GROVES|HARBOR|HBR|HARBR|HRBOR|HARBORS|HAVEN|HVN|HEIGHTS|HTS|HIGHWAY|HWY|HILL|HL|HILLS|HLS|HOLLOW|HOLW|HOLLOWS|HOLWS|INLET|INLT|ISLAND|IS|ISLND|ISLANDS|ISS|ISLS|JUNCTION|JCT|JCTION|JCTN|JUNCTN|JUNCTON|JUNCTIONS|JCTS|KEY|KY|KEYS|KYS|KNOLL|KNL|KNOLLS|KNLS|LAKE|LK|LAKES|LKS|LAND|LANDING|LNDG|LNDNG|LANE|LN|LIGHT|LGT|LIGHTS|LGTS|LOAF|LF|LOCK|LCK|LOCKS|LCKS|LODGE|LDG|LDGE|LODG|LOOP|LOOPS|MALL|MANOR|MNR|MANORS|MNRS|MEADOW|MDW|MDWS|MEADOWS|MEDOWS|MEWS|MILL|ML|MILLS|MLS|MISSION|MISSN|MSSN|MOTORWAY|MTWY|MOUNT|MT|MOUNTAIN|MTN|MNTN|MOUNTAINS|MTNS|NECK|NCK|ORCHARD|ORCHRD|OVAL|OVL|OVERPASS|PARK|PARKS|PARKWAY|PKWY|PARKWAYS|PKWYS|PASS|PASSAGE|PATH|PIKE|PIKES|PINE|PNE|PINES|PNES|PLACE|PL|PLAIN|PLN|PLAINS|PLNS|PLAZA|PLZ|PLZA|POINT|PT|POINTS|PTS|PORT|PRT|PORTS|PRTS|PRAIRIE|PR|PRR|RADIAL|RADIEL|RADL|RAMP|RANCH|RNCH|RANCHES|RNCHS|RAPID|RPD|RAPIDS|RPDS|REST|RST|RIDGE|RDG|RDGE|RIDGES|RDGS|RIVER|RIV|RVR|ROAD|RD|ROADS|RDS|ROUTE|RTE|ROW|RUE|RUN|SHOAL|SHL|SHOALS|SHLS|SHORE|SHR|SHORES|SHRS|SKYWAY|SPG|SPNG|SPRING|SPRNG|SPRINGS|SPGS|SPUR|SPURS|SQUARE|SQ|SQR|SQRE|SQU|SQUARES|STA|STATION|STATN|STN|STRAVENUE|STRAV|STRAVEN|STRAVN|STRVN|STRVNUE|STREAM|STRM|STREET|ST|STREETS|STS|SUMMIT|SMT|TERRACE|TER|TERR|THROUGHWAY|TRWY|TRACE|TRACES|TRACK|TRACKS|TRAFFICWAY|TRFY|TRAIL|TRL|TRAILER|TRLR|TRLRS|TUNNEL|TUNL|TUNLS|TUNNL|TURNPIKE|TPKE|UNDERPASS|UN|UNP|UNPS|UNION|UNIONS|VALLEY|VALLY|VLLY|VALLEYS|VLYS|VIADUCT|VIA|VIADCT|VIADUCT|VIEW|VW|VIEWS|VWS|VILLAGE|VILL|VILLAG|VILLG|VILLIAGE|VLG|VILLAGES|VLGS|VILLE|VL|VISTA|VIS|VST|VSTA|WALK|WALKS|WALL|WAY|WAYS|WELL|WL|WELLS|WLS)";

    // Search for a street address anywhere in the line (captures the canonical street starting at house number)
    // Use greedy matching to capture as much as possible on one line
    private static final Pattern STREET_SEARCH = Pattern.compile(
            "(?i)(\\d{1,6}[A-Za-z]?\\s+[A-Za-z0-9' .-]+?)\\s+((?:" + STREET_TYPES + ")(?:\\.)?)(.*)");

    // Strict whole-line street matcher (unused in main flow but kept for reference)
    private static final Pattern STREET_LINE = Pattern.compile(
            "(?i)^\\s*\\d{1,6}[A-Za-z]?\\s+[A-Za-z0-9' .-]+\\s+(?:" + STREET_TYPES + ")(?:\\.)?(?:\\s+#?[A-Za-z0-9\\s-]+|\\s+(?:APT|UNIT|STE|SUITE)\\s+[A-Za-z0-9\\s-]+)?\\s*$");

    private static final Pattern CITY_LINE = Pattern.compile("(?i)^\\s*[A-Za-z][A-Za-z\\s.-]{1,40}$");

    private static final Pattern STATE_PATTERN = Pattern.compile("\\b(AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY)\\b$", Pattern.CASE_INSENSITIVE);

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
            java.util.regex.Matcher m = STREET_SEARCH.matcher(a);
            
            if (!m.find()) continue;
            
            // Group 1: house number + street name, Group 2: street type, Group 3: everything after (unit info)
            String streetBase = m.group(1).trim();
            String streetType = m.group(2).trim(); 
            String unitInfo = m.group(3).trim();
            
            String street = streetBase + " " + streetType;
            if (!unitInfo.isEmpty()) {
                street = street + " " + unitInfo;
            }
            
            // Check if this line looks incomplete (ends with partial unit/apt info)
            // and try to merge with next line if it seems to continue the address
            if (i + 1 < lines.size()) {
                String nextLine = lines.get(i + 1).trim();
                
                // If street ends with incomplete unit indicators, try to merge
                if (street.endsWith(" UN") && nextLine.matches("^IT\\s+\\d+.*")) {
                    street = street + nextLine; // "UN" + "IT 2315" -> "UNIT 2315"
                    i++; // consume next line
                } else if (street.endsWith(" APT") && nextLine.matches("^\\d+.*")) {
                    street = street + " " + nextLine; // "APT" + "312" -> "APT 312"
                    i++; // consume next line
                } else if (street.matches(".*\\b(UNIT|STE|SUITE)$") && nextLine.matches("^\\d+.*")) {
                    street = street + " " + nextLine; // Add apartment/unit number
                    i++; // consume next line
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
        
        // Fix cases where street name gets run together with street type (OCR issue)
        // "CODERD" -> "CODE RD", "MAINST" -> "MAIN ST", etc.
        s = s.replaceAll("([A-Z]+)(RD|ST|DR|LN|CT|AVE|BLVD|WAY|PL)\\b", "$1 $2");

        s = s.replaceAll("[\\s]+", " ").trim();
        return s;
    }
}