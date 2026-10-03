package ru.extrack.plugin.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Messages. The file is generated from the defaults below, keys missing after an update are added
 * on load, so admins never lose their edits.
 */
public final class Lang {

    private static final List<LangText> ALL = new ArrayList<>();

    private static final String PREFIX_PATH    = "Prefix";
    private static final String PREFIX_DEFAULT = "<dgray>[<#4C8DFF>ExTrack<dgray>] <gray>";

    public static final LangText COMMAND_HELP = text("Command.Help",
        "<prefix><white>Команды ExTrack",
        "<white>/extrack link <код> <dgray>- <gray>привязать игровой аккаунт к панели",
        "<white>/extrack code <код> <dgray>- <gray>подтвердить вход сотрудника",
        "<white>/extrack info <ник> <dgray>- <gray>сводка по игроку из панели",
        "<white>/extrack status <dgray>- <gray>состояние подключения",
        "<white>/extrack reload <dgray>- <gray>перечитать config.yml и messages.yml",
        "<white>/report <ник> <текст> <dgray>- <gray>пожаловаться на игрока",
        "<white>/bug <текст> <dgray>- <gray>сообщить об ошибке");
    public static final LangText COMMAND_USAGE         = text("Command.Usage", "<prefix><lred>Использование: <white>%usage%");
    public static final LangText COMMAND_UNKNOWN       = text("Command.Unknown", "<prefix><lred>Нет такой команды. <gray>Список: <white>/extrack help");
    public static final LangText COMMAND_NO_PERMISSION = text("Command.NoPermission", "<prefix><lred>Недостаточно прав.");
    public static final LangText COMMAND_PLAYER_ONLY   = text("Command.PlayerOnly", "<prefix><lred>Команда доступна только игрокам.");
    public static final LangText COMMAND_CONSOLE_ONLY  = text("Command.ConsoleOnly", "<prefix><lred>Команда выполняется только из консоли или по RCON.");
    public static final LangText COMMAND_COOLDOWN      = text("Command.Cooldown", "<prefix><lred>Повторить можно через <white>%seconds% сек.");

    public static final LangText ERROR_NOT_CONNECTED = text("Error.NotConnected", "<prefix><lred>Плагин не подключён к панели. <gray>Проверьте <white>token<gray> в config.yml.");
    public static final LangText ERROR_REQUEST       = text("Error.Request", "<prefix><lred>Запрос не выполнен: <gray>%error%");
    public static final LangText ERROR_NO_PLAYER     = text("Error.PlayerNotFound", "<prefix><lred>Игрок <white>%player%<lred> не найден.");

    public static final LangText RELOAD_DONE = text("Reload.Done", "<prefix><lgreen>Конфигурация перезагружена.");

    public static final LangText STATUS_INFO = text("Status.Info",
        "<prefix><white>Подключение к панели",
        "<gray>Адрес: <white>%url%",
        "<gray>Связь: %state%",
        "<gray>Сервер в панели: <white>%server%",
        "<gray>Ждут отправки: <white>%queued%<gray>, потеряно: <white>%dropped%",
        "<gray>Последняя ошибка: <white>%error%");
    public static final LangText STATUS_ONLINE     = text("Status.Online", "<lgreen>на связи");
    public static final LangText STATUS_CONNECTING = text("Status.Connecting", "<lyellow>подключение");
    public static final LangText STATUS_OFFLINE    = text("Status.Offline", "<lred>нет связи");
    public static final LangText STATUS_BAD_TOKEN  = text("Status.BadToken", "<lred>токен не принят");
    public static final LangText STATUS_IP_BLOCKED = text("Status.IpBlocked", "<lred>IP сервера не в белом списке");
    public static final LangText STATUS_LOCKED     = text("Status.Locked", "<lorange>сервер заморожен по тарифу");
    public static final LangText STATUS_NO_TOKEN   = text("Status.NoToken", "<lred>токен не указан");

    public static final LangText LINK_FORMAT  = text("Link.Format", "<prefix><lred>Код состоит из 6 латинских букв и цифр. <gray>Возьмите его в обзоре сервера на сайте.");
    public static final LangText LINK_DONE    = text("Link.Done", "<prefix><lgreen>Аккаунт привязан к профилю <white>%nickname%<lgreen>.");
    public static final LangText LINK_INVALID = text("Link.Invalid", "<prefix><lred>Код не подошёл или устарел. <gray>Получите новый на сайте.");
    public static final LangText LINK_TAKEN   = text("Link.Taken", "<prefix><lred>Этот игровой аккаунт уже привязан к другому сотруднику.");

    public static final LangText STAFF_REQUIRED = text("Staff.Required",
        "<prefix><lyellow>Подтвердите вход в аккаунт сотрудника.",
        "<gray>Код пришёл на сайт ExTrack и в Telegram. Введите <white>/extrack code <код>");
    public static final LangText STAFF_REMINDER     = text("Staff.Reminder", "<prefix><lyellow>Вход не подтверждён. <gray>Введите <white>/extrack code <код>");
    public static final LangText STAFF_VERIFIED     = text("Staff.Verified", "<prefix><lgreen>Вход подтверждён.");
    public static final LangText STAFF_WRONG        = text("Staff.Wrong", "<prefix><lred>Неверный код. <gray>Осталось попыток: <white>%attempts%");
    public static final LangText STAFF_FORMAT       = text("Staff.Format", "<prefix><lred>Код состоит только из цифр.");
    public static final LangText STAFF_CHECKING     = text("Staff.Checking", "<prefix><gray>Код проверяется, подождите пару секунд.");
    public static final LangText STAFF_NOT_REQUIRED = text("Staff.NotRequired", "<prefix><gray>Подтверждать вход не нужно.");
    public static final LangText STAFF_KICK_EXPIRED = text("Staff.Kick.Expired",
        "<lred>Время на подтверждение входа истекло.",
        "<gray>Зайдите снова и введите новый код.");
    public static final LangText STAFF_KICK_LOCKED = text("Staff.Kick.Locked",
        "<lred>Вход заблокирован после нескольких неверных кодов.",
        "<gray>Владелец сервера получил уведомление.");
    public static final LangText STAFF_KICK_UNAVAILABLE = text("Staff.Kick.Unavailable",
        "<lred>Не удалось проверить вход сотрудника.",
        "<gray>Панель ExTrack не ответила, попробуйте зайти через минуту.");

    public static final LangText REPORT_SENT      = text("Report.Sent", "<prefix><lgreen>Жалоба отправлена администрации, номер <white>#%number%<lgreen>.");
    public static final LangText REPORT_BUG_SENT  = text("Report.BugSent", "<prefix><lgreen>Сообщение об ошибке отправлено, номер <white>#%number%<lgreen>.");
    public static final LangText REPORT_SELF      = text("Report.Self", "<prefix><lred>Нельзя пожаловаться на самого себя.");
    public static final LangText REPORT_TOO_SHORT = text("Report.TooShort", "<prefix><lred>Опишите проблему подробнее.");
    public static final LangText REPORT_LIMIT     = text("Report.Limit", "<prefix><lred>Слишком много обращений. <gray>Попробуйте через несколько минут.");
    public static final LangText REPORT_DISABLED  = text("Report.Disabled", "<prefix><lred>Обращения из игры отключены.");

    public static final LangText INFO_CARD = text("Info.Card",
        "<prefix><white>%player%",
        "<gray>Впервые: <white>%first_seen%<gray>, наиграно: <white>%playtime%",
        "<gray>Аккаунтов с тех же IP: <white>%alts%%watched%");
    public static final LangText INFO_WATCHED     = text("Info.Watched", "<gray>, <lorange>под наблюдением");
    public static final LangText INFO_PUNISHMENT  = text("Info.Punishment", "<dgray>- <lred>%type%<gray> до <white>%until%<gray>: %reason%");
    public static final LangText INFO_CLEAN       = text("Info.Clean", "<gray>Активных наказаний нет.");
    public static final LangText INFO_NOTE        = text("Info.Note", "<dgray>- %pinned%<gray>%author%: <white>%text%");
    public static final LangText INFO_PINNED      = text("Info.Pinned", "<lorange>[закреплено] ");
    public static final LangText INFO_UNKNOWN     = text("Info.Unknown", "<prefix><gray>Игрок <white>%player%<gray> ещё не появлялся в логах.");
    public static final LangText INFO_FOREVER     = text("Info.Forever", "навсегда");
    public static final LangText INFO_BAN         = text("Info.Type.Ban", "бан");
    public static final LangText INFO_IPBAN       = text("Info.Type.IpBan", "бан по IP");
    public static final LangText INFO_MUTE        = text("Info.Type.Mute", "мут");
    public static final LangText INFO_HOURS       = text("Info.Hours", "%number% ч");
    public static final LangText INFO_MINUTES     = text("Info.Minutes", "%number% мин");

    public static final LangText DONATION_DONE    = text("Donation.Done", "<prefix><lgreen>Донат записан: <white>%player%<lgreen>, <white>%amount% %currency%<lgreen>, %product%");
    public static final LangText DONATION_AMOUNT  = text("Donation.Amount", "<prefix><lred>Неверная сумма: <white>%amount%");
    public static final LangText DONATION_UNKNOWN = text("Donation.Unknown", "<prefix><lred>Игрок <white>%player%<lred> ещё не заходил на сервер, донат не записан.");

    public static final LangText ACTION_MESSAGE = text("Action.Message", "<prefix><white>%message%");

    private Lang() {
    }

    public static void load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        boolean changed = false;
        if (!yaml.contains(PREFIX_PATH)) {
            yaml.set(PREFIX_PATH, PREFIX_DEFAULT);
            changed = true;
        }
        for (LangText text : ALL) {
            if (yaml.contains(text.path())) continue;
            yaml.set(text.path(), text.defaultValue());
            changed = true;
        }
        if (changed) {
            try {
                yaml.save(file);
            }
            catch (IOException exception) {
                plugin.getLogger().warning("Не удалось сохранить messages.yml: " + exception.getMessage());
            }
        }

        String prefix = yaml.getString(PREFIX_PATH, PREFIX_DEFAULT);
        for (LangText text : ALL) {
            text.load(yaml, prefix);
        }
    }

    private static LangText text(String path, String... lines) {
        LangText text = new LangText(path, lines);
        ALL.add(text);
        return text;
    }
}
