package com.rb.control.session;

import com.rb.common.SessionInfo;
import com.rb.common.jwt.JwtService;
import com.rb.control.audit.AuditService;
import com.rb.control.container.ContainerInfo;
import com.rb.control.container.ContainerManager;
import com.rb.control.redis.SessionStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private final ContainerManager containerManager;
    private final SessionStore sessionStore;
    private final JwtService jwtService;
    private final AuditService auditService;

    private final ConcurrentHashMap<String, IdempotEntry> idempot = new ConcurrentHashMap<>();
    private static final long IDEMPOT_TTL_MS = 30_000;

    private record IdempotEntry(String sessionId, long expiresAt) {}

    public SessionService(ContainerManager containerManager,
                          SessionStore sessionStore,
                          JwtService jwtService,
                          AuditService auditService) {
        this.containerManager = containerManager;
        this.sessionStore = sessionStore;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    public SessionInfo createSession(String userId, String requestId) {
        return createSession(userId, requestId, null);
    }

    /**
     * @param browserProxy 可选：为本次会话指定代理（住宅/移动代理），用于淘宝、京东等强风控站点
     */
    public SessionInfo createSession(String userId, String requestId, String browserProxy) {
        return createSession(userId, requestId, browserProxy, "default");
    }

    public SessionInfo createSession(String userId, String requestId, String browserProxy, String accountId) {
        sessionStore.requireAccount(userId, accountId);
        if (requestId != null) requestId = userId + ":" + accountId + ":" + requestId;
        if (requestId != null && !requestId.isBlank()) {
            cleanupIdempot();
            IdempotEntry cached = idempot.get(requestId);
            if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                Map<String, String> meta = sessionStore.get(cached.sessionId);
                if (meta != null && List.of("ACTIVE", "DISCONNECTED").contains(meta.get("state"))) {
                    String token = jwtService.sign(cached.sessionId,
                            List.of("NAVIGATE", "MOUSE", "KEYBOARD"));
                    long expiresAt = System.currentTimeMillis() + jwtService.getTtlSeconds() * 1000;
                    return new SessionInfo(cached.sessionId, userId,
                            meta.get("containerId"), meta.get("cdpUrl"), token, expiresAt);
                }
            }
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        if (sessionStore.reserveSlot(userId, sessionId, accountId) == 0) {
            throw new QuotaExceededException("最多同时创建 3 个会话，请先关闭一个会话");
        }
        ContainerInfo container = null;
        try {
            container = containerManager.create(sessionId, browserProxy);
            if (!sessionStore.activate(sessionId, container.getContainerId(), container.getCdpUrl())) {
                throw new IllegalStateException("会话已在启动期间被关闭");
            }

            List<String> allowedOps = List.of("NAVIGATE", "MOUSE", "KEYBOARD");
            String token = jwtService.sign(sessionId, allowedOps);
            long expiresAt = System.currentTimeMillis() + jwtService.getTtlSeconds() * 1000;

            if (requestId != null && !requestId.isBlank()) {
                idempot.put(requestId, new IdempotEntry(sessionId,
                        System.currentTimeMillis() + IDEMPOT_TTL_MS));
            }

            auditService.log("SESSION_CREATE", userId, sessionId,
                    "container=" + container.getContainerId());
            return new SessionInfo(sessionId, userId, container.getContainerId(),
                    container.getCdpUrl(), token, expiresAt);
        } catch (Exception e) {
            if (container != null) containerManager.destroy(container.getContainerId());
            sessionStore.delete(sessionId);
            auditService.log("SESSION_CREATE_FAILED", userId, sessionId, e.getMessage());
            throw e;
        }
    }

    public SessionInfo connectSession(String userId, String sessionId) {
        sessionStore.normalizeSlots(userId);
        Map<String, String> meta = sessionStore.get(sessionId);
        if (meta == null || !userId.equals(meta.get("userId")) || !List.of("ACTIVE", "DISCONNECTED").contains(meta.get("state"))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "会话不存在或已关闭");
        }
        String token = jwtService.sign(sessionId, List.of("NAVIGATE", "MOUSE", "KEYBOARD"));
        return new SessionInfo(sessionId, userId, meta.get("containerId"), meta.get("cdpUrl"),
                token, System.currentTimeMillis() + jwtService.getTtlSeconds() * 1000);
    }

    public void recycle(String sessionId, String reason) {
        Map<String, String> meta = sessionStore.get(sessionId);
        if (meta == null) return;

        String userId = meta.get("userId");
        String containerId = meta.get("containerId");

        System.out.println("[" + sessionId + "] 回收: " + reason);

        // 先把状态改为 DRAINING，数据平面会感知并导出 storage_state
        sessionStore.updateState(sessionId, "DRAINING");
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        containerManager.destroy(containerId);
        sessionStore.delete(sessionId);
        auditService.log("SESSION_RECYCLE", userId, sessionId, reason);
    }

    public boolean destroySession(String userId, String sessionId) {
        Map<String, String> meta = sessionStore.get(sessionId);
        if (meta == null) return false;
        if (!userId.equals(meta.get("userId"))) return false;
        recycle(sessionId, "user_delete");
        return true;
    }

    public int resetUser(String userId) {
        int count = 0;
        for (String sessionId : sessionStore.allSessionIds()) {
            Map<String, String> meta = sessionStore.get(sessionId);
            if (meta == null) continue;
            if (!userId.equals(meta.get("userId"))) continue;

            String containerId = meta.get("containerId");
            System.out.println("[" + sessionId + "] 重置回收");
            sessionStore.delete(sessionId);
            if (containerId != null) {
                try { containerManager.destroy(containerId); } catch (Exception ignored) {}
            }
            count++;
        }
        auditService.log("USER_RESET", userId, null, "recycled=" + count);
        return count;
    }

    public List<Map<String, Object>> listByUser(String userId) {
        sessionStore.normalizeSlots(userId);
        long now = System.currentTimeMillis();
        return sessionStore.allSessionIds().stream()
                .map(sessionStore::get)
                .filter(m -> m != null && userId.equals(m.get("userId")))
                .sorted(java.util.Comparator.comparingLong((Map<String, String> m) -> Long.parseLong(m.get("createdAt")))
                        .thenComparing(m -> m.get("sessionId")))
                .map(m -> {
                    Map<String, Object> r = new java.util.HashMap<>();
                    r.put("sessionId", m.get("sessionId"));
                    r.put("sessionNumber", Integer.parseInt(m.getOrDefault("sessionNumber", "0")));
                    r.put("userId", m.get("userId"));
                    r.put("accountId", m.getOrDefault("accountId", "default"));
                    r.put("containerId", m.get("containerId"));
                    r.put("state", m.get("state"));
                    r.put("createdAt", Long.parseLong(m.get("createdAt")));
                    r.put("lastActiveAt", Long.parseLong(m.get("lastActiveAt")));
                    r.put("idleSeconds", (now - Long.parseLong(m.get("lastActiveAt"))) / 1000);
                    return r;
                })
                .toList();
    }

    private void cleanupIdempot() {
        long now = System.currentTimeMillis();
        idempot.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String msg) { super(msg); }
    }
}