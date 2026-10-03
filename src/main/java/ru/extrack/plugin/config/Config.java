package ru.extrack.plugin.config;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import ru.extrack.plugin.client.CommandPolicy;
import ru.extrack.plugin.event.EventType;

/**
 * An immutable view of config.yml. /extrack reload builds a new one and swaps the reference.
 */
public final class Config {

    private static final String      DEFAULT_URL = "https://extrack.su";
    private static final Set<String> OLD_URLS    = new HashSet<>(Arrays.asList(
        "https://extrack.ru", "http://extrack.ru", "https://www.extrack.ru", "http://www.extrack.ru"));

    private final String        token;
    private final String        url;
    private final CommandPolicy commandPolicy;
    private final Set<EventType> events;
    private final Set<String>   ignoredWorlds;
    private final Set<String>   ignoredCommands;
    private final Set<String>   privateCommands;
    private final Set<String>   replyCommands;
    private final Set<String>   secretCommands;
    private final Set<String>   teleportCauses;
    private final double        teleportDistanceSquared;
    private final Set<String>   containers;
    private final boolean       staffFailClosed;
    private final int           staffTimeoutMillis;
    private final boolean       consoleErrors;
    private final boolean       luckPermsGroups;
    private final boolean       reports;
    private final int           reportCooldownSeconds;
    private final String        donationCurrency;
    private final int           bufferSize;

    private Config(FileConfiguration yaml, Logger logger) {
        this.token = string(yaml, "token", "");
        this.url = url(string(yaml, "url", DEFAULT_URL), logger);
        this.commandPolicy = CommandPolicy.of(yaml.getStringList("allowed-commands"), logger);

        Set<EventType> events = EnumSet.noneOf(EventType.class);
        for (EventType type : EventType.values()) {
            // getBoolean without a fallback reads the bundled config.yml for keys an older file lacks
            if (yaml.getBoolean("events." + type.id())) events.add(type);
        }
        this.events = Collections.unmodifiableSet(events);

        this.ignoredWorlds = exact(yaml.getStringList("logging.ignored-worlds"));
        this.ignoredCommands = commands(yaml.getStringList("logging.ignored-commands"));
        this.privateCommands = commands(yaml.getStringList("logging.private-commands"));
        this.replyCommands = commands(yaml.getStringList("logging.reply-commands"));
        this.secretCommands = commands(yaml.getStringList("logging.secret-commands"));
        this.teleportCauses = upper(yaml.getStringList("logging.teleport.causes"));
        double distance = Math.max(0D, yaml.getDouble("logging.teleport.min-distance"));
        this.teleportDistanceSquared = distance * distance;
        this.containers = upper(yaml.getStringList("logging.containers"));

        this.staffFailClosed = yaml.getBoolean("staff-protection.fail-closed");
        this.staffTimeoutMillis = Math.max(2, Math.min(20, yaml.getInt("staff-protection.timeout"))) * 1000;
        this.consoleErrors = yaml.getBoolean("console-errors");
        this.luckPermsGroups = yaml.getBoolean("luckperms-groups");
        this.reports = yaml.getBoolean("reports.enabled");
        this.reportCooldownSeconds = Math.max(0, yaml.getInt("reports.cooldown"));

        String currency = string(yaml, "donation-currency", "RUB").toUpperCase(Locale.ROOT);
        this.donationCurrency = currency.matches("[A-Z]{3}") ? currency : "RUB";
        this.bufferSize = Math.max(1_000, Math.min(1_000_000, yaml.getInt("buffer-size")));
    }

    public static Config load(FileConfiguration yaml, Logger logger) {
        return new Config(yaml, logger);
    }

    /** Whether something was changed that needs a new connection. */
    public boolean connectionChanged(Config other) {
        return !this.token.equals(other.token) || !this.url.equals(other.url);
    }

    public boolean hasToken() {
        return this.token.startsWith("et_") && this.token.length() >= 12 && this.token.length() <= 80
            && !this.token.contains("...") && this.token.indexOf(' ') < 0;
    }

    public boolean logs(EventType type) {
        return this.events.contains(type);
    }

    public boolean ignores(World world) {
        return world != null && !this.ignoredWorlds.isEmpty() && this.ignoredWorlds.contains(world.getName());
    }

    public String token() {
        return this.token;
    }

    public String url() {
        return this.url;
    }

    public CommandPolicy commandPolicy() {
        return this.commandPolicy;
    }

    public Set<String> ignoredCommands() {
        return this.ignoredCommands;
    }

    public Set<String> privateCommands() {
        return this.privateCommands;
    }

    public Set<String> replyCommands() {
        return this.replyCommands;
    }

    public Set<String> secretCommands() {
        return this.secretCommands;
    }

    public Set<String> teleportCauses() {
        return this.teleportCauses;
    }

    public double teleportDistanceSquared() {
        return this.teleportDistanceSquared;
    }

    public Set<String> containers() {
        return this.containers;
    }

    public boolean staffFailClosed() {
        return this.staffFailClosed;
    }

    public int staffTimeoutMillis() {
        return this.staffTimeoutMillis;
    }

    public boolean consoleErrors() {
        return this.consoleErrors;
    }

    public boolean luckPermsGroups() {
        return this.luckPermsGroups;
    }

    public boolean reports() {
        return this.reports;
    }

    public int reportCooldownSeconds() {
        return this.reportCooldownSeconds;
    }

    public String donationCurrency() {
        return this.donationCurrency;
    }

    public int bufferSize() {
        return this.bufferSize;
    }

    private static String string(FileConfiguration yaml, String path, String fallback) {
        String value = yaml.getString(path);
        return value == null ? fallback : value.trim();
    }

    private static String url(String raw, Logger logger) {
        String url = raw;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            logger.warning("url в config.yml должен начинаться с https://, используется " + DEFAULT_URL);
            return DEFAULT_URL;
        }
        if (OLD_URLS.contains(url.toLowerCase(Locale.ROOT))) {
            // the panel moved, configs written before 1.0.2 still point to the blocked domain
            logger.warning("Панель переехала на " + DEFAULT_URL + ", используется новый адрес. Замените url в config.yml, чтобы убрать это сообщение.");
            return DEFAULT_URL;
        }
        if (url.startsWith("http://") && !url.startsWith("http://localhost") && !url.startsWith("http://127.0.0.1")) {
            logger.warning("url начинается с http://: токен сервера уходит по сети без шифрования. Укажите адрес с https://");
        }
        return url;
    }

    private static Set<String> commands(Collection<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            String label = value.trim().toLowerCase(Locale.ROOT);
            if (label.startsWith("/")) label = label.substring(1);
            if (!label.isEmpty()) result.add(label);
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> upper(List<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            String name = value.trim().toUpperCase(Locale.ROOT);
            if (!name.isEmpty()) result.add(name);
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> exact(List<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            if (!value.trim().isEmpty()) result.add(value.trim());
        }
        return Collections.unmodifiableSet(result);
    }
}
