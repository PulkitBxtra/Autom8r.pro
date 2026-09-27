package com.bxtralabs.pod.connector.connections;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CredentialCipherTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private final CredentialCipher cipher = new CredentialCipher(randomKey(), JSON);

    @Test
    void roundTrip() {
        Map<String, String> creds = Map.of("token", "ghp_secret123", "apiKey", "key-with-ünïcode");
        assertEquals(creds, cipher.decrypt(cipher.encrypt(creds)));
    }

    @Test
    void storedValueHidesTheSecret() {
        String stored = cipher.encrypt(Map.of("token", "ghp_secret123"));
        assertTrue(stored.startsWith("v1:"));
        assertFalse(stored.contains("ghp_secret123"));
        assertFalse(new String(Base64.getDecoder().decode(stored.substring(3))).contains("ghp_secret123"));
    }

    @Test
    void freshIvEveryTime() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            assertTrue(seen.add(cipher.encrypt(Map.of("token", "same"))), "identical input must not repeat ciphertext");
        }
    }

    @Test
    void tamperingIsDetected() {
        String stored = cipher.encrypt(Map.of("token", "ghp_secret123"));
        byte[] blob = Base64.getDecoder().decode(stored.substring(3));
        blob[blob.length - 1] ^= 1; // flip one bit of the auth tag
        String tampered = "v1:" + Base64.getEncoder().encodeToString(blob);

        Exception e = assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
        assertFalse(e.getMessage().contains("v1:"), "error must not echo the stored value");
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String stored = cipher.encrypt(Map.of("token", "ghp_secret123"));
        assertThrows(IllegalStateException.class, () -> new CredentialCipher(randomKey(), JSON).decrypt(stored));
    }

    @Test
    void unknownFormatIsRejected() {
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("plain-text-token"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(null));
    }

    @Test
    void missingKeyDisablesInsteadOfCrashing() {
        CredentialCipher unconfigured = new CredentialCipher("", JSON);
        assertFalse(unconfigured.isConfigured());
        assertThrows(ConnectionsNotConfiguredException.class, () -> unconfigured.encrypt(Map.of("t", "x")));
        assertThrows(ConnectionsNotConfiguredException.class, () -> unconfigured.decrypt("v1:abc"));
    }

    @Test
    void badKeysFailFastWithClearMessages() {
        Exception shortKey = assertThrows(IllegalStateException.class,
                () -> new CredentialCipher(Base64.getEncoder().encodeToString(new byte[16]), JSON));
        assertTrue(shortKey.getMessage().contains("32 bytes"));
        Exception notBase64 = assertThrows(IllegalStateException.class, () -> new CredentialCipher("not base64 !!", JSON));
        assertTrue(notBase64.getMessage().contains("base64"));
    }
}
