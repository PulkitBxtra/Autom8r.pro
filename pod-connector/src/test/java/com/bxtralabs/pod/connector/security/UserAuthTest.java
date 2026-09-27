package com.bxtralabs.pod.connector.security;

import com.bxtralabs.pod.connector.repository.RevokedTokenRepository;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UserAuthTest {

    private static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";

    private final RevokedTokenRepository revoked = mock(RevokedTokenRepository.class);
    private final UserAuth auth = new UserAuth(new JwtUtil(SECRET), revoked);

    // Signed the way pod-backend's JwtUtil.generateToken does it.
    private static String token(String secret, String userId, long expiresInMs) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .claim("email", "a@b.co")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiresInMs))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void validTokenReturnsTheUserId() {
        assertEquals("usr_1", auth.requireUserId("Bearer " + token(SECRET, "usr_1", 60_000)));
    }

    @Test
    void loggedOutTokenIsRejected() {
        String t = token(SECRET, "usr_1", 60_000);
        when(revoked.existsById(UserAuth.sha256(t))).thenReturn(true);

        Exception e = assertThrows(IllegalArgumentException.class, () -> auth.requireUserId("Bearer " + t));
        assertTrue(e.getMessage().contains("logged out"));
    }

    @Test
    void revocationIsLookedUpByTheSameHashPodBackendStores() {
        // pod-backend's TokenBlacklist: SHA-256 of the raw token, lowercase hex.
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", UserAuth.sha256("hello"));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String forged = token("some-other-secret-0123456789abcdef0123456789", "usr_1", 60_000);
        assertThrows(JwtException.class, () -> auth.requireUserId("Bearer " + forged));
    }

    @Test
    void expiredTokenIsRejected() {
        assertThrows(JwtException.class, () -> auth.requireUserId("Bearer " + token(SECRET, "usr_1", -1_000)));
    }

    @Test
    void garbageTokenIsRejected() {
        assertThrows(JwtException.class, () -> auth.requireUserId("Bearer not.a.jwt"));
    }

    @Test
    void missingOrMalformedHeaderIsRejectedWithoutTouchingTheDb() {
        assertThrows(IllegalArgumentException.class, () -> auth.requireUserId(null));
        assertThrows(IllegalArgumentException.class, () -> auth.requireUserId("Token abc"));
        verify(revoked, never()).existsById(anyString());
    }

    @Test
    void blankSecretFailsFastWithAClearMessage() {
        Exception e = assertThrows(IllegalStateException.class, () -> new JwtUtil(" "));
        assertTrue(e.getMessage().contains("JWT_SECRET"));
    }
}
