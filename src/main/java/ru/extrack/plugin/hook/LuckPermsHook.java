package ru.extrack.plugin.hook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.client.ApiException;
import ru.extrack.plugin.client.ExTrackClient;

/**
 * Player groups from LuckPerms. Groups are read through the "group.name" permissions LuckPerms grants,
 * the API is touched by reflection only to learn when a player's data was recalculated, so the plugin
 * has no compile time dependency and works with any LuckPerms 5 build.
 */
public final class LuckPermsHook {

    private static final Pattern GROUP      = Pattern.compile("[a-z0-9_.+-]{1,48}");
    private static final int     MAX_GROUPS = 32;

    private final ExTrackPlugin           plugin;
    private final Set<UUID>               dirty = ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<String>> known = new ConcurrentHashMap<>();

    private Object             subscription;
    private Method             getUser;
    private Method             getUniqueId;
    private ScheduledFuture<?> task;

    private LuckPermsHook(ExTrackPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return null when LuckPerms is not installed */
    public static LuckPermsHook enable(ExTrackPlugin plugin) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return null;
        LuckPermsHook hook = new LuckPermsHook(plugin);
        try {
            hook.subscribe();
        }
        catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            plugin.getLogger().warning("Не удалось подписаться на события LuckPerms, группы будут обновляться только при входе: " + exception);
        }
        hook.task = plugin.worker().scheduleWithFixedDelay(hook::flush, 5L, 5L, TimeUnit.SECONDS);
        return hook;
    }

    public void close() {
        if (this.task != null) this.task.cancel(false);
        if (this.subscription instanceof AutoCloseable) {
            try {
                ((AutoCloseable) this.subscription).close();
            }
            catch (Exception ignored) {
                // LuckPerms may already be disabled on shutdown
            }
        }
        this.dirty.clear();
        this.known.clear();
    }

    /** Groups at join. Remembered, so the recalculation LuckPerms does on login is not sent twice. */
    public List<String> snapshot(Player player) {
        List<String> groups = groups(player);
        this.known.put(player.getUniqueId(), groups);
        return groups;
    }

    public void forget(UUID uuid) {
        this.known.remove(uuid);
        this.dirty.remove(uuid);
    }

    private void subscribe() throws ReflectiveOperationException {
        ClassLoader loader = this.plugin.getClass().getClassLoader();
        Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader);
        Class<?> api = Class.forName("net.luckperms.api.LuckPerms", true, loader);
        Class<?> bus = Class.forName("net.luckperms.api.event.EventBus", true, loader);
        Class<?> event = Class.forName("net.luckperms.api.event.user.UserDataRecalculateEvent", true, loader);
        Class<?> user = Class.forName("net.luckperms.api.model.user.User", true, loader);

        this.getUser = event.getMethod("getUser");
        this.getUniqueId = user.getMethod("getUniqueId");

        Object luckPerms = provider.getMethod("get").invoke(null);
        Object eventBus = api.getMethod("getEventBus").invoke(luckPerms);
        Consumer<Object> handler = this::onRecalculate;
        this.subscription = bus.getMethod("subscribe", Object.class, Class.class, Consumer.class)
            .invoke(eventBus, this.plugin, event, handler);
    }

    // LuckPerms threads, recalculations come in bursts: on login, on world change, on every edit
    private void onRecalculate(Object event) {
        try {
            Object user = this.getUser.invoke(event);
            this.dirty.add((UUID) this.getUniqueId.invoke(user));
        }
        catch (ReflectiveOperationException | RuntimeException ignored) {
            // a broken event is not worth a stack trace in the console
        }
    }

    private void flush() {
        if (this.dirty.isEmpty()) return;
        List<UUID> batch = new ArrayList<>(this.dirty);
        this.dirty.removeAll(batch);
        this.plugin.scheduler().runGlobal(() -> this.collect(batch));
    }

    private void collect(List<UUID> uuids) {
        Map<Player, List<String>> changed = new HashMap<>();
        for (UUID uuid : uuids) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            List<String> groups = groups(player);
            List<String> previous = this.known.put(uuid, groups);
            if (!groups.equals(previous)) changed.put(player, groups);
        }
        if (changed.isEmpty()) return;

        JsonArray players = new JsonArray();
        for (Map.Entry<Player, List<String>> entry : changed.entrySet()) {
            JsonObject ref = new JsonObject();
            ref.addProperty("uuid", entry.getKey().getUniqueId().toString());
            ref.addProperty("name", entry.getKey().getName());
            JsonArray groups = new JsonArray();
            for (String group : entry.getValue()) {
                groups.add(group);
            }
            JsonObject item = new JsonObject();
            item.add("player", ref);
            item.add("groups", groups);
            players.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("players", players);

        ExTrackClient client = this.plugin.client();
        if (client == null) return;
        this.plugin.io().execute(() -> {
            try {
                client.postGroups(body);
            }
            catch (ApiException exception) {
                this.plugin.getLogger().fine("Группы игроков не отправлены: " + exception.getMessage());
            }
        });
    }

    private static List<String> groups(Player player) {
        List<String> groups = new ArrayList<>(4);
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String permission = info.getPermission();
            if (permission.length() <= 6 || !permission.regionMatches(true, 0, "group.", 0, 6)) continue;
            String group = permission.substring(6).toLowerCase(Locale.ROOT);
            if (GROUP.matcher(group).matches() && !groups.contains(group)) groups.add(group);
            if (groups.size() == MAX_GROUPS) break;
        }
        Collections.sort(groups);
        return groups;
    }
}
