package ru.extrack.plugin.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.Perms;
import ru.extrack.plugin.Placeholders;
import ru.extrack.plugin.client.ApiException;
import ru.extrack.plugin.client.ExTrackClient;
import ru.extrack.plugin.client.ServerInfo;
import ru.extrack.plugin.config.Config;
import ru.extrack.plugin.config.Lang;
import ru.extrack.plugin.config.LangText;
import ru.extrack.plugin.event.EventType;
import ru.extrack.plugin.event.LogEvent;
import ru.extrack.plugin.platform.Compat;
import ru.extrack.plugin.util.Colorizer;
import ru.extrack.plugin.util.Json;
import ru.extrack.plugin.util.Names;

public final class ExTrackCommand implements TabExecutor {

    private static final long              LINK_COOLDOWN = 3_000L;
    private static final BigDecimal        MAX_AMOUNT    = new BigDecimal("10000000");
    private static final DateTimeFormatter DATE          = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT);

    private final ExTrackPlugin   plugin;
    private final Map<UUID, Long> linkAttempts = new ConcurrentHashMap<>();

    public ExTrackCommand(ExTrackPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            Lang.COMMAND_HELP.send(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "code":
                this.code(sender, args);
                break;
            case "link":
                this.link(sender, args);
                break;
            case "info":
                this.info(sender, args);
                break;
            case "status":
                this.status(sender);
                break;
            case "reload":
                this.reload(sender);
                break;
            case "donation":
                this.donation(sender, args);
                break;
            case "help":
                Lang.COMMAND_HELP.send(sender);
                break;
            default:
                Lang.COMMAND_UNKNOWN.send(sender);
                break;
        }
        return true;
    }

    private void code(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            Lang.COMMAND_PLAYER_ONLY.send(sender);
            return;
        }
        if (args.length < 2) {
            Lang.COMMAND_USAGE.send(sender, Placeholders.USAGE, "/extrack code <код>");
            return;
        }
        this.plugin.staffGuard().submit((Player) sender, String.join("", Arrays.copyOfRange(args, 1, args.length)));
    }

    private void link(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            Lang.COMMAND_PLAYER_ONLY.send(sender);
            return;
        }
        if (!sender.hasPermission(Perms.LINK)) {
            Lang.COMMAND_NO_PERMISSION.send(sender);
            return;
        }
        if (args.length < 2) {
            Lang.COMMAND_USAGE.send(sender, Placeholders.USAGE, "/extrack link <код>");
            return;
        }
        String code = args[1].trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9]{6}")) {
            Lang.LINK_FORMAT.send(sender);
            return;
        }
        ExTrackClient client = this.connected(sender);
        if (client == null) return;

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = this.linkAttempts.get(uuid);
        if (last != null && now - last < LINK_COOLDOWN) {
            Lang.COMMAND_COOLDOWN.send(sender, Placeholders.SECONDS, (LINK_COOLDOWN - (now - last)) / 1000L + 1L);
            return;
        }
        this.linkAttempts.put(uuid, now);

        String name = player.getName();
        this.plugin.io().execute(() -> {
            try {
                JsonObject result = client.link(uuid, name, code);
                if (Json.bool(result, "ok")) {
                    String nickname = Json.string(result, "nickname");
                    this.plugin.tell(sender, Lang.LINK_DONE, Placeholders.NICKNAME, Colorizer.strip(nickname == null ? name : nickname));
                }
                else {
                    this.plugin.tell(sender, "taken".equals(Json.string(result, "reason")) ? Lang.LINK_TAKEN : Lang.LINK_INVALID);
                }
            }
            catch (ApiException exception) {
                this.plugin.tell(sender, Lang.ERROR_REQUEST, Placeholders.ERROR, Colorizer.strip(exception.getMessage()));
            }
        });
    }

    private void info(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Perms.INFO)) {
            Lang.COMMAND_NO_PERMISSION.send(sender);
            return;
        }
        if (args.length < 2) {
            Lang.COMMAND_USAGE.send(sender, Placeholders.USAGE, "/extrack info <ник>");
            return;
        }
        String name = args[1];
        UUID uuid = Names.isPlayerName(name) ? Compat.cachedId(name) : null;
        if (uuid == null) {
            Lang.ERROR_NO_PLAYER.send(sender, Placeholders.PLAYER, name);
            return;
        }
        ExTrackClient client = this.connected(sender);
        if (client == null) return;

        this.plugin.io().execute(() -> {
            try {
                JsonObject player = client.playerInfo(uuid);
                if (player == null) this.plugin.tell(sender, Lang.INFO_UNKNOWN, Placeholders.PLAYER, name);
                else this.plugin.tell(sender, this.card(player, name, client.info()));
            }
            catch (ApiException exception) {
                this.plugin.tell(sender, Lang.ERROR_REQUEST, Placeholders.ERROR, Colorizer.strip(exception.getMessage()));
            }
        });
    }

    private String card(JsonObject player, String fallbackName, ServerInfo info) {
        ZoneId zone = zone(info);
        StringBuilder out = new StringBuilder();
        String name = Json.string(player, "name");
        out.append(Lang.INFO_CARD.text(
            Placeholders.PLAYER, Colorizer.strip(name == null ? fallbackName : name),
            Placeholders.FIRST_SEEN, date(Json.string(player, "firstSeenAt"), zone),
            Placeholders.PLAYTIME, playtime(Json.number(player, "playtimeSeconds", 0L)),
            Placeholders.ALTS, Json.integer(player, "alts", 0),
            Placeholders.WATCHED, Json.bool(player, "watched") ? Lang.INFO_WATCHED.text() : ""));

        JsonArray punishments = Json.array(player, "activePunishments");
        if (punishments == null || punishments.size() == 0) {
            out.append('\n').append(Lang.INFO_CLEAN.text());
        }
        else {
            for (JsonElement element : punishments) {
                if (!element.isJsonObject()) continue;
                JsonObject punishment = element.getAsJsonObject();
                String expires = Json.string(punishment, "expiresAt");
                out.append('\n').append(Lang.INFO_PUNISHMENT.text(
                    Placeholders.TYPE, punishmentType(Json.string(punishment, "type")),
                    Placeholders.UNTIL, expires == null ? Lang.INFO_FOREVER.text() : date(expires, zone),
                    Placeholders.REASON, orDash(Colorizer.strip(Json.string(punishment, "reason")))));
            }
        }

        JsonArray notes = Json.array(player, "notes");
        if (notes != null) {
            for (JsonElement element : notes) {
                if (!element.isJsonObject()) continue;
                JsonObject note = element.getAsJsonObject();
                String author = Json.string(note, "author");
                out.append('\n').append(Lang.INFO_NOTE.text(
                    Placeholders.PINNED, Json.bool(note, "pinned") ? Lang.INFO_PINNED.text() : "",
                    Placeholders.AUTHOR, orDash(Colorizer.strip(author)),
                    Placeholders.TEXT, orDash(Colorizer.strip(Json.string(note, "body")))));
            }
        }
        return out.toString();
    }

    private void status(CommandSender sender) {
        if (!sender.hasPermission(Perms.STATUS)) {
            Lang.COMMAND_NO_PERMISSION.send(sender);
            return;
        }
        Config config = this.plugin.config();
        ExTrackClient client = this.plugin.client();
        ServerInfo info = client == null ? null : client.info();
        String error = client == null ? null : client.lastError();
        Lang.STATUS_INFO.send(sender,
            Placeholders.URL, config.url(),
            Placeholders.STATE, state(client),
            Placeholders.SERVER, info == null ? "-" : info.name(),
            Placeholders.QUEUED, this.plugin.eventQueue().size(),
            Placeholders.DROPPED, this.plugin.eventQueue().droppedTotal(),
            Placeholders.ERROR, error == null ? "-" : Colorizer.strip(error));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(Perms.RELOAD)) {
            Lang.COMMAND_NO_PERMISSION.send(sender);
            return;
        }
        this.plugin.reload();
        Lang.RELOAD_DONE.send(sender);
    }

    /**
     * Called by donation shops after a payment: {@code extrack donation <nick> <amount[:currency]> <product...>}.
     * Console and RCON only, otherwise anyone with the command could invent revenue.
     */
    private void donation(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            Lang.COMMAND_CONSOLE_ONLY.send(sender);
            return;
        }
        if (args.length < 4) {
            Lang.COMMAND_USAGE.send(sender, Placeholders.USAGE, "extrack donation <ник> <сумма[:валюта]> <товар>");
            return;
        }
        String name = args[1];
        if (!Names.isPlayerName(name)) {
            Lang.ERROR_NO_PLAYER.send(sender, Placeholders.PLAYER, name);
            return;
        }

        String[] amountParts = args[2].split(":", 2);
        String currency = amountParts.length == 2 ? amountParts[1].trim().toUpperCase(Locale.ROOT) : this.plugin.config().donationCurrency();
        BigDecimal amount = amount(amountParts[0]);
        if (amount == null || !currency.matches("[A-Z]{3}")) {
            Lang.DONATION_AMOUNT.send(sender, Placeholders.AMOUNT, args[2]);
            return;
        }

        UUID uuid = Compat.cachedId(name);
        if (uuid == null && !Bukkit.getOnlineMode()) {
            // offline mode derives the UUID from the name, nothing to look up
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        }
        if (uuid == null) {
            Lang.DONATION_UNKNOWN.send(sender, Placeholders.PLAYER, name);
            return;
        }
        if (this.connected(sender) == null) return;

        String product = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        if (product.length() > 100) product = product.substring(0, 100);
        this.plugin.log(LogEvent.of(EventType.DONATION)
            .player(uuid, name)
            .data("amount", amount)
            .data("currency", currency)
            .data("product", product));
        Lang.DONATION_DONE.send(sender,
            Placeholders.PLAYER, name,
            Placeholders.AMOUNT, amount.toPlainString(),
            Placeholders.CURRENCY, currency,
            Placeholders.PRODUCT, Colorizer.strip(product));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender instanceof Player) {
                options.add("code");
                if (sender.hasPermission(Perms.LINK)) options.add("link");
            }
            if (sender.hasPermission(Perms.INFO)) options.add("info");
            if (sender.hasPermission(Perms.STATUS)) options.add("status");
            if (sender.hasPermission(Perms.RELOAD)) options.add("reload");
            if (sender instanceof ConsoleCommandSender) options.add("donation");
            options.add("help");
            return filter(options, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean names = (sub.equals("info") && sender.hasPermission(Perms.INFO))
                || (sub.equals("donation") && sender instanceof ConsoleCommandSender);
            if (names) return playerNames(args[1]);
        }
        return Collections.emptyList();
    }

    private ExTrackClient connected(CommandSender sender) {
        ExTrackClient client = this.plugin.client();
        if (client == null) Lang.ERROR_NOT_CONNECTED.send(sender);
        return client;
    }

    private static String state(ExTrackClient client) {
        if (client == null) return Lang.STATUS_NO_TOKEN.text();
        LangText text;
        switch (client.status()) {
            case ONLINE:
                text = Lang.STATUS_ONLINE;
                break;
            case OFFLINE:
                text = Lang.STATUS_OFFLINE;
                break;
            case BAD_TOKEN:
                text = Lang.STATUS_BAD_TOKEN;
                break;
            case IP_BLOCKED:
                text = Lang.STATUS_IP_BLOCKED;
                break;
            case LOCKED:
                text = Lang.STATUS_LOCKED;
                break;
            default:
                text = Lang.STATUS_CONNECTING;
                break;
        }
        return text.text();
    }

    private static BigDecimal amount(String raw) {
        try {
            BigDecimal amount = new BigDecimal(raw.trim().replace(',', '.')).setScale(2, RoundingMode.HALF_UP);
            return amount.signum() > 0 && amount.compareTo(MAX_AMOUNT) <= 0 ? amount : null;
        }
        catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    private static String punishmentType(String type) {
        if ("ban".equals(type)) return Lang.INFO_BAN.text();
        if ("ipban".equals(type)) return Lang.INFO_IPBAN.text();
        if ("mute".equals(type)) return Lang.INFO_MUTE.text();
        return orDash(type);
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String playtime(long seconds) {
        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        String result = Lang.INFO_MINUTES.text(Placeholders.NUMBER, minutes);
        return hours > 0L ? Lang.INFO_HOURS.text(Placeholders.NUMBER, hours) + " " + result : result;
    }

    private static String date(String iso, ZoneId zone) {
        if (iso == null) return "-";
        try {
            return DATE.format(Instant.parse(iso).atZone(zone));
        }
        catch (DateTimeParseException exception) {
            return iso;
        }
    }

    private static ZoneId zone(ServerInfo info) {
        if (info != null && info.timezone() != null) {
            try {
                return ZoneId.of(info.timezone());
            }
            catch (RuntimeException ignored) {
                // an unknown zone id from the panel, fall back to the machine's
            }
        }
        return ZoneId.systemDefault();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.startsWith(lower)) result.add(option);
        }
        return result;
    }

    static List<String> playerNames(String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) result.add(player.getName());
            if (result.size() == 50) break;
        }
        return result;
    }
}
