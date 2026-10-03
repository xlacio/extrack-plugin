package ru.extrack.plugin.platform;

import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

final class BukkitTaskScheduler implements TaskScheduler {

    private final Plugin plugin;

    BukkitTaskScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runGlobal(Runnable task) {
        if (this.plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(this.plugin, task);
        }
    }

    @Override
    public void runEntity(Entity entity, Runnable task, Runnable retired) {
        this.runGlobal(() -> {
            if (entity.isValid()) task.run();
            else if (retired != null) retired.run();
        });
    }

    @Override
    public void runPlayer(UUID uuid, Consumer<Player> action, Runnable offline) {
        this.runGlobal(() -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) action.accept(player);
            else if (offline != null) offline.run();
        });
    }

    @Override
    public Task runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(this.plugin, task, delayTicks, periodTicks);
        return bukkitTask::cancel;
    }

    @Override
    public void cancelAll() {
        Bukkit.getScheduler().cancelTasks(this.plugin);
    }
}
