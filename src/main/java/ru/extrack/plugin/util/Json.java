package ru.extrack.plugin.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Null-safe readers: the server may omit fields, send null, or be an older version. */
public final class Json {

    private Json() {
    }

    public static JsonObject object(JsonObject json, String key) {
        JsonElement element = json == null ? null : json.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static JsonArray array(JsonObject json, String key) {
        JsonElement element = json == null ? null : json.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    public static String string(JsonObject json, String key) {
        JsonPrimitive primitive = primitive(json, key);
        return primitive == null ? null : primitive.getAsString();
    }

    public static boolean bool(JsonObject json, String key) {
        JsonPrimitive primitive = primitive(json, key);
        return primitive != null && primitive.isBoolean() && primitive.getAsBoolean();
    }

    public static int integer(JsonObject json, String key, int fallback) {
        JsonPrimitive primitive = primitive(json, key);
        return primitive != null && primitive.isNumber() ? primitive.getAsInt() : fallback;
    }

    public static long number(JsonObject json, String key, long fallback) {
        JsonPrimitive primitive = primitive(json, key);
        return primitive != null && primitive.isNumber() ? primitive.getAsLong() : fallback;
    }

    private static JsonPrimitive primitive(JsonObject json, String key) {
        JsonElement element = json == null ? null : json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsJsonPrimitive() : null;
    }
}
