package ru.extrack.plugin.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.config.Config;
import ru.extrack.plugin.console.ConsoleRecord;
import ru.extrack.plugin.event.LogEvent;
import ru.extrack.plugin.platform.Platform;
import ru.extrack.plugin.platform.TaskScheduler;
import ru.extrack.plugin.util.Json;

/**
 * Connection to the panel: handshake, batched events, console errors, heartbeat and the action queue.
 * Nothing here runs on a game thread except collecting the heartbeat on Paper.
 */
public final class ExTrackClient {

    private static final long FIVE_MINUTES  = TimeUnit.MINUTES.toMillis(5);
    private static final int  BATCHES_PER_RUN = 20;

    public enum Status {
        CONNECTING, ONLINE, OFFLINE, BAD_TOKEN, IP_BLOCKED, LOCKED
    }

    private final ExTrackPlugin plugin;
    private final Logger        logger;
    private final ApiClient     api;
    private final EventQueue    queue;
    private final ActionPoller  poller;
    private final JsonObject    handshakeBody;

    private final ReentrantLock      sendLock           = new ReentrantLock();
    private final AtomicBoolean      flushRequested     = new AtomicBoolean();
    private final AtomicBoolean      handshakeRunning   = new AtomicBoolean();
    private final AtomicBoolean      handshakeScheduled = new AtomicBoolean();
    private final List<Future<?>>    timers             = new CopyOnWriteArrayList<>();

    private volatile ServerInfo info;
    private volatile Status     status = Status.CONNECTING;
    private volatile String     lastError;
    private volatile boolean    closed;
    private volatile long       pausedUntil;
    private volatile int        batchSize = 500;

    private TaskScheduler.Task heartbeat;
    private boolean            background;
    private int                sendFailures;
    private int                handshakeFailures;

    public ExTrackClient(ExTrackPlugin plugin, Config config) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.queue = plugin.eventQueue();
        String version = plugin.getDescription().getVersion();
        String agent = "ExTrack/" + version + " (" + plugin.platform().id() + " " + Platform.minecraftVersion() + ")";
        this.api = new ApiClient(config.url(), config.token(), agent);
        this.handshakeBody = handshakeBody(plugin, version);
        this.poller = new ActionPoller(plugin, this);
    }

    public void start() {
        this.timers.add(this.plugin.worker().scheduleWithFixedDelay(this::requestFlush, 5L, 5L, TimeUnit.SECONDS));
        this.timers.add(this.plugin.worker().scheduleWithFixedDelay(() -> this.submit(this::flushConsole), 10L, 10L, TimeUnit.SECONDS));
        this.timers.add(this.plugin.worker().scheduleWithFixedDelay(this::reportDrops, 1L, 1L, TimeUnit.MINUTES));
        this.submit(this::handshake);
    }

    /**
     * @param drainMillis how long to keep sending queued events, 0 to leave them for the next client
     */
    public void close(long drainMillis) {
        this.closed = true;
        for (Future<?> timer : this.timers) {
            timer.cancel(false);
        }
        synchronized (this) {
            if (this.heartbeat != null) this.heartbeat.cancel();
        }
        this.poller.stop();
        if (drainMillis <= 0L) return;

        long deadline = System.currentTimeMillis() + drainMillis;
        try {
            if (!this.sendLock.tryLock(drainMillis, TimeUnit.MILLISECONDS)) return;
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            while (this.queue.size() > 0) {
                long left = deadline - System.currentTimeMillis();
                if (left < 250L) break;
                List<LogEvent> batch = this.queue.poll(this.batchSize);
                try {
                    this.api.post("/events", out -> writeEvents(out, batch), (int) left);
                }
                catch (ApiException exception) {
                    if (exception.isRejected()) continue;
                    this.queue.requeue(batch);
                    break;
                }
            }
        }
        finally {
            this.sendLock.unlock();
        }
    }

    public void log(LogEvent event) {
        if (this.queue.offer(event) && this.queue.size() >= this.batchSize) this.requestFlush();
    }

    /** Asks the panel for fresh settings and staff list, used after linking and on the sync action. */
    public void refresh() {
        this.submit(this::handshake);
    }

    public ServerInfo info() {
        return this.info;
    }

    public Status status() {
        return this.status;
    }

    public String lastError() {
        return this.lastError;
    }

    ApiClient api() {
        return this.api;
    }

    // ---- staff and in-game requests, all blocking, called from IO threads ----

    public StaffLogin staffLogin(UUID uuid, String name, String ip, int timeout) throws ApiException {
        JsonObject body = new JsonObject();
        body.add("player", player(uuid, name));
        if (ip != null) body.addProperty("ip", ip);
        JsonObject response = this.api.post("/staff/login", body, timeout);
        return new StaffLogin(Json.bool(response, "staff"), Json.bool(response, "required"),
            Json.string(response, "challengeId"), Json.integer(response, "expiresIn", 300));
    }

    /** Keeps the member's last activity fresh when login protection is off. */
    public void touchStaff(UUID uuid, String name, String ip) {
        this.submit(() -> {
            try {
                this.staffLogin(uuid, name, ip, 10_000);
            }
            catch (ApiException ignored) {
                // nothing depends on it
            }
        });
    }

    public StaffVerify staffVerify(String challengeId, String code) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("challengeId", challengeId);
        body.addProperty("code", code);
        JsonObject response = this.api.post("/staff/verify", body, 15_000);
        return new StaffVerify(Json.bool(response, "ok"), Json.bool(response, "locked"),
            "expired".equals(Json.string(response, "reason")), Json.integer(response, "attemptsLeft", 0));
    }

    /** @return the response: {@code ok}, {@code reason}, {@code nickname} */
    public JsonObject link(UUID uuid, String name, String code) throws ApiException {
        JsonObject body = new JsonObject();
        body.add("player", player(uuid, name));
        body.addProperty("code", code);
        JsonObject response = this.api.post("/staff/link", body, 15_000);
        if (Json.bool(response, "ok")) this.refresh();
        return response;
    }

    /** Group changes during play, see {@code POST /groups}. */
    public void postGroups(JsonObject body) throws ApiException {
        this.api.post("/groups", body, 15_000);
    }

    /** @return the ticket number */
    public int report(boolean bug, UUID uuid, String name, String target, String text, String world, int x, int y, int z) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("type", bug ? "bug" : "complaint");
        body.addProperty("message", text);
        body.add("player", player(uuid, name));
        if (target != null) {
            JsonObject targetJson = new JsonObject();
            targetJson.addProperty("name", target);
            body.add("target", targetJson);
        }
        if (world != null) {
            JsonObject location = new JsonObject();
            location.addProperty("world", world);
            location.addProperty("x", x);
            location.addProperty("y", y);
            location.addProperty("z", z);
            body.add("location", location);
        }
        return Json.integer(this.api.post("/tickets", body, 15_000), "number", 0);
    }

    /** @return the player summary, null if the panel has never seen this player */
    public JsonObject playerInfo(UUID uuid) throws ApiException {
        return Json.object(this.api.get("/players/" + uuid, 15_000, null), "player");
    }

    // ---- background work ----

    private void handshake() {
        if (this.closed || !this.handshakeRunning.compareAndSet(false, true)) return;
        try {
            ServerInfo next = ServerInfo.parse(this.api.post("/handshake", this.handshakeBody, 15_000));
            this.handshakeFailures = 0;
            this.info = next;
            this.batchSize = next.maxBatch();
            this.plugin.staffGuard().onServerInfo(next);
            if (next.locked()) this.setStatus(Status.LOCKED, "сервер заморожен по тарифу");
            else this.setStatus(Status.ONLINE, null);
            this.startBackground(next);
        }
        catch (ApiException exception) {
            if (exception.isRejected()) this.logger.warning("Панель отклонила подключение: " + exception.getMessage());
            this.onFailure(exception);
            long delay = exception.isAuth() ? FIVE_MINUTES : Math.min(FIVE_MINUTES, 15_000L << Math.min(this.handshakeFailures++, 4));
            this.scheduleHandshake(delay);
        }
        finally {
            this.handshakeRunning.set(false);
        }
    }

    private void scheduleHandshake(long delay) {
        if (this.closed || !this.handshakeScheduled.compareAndSet(false, true)) return;
        this.timers.removeIf(Future::isDone);
        try {
            this.timers.add(this.plugin.worker().schedule(() -> {
                this.handshakeScheduled.set(false);
                this.submit(this::handshake);
            }, delay, TimeUnit.MILLISECONDS));
        }
        catch (RejectedExecutionException exception) {
            this.handshakeScheduled.set(false);
        }
    }

    private synchronized void startBackground(ServerInfo info) {
        if (this.background || this.closed) return;
        this.background = true;
        long period = info.heartbeatSeconds();
        if (this.plugin.platform().isFolia()) {
            this.timers.add(this.plugin.worker().scheduleAtFixedRate(() -> this.submit(() -> this.sendHeartbeat(Heartbeat.collect(true))),
                1L, period, TimeUnit.SECONDS));
        }
        else {
            // the world counters are only safe to read on the main thread, the request goes out on IO
            this.heartbeat = this.plugin.scheduler().runGlobalTimer(() -> {
                Heartbeat snapshot = Heartbeat.collect(false);
                this.submit(() -> this.sendHeartbeat(snapshot));
            }, 20L, period * 20L);
        }
        this.poller.start();
    }

    private void requestFlush() {
        if (this.closed || this.queue.size() == 0 || System.currentTimeMillis() < this.pausedUntil) return;
        if (!this.flushRequested.compareAndSet(false, true)) return;
        if (!this.submit(() -> {
            try {
                this.flushEvents();
            }
            finally {
                this.flushRequested.set(false);
            }
        })) {
            this.flushRequested.set(false);
        }
    }

    private void flushEvents() {
        if (!this.sendLock.tryLock()) return;
        try {
            for (int i = 0; i < BATCHES_PER_RUN && !this.closed && System.currentTimeMillis() >= this.pausedUntil; i++) {
                List<LogEvent> batch = this.queue.poll(this.batchSize);
                if (batch.isEmpty()) return;
                try {
                    this.api.post("/events", out -> writeEvents(out, batch), 30_000);
                    this.sendFailures = 0;
                    this.onSuccess();
                }
                catch (ApiException exception) {
                    if (exception.isRejected()) {
                        this.logger.warning("Панель отклонила пачку из " + batch.size() + " событий: " + exception.getMessage());
                        continue;
                    }
                    this.queue.requeue(batch);
                    this.pause(exception);
                    this.onFailure(exception);
                    return;
                }
            }
        }
        finally {
            this.sendLock.unlock();
        }
    }

    private void flushConsole() {
        if (this.closed || System.currentTimeMillis() < this.pausedUntil) return;
        List<ConsoleRecord> records = this.plugin.consoleQueue().poll(500);
        if (records.isEmpty()) return;
        try {
            this.api.post("/console", out -> {
                out.beginObject().name("records").beginArray();
                for (ConsoleRecord record : records) {
                    record.write(out);
                }
                out.endArray().endObject();
            }, 20_000);
        }
        catch (ApiException exception) {
            // console lines are best effort, a dead panel must not pile them up
            if (!exception.isRejected()) this.onFailure(exception);
        }
    }

    private void sendHeartbeat(Heartbeat snapshot) {
        if (this.closed) return;
        try {
            JsonObject response = this.api.post("/heartbeat", snapshot::write, 15_000);
            boolean locked = Json.bool(response, "locked");
            if (locked) this.setStatus(Status.LOCKED, "сервер заморожен по тарифу");
            else if (this.status == Status.LOCKED) this.refresh();
            else this.onSuccess();
        }
        catch (ApiException exception) {
            this.onFailure(exception);
        }
    }

    private void reportDrops() {
        long dropped = this.queue.takeDropped();
        if (dropped > 0L) {
            this.logger.warning("Очередь событий заполнена, за минуту не записано " + dropped + " событий. Связь с панелью: " + this.status);
        }
    }

    private void pause(ApiException exception) {
        long delay;
        if (exception.isAuth() || exception.isLocked()) delay = FIVE_MINUTES;
        else if (exception.isRateLimited()) delay = exception.retryAfter() > 0 ? exception.retryAfter() * 1000L : 15_000L;
        else delay = Math.min(60_000L, 2_000L << Math.min(this.sendFailures++, 5));
        this.pausedUntil = System.currentTimeMillis() + delay;
    }

    void onSuccess() {
        if (this.status != Status.ONLINE) this.setStatus(Status.ONLINE, null);
    }

    void onFailure(ApiException exception) {
        Status next;
        if (exception.isLocked()) next = Status.LOCKED;
        else if (exception.status() == 401) next = Status.BAD_TOKEN;
        else if (exception.status() == 403) next = Status.IP_BLOCKED;
        else if (exception.isRateLimited() || exception.isRejected()) return;
        else next = Status.OFFLINE;
        this.setStatus(next, exception.getMessage());
    }

    private synchronized void setStatus(Status next, String error) {
        this.lastError = error;
        Status previous = this.status;
        if (previous == next || this.closed) return;
        this.status = next;

        switch (next) {
            case ONLINE:
                if (previous == Status.CONNECTING) {
                    ServerInfo current = this.info;
                    this.logger.info("Подключено к ExTrack" + (current == null ? "" : ", сервер «" + current.name() + "»") + ".");
                }
                else {
                    this.logger.info("Связь с ExTrack восстановлена.");
                }
                break;
            case OFFLINE:
                this.logger.warning("Нет связи с ExTrack: " + error + ". События копятся в памяти и уйдут, когда связь появится.");
                break;
            case BAD_TOKEN:
                this.logger.severe("ExTrack не принял токен: " + error + " Проверьте token в config.yml и выполните /extrack reload.");
                break;
            case IP_BLOCKED:
                this.logger.severe(error + ". Добавьте IP этого сервера в белый список в настройках сервера на сайте.");
                break;
            case LOCKED:
                this.logger.warning("Сервер заморожен в панели ExTrack: логи не принимаются, пока владелец не продлит тариф.");
                break;
            default:
                break;
        }
    }

    private boolean submit(Runnable task) {
        if (this.closed) return false;
        try {
            this.plugin.io().execute(task);
            return true;
        }
        catch (RejectedExecutionException exception) {
            return false;
        }
    }

    private static void writeEvents(JsonWriter out, List<LogEvent> batch) throws IOException {
        out.beginObject().name("events").beginArray();
        for (LogEvent event : batch) {
            event.write(out);
        }
        out.endArray().endObject();
    }

    private static JsonObject player(UUID uuid, String name) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid.toString());
        json.addProperty("name", name);
        return json;
    }

    private static JsonObject handshakeBody(ExTrackPlugin plugin, String version) {
        JsonObject body = new JsonObject();
        body.addProperty("pluginVersion", cut(version, 32));
        body.addProperty("platform", plugin.platform().id());
        body.addProperty("mcVersion", cut(Platform.minecraftVersion(), 64));
        body.addProperty("onlineMode", Bukkit.getOnlineMode());
        body.addProperty("maxPlayers", Math.max(0, Math.min(1_000_000, Bukkit.getMaxPlayers())));

        // lets the panel tell which plugin an error in the console belongs to
        JsonArray plugins = new JsonArray();
        for (Plugin installed : Bukkit.getPluginManager().getPlugins()) {
            PluginDescriptionFile description = installed.getDescription();
            JsonObject entry = new JsonObject();
            entry.addProperty("name", cut(description.getName(), 64));
            entry.addProperty("version", cut(description.getVersion(), 64));
            entry.addProperty("main", cut(description.getMain(), 200));
            plugins.add(entry);
            if (plugins.size() == 1000) break;
        }
        body.add("plugins", plugins);
        return body;
    }

    private static String cut(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    public static final class StaffLogin {

        private final boolean staff;
        private final boolean required;
        private final String  challengeId;
        private final int     expiresIn;

        StaffLogin(boolean staff, boolean required, String challengeId, int expiresIn) {
            this.staff = staff;
            this.required = required;
            this.challengeId = challengeId;
            this.expiresIn = expiresIn;
        }

        public boolean staff() {
            return this.staff;
        }

        public boolean required() {
            return this.required;
        }

        public String challengeId() {
            return this.challengeId;
        }

        public int expiresIn() {
            return this.expiresIn;
        }
    }

    public static final class StaffVerify {

        private final boolean ok;
        private final boolean locked;
        private final boolean expired;
        private final int     attemptsLeft;

        StaffVerify(boolean ok, boolean locked, boolean expired, int attemptsLeft) {
            this.ok = ok;
            this.locked = locked;
            this.expired = expired;
            this.attemptsLeft = attemptsLeft;
        }

        public boolean ok() {
            return this.ok;
        }

        public boolean locked() {
            return this.locked;
        }

        public boolean expired() {
            return this.expired;
        }

        public int attemptsLeft() {
            return this.attemptsLeft;
        }
    }
}
