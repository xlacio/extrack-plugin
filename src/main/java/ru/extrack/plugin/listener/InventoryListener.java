package ru.extrack.plugin.listener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.config.Config;
import ru.extrack.plugin.event.EventType;
import ru.extrack.plugin.event.LogEvent;
import ru.extrack.plugin.util.Events;
import ru.extrack.plugin.util.Names;

/**
 * Container logging by snapshot and diff: contents are counted when the player opens a chest and again
 * when they close it. Two short loops over at most 54 slots instead of decoding every click type.
 */
public final class InventoryListener {

    private static final int MAX_CHANGES_PER_CLOSE = 24;

    private final ExTrackPlugin       plugin;
    private final Map<UUID, Snapshot> open = new ConcurrentHashMap<>();

    public InventoryListener(ExTrackPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(Events events, Config config) {
        boolean containers = config.logs(EventType.CONTAINER_OPEN) || config.logs(EventType.CONTAINER_TAKE) || config.logs(EventType.CONTAINER_PUT);
        if (containers && !config.containers().isEmpty()) {
            events.on(InventoryOpenEvent.class, EventPriority.MONITOR, true, this::onOpen);
            events.on(InventoryCloseEvent.class, EventPriority.MONITOR, this::onClose);
            events.on(PlayerQuitEvent.class, EventPriority.MONITOR, event -> this.open.remove(event.getPlayer().getUniqueId()));
        }
        if (config.logs(EventType.ITEM_DROP)) events.on(PlayerDropItemEvent.class, EventPriority.MONITOR, true, this::onDrop);
        if (config.logs(EventType.ITEM_PICKUP)) events.on(EntityPickupItemEvent.class, EventPriority.MONITOR, true, this::onPickup);
        if (config.logs(EventType.CRAFT)) events.on(CraftItemEvent.class, EventPriority.MONITOR, true, this::onCraft);
    }

    private void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Config config = this.plugin.config();
        Inventory inventory = event.getInventory();
        String type = inventory.getType().name();
        if (!config.containers().contains(type)) return;

        // menus of other plugins have no location, they are not containers in the world
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null || config.ignores(location.getWorld())) return;

        Player player = (Player) event.getPlayer();
        String container = type.toLowerCase(Locale.ROOT);
        if (config.logs(EventType.CONTAINER_OPEN)) {
            this.plugin.log(LogEvent.of(EventType.CONTAINER_OPEN).player(player).at(location).data("container", container));
        }
        if (config.logs(EventType.CONTAINER_TAKE) || config.logs(EventType.CONTAINER_PUT)) {
            this.open.put(player.getUniqueId(), new Snapshot(location, container, count(inventory)));
        }
    }

    private void onClose(InventoryCloseEvent event) {
        Snapshot snapshot = this.open.remove(event.getPlayer().getUniqueId());
        if (snapshot == null || !(event.getPlayer() instanceof Player)) return;
        Inventory inventory = event.getInventory();
        if (!snapshot.location.equals(inventory.getLocation())) return;

        Config config = this.plugin.config();
        Player player = (Player) event.getPlayer();
        Map<Material, Integer> after = count(inventory);
        Set<Material> materials = new HashSet<>(snapshot.counts.keySet());
        materials.addAll(after.keySet());

        int logged = 0;
        for (Material material : materials) {
            int delta = after.getOrDefault(material, 0) - snapshot.counts.getOrDefault(material, 0);
            if (delta == 0) continue;
            EventType type = delta > 0 ? EventType.CONTAINER_PUT : EventType.CONTAINER_TAKE;
            if (!config.logs(type)) continue;
            this.plugin.log(LogEvent.of(type)
                .player(player)
                .at(snapshot.location)
                .data("item", Names.key(material))
                .data("amount", Math.abs(delta))
                .data("container", snapshot.container));
            if (++logged == MAX_CHANGES_PER_CLOSE) break;
        }
    }

    private void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (this.plugin.config().ignores(player.getWorld())) return;
        ItemStack item = event.getItemDrop().getItemStack();
        this.plugin.log(LogEvent.of(EventType.ITEM_DROP)
            .player(player)
            .at(player.getLocation())
            .data("item", Names.key(item.getType()))
            .data("amount", item.getAmount()));
    }

    private void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (this.plugin.config().ignores(player.getWorld())) return;
        ItemStack item = event.getItem().getItemStack();
        int amount = item.getAmount() - event.getRemaining();
        if (amount <= 0) return;
        this.plugin.log(LogEvent.of(EventType.ITEM_PICKUP)
            .player(player)
            .at(player.getLocation())
            .data("item", Names.key(item.getType()))
            .data("amount", amount));
    }

    private void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) return;
        Player player = (Player) event.getWhoClicked();
        if (this.plugin.config().ignores(player.getWorld())) return;
        this.plugin.log(LogEvent.of(EventType.CRAFT)
            .player(player)
            .at(player.getLocation())
            .data("item", Names.key(result.getType()))
            .data("amount", result.getAmount())
            .data("bulk", event.isShiftClick()));
    }

    private static Map<Material, Integer> count(Inventory inventory) {
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            counts.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return counts;
    }

    private static final class Snapshot {

        private final Location               location;
        private final String                 container;
        private final Map<Material, Integer> counts;

        private Snapshot(Location location, String container, Map<Material, Integer> counts) {
            this.location = location;
            this.container = container;
            this.counts = counts;
        }
    }
}
