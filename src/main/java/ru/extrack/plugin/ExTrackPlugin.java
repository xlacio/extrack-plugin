package ru.extrack.plugin;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ru.extrack.plugin.api.ExTrackApi;
import ru.extrack.plugin.client.EventQueue;
import ru.extrack.plugin.client.ExTrackClient;
import ru.extrack.plugin.command.ExTrackCommand;
import ru.extrack.plugin.command.ReportCommand;
import ru.extrack.plugin.config.Config;
import ru.extrack.plugin.config.Lang;
import ru.extrack.plugin.config.LangText;
import ru.extrack.plugin.console.ConsoleCapture;
import ru.extrack.plugin.console.ConsoleQueue;
import ru.extrack.plugin.event.LogEvent;
import ru.extrack.plugin.hook.LuckPermsHook;
import ru.extrack.plugin.listener.ChatListener;
import ru.extrack.plugin.listener.CombatListener;
import ru.extrack.plugin.listener.InventoryListener;
import ru.extrack.plugin.listener.SessionListener;
import ru.extrack.plugin.listener.WorldListener;
import ru.extrack.plugin.platform.Platform;
import ru.extrack.plugin.platform.TaskScheduler;
import ru.extrack.plugin.staff.StaffGuard;
import ru.extrack.plugin.util.Events;

public final class ExTrackPlugin extends JavaPlugin {

    private static final long SHUTDOWN_DRAIN_MILLIS = 3_000L;

    private Platform                 platform;
    private TaskScheduler            scheduler;
    private ScheduledExecutorService worker;
    private ExecutorService          io;
    private EventQueue               eventQueue;
    private ConsoleQueue             consoleQueue;
    private StaffGuard               staffGuard;
    private SessionListener          sessionListener;
    private Events                   moduleEvents;

    private volatile Config        config;
    private volatile ExTrackClient client;
    private volatile LuckPermsHook luckPerms;
    private ConsoleCapture         consoleCapture;

    @Override
    public void onEnable() {
        long started = System.currentTimeMillis();
        this.platform = Platform.detect();
        if (this.platform == null) {
            this.getLogger().severe("ExTrack работает на Paper, Purpur и Folia 1.16.5 и новее, на этом ядре плагин выключен.");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.scheduler = TaskScheduler.create(this, this.platform);
        this.worker = new ScheduledThreadPoolExecutor(1, threads("ExTrack Worker"));
        ThreadPoolExecutor io = new ThreadPoolExecutor(4, 4, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), threads("ExTrack IO"));
        io.allowCoreThreadTimeOut(true);
        this.io = io;

        this.eventQueue = new EventQueue();
        this.consoleQueue = new ConsoleQueue();
        this.loadSettings();
        this.worker.execute(() -> this.eventQueue.restore(this.spoolFile(), this.getLogger()));

        // staff checks stay registered for the whole life of the plugin, a reload must not open a gap
        this.staffGuard = new StaffGuard(this);
        this.staffGuard.start(new Events(this));
        this.sessionListener = new SessionListener(this);
        this.moduleEvents = this.enableModules();

        this.bind("extrack", new ExTrackCommand(this));
        ReportCommand reports = new ReportCommand(this);
        this.bind("report", reports);
        this.bind("bug", reports);

        this.connect();
        ExTrackApi.bind(this);
        this.getLogger().info("Плагин загружен за " + (System.currentTimeMillis() - started) + " мс ("
            + this.platform.id() + " " + Platform.minecraftVersion() + ").");
    }

    @Override
    public void onDisable() {
        if (this.platform == null) return;
        ExTrackApi.unbind();
        this.disableModules();
        this.staffGuard.stop();

        ExTrackClient current = this.client;
        this.client = null;
        if (current != null) current.close(SHUTDOWN_DRAIN_MILLIS);
        this.eventQueue.spool(this.spoolFile(), this.getLogger());

        this.scheduler.cancelAll();
        this.worker.shutdownNow();
        this.io.shutdownNow();
    }

    /** /extrack reload: new settings, new listeners, a new connection only if token or url changed. */
    public void reload() {
        Config previous = this.config;
        this.loadSettings();

        // subscribe the new set first, so no event slips between the two
        Events previousEvents = this.moduleEvents;
        this.disableModules();
        this.moduleEvents = this.enableModules();
        previousEvents.unregisterAll();

        if (this.client == null || previous.connectionChanged(this.config)) this.connect();
    }

    public void log(LogEvent event) {
        ExTrackClient current = this.client;
        if (current != null) current.log(event);
    }

    /** Sends a message from any thread, on the player's own thread if it is a player. */
    public void tell(CommandSender sender, LangText text, Object... replacements) {
        this.tell(sender, text.text(replacements));
    }

    public void tell(CommandSender sender, String message) {
        if (message.isEmpty()) return;
        if (sender instanceof Player) this.scheduler.runEntity((Player) sender, () -> sender.sendMessage(message));
        else sender.sendMessage(message);
    }

    public Platform platform() {
        return this.platform;
    }

    public TaskScheduler scheduler() {
        return this.scheduler;
    }

    /** Timers and short tasks. Never blocks on the network. */
    public ScheduledExecutorService worker() {
        return this.worker;
    }

    /** Requests to the panel. */
    public ExecutorService io() {
        return this.io;
    }

    public Config config() {
        return this.config;
    }

    public ExTrackClient client() {
        return this.client;
    }

    public LuckPermsHook luckPerms() {
        return this.luckPerms;
    }

    public StaffGuard staffGuard() {
        return this.staffGuard;
    }

    public EventQueue eventQueue() {
        return this.eventQueue;
    }

    public ConsoleQueue consoleQueue() {
        return this.consoleQueue;
    }

    private void loadSettings() {
        this.saveDefaultConfig();
        this.reloadConfig();
        this.config = Config.load(this.getConfig(), this.getLogger());
        Lang.load(this);
        this.eventQueue.setCapacity(this.config.bufferSize());
    }

    private Events enableModules() {
        Config config = this.config;
        Events events = new Events(this);
        this.sessionListener.register(events, config);
        new ChatListener(this).register(events, config);
        new WorldListener(this).register(events, config);
        new InventoryListener(this).register(events, config);
        new CombatListener(this).register(events, config);

        if (config.luckPermsGroups()) {
            LuckPermsHook hook = LuckPermsHook.enable(this);
            if (hook != null) events.on(PlayerQuitEvent.class, EventPriority.MONITOR, event -> hook.forget(event.getPlayer().getUniqueId()));
            this.luckPerms = hook;
        }
        if (config.consoleErrors()) this.consoleCapture = ConsoleCapture.attach(this.consoleQueue, this.getName(), this.getLogger());
        return events;
    }

    private void disableModules() {
        if (this.consoleCapture != null) {
            this.consoleCapture.detach();
            this.consoleCapture = null;
        }
        LuckPermsHook hook = this.luckPerms;
        this.luckPerms = null;
        if (hook != null) hook.close();
    }

    private void connect() {
        ExTrackClient previous = this.client;
        this.client = null;
        // queued events belong to the plugin, the next connection picks them up
        if (previous != null) previous.close(0L);

        if (!this.config.hasToken()) {
            this.getLogger().warning("Укажите token в config.yml и выполните /extrack reload. Токен выдаётся в панели: настройки сервера, раздел «Подключение».");
            return;
        }
        ExTrackClient next = new ExTrackClient(this, this.config);
        this.client = next;
        next.start();
    }

    private void bind(String name, TabExecutor executor) {
        PluginCommand command = this.getCommand(name);
        if (command == null) return;
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private File spoolFile() {
        return new File(this.getDataFolder(), "unsent-events.jsonl");
    }

    private static ThreadFactory threads(String name) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, name + " #" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
