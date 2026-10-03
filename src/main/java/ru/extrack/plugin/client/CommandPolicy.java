package ru.extrack.plugin.client;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Console commands the panel may run. Punishment templates are edited on the site, so even a stolen
 * panel account must not be able to turn this server against its owner.
 */
public final class CommandPolicy {

    /** Never allowed, whatever the config says: these hand out rights or control the server. */
    private static final Set<String> FORBIDDEN = new HashSet<>(Arrays.asList(
        "op", "deop", "execute", "sudo", "stop", "restart", "reload", "rl", "plugman", "plugins",
        "lp", "luckperms", "perm", "perms", "permission", "permissions", "pex", "manuadd", "mangadd",
        "whitelist", "save-off", "function", "datapack", "script", "skript", "sk", "eval", "js"
    ));

    private static final int MAX_LENGTH = 512;

    private final Set<String> allowed;

    private CommandPolicy(Set<String> allowed) {
        this.allowed = allowed;
    }

    public static CommandPolicy of(Collection<String> commands, Logger logger) {
        Set<String> allowed = new HashSet<>();
        for (String command : commands) {
            if (command == null) continue;
            String label = command.trim().toLowerCase(Locale.ROOT);
            if (label.startsWith("/")) label = label.substring(1);
            if (label.isEmpty()) continue;
            if (FORBIDDEN.contains(label)) {
                logger.warning("Команда " + label + " в allowed-commands проигнорирована: панель не может выполнять её ни при каких настройках.");
                continue;
            }
            allowed.add(label);
        }
        return new CommandPolicy(Collections.unmodifiableSet(allowed));
    }

    /**
     * @return the command ready for dispatch, or null if it must not run
     */
    public String check(String command) {
        if (command == null) return null;
        String line = command.trim();
        if (line.startsWith("/")) line = line.substring(1);
        if (line.isEmpty() || line.length() > MAX_LENGTH) return null;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            // line breaks, other control characters and color codes have no place in a punishment command
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029' || c == '\u00a7') return null;
        }

        int space = line.indexOf(' ');
        String label = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = label.lastIndexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (label.isEmpty() || FORBIDDEN.contains(label) || !this.allowed.contains(label)) return null;
        return line;
    }

    public Set<String> allowed() {
        return this.allowed;
    }
}
