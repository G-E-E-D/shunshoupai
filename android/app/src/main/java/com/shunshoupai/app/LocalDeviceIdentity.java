package com.shunshoupai.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/** Stable signing identity. The non-exportable private key stays in Android Keystore. */
final class LocalDeviceIdentity {
    private static final Object KEY_LOCK = new Object();
    private final String alias;

    LocalDeviceIdentity(Context context) {
        alias = context.getApplicationContext().getPackageName() + ".host-identity-p256-v1";
    }
    LocalDeviceIdentity(Context context,String localDeviceId) {
        PeerIdentity.uuid(localDeviceId);
        alias = context.getApplicationContext().getPackageName() + ".host-identity-p256-v1." + localDeviceId;
    }

    private KeyStore store() throws Exception {
        synchronized (KEY_LOCK) {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            if (!keys.containsAlias(alias)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
                generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build());
                generator.generateKeyPair();
            }
            return keys;
        }
    }

    String publicKey() throws Exception {
        KeyStore keys = store();
        if (keys.getCertificate(alias) == null) throw new SecurityException("本机身份密钥不可用。");
        return Base64.encodeToString(keys.getCertificate(alias).getPublicKey().getEncoded(), Base64.NO_WRAP);
    }

    JSONObject proof(String challenge, String clientId, String peerId, String spaceId) throws Exception {
        if (challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("身份挑战无效。");
        PeerIdentity.uuid(clientId); PeerIdentity.uuid(peerId); PeerIdentity.uuid(spaceId);
        KeyStore keys = store();
        java.security.Key key = keys.getKey(alias, null);
        if (!(key instanceof PrivateKey)) throw new SecurityException("本机身份密钥不可用。");
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign((PrivateKey) key);
        signer.update((challenge + "\n" + clientId + "\n" + peerId + "\n" + spaceId).getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("peer_id", peerId).put("space_id", spaceId)
            .put("identity", new JSONObject().put("public_key", publicKey())
                .put("signature", Base64.encodeToString(signer.sign(), Base64.NO_WRAP)));
    }
}
