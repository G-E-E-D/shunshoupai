package com.shunshoupai.app;

import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** Android JSONObject accepts non-JSON syntax; model input must use strict JSON. */
final class PlanningJson {
    static JSONObject object(String input,int limit)throws Exception {
        if(input==null||input.length()>limit)throw new IllegalArgumentException("JSON too large");
        try(JsonReader reader=new JsonReader(new StringReader(input))) {
            reader.setLenient(false);Object value=read(reader,0);
            if(!(value instanceof JSONObject)||reader.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException("Invalid JSON object");
            return (JSONObject)value;
        }
    }
    private static Object read(JsonReader reader,int depth)throws Exception {
        if(depth>20)throw new IllegalArgumentException("JSON too deep");
        switch(reader.peek()) {
            case BEGIN_OBJECT: {
                JSONObject result=new JSONObject();Set<String> keys=new HashSet<>();reader.beginObject();
                while(reader.hasNext()){String key=reader.nextName();if(!keys.add(key)||keys.size()>1000)throw new IllegalArgumentException("Duplicate JSON key");result.put(key,read(reader,depth+1));}
                reader.endObject();return result;
            }
            case BEGIN_ARRAY: {
                JSONArray result=new JSONArray();reader.beginArray();while(reader.hasNext()){if(result.length()>=1000)throw new IllegalArgumentException("JSON array too large");result.put(read(reader,depth+1));}reader.endArray();return result;
            }
            case STRING:return reader.nextString();
            case BOOLEAN:return reader.nextBoolean();
            case NULL:reader.nextNull();return JSONObject.NULL;
            case NUMBER:{double number=Double.parseDouble(reader.nextString());if(!Double.isFinite(number))throw new IllegalArgumentException("Invalid number");return number;}
            default:throw new IllegalArgumentException("Invalid JSON value");
        }
    }
    static String string(JSONObject object,String key)throws Exception {Object value=object.get(key);if(!(value instanceof String))throw new IllegalArgumentException("Expected string");return (String)value;}
    static boolean bool(JSONObject object,String key)throws Exception {Object value=object.get(key);if(!(value instanceof Boolean))throw new IllegalArgumentException("Expected boolean");return (Boolean)value;}
    static void keys(JSONObject object,String... allowed)throws Exception {
        Set<String> names=new HashSet<>(java.util.Arrays.asList(allowed));java.util.Iterator<String> keys=object.keys();while(keys.hasNext())if(!names.contains(keys.next()))throw new IllegalArgumentException("Unknown field");
    }
}
