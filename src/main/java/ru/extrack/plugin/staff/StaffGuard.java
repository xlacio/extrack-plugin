package ru.extrack.plugin.staff;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.Placeholders;
import ru.extrack.plugin.client.ApiException;
import ru.extrack.plugin.client.ExTrackClient;
import ru.extrack.plugin.client.ServerInfo;
import ru.extrack.plugin.config.Lang;
import ru.extrack.plugin.config.LangText;
import ru.extrack.plugin.event.EventType;
import ru.extrack.plugin.event.LogEvent;
import ru.extrack.plugin.platform.Compat;
import ru.extrack.plugin.util.Colorizer;
import ru.extrack.plugin.util.Commands;
import ru.extrack.plugin.util.Events;
import ru.extrack.plugin.util.Net;

/**
 * Staff login protection. The check runs in the async pre-login event, before the player exists in the
 * world, so there is no moment between joining and being frozen in which a command could slip through.
 */
public final class StaffGuard {

    private static final long REMINDER_INTERVAL = 10_000L;
    private static final long REMINDER_MIN_GAP  = 3_000L;
    private static final long ATTEMPT_COOLDOWN  = 2_000L;

    private final ExTrackPlugin        plugin;
    private final StaffCache           cache;
    private final Map<UUID, Challenge> pending    = new ConcurrentHashMap<>();
    private final FreezeListener       freeze;
    private final Set<String>          codeLabels = new HashSet<>();

    private boolean            frozen;
    private ScheduledFuture<?> ticker;

    public StaffGuard(ExTrackPlugin plugin) {
        this.plugin = plugin;
        this.cache = new StaffCache(new File(plugin.getDataFolder(), "staff-cache.json"), plugin.getLogger());
        this.freeze = new FreezeListener(this, new Events(plugin));
    }

    public void start(Events events) {
        this.cache.load();

        this.codeLabels.add("extrack");
        PluginCommand command = this.plugin.getCommand("extrack");
        if (command != null) {
            for (String alias : command.getAliases()) {
                this.codeLabels.add(alias.toLowerCase(Locale.ROOT));
            }
        }

        events.on(AsyncPlayerPreLoginEvent.class, EventPriority.HIGH, this::onPreLogin);
        events.on(PlayerJoinEvent.class, EventPriority.LOWEST, this::onJoin);
        events.on(PlayerQuitEvent.class, EventPriority.MONITOR, this::onQuit);
        this.ticker = this.plugin.worker().scheduleWithFixedDelay(this::tick, 2L, 2L, TimeUnit.SECONDS);
    }

    public void stop() {
        if (this.ticker != null) this.ticker.cancel(false);
        this.pending.clear();
        this.releaseFreeze();
    }

    /** Called on an IO thread after every handshake. */
    public void onServerInfo(ServerInfo info) {
        if (this.cache.update(info.protection(), info.staff())) this.cache.save();
    }

    public boolean isFrozen(UUID uuid) {
        return this.pending.containsKey(uuid);
    }

    /** The panel kicked this player, a declined login for example. */
    public void forget(UUID uuid) {
        this.pending.remove(uuid);
    }

    /** "/extrack code 123456" and its aliases, the only command a frozen player may use. */
    public boolean isCodeCommand(String message) {
        String line = message.startsWith("/") ? message.substring(1) : message;
        int space = line.indexOf(' ');
        if (space <= 0 || !this.codeLabels.contains(Commands.label(line.substring(0, space)))) return false;
        String rest = line.substring(space + 1).trim();
        return rest.regionMatches(true, 0, "code", 0, 4) && (rest.length() == 4 || rest.charAt(4) == ' ');
    }

    public void submit(Player player, String input) {
        UUID uuid = player.getUniqueId();
        Challenge challenge = this.pending.get(uuid);
        if (challenge == null || challenge.owner != player) {
            Lang.STAFF_NOT_REQUIRED.send(player);
            return;
        }

        String code = input == null ? "" : input.replace(" ", "");
        if (code.isEmpty() || code.length() > 12 || !isDigits(code)) {
            Lang.STAFF_FORMAT.send(player);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - challenge.lastAttempt < ATTEMPT_COOLDOWN || !challenge.busy.compareAndSet(false, true)) {
            Lang.STAFF_CHECKING.send(player);
            return;
        }
        challenge.lastAttempt = now;

        ExTrackClient client = this.plugin.client();
        if (client == null) {
            challenge.busy.set(false);
            Lang.ERROR_NOT_CONNECTED.send(player);
            return;
        }
        this.plugin.io().execute(() -> this.verify(client, player, challenge, code));
    }

    void remind(Player player) {
        Challenge challenge = this.pending.get(player.getUniqueId());
        if (challenge == null) return;
        long now = System.currentTimeMillis();
        if (now - challenge.lastReminder < REMINDER_MIN_GAP) return;
        challenge.lastReminder = now;
        this.plugin.scheduler().runEntity(player, () -> Lang.STAFF_REMINDER.send(player));
    }

    private void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        ExTrackClient client = this.plugin.client();
        if (client == null) return;

        UUID uuid = event.getUniqueId();
        ServerInfo info = client.info();
        // only staff can be challenged, everybody else joins without a request to the panel
        boolean staff = info != null ? info.isStaff(uuid) : this.cache.isStaff(uuid);
        if (!staff) return;

        String name = event.getName();
        String ip = Net.address(event.getAddress());
        boolean protection = info != null ? info.protection() : this.cache.protection();
        if (!protection) {
            client.touchStaff(uuid, name, ip);
            return;
        }

        try {
            ExTrackClient.StaffLogin login = client.staffLogin(uuid, name, ip, this.plugin.config().staffTimeoutMillis());
            if (!login.required()) {
                this.pending.remove(uuid);
                return;
            }
            if (login.challengeId() == null) {
                throw new ApiException(0, "bad_response", "панель не выдала код подтверждения", 0);
            }
            this.pending.put(uuid, new Challenge(login.challengeId(), System.currentTimeMillis() + login.expiresIn() * 1000L));
            this.plugin.log(LogEvent.of(EventType.STAFF_AUTH).player(uuid, name).data("result", "required"));
        }
        catch (ApiException exception) {
            if (!this.plugin.config().staffFailClosed()) {
                this.plugin.getLogger().warning("Вход сотрудника " + name + " не проверен (" + exception.getMessage() + "), fail-closed выключен, игрок пущен.");
                return;
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Compat.legacy(Lang.STAFF_KICK_UNAVAILABLE.text()));
            this.plugin.getLogger().warning("Сотрудник " + name + " не пущен: вход не удалось проверить (" + exception.getMessage() + ").");
        }
    }

    private void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Challenge challenge = this.pending.get(player.getUniqueId());
        if (challenge == null) return;
        challenge.owner = player;
        challenge.lastReminder = System.currentTimeMillis();
        this.engageFreeze();
        Lang.STAFF_REQUIRED.send(player);
    }

    private void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Challenge challenge = this.pending.get(player.getUniqueId());
        // a second login under the same UUID may already hold a new challenge, leave that one alone
        if (challenge != null && challenge.owner == player) this.pending.remove(player.getUniqueId(), challenge);
    }

    private void verify(ExTrackClient client, Player player, Challenge challenge, String code) {
        try {
            ExTrackClient.StaffVerify result = client.staffVerify(challenge.id, code);
            if (result.ok()) {
                this.pending.remove(player.getUniqueId(), challenge);
                this.tell(player, Lang.STAFF_VERIFIED);
                this.logResult(player, "verified");
            }
            else if (result.locked() || result.expired()) {
                this.pending.remove(player.getUniqueId(), challenge);
                this.kick(player, result.locked() ? Lang.STAFF_KICK_LOCKED : Lang.STAFF_KICK_EXPIRED);
                this.logResult(player, result.locked() ? "locked" : "expired");
            }
            else {
                this.tell(player, Lang.STAFF_WRONG, Placeholders.ATTEMPTS, result.attemptsLeft());
            }
        }
        catch (ApiException exception) {
            this.tell(player, Lang.ERROR_REQUEST, Placeholders.ERROR, Colorizer.strip(exception.getMessage()));
        }
        finally {
            challenge.busy.set(false);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Challenge> entry : this.pending.entrySet()) {
            Challenge challenge = entry.getValue();
            Player owner = challenge.owner;
            if (now >= challenge.expiresAt) {
                if (this.pending.remove(entry.getKey(), challenge) && owner != null) {
                    this.kick(owner, Lang.STAFF_KICK_EXPIRED);
                    this.logResult(owner, "expired");
                }
            }
            else if (owner != null && now - challenge.lastReminder >= REMINDER_INTERVAL) {
                challenge.lastReminder = now;
                this.tell(owner, Lang.STAFF_REMINDER);
            }
        }
        if (this.pending.isEmpty()) this.releaseFreeze();
    }

    // registration and removal share one lock, otherwise a join on one region thread could register the
    // listener a moment before the ticker removes it
    private synchronized void engageFreeze() {
        if (this.frozen) return;
        this.freeze.register();
        this.frozen = true;
    }

    private synchronized void releaseFreeze() {
        if (!this.frozen || !this.pending.isEmpty()) return;
        this.freeze.unregister();
        this.frozen = false;
    }

    private void tell(Player player, LangText text, Object... replacements) {
        this.plugin.scheduler().runEntity(player, () -> text.send(player, replacements));
    }

    private void kick(Player player, LangText text) {
        this.plugin.scheduler().runEntity(player, () -> Compat.kick(player, text.text()));
    }

    private void logResult(Player player, String result) {
        this.plugin.log(LogEvent.of(EventType.STAFF_AUTH).player(player).data("result", result));
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static final class Challenge {

        private final String        id;
        private final long          expiresAt;
        private final AtomicBoolean busy = new AtomicBoolean();

        private volatile Player owner;
        private volatile long   lastReminder;
        private volatile long   lastAttempt;

        private Challenge(String id, long expiresAt) {
            this.id = id;
            this.expiresAt = expiresAt;
        }
    }
}
