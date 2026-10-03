package ru.extrack.plugin.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import ru.extrack.plugin.ExTrackPlugin;
import ru.extrack.plugin.config.Config;
import ru.extrack.plugin.event.EventType;
import ru.extrack.plugin.event.LogEvent;
import ru.extrack.plugin.platform.Compat;
import ru.extrack.plugin.util.Commands;
import ru.extrack.plugin.util.Events;
import ru.extrack.plugin.util.Names;

public final class ChatListener {

    private static final int ANVIL_RESULT = 2;
    private static final int BOOK_LIMIT   = 2000;

    private final ExTrackPlugin plugin;

    public ChatListener(ExTrackPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(Events events, Config config) {
        if (config.logs(EventType.CHAT)) events.on(AsyncChatEvent.class, EventPriority.MONITOR, true, this::onChat);
        if (config.logs(EventType.COMMAND) || config.logs(EventType.PRIVATE_MESSAGE)) {
            events.on(PlayerCommandPreprocessEvent.class, EventPriority.MONITOR, true, this::onCommand);
        }
        if (config.logs(EventType.SIGN_EDIT)) events.on(SignChangeEvent.class, EventPriority.MONITOR, true, this::onSign);
        if (config.logs(EventType.BOOK_EDIT)) events.on(PlayerEditBookEvent.class, EventPriority.MONITOR, true, this::onBook);
        if (config.logs(EventType.ANVIL_RENAME)) events.on(InventoryClickEvent.class, EventPriority.MONITOR, true, this::onAnvil);
    }

    private void onChat(AsyncChatEvent event) {
        this.plugin.log(LogEvent.of(EventType.CHAT)
            .player(event.getPlayer())
            .message(Compat.plain(event.message())));
    }

    private void onCommand(PlayerCommandPreprocessEvent event) {
        Config config = this.plugin.config();
        String message = event.getMessage();
        String line = message.startsWith("/") ? message.substring(1) : message;
        int space = line.indexOf(' ');
        String label = Commands.label(space < 0 ? line : line.substring(0, space));
        if (label.isEmpty() || config.ignoredCommands().contains(label)) return;

        Player player = event.getPlayer();
        if (config.privateCommands().contains(label)) {
            // never logged as a plain command: private messages are visible only to staff with that right
            if (config.logs(EventType.PRIVATE_MESSAGE)) this.logPrivate(player, label, space < 0 ? "" : line.substring(space + 1).trim(), config);
            return;
        }
        if (!config.logs(EventType.COMMAND)) return;

        if (config.secretCommands().contains(label)) {
            // keep the subcommand of our own command readable: "/extrack code ******"
            int keep = label.equals("extrack") || label.equals("et") ? 1 : 0;
            message = Commands.mask(message, keep);
        }
        this.plugin.log(LogEvent.of(EventType.COMMAND).player(player).message(message));
    }

    private void logPrivate(Player player, String label, String arguments, Config config) {
        String target = null;
        String text = arguments;
        if (!config.replyCommands().contains(label)) {
            int space = arguments.indexOf(' ');
            if (space < 0) return;
            target = arguments.substring(0, space);
            text = arguments.substring(space + 1).trim();
        }
        if (text.isEmpty()) return;

        LogEvent log = LogEvent.of(EventType.PRIVATE_MESSAGE).player(player).message(text).data("command", label);
        if (Names.isPlayerName(target)) log.target(target);
        this.plugin.log(log);
    }

    private void onSign(SignChangeEvent event) {
        Block block = event.getBlock();
        if (this.plugin.config().ignores(block.getWorld())) return;

        StringBuilder text = new StringBuilder();
        for (Component line : event.lines()) {
            String plain = Compat.plain(line);
            if (plain == null || plain.trim().isEmpty()) continue;
            if (text.length() > 0) text.append(" | ");
            text.append(plain.trim());
        }
        if (text.length() == 0) return;
        this.plugin.log(LogEvent.of(EventType.SIGN_EDIT).player(event.getPlayer()).at(block).message(text.toString()));
    }

    private void onBook(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        if (this.plugin.config().ignores(player.getWorld())) return;

        BookMeta book = event.getNewBookMeta();
        StringBuilder text = new StringBuilder();
        String title = event.isSigning() ? Compat.plain(book.title()) : null;
        if (title != null && !title.isEmpty()) text.append('[').append(title).append("] ");
        List<Component> pages = book.pages();
        for (Component page : pages) {
            String plain = Compat.plain(page);
            if (plain == null || plain.isEmpty()) continue;
            if (text.length() > 0) text.append(' ');
            text.append(plain.replace('\n', ' '));
            if (text.length() >= BOOK_LIMIT) break;
        }
        if (text.length() == 0) return;
        this.plugin.log(LogEvent.of(EventType.BOOK_EDIT)
            .player(player)
            .at(player.getLocation())
            .message(text.toString())
            .data("pages", pages.size())
            .data("signed", event.isSigning()));
    }

    // InventoryClickEvent fires for every click in every inventory: the cheap checks go first
    private void onAnvil(InventoryClickEvent event) {
        if (event.getRawSlot() != ANVIL_RESULT || event.getInventory().getType() != InventoryType.ANVIL) return;
        if (!(event.getWhoClicked() instanceof Player)) return;

        ItemStack result = event.getCurrentItem();
        if (result == null || !result.hasItemMeta()) return;
        ItemMeta meta = result.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String name = Compat.plain(meta.displayName());
        if (name == null || name.isEmpty() || name.equals(displayName(event.getInventory().getItem(0)))) return;

        Player player = (Player) event.getWhoClicked();
        this.plugin.log(LogEvent.of(EventType.ANVIL_RENAME)
            .player(player)
            .at(player.getLocation())
            .message(name)
            .data("item", Names.key(result.getType())));
    }

    /** A repair keeps the old name, only a changed one is a rename. */
    private static String displayName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? Compat.plain(meta.displayName()) : null;
    }
}
