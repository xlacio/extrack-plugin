package ru.extrack.plugin.util;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;

public final class Names {

    private static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_.*-]{1,32}");

    private static final Map<Material, String>   MATERIALS = new ConcurrentHashMap<>();
    private static final Map<EntityType, String> ENTITIES  = new ConcurrentHashMap<>();

    private Names() {
    }

    /** {@code minecraft:diamond_ore}. Cached, block events call this on every break. */
    public static String key(Material material) {
        // get first: computeIfAbsent locks the bin on Java 8 even when the key exists
        String key = MATERIALS.get(material);
        if (key == null) {
            key = material.getKey().toString();
            MATERIALS.put(material, key);
        }
        return key;
    }

    public static String key(EntityType type) {
        String key = ENTITIES.get(type);
        if (key == null) {
            key = type.name().toLowerCase(Locale.ROOT);
            ENTITIES.put(type, key);
        }
        return key;
    }

    /** The same pattern the API checks, Bedrock players with a Floodgate prefix pass too. */
    public static boolean isPlayerName(String name) {
        return name != null && PLAYER.matcher(name).matches();
    }

    public static String format(Location location) {
        World world = location.getWorld();
        return (world == null ? "?" : world.getName()) + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ();
    }

    public static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
