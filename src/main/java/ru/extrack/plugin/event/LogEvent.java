package ru.extrack.plugin.event;

import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * One log line. Built on the game thread, serialized on the network thread, so building one is just
 * a few field writes.
 *
 * <pre>{@code
 * ExTrackApi.log(LogEvent.of(EventType.SHOP).player(player).message("Купил 16 алмазов").data("price", 1200));
 * }</pre>
 */
public final class LogEvent {

    private static final int MAX_MESSAGE = 2000;

    private final EventType type;
    private final long      time;
    private final String    raw;

    private UUID         playerId;
    private String       playerName;
    private UUID         targetId;
    private String       targetName;
    private String       world;
    private int          x;
    private int          y;
    private int          z;
    private String       message;
    private String[]     dataKeys;
    private Object[]     dataValues;
    private int          dataSize;
    private String       ip;
    private String       clientBrand;
    private String       clientVersion;
    private List<String> groups;

    private LogEvent(EventType type, long time, String raw) {
        this.type = type;
        this.time = time;
        this.raw = raw;
    }

    public static LogEvent of(EventType type) {
        return new LogEvent(Objects.requireNonNull(type, "type"), System.currentTimeMillis(), null);
    }

    /** An already serialized event restored from the spool file. */
    public static LogEvent raw(String json) {
        return new LogEvent(null, 0L, json);
    }

    public LogEvent player(Player player) {
        return this.player(player.getUniqueId(), player.getName());
    }

    public LogEvent player(UUID uuid, String name) {
        this.playerId = uuid;
        this.playerName = name;
        return this;
    }

    public LogEvent target(Player target) {
        return this.target(target.getUniqueId(), target.getName());
    }

    public LogEvent target(UUID uuid, String name) {
        this.targetId = uuid;
        this.targetName = name;
        return this;
    }

    /** For targets known only by name, such as the recipient of /msg. */
    public LogEvent target(String name) {
        return this.target(null, name);
    }

    public LogEvent at(Location location) {
        World world = location.getWorld();
        return this.at(world == null ? null : world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public LogEvent at(Block block) {
        return this.at(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public LogEvent at(String world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public LogEvent message(String message) {
        this.message = message != null && message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message;
        return this;
    }

    /** Strings, numbers and booleans. Nulls are skipped. The API keeps up to 4 KB of data per event. */
    public LogEvent data(String key, Object value) {
        if (value == null) return this;
        if (this.dataKeys == null) {
            this.dataKeys = new String[4];
            this.dataValues = new Object[4];
        }
        else if (this.dataSize == this.dataKeys.length) {
            this.dataKeys = Arrays.copyOf(this.dataKeys, this.dataSize * 2);
            this.dataValues = Arrays.copyOf(this.dataValues, this.dataSize * 2);
        }
        this.dataKeys[this.dataSize] = key;
        this.dataValues[this.dataSize] = value;
        this.dataSize++;
        return this;
    }

    public LogEvent ip(String ip) {
        this.ip = ip;
        return this;
    }

    public LogEvent client(String brand, String version) {
        this.clientBrand = brand;
        this.clientVersion = version;
        return this;
    }

    public LogEvent groups(List<String> groups) {
        this.groups = groups == null ? null : new ArrayList<>(groups);
        return this;
    }

    public EventType type() {
        return this.type;
    }

    public void write(JsonWriter out) throws IOException {
        if (this.raw != null) {
            out.jsonValue(this.raw);
            return;
        }

        out.beginObject();
        out.name("type").value(this.type.id());
        out.name("time").value(this.time);
        if (this.playerId != null) {
            out.name("player").beginObject()
                .name("uuid").value(this.playerId.toString())
                .name("name").value(this.playerName)
                .endObject();
        }
        if (this.targetName != null) {
            out.name("target").beginObject();
            if (this.targetId != null) out.name("uuid").value(this.targetId.toString());
            out.name("name").value(this.targetName).endObject();
        }
        if (this.world != null) {
            out.name("world").value(this.world);
            out.name("x").value(this.x);
            out.name("y").value(this.y);
            out.name("z").value(this.z);
        }
        if (this.message != null) out.name("message").value(this.message);
        if (this.dataSize > 0) {
            out.name("data").beginObject();
            for (int i = 0; i < this.dataSize; i++) {
                out.name(this.dataKeys[i]);
                Object value = this.dataValues[i];
                if (value instanceof Number) out.value((Number) value);
                else if (value instanceof Boolean) out.value(((Boolean) value).booleanValue());
                else out.value(String.valueOf(value));
            }
            out.endObject();
        }
        if (this.ip != null) out.name("ip").value(this.ip);
        if (this.clientBrand != null || this.clientVersion != null) {
            out.name("client").beginObject();
            if (this.clientBrand != null) out.name("brand").value(this.clientBrand);
            if (this.clientVersion != null) out.name("version").value(this.clientVersion);
            out.endObject();
        }
        if (this.groups != null) {
            out.name("groups").beginArray();
            for (String group : this.groups) {
                out.value(group);
            }
            out.endArray();
        }
        out.endObject();
    }
}
