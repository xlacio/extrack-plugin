package ru.extrack.plugin.platform;

import org.bukkit.Bukkit;

public enum Platform {

    PAPER("paper"),
    PURPUR("purpur"),
    FOLIA("folia");

    private final String id;

    Platform(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public boolean isFolia() {
        return this == FOLIA;
    }

    /**
     * @return null on Spigot and anything else without the Paper chat API
     */
    public static Platform detect() {
        if (!hasClass("io.papermc.paper.event.player.AsyncChatEvent")) return null;
        if (hasClass("io.papermc.paper.threadedregions.RegionizedServer")) return FOLIA;
        if (hasClass("org.purpurmc.purpur.PurpurConfig") || hasClass("net.pl3x.purpur.PurpurConfig")) return PURPUR;
        return PAPER;
    }

    public static String minecraftVersion() {
        String version = Bukkit.getBukkitVersion();
        int dash = version.indexOf('-');
        return dash > 0 ? version.substring(0, dash) : version;
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, Platform.class.getClassLoader());
            return true;
        }
        catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
