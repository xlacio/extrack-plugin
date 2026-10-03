package ru.extrack.plugin.command;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.Perms;
import ru.extrack.plugin.Placeholders;
import ru.extrack.plugin.client.ApiException;
import ru.extrack.plugin.client.ExTrackClient;
import ru.extrack.plugin.config.Config;
import ru.extrack.plugin.config.Lang;
import ru.extrack.plugin.util.Colorizer;
import ru.extrack.plugin.util.Names;

/** /report and /bug: both become tickets in the panel. */
public final class ReportCommand implements TabExecutor {

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 1000;

    private final ExTrackPlugin   plugin;
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();

    public ReportCommand(ExTrackPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean bug = command.getName().equalsIgnoreCase("bug");
        if (!(sender instanceof Player)) {
            Lang.COMMAND_PLAYER_ONLY.send(sender);
            return true;
        }
        if (!sender.hasPermission(bug ? Perms.BUG : Perms.REPORT)) {
            Lang.COMMAND_NO_PERMISSION.send(sender);
            return true;
        }
        Config config = this.plugin.config();
        if (!config.reports()) {
            Lang.REPORT_DISABLED.send(sender);
            return true;
        }
        if (args.length < (bug ? 1 : 2)) {
            Lang.COMMAND_USAGE.send(sender, Placeholders.USAGE, bug ? "/bug <текст>" : "/report <ник> <текст>");
            return true;
        }

        Player player = (Player) sender;
        String target = null;
        if (!bug) {
            target = args[0];
            if (!Names.isPlayerName(target)) {
                Lang.ERROR_NO_PLAYER.send(sender, Placeholders.PLAYER, target);
                return true;
            }
            if (target.equalsIgnoreCase(player.getName())) {
                Lang.REPORT_SELF.send(sender);
                return true;
            }
        }

        String text = String.join(" ", Arrays.copyOfRange(args, bug ? 0 : 1, args.length)).trim();
        if (text.length() < MIN_LENGTH) {
            Lang.REPORT_TOO_SHORT.send(sender);
            return true;
        }
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH);

        ExTrackClient client = this.plugin.client();
        if (client == null) {
            Lang.ERROR_NOT_CONNECTED.send(sender);
            return true;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldown = config.reportCooldownSeconds() * 1000L;
        Long last = this.lastReport.get(uuid);
        if (last != null && now - last < cooldown) {
            Lang.COMMAND_COOLDOWN.send(sender, Placeholders.SECONDS, (cooldown - (now - last)) / 1000L + 1L);
            return true;
        }
        this.lastReport.put(uuid, now);
        if (this.lastReport.size() > 1_000) this.lastReport.values().removeIf(time -> now - time > cooldown);

        Location location = player.getLocation();
        World world = location.getWorld();
        String name = player.getName();
        String reportTarget = target;
        String reportText = text;
        this.plugin.io().execute(() -> {
            try {
                int number = client.report(bug, uuid, name, reportTarget, reportText,
                    world == null ? null : world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
                this.plugin.tell(sender, bug ? Lang.REPORT_BUG_SENT : Lang.REPORT_SENT, Placeholders.NUMBER, number);
            }
            catch (ApiException exception) {
                if (exception.isRateLimited()) this.plugin.tell(sender, Lang.REPORT_LIMIT);
                else this.plugin.tell(sender, Lang.ERROR_REQUEST, Placeholders.ERROR, Colorizer.strip(exception.getMessage()));
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && command.getName().equalsIgnoreCase("report")) return ExTrackCommand.playerNames(args[0]);
        return Collections.emptyList();
    }
}
