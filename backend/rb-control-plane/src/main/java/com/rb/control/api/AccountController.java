package com.rb.control.api;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    @org.springframework.beans.factory.annotation.Value("${rb.workspace.user-id:local-workspace}")
    private String workspaceUserId;

    @GetMapping("/workspace")
    public Map<String, String> workspace() {
        return Map.of("userId", workspaceUserId);
    }

    private final StringRedisTemplate redis;
    public AccountController(StringRedisTemplate redis) { this.redis = redis; }

    public static String key(String userId) { return "rb:accounts:" + userId; }

    private String requireUser(String userId) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing X-User-Id");
        return userId;
    }

    @GetMapping
    public List<Map<String, String>> list(@RequestHeader(value="X-User-Id", required=false) String userId) {
        var result = new ArrayList<Map<String, String>>();
        requireUser(userId);
        result.add(Map.of("id", "default", "name", "默认账号（历史登录态）"));
        redis.opsForHash().entries(key(userId)).forEach((id, name) ->
                result.add(Map.of("id", id.toString(), "name", name.toString())));
        result.subList(1, result.size()).sort(Comparator.comparing(m -> m.get("name")));
        return result;
    }

    @PostMapping
    public Map<String, String> create(@RequestHeader(value="X-User-Id", required=false) String userId,
                                       @RequestBody Map<String, String> body) {
        requireUser(userId);
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty() || name.length() > 60)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号名称应为 1 到 60 个字符");
        String id = UUID.randomUUID().toString();
        redis.opsForHash().put(key(userId), id, name);
        return Map.of("id", id, "name", name);
    }
}
