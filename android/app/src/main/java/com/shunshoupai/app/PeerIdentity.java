package com.shunshoupai.app;

import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

/** Verifies stable peer/space identity. Trusted-LAN HTTP does not authenticate the transport channel. */
final class PeerIdentity {
    final String peerId;
    final String spaceId;
    final String publicKey;
    final String name;

    private PeerIdentity(String peerId, String spaceId, String publicKey, String name) {
        this.peerId = peerId; this.spaceId = spaceId; this.publicKey = publicKey; this.name = name;
    }

    static String challenge() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    static String uuid(String value) {
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("设备标识无效。");
        return value;
    }

    static PeerIdentity verify(JSONObject response, String challenge, String clientId,
            String pinnedKey, String expectedPeer, String expectedSpace) throws Exception {
        String peer = uuid(response.getString("peer_id"));
        String space = uuid(response.getString("space_id"));
        JSONObject identity = response.getJSONObject("identity");
        String encodedKey = identity.getString("public_key");
        String encodedSignature = identity.getString("signature");
        if (encodedKey.length() > 2048 || encodedSignature.length() > 1024) throw new SecurityException("设备身份证明无效。");
        byte[] keyBytes = Base64.decode(encodedKey, Base64.DEFAULT);
        String canonicalKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyBytes));
        if (!(key instanceof ECPublicKey) || ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != 256)
            throw new SecurityException("不支持的设备身份密钥。");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update((challenge + "\n" + clientId + "\n" + peer + "\n" + space).getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(Base64.decode(encodedSignature, Base64.DEFAULT))) throw new SecurityException("无法验证设备身份，请重新扫描设备二维码。");
        if ((pinnedKey != null && !pinnedKey.equals(canonicalKey))
            || (expectedPeer != null && !expectedPeer.equals(peer))
            || (expectedSpace != null && !expectedSpace.equals(space)))
            throw new SecurityException("设备身份与原配对不一致，已停止连接；手机日程仍保留。");
        JSONObject device = response.optJSONObject("device");
        String name = device == null ? "已配对设备" : device.optString("name", "已配对设备");
        return new PeerIdentity(peer, space, canonicalKey, name.substring(0, Math.min(name.length(), 120)));
    }
}
