package ru.extrack.plugin.util;

import java.util.function.Consumer;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * Lambda based listener registration. Lets the plugin subscribe only to the events that are enabled
 * in the config: Paper does not even construct some events (moves, for example) while nobody listens.
 */
public final class Events {

    private final Plugin   plugin;
    private final Listener owner = new Listener() {};

    public Events(Plugin plugin) {
        this.plugin = plugin;
    }

    public <E extends Event> void on(Class<E> type, EventPriority priority, Consumer<E> handler) {
        this.on(type, priority, false, handler);
    }

    public <E extends Event> void on(Class<E> type, EventPriority priority, boolean ignoreCancelled, Consumer<E> handler) {
        // events without their own HandlerList share the parent's, so subclasses of other kinds arrive here too
        EventExecutor executor = (listener, event) -> {
            if (type.isInstance(event)) handler.accept(type.cast(event));
        };
        this.plugin.getServer().getPluginManager().registerEvent(type, this.owner, priority, executor, this.plugin, ignoreCancelled);
    }

    public void unregisterAll() {
        HandlerList.unregisterAll(this.owner);
    }
}
