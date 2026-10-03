package ru.extrack.plugin.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.config.Config;
import ru.extrack.plugin.event.EventType;
import ru.extrack.plugin.event.LogEvent;
import ru.extrack.plugin.hook.LuckPermsHook;
import ru.extrack.plugin.platform.Compat;
import ru.extrack.plugin.util.Events;
import ru.extrack.plugin.util.Names;
import ru.extrack.plugin.util.Net;

public final class SessionListener {

    private final ExTrackPlugin     plugin;
    private final Map<UUID, String> kickReasons = new ConcurrentHashMap<>();

    public SessionListener(ExTrackPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(Events events, Config config) {
        if (config.logs(EventType.JOIN)) events.on(PlayerJoinEvent.class, EventPriority.MONITOR, this::onJoin);
        if (config.logs(EventType.QUIT)) {
            events.on(PlayerKickEvent.class, EventPriority.MONITOR, true, this::onKick);
            events.on(PlayerQuitEvent.class, EventPriority.MONITOR, this::onQuit);
        }
        if (config.logs(EventType.WORLD_CHANGE)) events.on(PlayerChangedWorldEvent.class, EventPriority.MONITOR, this::onWorldChange);
        if (config.logs(EventType.TELEPORT)) events.on(PlayerTeleportEvent.class, EventPriority.MONITOR, true, this::onTeleport);
        if (config.logs(EventType.GAMEMODE)) events.on(PlayerGameModeChangeEvent.class, EventPriority.MONITOR, true, this::onGameMode);
        if (config.logs(EventType.ADVANCEMENT)) events.on(PlayerAdvancementDoneEvent.class, EventPriority.MONITOR, this::onAdvancement);
    }

    private void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        LogEvent log = LogEvent.of(EventType.JOIN)
            .player(player)
            .ip(Net.address(player.getAddress()))
            .client(Compat.clientBrand(player), Compat.clientVersion(player));
        LuckPermsHook luckPerms = this.plugin.luckPerms();
        if (luckPerms != null) log.groups(luckPerms.snapshot(player));
        this.plugin.log(log);
    }

    private void onKick(PlayerKickEvent event) {
        String reason = Compat.plain(event.reason());
        if (reason != null && !reason.isEmpty()) this.kickReasons.put(event.getPlayer().getUniqueId(), reason);
    }

    private void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String reason = this.kickReasons.remove(player.getUniqueId());
        LogEvent log = LogEvent.of(EventType.QUIT).player(player);
        if (reason != null) log.message(reason).data("kicked", true);
        this.plugin.log(log);
    }

    private void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (this.plugin.config().ignores(player.getWorld())) return;
        this.plugin.log(LogEvent.of(EventType.WORLD_CHANGE)
            .player(player)
            .at(player.getLocation())
            .data("from", event.getFrom().getName()));
    }

    private void onTeleport(PlayerTeleportEvent event) {
        Config config = this.plugin.config();
        String cause = event.getCause().name();
        if (!config.teleportCauses().contains(cause)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || config.ignores(to.getWorld())) return;
        // distanceSquared throws for locations in different worlds
        boolean sameWorld = from.getWorld() == to.getWorld();
        if (sameWorld && from.distanceSquared(to) < config.teleportDistanceSquared()) return;

        this.plugin.log(LogEvent.of(EventType.TELEPORT)
            .player(event.getPlayer())
            .at(to)
            .data("cause", Names.lower(event.getCause()))
            .data("from", Names.format(from)));
    }

    private void onGameMode(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        this.plugin.log(LogEvent.of(EventType.GAMEMODE)
            .player(player)
            .at(player.getLocation())
            .data("from", Names.lower(player.getGameMode()))
            .data("to", Names.lower(event.getNewGameMode())));
    }

    private void onAdvancement(PlayerAdvancementDoneEvent event) {
        NamespacedKey key = event.getAdvancement().getKey();
        // every unlocked recipe is an advancement too, those would flood the log
        if (key.getKey().startsWith("recipes/")) return;
        this.plugin.log(LogEvent.of(EventType.ADVANCEMENT)
            .player(event.getPlayer())
            .data("advancement", key.toString()));
    }
}
