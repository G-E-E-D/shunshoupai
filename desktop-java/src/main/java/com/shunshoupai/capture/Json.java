package com.shunshoupai.capture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class Json {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private Json() {}
    static JsonObject object(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static JsonObject message(String type) { JsonObject value = new JsonObject(); value.addProperty("type", type); return value; }
    static String string(JsonObject value, String key, String fallback) {
        return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : fallback;
    }
    static boolean bool(JsonObject value, String key) { return value.has(key) && value.get(key).getAsBoolean(); }
}
