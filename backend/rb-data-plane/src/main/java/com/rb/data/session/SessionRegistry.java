package com.rb.data.session;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {

    private final ConcurrentHashMap<String, BrowserSession> sessions = new ConcurrentHashMap<>();

    public void register(BrowserSession session) {
        sessions.put(session.getSessionId(), session);
    }

    public BrowserSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public BrowserSession remove(String sessionId) {
        return sessions.remove(sessionId);
    }

    public Collection<BrowserSession> all() {
        return sessions.values();
    }
}