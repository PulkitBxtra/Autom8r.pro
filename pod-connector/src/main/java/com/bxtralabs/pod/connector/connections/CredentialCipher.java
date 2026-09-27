package com.bxtralabs.pod.connector.connections;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

// Encrypts connection credentials (tokens, API keys) before they're stored, and decrypts them
// when this pod needs them.
//
// AES-256-GCM with a fresh random 96-bit IV per value. GCM's authentication tag means a
// tampered or truncated value fails to decrypt instead of producing garbage.
// Stored format: "v1:" + base64(iv || ciphertext+tag). The "v1" prefix leaves room to rotate
// to a new key later ("v2") while old values stay readable.
//
// The key comes from CONNECTIONS_ENCRYPTION_KEY (base64 of 32 random bytes:
// `openssl rand -base64 32`). If it isn't set the pod still starts, but anything that needs
// encryption throws ConnectionsNotConfiguredException (-> 503) rather than storing plain text.
@Component
public class CredentialCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final JsonMapper jsonMapper;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${connections.encryption-key:}") String base64Key, JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        if (base64Key == null || base64Key.isBlank()) {
            System.out.println("WARN: CONNECTIONS_ENCRYPTION_KEY is not set; connections are disabled");
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CONNECTIONS_ENCRYPTION_KEY is not valid base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("CONNECTIONS_ENCRYPTION_KEY must decode to 32 bytes (AES-256), got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(Map<String, String> credentials) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(jsonMapper.writeValueAsBytes(credentials));
            byte[] blob = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
            return PREFIX + Base64.getEncoder().encodeToString(blob);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt credentials", e);
        }
    }

    public Map<String, String> decrypt(String stored) {
        requireKey();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unknown credential format");
        }
        try {
            byte[] blob = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            byte[] json = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
            return jsonMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            // Wrong key or tampered data. Never put the stored value (or the cause) in the message.
            throw new IllegalStateException("Could not decrypt credentials (wrong key or corrupted data)");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new ConnectionsNotConfiguredException();
        }
    }
}
