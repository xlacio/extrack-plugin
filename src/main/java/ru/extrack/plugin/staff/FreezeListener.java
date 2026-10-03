package ru.extrack.plugin.staff;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;
import ru.extrack.plugin.util.Events;

/**
 * Keeps staff members who have not entered their code in place. Registered only while somebody is
 * frozen: move and damage events are among the hottest on a server, nobody else should pay for them.
 */
final class FreezeListener {

    private static final Set<String> BLOCKED_TELEPORTS = new HashSet<>(Arrays.asList(
        "ENDER_PEARL", "CHORUS_FRUIT", "COMMAND", "SPECTATE", "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY"));

    private final StaffGuard guard;
    private final Events     events;

    FreezeListener(StaffGuard guard, Events events) {
        this.guard = guard;
        this.events = events;
    }

    void register() {
        EventPriority priority = EventPriority.LOWEST;
        this.events.on(PlayerMoveEvent.class, priority, this::onMove);
        this.events.on(PlayerCommandPreprocessEvent.class, priority, this::onCommand);
        this.events.on(AsyncChatEvent.class, priority, event -> this.deny(event.getPlayer(), event));
        this.events.on(PlayerInteractEvent.class, priority, event -> this.deny(event.getPlayer(), event));
        this.events.on(PlayerInteractEntityEvent.class, priority, event -> this.deny(event.getPlayer(), event));
        this.events.on(PlayerInteractAtEntityEvent.class, priority, event -> this.deny(event.getPlayer(), event));
        this.events.on(BlockBreakEvent.class, priority, event -> this.deny(event.getPlayer(), event));
        this.events.on(BlockPlaceEvent.class, priority, event -> this.deny(event.getPlayer(), event));
        this.events.on(PlayerDropItemEvent.class, priority, event -> this.deny(event.getPlayer(), event));
        this.events.on(PlayerSwapHandItemsEvent.class, priority, event -> this.deny(event.getPlayer(), event));
        this.events.on(PlayerItemConsumeEvent.class, priority, event -> this.deny(event.getPlayer(), event));
        this.events.on(InventoryClickEvent.class, priority, event -> this.deny(event.getWhoClicked(), event));
        this.events.on(InventoryDragEvent.class, priority, event -> this.deny(event.getWhoClicked(), event));
        this.events.on(InventoryOpenEvent.class, priority, event -> this.deny(event.getPlayer(), event));
        this.events.on(EntityPickupItemEvent.class, priority, event -> this.deny(event.getEntity(), event));
        this.events.on(EntityDamageEvent.class, priority, this::onDamage);
        this.events.on(PlayerTeleportEvent.class, priority, this::onTeleport);
    }

    void unregister() {
        this.events.unregisterAll();
    }

    private void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        // looking around is fine, walking away is not
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) return;
        this.deny(event.getPlayer(), event);
    }

    private void onCommand(PlayerCommandPreprocessEvent event) {
        if (this.guard.isCodeCommand(event.getMessage())) return;
        this.deny(event.getPlayer(), event);
    }

    private void onDamage(EntityDamageEvent event) {
        if (this.isFrozen(event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent)) return;
        Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
        if (damager instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Entity) damager = (Entity) shooter;
        }
        if (this.isFrozen(damager)) event.setCancelled(true);
    }

    private void onTeleport(PlayerTeleportEvent event) {
        if (BLOCKED_TELEPORTS.contains(event.getCause().name())) this.deny(event.getPlayer(), event);
    }

    private void deny(Entity entity, Cancellable event) {
        if (!this.isFrozen(entity)) return;
        event.setCancelled(true);
        this.guard.remind((Player) entity);
    }

    private boolean isFrozen(Entity entity) {
        return entity instanceof Player && this.guard.isFrozen(entity.getUniqueId());
    }
}
