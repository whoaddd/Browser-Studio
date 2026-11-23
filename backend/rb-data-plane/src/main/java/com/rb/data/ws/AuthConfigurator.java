package com.rb.data.ws;


import com.rb.common.jwt.JwtVerifier;
import io.jsonwebtoken.Claims;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.List;

public class AuthConfigurator extends ServerEndpointConfig.Configurator {

    private static JwtVerifier jwtVerifier;

    public static void setJwtVerifier(JwtVerifier verifier) {
        AuthConfigurator.jwtVerifier = verifier;
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig sec,
                                HandshakeRequest request,
                                HandshakeResponse response) {
        String query = request.getQueryString();
        String sessionId = extractQueryParam(query, "sessionId");
        String token = extractQueryParam(query, "token");

        if (sessionId == null || token == null) {
            sec.getUserProperties().put("auth_error", "missing sessionId or token");
            return;
        }

        try {
            Claims claims = jwtVerifier.verify(token);
            String tokenSessionId = claims.get("session_id", String.class);
            if (!sessionId.equals(tokenSessionId)) {
                sec.getUserProperties().put("auth_error", "session_id mismatch");
                return;
            }
            @SuppressWarnings("unchecked")
            List<String> allowedOps = claims.get("allowed_ops", List.class);
            sec.getUserProperties().put("sessionId", sessionId);
            sec.getUserProperties().put("allowed_ops", allowedOps);
        } catch (Exception e) {
            sec.getUserProperties().put("auth_error", "invalid token: " + e.getMessage());
        }
    }

    private String extractQueryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) return kv[1];
        }
        return null;
    }
}