package com.rb.common;

public class SessionInfo {
    private final String sessionId;
    private final String userId;
    private final String containerId;
    private final String cdpUrl;
    private final String wsPath;
    private final long tokenExpiresAt;

    public SessionInfo(String sessionId, String userId, String containerId, String cdpUrl,
                       String token, long tokenExpiresAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.containerId = containerId;
        this.cdpUrl = cdpUrl;
        this.wsPath = "/ws?sessionId=" + sessionId + "&token=" + token;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public String getContainerId() { return containerId; }
    public String getCdpUrl() { return cdpUrl; }
    public String getWsPath() { return wsPath; }
    public long getTokenExpiresAt() { return tokenExpiresAt; }
}