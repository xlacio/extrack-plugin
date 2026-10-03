package ru.extrack.plugin.util;

import java.util.Locale;

public final class Commands {

    private Commands() {
    }

    /** "/LiteBans:Ban" and "ban" both give "ban". */
    public static String label(String token) {
        String label = token.startsWith("/") ? token.substring(1) : token;
        int colon = label.lastIndexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        return label.toLowerCase(Locale.ROOT);
    }

    /** Replaces arguments with asterisks, {@code keep} leading arguments stay readable. */
    public static String mask(String line, int keep) {
        String body = line.startsWith("/") ? line.substring(1) : line;
        String[] parts = body.trim().split(" +");
        StringBuilder out = new StringBuilder(line.length() + 1).append('/').append(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            out.append(' ');
            if (i <= keep) {
                out.append(parts[i]);
                continue;
            }
            for (int j = Math.min(parts[i].length(), 8); j > 0; j--) {
                out.append('*');
            }
        }
        return out.toString();
    }
}
