package ru.extrack.plugin.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.ChatColor;

/**
 * Turns {@code <gray>}, {@code <#ffaa00>}, {@code <b>} and friends into legacy section codes.
 * Legacy strings work with every server version from 1.16.5 up, hex colors included.
 */
public final class Colorizer {

    private static final char SECTION = '\u00a7';

    private static final Map<String, String>    COLORS      = new HashMap<>();
    private static final Map<String, Character> DECORATIONS = new HashMap<>();

    static {
        String[] names = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
        String codes = "0123456789abcdef";
        for (int i = 0; i < names.length; i++) {
            COLORS.put(names[i], String.valueOf(SECTION) + codes.charAt(i));
        }
        COLORS.put("dgray", COLORS.get("dark_gray"));
        COLORS.put("lgray", hex("d4d9d8"));
        COLORS.put("lred", hex("ff6b6b"));
        COLORS.put("lgreen", hex("8ee58e"));
        COLORS.put("lyellow", hex("ffe27a"));
        COLORS.put("lorange", hex("ffb56b"));
        COLORS.put("lblue", hex("7fb2ff"));
        COLORS.put("laqua", hex("7de8e1"));
        COLORS.put("lpurple", hex("c9a7ff"));

        decoration('l', "b", "bold");
        decoration('o', "i", "em", "italic");
        decoration('n', "u", "underlined");
        decoration('m', "st", "strikethrough");
        decoration('k', "obf", "obfuscated");
    }

    private Colorizer() {
    }

    public static String apply(String input) {
        if (input == null || input.isEmpty()) return "";

        StringBuilder out = new StringBuilder(input.length() + 32);
        Deque<String> colors = new ArrayDeque<>();
        List<Character> decorations = new ArrayList<>(2);

        int index = 0;
        while (index < input.length()) {
            char c = input.charAt(index);
            int end = c == '<' ? input.indexOf('>', index + 1) : -1;
            if (end < 0) {
                out.append(c);
                index++;
                continue;
            }

            String tag = input.substring(index + 1, end).toLowerCase(Locale.ROOT);
            boolean closing = tag.startsWith("/");
            String name = closing ? tag.substring(1) : tag;
            String color = color(name);
            Character decoration = DECORATIONS.get(name);

            if (color != null) {
                if (!closing) colors.push(color);
                else if (!colors.isEmpty()) colors.pop();
                restore(out, colors, decorations);
            }
            else if (decoration != null) {
                if (closing) {
                    decorations.remove(decoration);
                    restore(out, colors, decorations);
                }
                else if (!decorations.contains(decoration)) {
                    decorations.add(decoration);
                    out.append(SECTION).append(decoration.charValue());
                }
            }
            else if (!closing && (name.equals("reset") || name.equals("r"))) {
                colors.clear();
                decorations.clear();
                out.append(SECTION).append('r');
            }
            else {
                // not a formatting tag, things like "<code>" stay as they are
                out.append(input, index, end + 1);
            }
            index = end + 1;
        }
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }

    /** Removes color codes from text that comes from outside, so it cannot recolor the message around it. */
    public static String strip(String text) {
        if (text == null) return "";
        if (text.indexOf(SECTION) < 0) return text;

        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            // a code is the sign and the character after it
            if (text.charAt(i) == SECTION) i++;
            else out.append(text.charAt(i));
        }
        return out.toString();
    }

    private static void restore(StringBuilder out, Deque<String> colors, List<Character> decorations) {
        if (colors.isEmpty()) out.append(SECTION).append('r');
        else out.append(colors.peek());
        for (Character decoration : decorations) {
            out.append(SECTION).append(decoration.charValue());
        }
    }

    private static String color(String name) {
        String named = COLORS.get(name);
        if (named != null) return named;
        if (name.length() == 7 && name.charAt(0) == '#' && isHex(name.substring(1))) return hex(name.substring(1));
        return null;
    }

    private static String hex(String rgb) {
        StringBuilder builder = new StringBuilder(14).append(SECTION).append('x');
        for (int i = 0; i < rgb.length(); i++) {
            builder.append(SECTION).append(rgb.charAt(i));
        }
        return builder.toString();
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    private static void decoration(char code, String... names) {
        for (String name : names) {
            DECORATIONS.put(name, code);
        }
    }
}
