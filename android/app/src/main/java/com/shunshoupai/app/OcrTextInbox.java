package com.shunshoupai.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Private, bounded OCR handoff. Only confirmed text is stored, never the bitmap. */
final class OcrTextInbox {
    static final int MAX_TEXT_LENGTH=2000,MAX_PENDING=10;
    private static final Object LOCK=new Object();
    private final SharedPreferences preferences;
    static final class Entry {
        final String id,scope,text;final long createdAt;
        Entry(JSONObject value)throws Exception{id=value.getString("id");scope=value.getString("scope");text=value.getString("text");createdAt=value.getLong("created_at");}
    }
    OcrTextInbox(Context context){this(context,"ocr-text-inbox-v1");}
    OcrTextInbox(Context context,String namespace){preferences=context.getSharedPreferences(namespace,Context.MODE_PRIVATE);}
    String offer(String scope,String text) {
        if(scope==null||scope.trim().isEmpty()||scope.length()>512)throw new IllegalArgumentException("日程位置无效，请重新打开识别。");
        if(text==null||text.trim().isEmpty())throw new IllegalArgumentException("请先确认需要整理的文字。");
        if(text.length()>MAX_TEXT_LENGTH)throw new IllegalArgumentException("一次最多整理2000字，请修改识别文字或分次框选；内容没有被截断。");
        synchronized(LOCK){try{JSONArray pending=read();if(pending.length()>=MAX_PENDING)throw new IllegalStateException("还有待处理的识别文字，请先在智能整理中处理后再继续。");
            String id=UUID.randomUUID().toString();pending.put(new JSONObject().put("id",id).put("scope",scope).put("text",text).put("created_at",System.currentTimeMillis()));write(pending);return id;
        }catch(IllegalStateException failure){throw failure;}catch(Exception failure){throw unreadable();}}
    }
    Entry get(String id){synchronized(LOCK){try{JSONArray entries=read();for(int i=0;i<entries.length();i++){JSONObject value=entries.getJSONObject(i);if(value.getString("id").equals(id))return new Entry(value);}return null;}catch(Exception failure){throw unreadable();}}}
    Entry peek(String scope){synchronized(LOCK){try{JSONArray entries=read();for(int i=0;i<entries.length();i++){JSONObject value=entries.getJSONObject(i);if(value.getString("scope").equals(scope))return new Entry(value);}return null;}catch(Exception failure){throw unreadable();}}}
    void discard(String id){synchronized(LOCK){try{JSONArray entries=read(),next=new JSONArray();for(int i=0;i<entries.length();i++){JSONObject value=entries.getJSONObject(i);if(!value.getString("id").equals(id))next.put(value);}write(next);}catch(IllegalStateException failure){throw failure;}catch(Exception failure){throw unreadable();}}}
    private JSONArray read()throws Exception {
        JSONArray values=new JSONArray(preferences.getString("entries","[]"));if(values.length()>MAX_PENDING)throw unreadable();
        for(int i=0;i<values.length();i++){Entry entry=new Entry(values.getJSONObject(i));if(entry.text.length()>MAX_TEXT_LENGTH||entry.text.trim().isEmpty()||entry.scope.isEmpty())throw unreadable();UUID.fromString(entry.id);}
        return values;
    }
    private void write(JSONArray values){if(!preferences.edit().putString("entries",values.toString()).commit())throw new IllegalStateException("识别文字尚未确认保存，请保持当前页面并重试。");}
    private static IllegalStateException unreadable(){return new IllegalStateException("暂时无法读取待处理识别文字，原有内容仍保留，请稍后重试。");}
}
