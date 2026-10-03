package ru.extrack.plugin.platform;

import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Game thread access that behaves the same on Paper and Folia.
 * Network and file work never goes through here, the plugin has its own executors for that.
 */
public interface TaskScheduler {

    static TaskScheduler create(Plugin plugin, Platform platform) {
        return platform.isFolia() ? new FoliaTaskScheduler(plugin) : new BukkitTaskScheduler(plugin);
    }

    /** Main thread on Paper, global region on Folia. */
    void runGlobal(Runnable task);

    /** Runs on the thread that owns the entity, or runs {@code retired} if the entity is already gone. */
    void runEntity(Entity entity, Runnable task, Runnable retired);

    default void runEntity(Entity entity, Runnable task) {
        this.runEntity(entity, task, null);
    }

    /** Finds an online player by UUID and runs the action on their thread. */
    void runPlayer(UUID uuid, Consumer<Player> action, Runnable offline);

    Task runGlobalTimer(Runnable task, long delayTicks, long periodTicks);

    void cancelAll();

    interface Task {

        void cancel();
    }
}
