package ru.extrack.plugin.platform;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Everything that differs between 1.16.5 and current Paper. Lookups happen once, on class load.
 */
public final class Compat {

    private static final Object       PLAIN_SERIALIZER;
    private static final MethodHandle PLAIN_SERIALIZE;
    private static final MethodHandle AVERAGE_TICK_TIME;
    private static final MethodHandle REGION_TPS;
    private static final MethodHandle CHUNK_COUNT;
    private static final MethodHandle ENTITY_COUNT;
    private static final MethodHandle CLIENT_BRAND;
    private static final MethodHandle OFFLINE_IF_CACHED;

    static {
        Object serializer = null;
        MethodHandle serialize = null;
        // PlainComponentSerializer is what 1.16.5 ships, newer Adventure renamed it
        for (String name : new String[]{"PlainTextComponentSerializer:plainText", "PlainComponentSerializer:plain"}) {
            String[] parts = name.split(":");
            try {
                Class<?> type = Class.forName("net.kyori.adventure.text.serializer.plain." + parts[0]);
                serializer = type.getMethod(parts[1]).invoke(null);
                serialize = MethodHandles.publicLookup().unreflect(type.getMethod("serialize", Component.class));
                break;
            }
            catch (ReflectiveOperationException | LinkageError ignored) {
                serializer = null;
            }
        }
        PLAIN_SERIALIZER = serializer;
        PLAIN_SERIALIZE = serialize;

        AVERAGE_TICK_TIME = find(Server.class, "getAverageTickTime");
        REGION_TPS = find(Server.class, "getRegionTPS", Location.class);
        CHUNK_COUNT = find(World.class, "getChunkCount");
        ENTITY_COUNT = find(World.class, "getEntityCount");
        CLIENT_BRAND = find(Player.class, "getClientBrandName");
        OFFLINE_IF_CACHED = find(Server.class, "getOfflinePlayerIfCached", String.class);
    }

    private Compat() {
    }

    public static String plain(Component component) {
        if (component == null) return null;
        if (PLAIN_SERIALIZE != null) {
            try {
                return (String) PLAIN_SERIALIZE.invoke(PLAIN_SERIALIZER, component);
            }
            catch (Throwable ignored) {
                // fall through to the legacy serializer
            }
        }
        return ChatColor.stripColor(LegacyComponentSerializer.legacySection().serialize(component));
    }

    public static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text == null ? "" : text);
    }

    public static void kick(Player player, String message) {
        player.kick(legacy(message));
    }

    /** TPS for the last minute, on Folia of the region around the main world spawn. */
    public static Double tps(boolean folia) {
        try {
            double value;
            if (folia) {
                List<World> worlds = Bukkit.getWorlds();
                if (REGION_TPS == null || worlds.isEmpty()) return null;
                double[] region = (double[]) REGION_TPS.invoke(Bukkit.getServer(), worlds.get(0).getSpawnLocation());
                if (region == null || region.length == 0) return null;
                value = region[0];
            }
            else {
                value = Bukkit.getTPS()[0];
            }
            return round(Math.max(0D, Math.min(100D, value)));
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    public static Double averageTickTime() {
        if (AVERAGE_TICK_TIME == null) return null;
        try {
            return round((double) AVERAGE_TICK_TIME.invoke(Bukkit.getServer()));
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    /** Must be called on the main thread. Null if this Paper build cannot count cheaply. */
    public static Integer chunkCount(World world) {
        return count(CHUNK_COUNT, world);
    }

    public static Integer entityCount(World world) {
        return count(ENTITY_COUNT, world);
    }

    public static String clientBrand(Player player) {
        if (CLIENT_BRAND == null) return null;
        try {
            return (String) CLIENT_BRAND.invoke(player);
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    public static String clientVersion(Player player) {
        return ViaVersion.version(player.getUniqueId());
    }

    /** Looks a player up in the user cache without touching the network. */
    public static UUID cachedId(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        if (OFFLINE_IF_CACHED == null) return null;
        try {
            OfflinePlayer offline = (OfflinePlayer) OFFLINE_IF_CACHED.invoke(Bukkit.getServer(), name);
            return offline == null ? null : offline.getUniqueId();
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    private static Integer count(MethodHandle handle, World world) {
        if (handle == null) return null;
        try {
            return (int) handle.invoke(world);
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    private static double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private static MethodHandle find(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getMethod(name, parameters);
            return MethodHandles.publicLookup().unreflect(method);
        }
        catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
    }

    /** ViaVersion 4 and 5, resolved on first use so the load order does not matter. */
    private static final class ViaVersion {

        private static final Object       API;
        private static final MethodHandle PLAYER_VERSION;
        private static final MethodHandle BY_PROTOCOL;
        private static final MethodHandle NAME;

        static {
            Object api = null;
            MethodHandle playerVersion = null;
            MethodHandle byProtocol = null;
            MethodHandle name = null;
            if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
                try {
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
                    Class<?> apiType = Class.forName("com.viaversion.viaversion.api.ViaAPI");
                    Class<?> protocol = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
                    api = via.getMethod("getAPI").invoke(null);
                    playerVersion = lookup.unreflect(apiType.getMethod("getPlayerVersion", UUID.class));
                    byProtocol = lookup.unreflect(protocol.getMethod("getProtocol", int.class));
                    name = lookup.unreflect(protocol.getMethod("getName"));
                }
                catch (ReflectiveOperationException | LinkageError exception) {
                    api = null;
                }
            }
            API = api;
            PLAYER_VERSION = playerVersion;
            BY_PROTOCOL = byProtocol;
            NAME = name;
        }

        static String version(UUID uuid) {
            if (API == null) return null;
            try {
                int protocol = (int) PLAYER_VERSION.invoke(API, uuid);
                if (protocol < 0) return null;
                return (String) NAME.invoke(BY_PROTOCOL.invoke(protocol));
            }
            catch (Throwable throwable) {
                return null;
            }
        }
    }
}
