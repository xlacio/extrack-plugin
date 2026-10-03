package ru.extrack.plugin.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.config.Config;
import ru.extrack.plugin.event.EventType;
import ru.extrack.plugin.event.LogEvent;
import ru.extrack.plugin.platform.Compat;
import ru.extrack.plugin.util.Events;
import ru.extrack.plugin.util.Names;

public final class CombatListener {

    private final ExTrackPlugin plugin;

    public CombatListener(ExTrackPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(Events events, Config config) {
        if (config.logs(EventType.DEATH) || config.logs(EventType.KILL)) {
            events.on(PlayerDeathEvent.class, EventPriority.MONITOR, this::onPlayerDeath);
        }
        if (config.logs(EventType.MOB_KILL)) events.on(EntityDeathEvent.class, EventPriority.MONITOR, this::onEntityDeath);
    }

    private void onPlayerDeath(PlayerDeathEvent event) {
        Config config = this.plugin.config();
        Player victim = event.getEntity();
        Location location = victim.getLocation();
        if (config.ignores(location.getWorld())) return;

        Player killer = victim.getKiller();
        EntityDamageEvent lastDamage = victim.getLastDamageCause();
        String cause = lastDamage == null ? null : Names.lower(lastDamage.getCause());

        if (config.logs(EventType.DEATH)) {
            // the vanilla death message is a translation key on Paper, the cause and the killer say more
            LogEvent log = LogEvent.of(EventType.DEATH)
                .player(victim)
                .at(location)
                .data("cause", cause)
                .data("level", victim.getLevel());
            if (killer != null) log.target(killer);
            else log.data("killer", mobKiller(lastDamage));
            this.plugin.log(log);
        }

        if (killer != null && killer != victim && config.logs(EventType.KILL)) {
            ItemStack weapon = killer.getInventory().getItemInMainHand();
            this.plugin.log(LogEvent.of(EventType.KILL)
                .player(killer)
                .target(victim)
                .at(location)
                .data("weapon", Names.key(weapon.getType()))
                .data("cause", cause));
        }
    }

    // EntityDeathEvent shares its handler list with PlayerDeathEvent
    private void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) return;
        Player killer = entity.getKiller();
        if (killer == null) return;
        Location location = entity.getLocation();
        if (this.plugin.config().ignores(location.getWorld())) return;

        LogEvent log = LogEvent.of(EventType.MOB_KILL)
            .player(killer)
            .at(location)
            .data("entity", Names.key(entity.getType()));
        // a named mob is usually somebody's pet or a shop villager
        Component customName = entity.customName();
        if (customName != null) log.data("name", Compat.plain(customName));
        this.plugin.log(log);
    }

    private static String mobKiller(EntityDamageEvent damage) {
        if (!(damage instanceof EntityDamageByEntityEvent)) return null;
        Entity damager = ((EntityDamageByEntityEvent) damage).getDamager();
        if (damager instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Entity) damager = (Entity) shooter;
        }
        return Names.key(damager.getType());
    }
}
