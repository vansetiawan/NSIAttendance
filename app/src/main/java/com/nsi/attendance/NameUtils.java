package com.nsi.attendance;

import java.util.*;
import java.util.regex.Pattern;

public final class NameUtils {
    private NameUtils() {}

    private static final Set<String> LOWER_PARTICLES = new HashSet<>(Arrays.asList(
            "bin","binti","van","von","de","da","di","al","af","abu","ibnu"
    ));
    private static final Pattern ROMAN = Pattern.compile("(?i)^(?=.)M{0,4}(CM|CD|D?C{0,3})"
            + "(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$");

    public static String toProperName(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return "";

        String[] tokens = s.split(" ");
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];

            // jika token mengandung tanda hubung, proses per sub
            if (t.contains("-")) {
                String[] subs = t.split("-");
                for (int j = 0; j < subs.length; j++) {
                    out.append(formatCore(subs[j], i == 0)); // aware posisi kata pertama
                    if (j < subs.length - 1) out.append("-");
                }
            } else {
                out.append(formatCore(t, i == 0));
            }

            if (i < tokens.length - 1) out.append(" ");
        }
        return out.toString();
    }

    private static String formatCore(String token, boolean isFirstWord) {
        if (token.isEmpty()) return token;

        // pertahankan singkatan yang bertitik (S.T., M.Kom., S.Si., dkk)
        if (token.contains(".") && token.matches(".*[A-Za-z].*")) {
            return token.toUpperCase();
        }
        // angka Romawi (I, II, III, IV, V, VI, VII, VIII, IX, X, dst)
        if (ROMAN.matcher(token).matches()) {
            return token.toUpperCase();
        }
        // partikel kecil (kecuali di awal nama)
        String lower = token.toLowerCase(Locale.ROOT);
        if (!isFirstWord && LOWER_PARTICLES.contains(lower)) {
            return lower;
        }

        // tangani apostrof: O'neill -> O'Neill
        if (token.contains("'")) {
            String[] parts = token.split("'");
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < parts.length; k++) {
                sb.append(capFirst(parts[k]));
                if (k < parts.length - 1) sb.append("'");
            }
            return sb.toString();
        }

        // default: kapital huruf pertama, lainnya kecil
        return capFirst(token);
    }

    private static String capFirst(String s) {
        if (s.isEmpty()) return s;
        String lower = s.toLowerCase(Locale.ROOT);
        int firstLetter = 0;
        // lewati non-huruf di depan (jaga kasus seperti “(andi)”)
        while (firstLetter < lower.length() && !Character.isLetter(lower.charAt(firstLetter))) {
            firstLetter++;
        }
        if (firstLetter >= lower.length()) return lower;
        return lower.substring(0, firstLetter)
                + Character.toUpperCase(lower.charAt(firstLetter))
                + (firstLetter + 1 < lower.length() ? lower.substring(firstLetter + 1) : "");
    }
}
