package ru.extrack.plugin.event;

/**
 * Event types the API accepts. Anything that does not fit goes to {@link #CUSTOM} with details in data.
 */
public enum EventType {

    JOIN("join"),
    QUIT("quit"),
    WORLD_CHANGE("world_change"),
    TELEPORT("teleport"),
    GAMEMODE("gamemode"),
    CHAT("chat"),
    COMMAND("command"),
    PRIVATE_MESSAGE("private_message"),
    SIGN_EDIT("sign_edit"),
    BOOK_EDIT("book_edit"),
    ANVIL_RENAME("anvil_rename"),
    BLOCK_BREAK("block_break"),
    BLOCK_PLACE("block_place"),
    EXPLOSION("explosion"),
    CONTAINER_OPEN("container_open"),
    CONTAINER_TAKE("container_take"),
    CONTAINER_PUT("container_put"),
    ITEM_DROP("item_drop"),
    ITEM_PICKUP("item_pickup"),
    CRAFT("craft"),
    DEATH("death"),
    KILL("kill"),
    MOB_KILL("mob_kill"),
    TRADE("trade"),
    ECONOMY("economy"),
    SHOP("shop"),
    DONATION("donation"),
    PUNISHMENT("punishment"),
    ANTICHEAT("anticheat"),
    REPORT("report"),
    STAFF_AUTH("staff_auth"),
    ADVANCEMENT("advancement"),
    CUSTOM("custom");

    private final String id;

    EventType(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }
}
