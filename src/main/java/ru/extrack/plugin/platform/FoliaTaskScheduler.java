package ru.extrack.plugin.platform;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Folia schedulers through method handles: the plugin is compiled against the 1.16.5 API,
 * which has none of these classes.
 */
final class FoliaTaskScheduler implements TaskScheduler {

    private static final String PACKAGE = "io.papermc.paper.threadedregions.scheduler.";

    private final Plugin       plugin;
    private final Object       globalScheduler;
    private final MethodHandle globalExecute;
    private final MethodHandle globalTimer;
    private final MethodHandle globalCancel;
    private final MethodHandle entityScheduler;
    private final MethodHandle entityRun;
    private final MethodHandle taskCancel;

    FoliaTaskScheduler(Plugin plugin) {
        this.plugin = plugin;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> globalType = Class.forName(PACKAGE + "GlobalRegionScheduler");
            Class<?> entityType = Class.forName(PACKAGE + "EntityScheduler");
            Class<?> taskType = Class.forName(PACKAGE + "ScheduledTask");

            this.globalScheduler = Server.class.getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
            this.globalExecute = lookup.unreflect(globalType.getMethod("execute", Plugin.class, Runnable.class));
            this.globalTimer = lookup.unreflect(globalType.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class));
            this.globalCancel = lookup.unreflect(globalType.getMethod("cancelTasks", Plugin.class));
            this.entityScheduler = lookup.unreflect(Entity.class.getMethod("getScheduler"));
            this.entityRun = lookup.unreflect(entityType.getMethod("run", Plugin.class, Consumer.class, Runnable.class));
            this.taskCancel = lookup.unreflect(taskType.getMethod("cancel"));
        }
        catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Folia scheduler API is not available", exception);
        }
    }

    @Override
    public void runGlobal(Runnable task) {
        if (!this.plugin.isEnabled()) return;
        try {
            this.globalExecute.invoke(this.globalScheduler, this.plugin, task);
        }
        catch (Throwable throwable) {
            throw rethrow(throwable);
        }
    }

    @Override
    public void runEntity(Entity entity, Runnable task, Runnable retired) {
        if (!this.plugin.isEnabled()) return;
        Consumer<Object> consumer = scheduled -> task.run();
        Object scheduled;
        try {
            Object scheduler = this.entityScheduler.invoke(entity);
            scheduled = this.entityRun.invoke(scheduler, this.plugin, consumer, retired);
        }
        catch (Throwable throwable) {
            throw rethrow(throwable);
        }
        // null means the entity was removed before the task could be queued, Folia does not call retired then
        if (scheduled == null && retired != null) retired.run();
    }

    @Override
    public void runPlayer(UUID uuid, Consumer<Player> action, Runnable offline) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            if (offline != null) offline.run();
            return;
        }
        this.runEntity(player, () -> action.accept(player), offline);
    }

    @Override
    public Task runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        Consumer<Object> consumer = scheduled -> task.run();
        Object scheduled;
        try {
            scheduled = this.globalTimer.invoke(this.globalScheduler, this.plugin, consumer, Math.max(1L, delayTicks), periodTicks);
        }
        catch (Throwable throwable) {
            throw rethrow(throwable);
        }
        return () -> {
            try {
                this.taskCancel.invoke(scheduled);
            }
            catch (Throwable throwable) {
                throw rethrow(throwable);
            }
        };
    }

    @Override
    public void cancelAll() {
        try {
            this.globalCancel.invoke(this.globalScheduler, this.plugin);
        }
        catch (Throwable throwable) {
            throw rethrow(throwable);
        }
    }

    private static RuntimeException rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException) return (RuntimeException) throwable;
        if (throwable instanceof Error) throw (Error) throwable;
        return new IllegalStateException(throwable);
    }
}
