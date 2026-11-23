package com.rb.control.audit;

import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class AuditService {

    private final Path file;
    private final Object lock = new Object();

    public AuditService(@Value("${rb.audit.file}") String path) throws IOException {
        this.file = Path.of(path);
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (!Files.exists(file)) Files.createFile(file);
    }

    public void log(String action, String userId, String sessionId, String detail) {
        JsonObject r = new JsonObject();
        r.addProperty("ts", System.currentTimeMillis());
        r.addProperty("action", action);
        r.addProperty("userId", userId == null ? "" : userId);
        r.addProperty("sessionId", sessionId == null ? "" : sessionId);
        r.addProperty("detail", detail == null ? "" : detail);
        synchronized (lock) {
            try {
                Files.writeString(file, r.toString() + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("审计写入失败: " + e.getMessage());
            }
        }
        System.out.println("[audit] " + action + " user=" + userId + " session=" + sessionId);
    }
}