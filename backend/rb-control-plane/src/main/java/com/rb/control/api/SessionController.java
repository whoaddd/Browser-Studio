package com.rb.control.api;

import com.rb.common.SessionInfo;
import com.rb.control.session.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 创建会话。
     *
     * Header:
     *   X-User-Id        必填
     *   X-Request-Id     可选，幂等键
     *   X-Browser-Proxy  可选，形如 http://user:pass@host:port 或 socks5://host:port
     *                    淘宝/京东等强风控站点建议指定住宅/移动代理
     */
    @PostMapping({"", "/"})
    public ResponseEntity<?> create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-Browser-Proxy", required = false) String browserProxy,
            @RequestHeader(value = "X-Account-Id", defaultValue = "default") String accountId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing X-User-Id"));
        }
        SessionInfo info = sessionService.createSession(userId, requestId, browserProxy, accountId);
        return ResponseEntity.ok(info);
    }

    @GetMapping({"", "/"})
    public List<Map<String, Object>> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) return List.of();
        return sessionService.listByUser(userId);
    }

    @PostMapping("/{sessionId}/connect")
    public ResponseEntity<?> connect(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String sessionId) {
        if (userId == null || userId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "missing X-User-Id"));
        return ResponseEntity.ok(sessionService.connectSession(userId, sessionId));
    }

    @DeleteMapping({"/{sessionId}", "/{sessionId}/"})
    public ResponseEntity<String> delete(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String sessionId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).body("missing X-User-Id");
        }
        boolean ok = sessionService.destroySession(userId, sessionId);
        return ok ? ResponseEntity.ok("destroyed: " + sessionId)
                : ResponseEntity.notFound().build();
    }

    @PostMapping({"/reset", "/reset/"})
    public ResponseEntity<?> reset(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing X-User-Id"));
        }
        int count = sessionService.resetUser(userId);
        return ResponseEntity.ok(Map.of("userId", userId, "recycled", count));
    }
}
