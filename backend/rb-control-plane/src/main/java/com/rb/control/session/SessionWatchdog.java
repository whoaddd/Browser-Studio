package com.rb.control.session;

import com.rb.control.redis.SessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SessionWatchdog {

    private final SessionService sessionService;
    private final SessionStore sessionStore;
    private final long reconnectWindowMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;

    public SessionWatchdog(SessionService sessionService,
                           SessionStore sessionStore,
                           @Value("${rb.session.reconnect-window-seconds}") long reconnectWindow,
                           @Value("${rb.session.idle-timeout-seconds}") long idleTimeout,
                           @Value("${rb.session.max-lifetime-seconds}") long maxLifetime) {
        this.sessionService = sessionService;
        this.sessionStore = sessionStore;
        this.reconnectWindowMs = reconnectWindow * 1000;
        this.idleTimeoutMs = idleTimeout * 1000;
        this.maxLifetimeMs = maxLifetime * 1000;
    }

    @Scheduled(fixedDelayString = "${rb.session.watchdog-interval-ms}")
    public void scan() {
        long now = System.currentTimeMillis();
        for (String sessionId : sessionStore.allSessionIds()) {
            var meta = sessionStore.get(sessionId);
            if (meta == null) continue;

            String state = meta.get("state");
            if ("DRAINING".equals(state)) continue;

            long createdAt = Long.parseLong(meta.get("createdAt"));
            long lastActiveAt = Long.parseLong(meta.get("lastActiveAt"));

            if (now - createdAt > maxLifetimeMs) {
                sessionService.recycle(sessionId, "max_lifetime");
                continue;
            }
            if ("DISCONNECTED".equals(state) && now - lastActiveAt > reconnectWindowMs) {
                sessionService.recycle(sessionId, "reconnect_window_expired");
                continue;
            }
            if ("ACTIVE".equals(state) && now - lastActiveAt > idleTimeoutMs) {
                sessionService.recycle(sessionId, "idle_timeout");
            }
        }
    }
}