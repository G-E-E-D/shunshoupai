package com.shunshoupai.app;

import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Bounded trusted-LAN JSON transport, with no redirects or custom TLS trust. */
final class PeerHttp {
    static final class Failure extends Exception { final int status; final String code; Failure(int status,String code,String message){super(message);this.status=status;this.code=code;} }
    static final class Result {final JSONObject body;final List<String> cookies;Result(JSONObject body,List<String> cookies){this.body=body;this.cookies=cookies;}}
    private final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();
    static String origin(String value){
        String normalized=ServerAddress.normalize(value);String host=URI.create(normalized).getHost();if(host==null)throw new IllegalArgumentException("设备地址无效。");
        if("localhost".equals(host))return normalized;
        String bare=host.startsWith("[")?host.substring(1,host.length()-1):host;
        if(!bare.contains(":")&&!bare.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IllegalArgumentException("设备配对请使用同一局域网的 IP 地址。");
        try{InetAddress address=InetAddress.getByName(bare);byte[] bytes=address.getAddress();boolean local=address.isLoopbackAddress()||(bytes.length==4&&address.isSiteLocalAddress())||(bytes.length==16&&(bytes[0]&0xfe)==0xfc);if(!local)throw new IllegalArgumentException("设备同步仅允许局域网地址。");}catch(java.net.UnknownHostException error){throw new IllegalArgumentException("设备地址无效。");}
        return normalized;
    }
    Result request(String rawOrigin,String path,String method,JSONObject body,String token,BooleanSupplier cancelled)throws Exception{
        if(cancelled.getAsBoolean())throw new java.io.IOException("操作已取消");String origin=origin(rawOrigin);
        if(path.startsWith("/")||path.contains("..")||path.contains("#"))throw new IllegalArgumentException("设备接口路径无效。");
        HttpURLConnection connection=(HttpURLConnection)new URL(origin+path).openConnection();connections.add(connection);
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(20000);connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Origin",origin.substring(0,origin.length()-1));if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>16*1024*1024)throw new Failure(413,"BODY_TOO_LARGE","本次同步内容过大，数据已保留。");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.length);try(java.io.OutputStream out=connection.getOutputStream()){out.write(bytes);}}
            int status=connection.getResponseCode();List<String> cookies=new ArrayList<>();for(Map.Entry<String,List<String>> header:connection.getHeaderFields().entrySet())if(header.getKey()!=null&&header.getKey().equalsIgnoreCase("Set-Cookie"))cookies.addAll(header.getValue());
            InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();if(stream==null)throw new Failure(status,"PEER_UNAVAILABLE","对方没有返回有效内容。");ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(InputStream in=stream){byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1){if(cancelled.getAsBoolean())throw new java.io.IOException("操作已取消");if(output.size()+count>16*1024*1024)throw new Failure(413,"BODY_TOO_LARGE","对方响应过大，已停止同步。");output.write(buffer,0,count);}}
            if(cancelled.getAsBoolean())throw new java.io.IOException("操作已取消");JSONObject response;
            if(output.size()==0)response=new JSONObject();else{JSONTokener json=new JSONTokener(output.toString(StandardCharsets.UTF_8.name()));Object value=json.nextValue();if(!(value instanceof JSONObject)||json.nextClean()!=0)throw new Failure(502,"INVALID_RESPONSE","对方返回了无效内容。");response=(JSONObject)value;}
            if(status<200||status>=300){JSONObject error=response.optJSONObject("error");throw new Failure(status,error==null?"PEER_UNAVAILABLE":error.optString("code","PEER_UNAVAILABLE"),error==null?"对方暂不可用":error.optString("message","对方未接受同步，本机内容已保留。"));}return new Result(response,cookies);
        }finally{connections.remove(connection);connection.disconnect();}
    }
    void cancel(){for(HttpURLConnection connection:connections)connection.disconnect();connections.clear();}
}
