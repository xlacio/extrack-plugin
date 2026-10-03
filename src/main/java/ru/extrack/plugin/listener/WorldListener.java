package ru.extrack.plugin.listener;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.config.Config;
import ru.extrack.plugin.event.EventType;
import ru.extrack.plugin.event.LogEvent;
import ru.extrack.plugin.util.Events;
import ru.extrack.plugin.util.Names;

public final class WorldListener {

    /** A TNT cannon sets off hundreds of charges a second, a few per second are enough to see it. */
    private static final int EXPLOSIONS_PER_SECOND = 20;

    private final ExTrackPlugin plugin;
    private final AtomicLong    second     = new AtomicLong();
    private final AtomicInteger explosions = new AtomicInteger();

    public WorldListener(ExTrackPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(Events events, Config config) {
        if (config.logs(EventType.BLOCK_BREAK)) events.on(BlockBreakEvent.class, EventPriority.MONITOR, true, this::onBreak);
        if (config.logs(EventType.BLOCK_PLACE)) events.on(BlockPlaceEvent.class, EventPriority.MONITOR, true, this::onPlace);
        if (config.logs(EventType.EXPLOSION)) {
            events.on(EntityExplodeEvent.class, EventPriority.MONITOR, true, this::onEntityExplode);
            events.on(BlockExplodeEvent.class, EventPriority.MONITOR, true, this::onBlockExplode);
        }
    }

    private void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (this.plugin.config().ignores(block.getWorld())) return;
        this.plugin.log(LogEvent.of(EventType.BLOCK_BREAK)
            .player(event.getPlayer())
            .at(block)
            .data("block", Names.key(block.getType())));
    }

    private void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (this.plugin.config().ignores(block.getWorld())) return;
        this.plugin.log(LogEvent.of(EventType.BLOCK_PLACE)
            .player(event.getPlayer())
            .at(block)
            .data("block", Names.key(block.getType())));
    }

    private void onEntityExplode(EntityExplodeEvent event) {
        if (event.blockList().isEmpty()) return;
        Location location = event.getLocation();
        if (this.plugin.config().ignores(location.getWorld()) || !this.allowExplosion()) return;

        Entity entity = event.getEntity();
        LogEvent log = LogEvent.of(EventType.EXPLOSION)
            .at(location)
            .data("source", Names.key(entity.getType()))
            .data("blocks", event.blockList().size());
        if (entity instanceof TNTPrimed) {
            Entity source = ((TNTPrimed) entity).getSource();
            if (source instanceof Player) log.player((Player) source);
        }
        else if (entity instanceof Creeper) {
            LivingEntity target = ((Creeper) entity).getTarget();
            if (target instanceof Player) log.target((Player) target);
        }
        this.plugin.log(log);
    }

    private void onBlockExplode(BlockExplodeEvent event) {
        if (event.blockList().isEmpty()) return;
        Block block = event.getBlock();
        if (this.plugin.config().ignores(block.getWorld()) || !this.allowExplosion()) return;
        this.plugin.log(LogEvent.of(EventType.EXPLOSION)
            .at(block)
            .data("source", Names.key(block.getType()))
            .data("blocks", event.blockList().size()));
    }

    private boolean allowExplosion() {
        long now = System.currentTimeMillis() / 1000L;
        long current = this.second.get();
        if (current != now && this.second.compareAndSet(current, now)) this.explosions.set(0);
        return this.explosions.incrementAndGet() <= EXPLOSIONS_PER_SECOND;
    }
}
