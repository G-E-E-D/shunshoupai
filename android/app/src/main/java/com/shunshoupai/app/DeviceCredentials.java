package com.shunshoupai.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

/** Revocable device capabilities are encrypted with an app-private Android Keystore key. */
final class DeviceCredentials {
    private static final String KEY_ALIAS = "shunshoupai-device-pairing-v1";
    private static final Object KEY_LOCK = new Object();
    private final SharedPreferences preferences;

    DeviceCredentials(Context context) {
        preferences = context.getSharedPreferences("device-credentials", Context.MODE_PRIVATE);
    }
    DeviceCredentials(Context context,String localDeviceId) {
        preferences = context.getSharedPreferences("device-credentials-"+localDeviceId, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        synchronized(KEY_LOCK) {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
    }

    synchronized void save(String peerId, String spaceId, String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD((peerId + "\n" + spaceId).getBytes(StandardCharsets.UTF_8));
        String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
            + Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!preferences.edit().putString(peerId, encrypted).commit()) throw new java.io.IOException("无法保存设备配对，请重试。");
    }

    synchronized String read(String peerId, String spaceId) {
        try {
            String value = preferences.getString(peerId, null);
            if (value == null) return null;
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            cipher.updateAAD((peerId + "\n" + spaceId).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception unavailableKey) {
            return null;
        }
    }

    synchronized void remove(String peerId) { preferences.edit().remove(peerId).commit(); }
}
