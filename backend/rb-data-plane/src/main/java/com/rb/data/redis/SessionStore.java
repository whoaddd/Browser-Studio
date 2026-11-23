package com.rb.data.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SessionStore {
    private final StringRedisTemplate redis;
    public SessionStore(StringRedisTemplate redis) { this.redis = redis; }

    public Map<String, String> get(String sessionId) {
        Map<Object, Object> raw = redis.opsForHash().entries("rb:session:" + sessionId);
        if (raw.isEmpty()) return null;
        return raw.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().toString()));
    }

    // HSET only when the session still exists; do not resurrect a recycled session.
    public void touch(String sessionId) {
        String script = "if redis.call('EXISTS',KEYS[1]) == 1 then return redis.call('HSET',KEYS[1],'lastActiveAt',ARGV[1]) else return 0 end";
        redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
                java.util.List.of("rb:session:" + sessionId), String.valueOf(System.currentTimeMillis()));
    }

    public void updateState(String sessionId, String state) {
        redis.opsForHash().put("rb:session:" + sessionId, "state", state);
        redis.opsForHash().put("rb:session:" + sessionId,
                "lastActiveAt", String.valueOf(System.currentTimeMillis()));
    }
}