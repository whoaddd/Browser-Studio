package com.rb.common.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${rb.jwt.secret}") String secret,
                      @Value("${rb.jwt.ttl-seconds}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String sign(String sessionId, List<String> allowedOps) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(sessionId)
                .claim("session_id", sessionId)
                .claim("allowed_ops", allowedOps)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }
}