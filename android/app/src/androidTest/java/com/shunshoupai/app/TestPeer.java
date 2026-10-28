package com.shunshoupai.app;

import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;

/** Synthetic P-256 identities for device protocol fixtures; never production credentials. */
final class TestPeer {
    final String peerId;
    final String spaceId;
    private final KeyPair keys;
    TestPeer() throws Exception { this(UUID.randomUUID().toString(),UUID.randomUUID().toString()); }
    TestPeer(String peerId,String spaceId) throws Exception {
        this.peerId=peerId;this.spaceId=spaceId;
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));keys=generator.generateKeyPair();
    }
    JSONObject proof(String challenge,String clientId) throws Exception {
        Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(keys.getPrivate());
        signer.update((challenge+"\n"+clientId+"\n"+peerId+"\n"+spaceId).getBytes(StandardCharsets.UTF_8));
        return context().put("identity",new JSONObject().put("public_key",Base64.encodeToString(keys.getPublic().getEncoded(),Base64.NO_WRAP)).put("signature",Base64.encodeToString(signer.sign(),Base64.NO_WRAP)));
    }
    JSONObject context() throws Exception {return new JSONObject().put("peer_id",peerId).put("space_id",spaceId).put("device",new JSONObject().put("id",peerId).put("name","测试电脑")).put("capabilities",new JSONObject().put("replica",1).put("web",true).put("ai",true));}
    PeerIdentity verified(String clientId) throws Exception {String challenge=PeerIdentity.challenge();return PeerIdentity.verify(proof(challenge,clientId),challenge,clientId,null,null,null);}
}
