package ru.extrack.plugin.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import ru.extrack.plugin.util.Json;

/** The handshake answer: server settings, staff list and login protection state. */
public final class ServerInfo {

    private final String    name;
    private final String    timezone;
    private final boolean   locked;
    private final boolean   protection;
    private final Set<UUID> staff;
    private final int       heartbeatSeconds;
    private final int       flushSeconds;
    private final int       maxBatch;
    private final int       actionsWaitSeconds;

    private ServerInfo(JsonObject json) {
        JsonObject server = Json.object(json, "server");
        JsonObject protection = Json.object(json, "protection");
        JsonObject settings = Json.object(json, "settings");

        this.name = Json.string(server, "name");
        this.timezone = Json.string(server, "timezone");
        this.locked = Json.bool(json, "locked");
        this.protection = Json.bool(protection, "enabled");
        this.heartbeatSeconds = clamp(Json.integer(settings, "heartbeatSeconds", 30), 10, 120);
        this.flushSeconds = clamp(Json.integer(settings, "flushSeconds", 5), 1, 60);
        this.maxBatch = clamp(Json.integer(settings, "maxBatch", 500), 50, 2000);
        this.actionsWaitSeconds = clamp(Json.integer(settings, "actionsWaitSeconds", 25), 5, 30);

        Set<UUID> staff = new HashSet<>();
        JsonArray list = Json.array(json, "staff");
        if (list != null) {
            for (JsonElement element : list) {
                if (!element.isJsonObject()) continue;
                String uuid = Json.string(element.getAsJsonObject(), "uuid");
                if (uuid == null) continue;
                try {
                    staff.add(UUID.fromString(uuid));
                }
                catch (IllegalArgumentException ignored) {
                    // the panel only stores valid UUIDs, skip anything odd
                }
            }
        }
        this.staff = Collections.unmodifiableSet(staff);
    }

    static ServerInfo parse(JsonObject json) {
        return new ServerInfo(json);
    }

    public String name() {
        return this.name == null ? "?" : this.name;
    }

    public String timezone() {
        return this.timezone;
    }

    public boolean locked() {
        return this.locked;
    }

    public boolean protection() {
        return this.protection;
    }

    public Set<UUID> staff() {
        return this.staff;
    }

    public boolean isStaff(UUID uuid) {
        return this.staff.contains(uuid);
    }

    public int heartbeatSeconds() {
        return this.heartbeatSeconds;
    }

    public int flushSeconds() {
        return this.flushSeconds;
    }

    public int maxBatch() {
        return this.maxBatch;
    }

    public int actionsWaitSeconds() {
        return this.actionsWaitSeconds;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
