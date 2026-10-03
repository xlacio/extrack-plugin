package ru.extrack.plugin.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.config.Lang;
import ru.extrack.plugin.platform.Compat;
import ru.extrack.plugin.util.Json;

/**
 * Long-polls the panel's action queue on its own thread and runs commands, messages and kicks.
 */
final class ActionPoller implements Runnable {

    private static final int  REMEMBERED   = 512;
    private static final long GAME_TIMEOUT = 10L;

    private final ExTrackPlugin plugin;
    private final ExTrackClient client;
    private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();

    /**
     * Results of actions already executed. If an ack gets lost, the panel sends the action again
     * a minute later, and a ban must not be issued twice.
     */
    private final Map<Long, Result> done = new LinkedHashMap<Long, Result>(64, 0.75F, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Result> eldest) {
            return this.size() > REMEMBERED;
        }
    };

    private volatile boolean running;
    private Thread thread;

    ActionPoller(ExTrackPlugin plugin, ExTrackClient client) {
        this.plugin = plugin;
        this.client = client;
    }

    void start() {
        this.running = true;
        this.thread = new Thread(this, "ExTrack Actions");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    void stop() {
        this.running = false;
        // the long-poll read does not react to interrupts, closing the socket does
        HttpURLConnection current = this.connection.get();
        if (current != null) current.disconnect();
        if (this.thread != null) this.thread.interrupt();
    }

    @Override
    public void run() {
        int failures = 0;
        while (this.running) {
            ServerInfo info = this.client.info();
            int wait = info == null ? 25 : info.actionsWaitSeconds();
            JsonObject response;
            try {
                response = this.client.api().get("/actions?wait=" + wait, (wait + 15) * 1000, this.connection);
                failures = 0;
            }
            catch (ApiException exception) {
                if (!this.running) return;
                this.client.onFailure(exception);
                long delay;
                if (exception.isAuth() || exception.isLocked()) delay = TimeUnit.MINUTES.toMillis(5);
                else if (exception.isRateLimited()) delay = Math.max(30, exception.retryAfter()) * 1000L;
                else delay = Math.min(60_000L, 2_000L << Math.min(failures++, 5));
                if (!this.sleep(delay)) return;
                continue;
            }

            // actions that arrive while the plugin shuts down stay in the queue and come back later
            if (!this.running) return;
            JsonArray actions = Json.array(response, "actions");
            if (actions == null || actions.size() == 0) continue;

            JsonArray results = new JsonArray();
            for (JsonElement element : actions) {
                if (!element.isJsonObject()) continue;
                Result result = this.handle(element.getAsJsonObject());
                if (result != null) results.add(result.toJson());
            }
            this.acknowledge(results);
        }
    }

    private Result handle(JsonObject action) {
        long id = Json.number(action, "id", -1L);
        if (id <= 0) return null;
        Result known = this.done.get(id);
        if (known != null) return known;

        String type = Json.string(action, "type");
        JsonObject payload = Json.object(action, "payload");
        if (payload == null) payload = new JsonObject();

        Result result;
        if ("command".equals(type)) result = this.command(id, Json.string(payload, "command"));
        else if ("message".equals(type)) result = this.message(id, payload);
        else if ("kick".equals(type)) result = this.kick(id, payload);
        else if ("sync".equals(type)) {
            this.client.refresh();
            result = Result.ok(id);
        }
        else result = Result.fail(id, "Неизвестное действие " + type + ", обновите плагин ExTrack");

        this.done.put(id, result);
        return result;
    }

    private Result command(long id, String command) {
        String line = this.plugin.config().commandPolicy().check(command);
        if (line == null) {
            this.plugin.getLogger().warning("Панель прислала команду вне allowed-commands, она не выполнена: " + command);
            return Result.fail(id, "Команда не разрешена в allowed-commands плагина");
        }

        this.plugin.getLogger().info("Команда из панели: " + line);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        this.plugin.scheduler().runGlobal(() -> {
            try {
                future.complete(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
            }
            catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        Boolean dispatched = this.await(future);
        if (dispatched == null) return Result.fail(id, "Сервер не выполнил команду за " + GAME_TIMEOUT + " секунд");
        return dispatched ? Result.ok(id) : Result.fail(id, "Команда не найдена или завершилась ошибкой, проверьте плагин наказаний");
    }

    private Result message(long id, JsonObject payload) {
        UUID uuid = uuid(Json.string(payload, "uuid"));
        String text = clean(Json.string(payload, "text"), 512);
        if (uuid == null || text.isEmpty()) return Result.fail(id, "Пустое сообщение или неверный UUID");

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        this.plugin.scheduler().runPlayer(uuid, player -> {
            Lang.ACTION_MESSAGE.send(player, "%message%", text);
            future.complete(true);
        }, () -> future.complete(false));
        Boolean delivered = this.await(future);
        return Boolean.TRUE.equals(delivered) ? Result.ok(id) : Result.fail(id, "Игрок не в сети");
    }

    private Result kick(long id, JsonObject payload) {
        UUID uuid = uuid(Json.string(payload, "uuid"));
        if (uuid == null) return Result.fail(id, "Неверный UUID");
        String reason = clean(Json.string(payload, "reason"), 256);

        // a declined staff login: forget the challenge so the player is not let in by a race
        this.plugin.staffGuard().forget(uuid);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        this.plugin.scheduler().runPlayer(uuid, player -> {
            Compat.kick(player, reason);
            future.complete(true);
        }, () -> future.complete(false));
        Boolean kicked = this.await(future);
        return Boolean.TRUE.equals(kicked) ? Result.ok(id) : Result.fail(id, "Игрок не в сети");
    }

    private Boolean await(CompletableFuture<Boolean> future) {
        try {
            return future.get(GAME_TIMEOUT, TimeUnit.SECONDS);
        }
        catch (TimeoutException | ExecutionException exception) {
            return exception instanceof ExecutionException ? Boolean.FALSE : null;
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void acknowledge(JsonArray results) {
        if (results.size() == 0) return;
        JsonObject body = new JsonObject();
        body.add("results", results);
        try {
            this.client.api().post("/actions/ack", body, 15_000);
        }
        catch (ApiException exception) {
            // the panel will resend these, and the answers come from the memory above
            this.plugin.getLogger().fine("Не удалось подтвердить команды панели: " + exception.getMessage());
        }
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return this.running;
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static UUID uuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String clean(String text, int max) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(Math.min(text.length(), max));
        for (int i = 0; i < text.length() && out.length() < max; i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' || (Character.isISOControl(c) && c != '\n')) continue;
            out.append(c);
        }
        return out.toString().trim();
    }

    private static final class Result {

        private final long    id;
        private final boolean ok;
        private final String  message;

        private Result(long id, boolean ok, String message) {
            this.id = id;
            this.ok = ok;
            this.message = message;
        }

        static Result ok(long id) {
            return new Result(id, true, null);
        }

        static Result fail(long id, String message) {
            return new Result(id, false, message);
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", this.id);
            json.addProperty("ok", this.ok);
            if (this.message != null) json.addProperty("message", this.message.length() > 500 ? this.message.substring(0, 500) : this.message);
            return json;
        }
    }
}
