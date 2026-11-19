package com.jtdev.routelisttotesla.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Whole English number expressions only; this does not replace OCR lookalike letters. */
public final class StreetNumberWords {
    private StreetNumberWords() { }
    private static final List<String> SMALL = List.of("ZERO", "ONE", "TWO", "THREE", "FOUR", "FIVE",
            "SIX", "SEVEN", "EIGHT", "NINE", "TEN", "ELEVEN", "TWELVE", "THIRTEEN", "FOURTEEN",
            "FIFTEEN", "SIXTEEN", "SEVENTEEN", "EIGHTEEN", "NINETEEN");
    private static final List<String> TENS = List.of("", "", "TWENTY", "THIRTY", "FORTY", "FIFTY",
            "SIXTY", "SEVENTY", "EIGHTY", "NINETY");
    private static final Map<String, String> ORDINALS = Map.ofEntries(
            Map.entry("ZEROTH", "ZERO"), Map.entry("FIRST", "ONE"), Map.entry("SECOND", "TWO"),
            Map.entry("THIRD", "THREE"), Map.entry("FOURTH", "FOUR"), Map.entry("FIFTH", "FIVE"),
            Map.entry("SIXTH", "SIX"), Map.entry("SEVENTH", "SEVEN"), Map.entry("EIGHTH", "EIGHT"),
            Map.entry("NINTH", "NINE"), Map.entry("TENTH", "TEN"), Map.entry("ELEVENTH", "ELEVEN"),
            Map.entry("TWELFTH", "TWELVE"), Map.entry("THIRTEENTH", "THIRTEEN"),
            Map.entry("FOURTEENTH", "FOURTEEN"), Map.entry("FIFTEENTH", "FIFTEEN"),
            Map.entry("SIXTEENTH", "SIXTEEN"), Map.entry("SEVENTEENTH", "SEVENTEEN"),
            Map.entry("EIGHTEENTH", "EIGHTEEN"), Map.entry("NINETEENTH", "NINETEEN"),
            Map.entry("TWENTIETH", "TWENTY"), Map.entry("THIRTIETH", "THIRTY"),
            Map.entry("FORTIETH", "FORTY"), Map.entry("FIFTIETH", "FIFTY"),
            Map.entry("SIXTIETH", "SIXTY"), Map.entry("SEVENTIETH", "SEVENTY"),
            Map.entry("EIGHTIETH", "EIGHTY"), Map.entry("NINETIETH", "NINETY"),
            Map.entry("HUNDREDTH", "HUNDRED"), Map.entry("THOUSANDTH", "THOUSAND"),
            Map.entry("MILLIONTH", "MILLION"));

    /** Callers retain street suffix, direction, address and location validation. */
    public static boolean equivalent(List<String> left, List<String> right) {
        Normalized a = normalize(left), b = normalize(right);
        return !a.forms.isEmpty() && a.words.equals(b.words) && !a.forms.equals(b.forms);
    }

    private record Normalized(List<String> words, List<Boolean> forms) { }
    private record NumberToken(int value, boolean ordinal) {
        String key() { return "@" + (ordinal ? "O" : "C") + value; }
    }

    private static Normalized normalize(List<String> input) {
        List<String> output = new ArrayList<>();
        List<Boolean> forms = new ArrayList<>();
        for (int i = 0; i < input.size();) {
            String token = input.get(i).toUpperCase(Locale.ROOT);
            NumberToken numeric = numeric(token);
            if (numeric != null) {
                output.add(numeric.key()); forms.add(false); i++; continue;
            }
            NumberToken word = null;
            int end = Math.min(input.size(), i + 24);
            for (; end > i; end--) {
                word = words(input.subList(i, end));
                if (word != null) break;
            }
            if (word == null) { output.add(token); i++; }
            else { output.add(word.key()); forms.add(true); i = end; }
        }
        return new Normalized(output, forms);
    }

    private static NumberToken numeric(String token) {
        if (!token.matches("(?:0|[1-9][0-9]{0,8})(?:ST|ND|RD|TH)?")) return null;
        boolean ordinal = Character.isLetter(token.charAt(token.length() - 1));
        String digits = ordinal ? token.substring(0, token.length() - 2) : token;
        int value = Integer.parseInt(digits);
        if (ordinal) {
            int lastTwo = value % 100;
            String suffix = lastTwo >= 11 && lastTwo <= 13 ? "TH" : switch (value % 10) {
                case 1 -> "ST"; case 2 -> "ND"; case 3 -> "RD"; default -> "TH";
            };
            if (!token.endsWith(suffix)) return null;
        }
        return new NumberToken(value, ordinal);
    }

    private static NumberToken words(List<String> source) {
        List<String> tokens = new ArrayList<>(source.stream().map(t -> t.toUpperCase(Locale.ROOT)).toList());
        String last = tokens.getLast();
        boolean ordinal = ORDINALS.containsKey(last);
        if (ordinal) tokens.set(tokens.size() - 1, ORDINALS.get(last));
        int value = scaled(tokens, 1_000_000);
        return value < 0 ? null : new NumberToken(value, ordinal);
    }

    private static int scaled(List<String> tokens, int scale) {
        if (tokens.isEmpty()) return -1;
        if (scale < 1000) return belowThousand(tokens);
        String name = scale == 1_000_000 ? "MILLION" : "THOUSAND";
        int index = tokens.indexOf(name);
        if (index < 0) return scaled(tokens, scale / 1000);
        if (tokens.lastIndexOf(name) != index) return -1;
        int group = index == 0 ? 1 : belowThousand(tokens.subList(0, index));
        if (group < 1 || group > 999) return -1;
        if (index == tokens.size() - 1) return group * scale;
        int tail = scaled(tokens.subList(index + 1, tokens.size()), scale / 1000);
        return tail < 0 || tail >= scale ? -1 : group * scale + tail;
    }

    private static int belowThousand(List<String> tokens) {
        int index = tokens.indexOf("HUNDRED");
        if (index < 0) return belowHundred(tokens);
        if (index > 1 || tokens.lastIndexOf("HUNDRED") != index) return -1;
        int group = index == 0 ? 1 : SMALL.indexOf(tokens.getFirst());
        if (group < 1 || group > 9) return -1;
        int start = index + 1;
        if (start == tokens.size()) return group * 100;
        if (tokens.get(start).equals("AND")) start++;
        int tail = belowHundred(tokens.subList(start, tokens.size()));
        return tail < 0 ? -1 : group * 100 + tail;
    }

    private static int belowHundred(List<String> tokens) {
        if (tokens.size() == 1) {
            int small = SMALL.indexOf(tokens.getFirst());
            if (small >= 0) return small;
            int tens = TENS.indexOf(tokens.getFirst());
            return tens >= 2 ? tens * 10 : -1;
        }
        if (tokens.size() != 2) return -1;
        int tens = TENS.indexOf(tokens.getFirst()), units = SMALL.indexOf(tokens.getLast());
        return tens >= 2 && units >= 1 && units <= 9 ? tens * 10 + units : -1;
    }
}
