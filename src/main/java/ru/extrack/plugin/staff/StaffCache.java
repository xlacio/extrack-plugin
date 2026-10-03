package ru.extrack.plugin.staff;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import ru.extrack.plugin.util.Json;

/**
 * The staff list from the last handshake, kept on disk. Without it a restart while the panel is down
 * would let anyone in under a moderator's name until the first successful connection.
 */
final class StaffCache {

    private final File   file;
    private final Logger logger;
    private final Gson   gson = new Gson();

    private volatile boolean   protection;
    private volatile Set<UUID> staff = Collections.emptySet();

    StaffCache(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    void load() {
        if (!this.file.isFile()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(this.file), StandardCharsets.UTF_8)) {
            JsonObject json = this.gson.fromJson(reader, JsonObject.class);
            Set<UUID> staff = new HashSet<>();
            JsonArray list = Json.array(json, "staff");
            if (list != null) {
                for (JsonElement element : list) {
                    try {
                        staff.add(UUID.fromString(element.getAsString()));
                    }
                    catch (RuntimeException ignored) {
                        // hand edited file, skip the broken entry
                    }
                }
            }
            this.protection = Json.bool(json, "protection");
            this.staff = Collections.unmodifiableSet(staff);
        }
        catch (IOException | JsonParseException exception) {
            this.logger.warning("Не удалось прочитать " + this.file.getName() + ": " + exception.getMessage());
        }
    }

    boolean protection() {
        return this.protection;
    }

    boolean isStaff(UUID uuid) {
        return this.staff.contains(uuid);
    }

    /** @return true if anything changed and the file has to be rewritten */
    boolean update(boolean protection, Set<UUID> staff) {
        if (this.protection == protection && this.staff.equals(staff)) return false;
        this.protection = protection;
        this.staff = staff;
        return true;
    }

    synchronized void save() {
        JsonObject json = new JsonObject();
        json.addProperty("protection", this.protection);
        JsonArray list = new JsonArray();
        for (UUID uuid : this.staff) {
            list.add(uuid.toString());
        }
        json.add("staff", list);

        File parent = this.file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        File temp = new File(this.file.getPath() + ".tmp");
        try {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
                this.gson.toJson(json, writer);
            }
            Files.move(temp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException exception) {
            this.logger.warning("Не удалось сохранить " + this.file.getName() + ": " + exception.getMessage());
        }
    }
}
