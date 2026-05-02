package com.bank.aiassistant.security.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * JWT token lifecycle management.
 *
 * <p>Generates signed JWTs (HS512), validates them, and maintains an in-memory
 * blacklist for revoked tokens (logout). For production, replace the blacklist
 * with a Redis SET with TTL equal to the token expiry.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${app.jwt.refresh-expiration-ms:604800000}") // 7 days default
    private long refreshExpirationMs;

    /** In-memory token blacklist — swap for Redis in production */
    private final Set<String> blacklistedTokens = ConcurrentHashMap.newKeySet();

    // ─────────────────────────────────────────────────────────────────────────
    // Token generation
    // ─────────────────────────────────────────────────────────────────────────

    public String generateAccessToken(Authentication authentication) {
        UserDetails principal = (UserDetails) authentication.getPrincipal();
        return buildToken(principal.getUsername(), principal, jwtExpirationMs);
    }

    public String generateRefreshToken(String username) {
        return Jwts.builder()
                .subject(username)
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpirationMs))
                .signWith(signingKey(), Jwts.SIG.HS512)
                .compact();
    }

    private String buildToken(String subject, UserDetails userDetails, long expiryMs) {
        String roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
        return Jwts.builder()
                .subject(subject)
                .claim("roles", roles)
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(signingKey(), Jwts.SIG.HS512)
                .compact();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Token inspection
    // ─────────────────────────────────────────────────────────────────────────

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            if (blacklistedTokens.contains(token)) {
                log.warn("Attempt to use a blacklisted (revoked) token");
                return false;
            }
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException ex) {
            log.warn("JWT expired: {}", ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.warn("JWT unsupported: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.warn("JWT malformed: {}", ex.getMessage());
        } catch (SecurityException ex) {
            log.warn("JWT signature invalid: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("JWT claims empty: {}", ex.getMessage());
        }
        return false;
    }

    /** Revoke a token (logout). Replace with Redis DEL+ZADD for distributed systems. */
    public void revokeToken(String token) {
        blacklistedTokens.add(token);
        log.debug("Token revoked; blacklist size={}", blacklistedTokens.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }
}
