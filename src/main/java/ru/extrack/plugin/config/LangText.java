package ru.extrack.plugin.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import ru.extrack.plugin.util.Colorizer;

/**
 * A message from messages.yml. Formatting tags are resolved once on load, placeholder values are inserted
 * afterwards and never parsed. Values that come from outside (panel texts, error messages) must go through
 * {@link Colorizer#strip} first, values that are other messages keep their colors.
 */
public final class LangText {

    private final String       path;
    private final List<String> defaults;

    private volatile String text = "";

    LangText(String path, String... defaults) {
        this.path = path;
        this.defaults = Collections.unmodifiableList(Arrays.asList(defaults));
    }

    String path() {
        return this.path;
    }

    Object defaultValue() {
        return this.defaults.size() == 1 ? this.defaults.get(0) : this.defaults;
    }

    void load(ConfigurationSection yaml, String prefix) {
        List<String> lines = yaml.isList(this.path)
            ? yaml.getStringList(this.path)
            : Collections.singletonList(yaml.getString(this.path, ""));
        this.text = Colorizer.apply(String.join("\n", lines).replace("<prefix>", prefix));
    }

    /** @param replacements pairs of placeholder and value: {@code "%player%", name} */
    public String text(Object... replacements) {
        String result = this.text;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            result = result.replace(String.valueOf(replacements[i]), String.valueOf(replacements[i + 1]));
        }
        return result;
    }

    public void send(CommandSender sender, Object... replacements) {
        String message = this.text(replacements);
        if (!message.isEmpty()) sender.sendMessage(message);
    }
}
