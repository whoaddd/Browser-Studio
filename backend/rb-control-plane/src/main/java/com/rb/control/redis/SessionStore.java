package com.rb.control.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class SessionStore {
    private static final String SESSIONS = "rb:sessions";
    private static final String PREFIX = "rb:session:";
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RESERVE_SLOT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                    local occupied = {}
                    local legacy = {}
                    local count = 0
                    for _, id in ipairs(redis.call('SMEMBERS', KEYS[1])) do
                        local key = ARGV[1] .. id
                        local fields = redis.call('HMGET', key, 'userId', 'sessionNumber', 'createdAt')
                        if fields[1] == ARGV[2] then
                            count = count + 1
                            local number = tonumber(fields[2])
                            if number and number >= 1 and number <= 3 and number % 1 == 0 and not occupied[number] then
                                occupied[number] = true
                            else
                                table.insert(legacy, { key = key, time = tonumber(fields[3]) or 0 })
                            end
                        end
                    end
                    table.sort(legacy, function(a, b)
                        if a.time == b.time then return a.key < b.key end
                        return a.time < b.time
                    end)
                    local function available()
                        for slot = 1, 3 do
                            if not occupied[slot] then return slot end
                        end
                        return 0
                    end
                    for _, session in ipairs(legacy) do
                        local slot = available()
                        if slot > 0 then occupied[slot] = true end
                        redis.call('HSET', session.key, 'sessionNumber', slot)
                    end
                    if ARGV[3] == '' or count >= 3 then return 0 end
                    local slot = available()
                    if slot == 0 then return 0 end
                    local key = ARGV[1] .. ARGV[3]
                    redis.call('HSET', key, 'sessionId', ARGV[3], 'userId', ARGV[2],
                        'sessionNumber', slot, 'accountId', ARGV[4], 'state', 'CREATING',
                        'createdAt', ARGV[5], 'lastActiveAt', ARGV[5])
                    redis.call('EXPIRE', key, 7200)
                    redis.call('SADD', KEYS[1], ARGV[3])
                    return slot
                    """, Long.class);
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> ACTIVATE =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                    if redis.call('HGET', KEYS[1], 'state') ~= 'CREATING' then return 0 end
                    redis.call('HSET', KEYS[1], 'state', 'ACTIVE', 'containerId', ARGV[1],
                        'cdpUrl', ARGV[2], 'lastActiveAt', ARGV[3])
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redis;
    public SessionStore(StringRedisTemplate redis) { this.redis = redis; }

    public long reserveSlot(String userId, String sessionId, String accountId) {
        return redis.execute(RESERVE_SLOT, List.of(SESSIONS), PREFIX, userId, sessionId, accountId,
                Long.toString(System.currentTimeMillis()));
    }

    public void normalizeSlots(String userId) {
        reserveSlot(userId, "", "");
    }

    public boolean activate(String sessionId, String containerId, String cdpUrl) {
        return Long.valueOf(1).equals(redis.execute(ACTIVATE, List.of(PREFIX + sessionId),
                containerId, cdpUrl, Long.toString(System.currentTimeMillis())));
    }

    public void requireAccount(String userId, String accountId) {
        if (!"default".equals(accountId) && !redis.opsForHash().hasKey(
                com.rb.control.api.AccountController.key(userId), accountId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "账号不存在");
        }
    }

    public Map<String, String> get(String sessionId) {
        Map<Object, Object> raw = redis.opsForHash().entries("rb:session:" + sessionId);
        if (raw.isEmpty()) return null;
        return raw.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().toString()));
    }

    public Set<String> allSessionIds() {
        Set<String> ids = redis.opsForSet().members("rb:sessions");
        return ids == null ? Collections.emptySet() : ids;
    }

    public void delete(String sessionId) {
        redis.delete("rb:session:" + sessionId);
        redis.opsForSet().remove("rb:sessions", sessionId);
    }

    public void updateState(String sessionId, String state) {
        redis.opsForHash().put("rb:session:" + sessionId, "state", state);
        redis.opsForHash().put("rb:session:" + sessionId,
                "lastActiveAt", String.valueOf(System.currentTimeMillis()));
    }
}