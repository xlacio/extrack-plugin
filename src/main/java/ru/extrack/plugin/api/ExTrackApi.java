package ru.extrack.plugin.api;

import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.event.LogEvent;

/**
 * Entry point for other plugins: shops, economy, anti-cheats. Add {@code softdepend: [ExTrack]} to your
 * plugin.yml and log whatever matters on your server:
 *
 * <pre>{@code
 * ExTrackApi.log(LogEvent.of(EventType.SHOP)
 *     .player(player)
 *     .message("Купил 16 алмазов")
 *     .data("price", 1200));
 * }</pre>
 *
 * Calls are cheap and safe from any thread, events are sent in the background.
 */
public final class ExTrackApi {

    private static volatile ExTrackPlugin plugin;

    private ExTrackApi() {
    }

    public static void log(LogEvent event) {
        ExTrackPlugin current = plugin;
        if (current != null && event != null) current.log(event);
    }

    /** Whether the plugin is running and has a token. Events are queued even while the panel is unreachable. */
    public static boolean isAvailable() {
        ExTrackPlugin current = plugin;
        return current != null && current.client() != null;
    }

    public static void bind(ExTrackPlugin instance) {
        plugin = instance;
    }

    public static void unbind() {
        plugin = null;
    }
}
