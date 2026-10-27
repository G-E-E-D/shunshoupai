package com.shunshoupai.app;

import android.os.Handler;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.HttpCookie;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground reconnect; local editing never waits for a network request. */
final class ScheduleSync {
    interface Listener { void result(String origin, String scope, String message, boolean loginRequired); }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler;
    private final LocalScheduleStore store;
    private final Listener listener;
    private boolean busy;
    private volatile boolean closed;
    private volatile long generation;
    private final Object lifecycle=new Object();

    ScheduleSync(LocalScheduleStore store, Handler handler, Listener listener) { this.store=store;this.handler=handler;this.listener=listener; }

    void sync(String origin) {
        if(busy || closed || origin.isEmpty()) return;
        busy=true;
        final long expectedGeneration=generation;
        String cookies=CookieManager.getInstance().getCookie(origin);
        worker.execute(() -> {
            String scope=null, message; boolean login=false;
            try {
                Map<String,String> jar=new LinkedHashMap<>();
                if(cookies!=null)for(String pair:cookies.split(";")){int split=pair.indexOf('=');if(split>0)jar.put(pair.substring(0,split).trim(),pair.substring(split+1).trim());}
                JSONObject session=request(origin,"api/auth/session",null,jar,expectedGeneration);
                if(cancelled(expectedGeneration)){handler.post(()->busy=false);return;}
                JSONObject user=session.optJSONObject("user");
                if(user==null) { message="日程已保存在手机；登录电脑账号后自动合并";login=true; }
                else {
                    JSONArray mutations;
                    synchronized(lifecycle) {
                        if(cancelled(expectedGeneration)){handler.post(()->busy=false);return;}
                        scope=store.bind(origin,user);
                        mutations=store.mutations(scope);
                    }
                    String cursor=null;
                    do {
                        JSONObject body=new JSONObject().put("account_id",user.getString("id")).put("mutations",cursor==null?mutations:new JSONArray());
                        if(cursor!=null)body.put("after",cursor);
                        JSONObject response=request(origin,"api/events/sync",body,jar,expectedGeneration);
                        synchronized(lifecycle) {
                            if(cancelled(expectedGeneration)){handler.post(()->busy=false);return;}
                            store.apply(scope,response);
                        }
                        cursor=response.optBoolean("has_more")?response.optString("next_cursor",null):null;
                        if(cursor!=null && cursor.isEmpty())throw new java.io.IOException("同步分页无效");
                    } while(cursor!=null && !cancelled(expectedGeneration));
                    int pending=0,conflicts=0;
                    for(JSONObject event:store.list(scope)){if(event.has("conflict"))conflicts++;else if(event.optBoolean("dirty"))pending++;}
                    message=conflicts>0?"手机修改已保留 · "+conflicts+" 项需确认":pending>0?"手机已保存 · "+pending+" 项等待下一次自动合并":"已同步 · "+user.optString("email");
                }
            } catch(ApiFailure error) {
                login=error.status==401 || "ACCOUNT_MISMATCH".equals(error.code);
                if(error.status==404)message="电脑端需更新后才能合并；手机日程已保存";
                else if(login)message="登录已过期，请重新连接账号；手机日程已保存";
                else message="暂未同步 · "+error.getMessage();
            } catch(Exception error) { message="日程已保存在手机，连接电脑后自动合并"; }
            final String resultScope=scope,resultMessage=message; final boolean needsLogin=login;
            handler.post(() -> { busy=false;if(!cancelled(expectedGeneration))listener.result(origin,resultScope,resultMessage,needsLogin); });
        });
    }

    private JSONObject request(String origin,String path,JSONObject body,Map<String,String> cookies,long expectedGeneration) throws Exception {
        if(cancelled(expectedGeneration))throw new java.io.IOException("已关闭");
        HttpURLConnection connection=(HttpURLConnection)new URL(origin+path).openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(4000);connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept","application/json");
        StringBuilder cookieHeader=new StringBuilder();
        for(Map.Entry<String,String> cookie:cookies.entrySet()){if(cookieHeader.length()>0)cookieHeader.append("; ");cookieHeader.append(cookie.getKey()).append('=').append(cookie.getValue());}
        if(cookieHeader.length()>0)connection.setRequestProperty("Cookie",cookieHeader.toString());
        connection.setRequestProperty("Origin",origin.substring(0,origin.length()-1));
        try {
            if(body!=null) {
                connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
                try(java.io.OutputStream output=connection.getOutputStream()){output.write(bytes);}
            }
            int status=connection.getResponseCode();
            for(Map.Entry<String,List<String>> header:connection.getHeaderFields().entrySet()) {
                if(header.getKey()!=null && header.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for(String cookie:header.getValue()) {
                        for(HttpCookie parsed:HttpCookie.parse(cookie)){if(parsed.getMaxAge()==0)cookies.remove(parsed.getName());else cookies.put(parsed.getName(),parsed.getValue());}
                        handler.post(() -> { if(!cancelled(expectedGeneration))CookieManager.getInstance().setCookie(origin,cookie,ignored -> CookieManager.getInstance().flush()); });
                    }
                }
            }
            InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();
            if(stream==null)throw new ApiFailure(status,"","服务暂时不可用");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=stream) {
                byte[] buffer=new byte[8192];int length;
                while((length=input.read(buffer))!=-1){if(bytes.size()+length>4*1024*1024)throw new java.io.IOException("响应过大");bytes.write(buffer,0,length);}
            }
            JSONObject result=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if(status<200 || status>=300) {
                JSONObject error=result.optJSONObject("error");
                throw new ApiFailure(status,error==null?"":error.optString("code"),error==null?"服务暂时不可用":error.optString("message"));
            }
            return result;
        } finally { connection.disconnect(); }
    }

    private boolean cancelled(long expectedGeneration){return closed||generation!=expectedGeneration;}
    void invalidate(){synchronized(lifecycle){generation++;}}
    void close(){synchronized(lifecycle){closed=true;generation++;}worker.execute(store::close);worker.shutdown();}
    private static final class ApiFailure extends Exception {
        final int status;final String code;
        ApiFailure(int status,String code,String message){super(message);this.status=status;this.code=code;}
    }
}
