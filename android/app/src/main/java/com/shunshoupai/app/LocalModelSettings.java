package com.shunshoupai.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Phone-only model settings. Never stored in a calendar or replica record. */
final class LocalModelSettings {
    private static final Object LOCK=new Object();
    private final SharedPreferences preferences;
    private final String namespace,alias;
    static final class Config {
        final String baseUrl,model,apiKey;final boolean allowPrivate;
        Config(String base,String model,String key,boolean allowPrivate){this.baseUrl=base;this.model=model;this.apiKey=key;this.allowPrivate=allowPrivate;}
    }
    LocalModelSettings(Context context,String namespace) {
        this.namespace=namespace;preferences=context.getSharedPreferences(namespace,Context.MODE_PRIVATE);
        try {byte[] hash=MessageDigest.getInstance("SHA-256").digest(namespace.getBytes(StandardCharsets.UTF_8));StringBuilder text=new StringBuilder();for(byte value:hash)text.append(String.format(java.util.Locale.ROOT,"%02x",value&255));alias="shunshoupai-local-model-"+text;}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private SecretKey key()throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(alias)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}
        return (SecretKey)store.getKey(alias,null);
    }
    private byte[] aad(int version){return (namespace+"\n"+version).getBytes(StandardCharsets.UTF_8);}
    Config read()throws LocalPlanningException {synchronized(LOCK){
        String value=preferences.getString("ciphertext",null);if(value==null)return null;
        try {String[] parts=value.split("\\.",-1);if(parts.length!=2)throw new IllegalArgumentException();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));cipher.updateAAD(aad(preferences.getInt("version",0)));
            JSONObject data=PlanningJson.object(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8),16384);
            return new Config(data.getString("base_url"),data.getString("model"),data.getString("api_key"),data.getBoolean("allow_private_network"));
        }catch(Exception failure){throw new LocalPlanningException("MODEL_SETTINGS_UNREADABLE","手机上的模型配置暂时无法读取。可继续使用本地规则，或移除配置后重新填写。");}
    }}
    JSONObject info()throws Exception {synchronized(LOCK){Config config=null;String warning="";try{config=read();}catch(LocalPlanningException failure){warning=failure.getMessage();}boolean configured=preferences.contains("ciphertext"),readable=warning.isEmpty();
        return new JSONObject().put("configured",configured).put("key_configured",configured).put("has_api_key",configured)
            .put("version",preferences.getInt("version",0)).put("base_url",config!=null?config.baseUrl:"https://api.deepseek.com")
            .put("model",config!=null?config.model:"deepseek-flash").put("key_hint",configured?(readable?"已保存在手机":"读取失败，请重新填写"):JSONObject.NULL)
            .put("readable",readable).put("warning",warning).put("allow_private_network",config!=null&&config.allowPrivate).put("source",configured?"model":"rules").put("provider",configured?"model":"rules").put("deadline_ms",25000);
    }}
    Config prepare(JSONObject input)throws Exception {synchronized(LOCK){
        try {PlanningJson.keys(input,"base_url","model","api_key","base_version","allow_private_network");
            boolean allow=input.has("allow_private_network")&&PlanningJson.bool(input,"allow_private_network");
            String base=LocalModelTransport.normalizeBase(PlanningJson.string(input,"base_url").trim(),allow);
            String model=PlanningJson.string(input,"model").trim(),apiKey=input.has("api_key")?PlanningJson.string(input,"api_key").trim():"";
            if(model.isEmpty()||model.length()>200||model.chars().anyMatch(Character::isISOControl)||apiKey.length()>4096||!apiKey.matches("[!-~]*"))throw new IllegalArgumentException();
            if(apiKey.isEmpty()){Config current=read();if(current==null)throw new LocalPlanningException("MODEL_KEY_REQUIRED","请填写模型服务商提供的 API Key。");if(!origin(base).equals(origin(current.baseUrl)))throw new LocalPlanningException("MODEL_KEY_REQUIRED","接口来源已变化，请重新填写 API Key，避免把旧密钥发送到新地址。");apiKey=current.apiKey;}
            return new Config(base,model,apiKey,allow);
        }catch(LocalPlanningException failure){throw failure;}catch(Exception failure){throw new LocalPlanningException("VALIDATION_ERROR","请检查模型接口地址、名称和 API Key。");}
    }}
    private String origin(String base)throws Exception {URI uri=new URI(base);int port=uri.getPort();if(port<0)port=uri.getScheme().equals("https")?443:80;return uri.getScheme()+"://"+uri.getHost().toLowerCase(java.util.Locale.ROOT)+":"+port;}
    JSONObject save(JSONObject input)throws Exception {synchronized(LOCK){
        int expected=version(input);if(expected!=preferences.getInt("version",0))throw stale();Config config=prepare(input);int next=expected+1;
        try {JSONObject data=new JSONObject().put("base_url",config.baseUrl).put("model",config.model).put("api_key",config.apiKey).put("allow_private_network",config.allowPrivate);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(aad(next));String encrypted=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+"."+Base64.encodeToString(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
            if(!preferences.edit().putString("ciphertext",encrypted).putInt("version",next).commit())throw new IllegalStateException();
        }catch(Exception failure){throw new LocalPlanningException("MODEL_SETTINGS_SAVE_FAILED","模型配置未保存，请稍后重试。原配置仍保留。");}
        return info();
    }}
    JSONObject clear(int expected)throws Exception {synchronized(LOCK){if(expected<0||expected!=preferences.getInt("version",0))throw stale();if(!preferences.edit().remove("ciphertext").putInt("version",expected+1).commit())throw new LocalPlanningException("MODEL_SETTINGS_SAVE_FAILED","未能移除配置，请重试。");return info();}}
    private int version(JSONObject input)throws Exception {Object value=input.opt("base_version");if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue()||((Number)value).intValue()<0||((Number)value).intValue()==Integer.MAX_VALUE)throw new LocalPlanningException("VALIDATION_ERROR","请先读取当前模型配置再保存。");return ((Number)value).intValue();}
    private LocalPlanningException stale(){return new LocalPlanningException("VERSION_CONFLICT","模型配置已变化，请重新读取后保存。");}
}
