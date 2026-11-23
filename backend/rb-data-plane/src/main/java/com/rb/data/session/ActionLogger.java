package com.rb.data.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class ActionLogger {

    private final Path baseDir;
    private final Object lock = new Object();

    public ActionLogger(@Value("${rb.storage.dir:./data/sessions}") String dir) throws IOException {
        this.baseDir = Path.of(dir);
        Files.createDirectories(baseDir);
    }

    public void append(String sessionId, JsonObject record) {
        Path sessionDir = baseDir.resolve(sessionId);
        Path file = sessionDir.resolve("actions.jsonl");
        synchronized (lock) {
            try {
                Files.createDirectories(sessionDir);
                Files.writeString(file, record.toString() + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[ActionLogger] 写入失败: " + e.getMessage());
            }
        }
    }

    public Path getLogFile(String sessionId) {
        return baseDir.resolve(sessionId).resolve("actions.jsonl");
    }

    public void appendNavigation(String sessionId, String type, String host) {
        JsonObject params = new JsonObject();
        params.addProperty("host", host);
        JsonObject action = new JsonObject();
        action.addProperty("sessionId", sessionId);
        action.addProperty("type", type);
        action.addProperty("ts", System.currentTimeMillis());
        action.add("params", params);
        append(sessionId, action);
    }

    public JsonArray recentSummary(String sessionId) throws IOException {
        byte[] tail;
        boolean truncated;
        Path file = getLogFile(sessionId);
        synchronized (lock) {
            if (!Files.exists(file)) return new JsonArray();
            try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
                long size = input.length();
                tail = new byte[(int) Math.min(size, 128 * 1024)];
                truncated = size > tail.length;
                input.seek(size - tail.length);
                input.readFully(tail);
            }
        }
        String content = new String(tail, StandardCharsets.UTF_8);
        if (truncated) {
            int newline = content.indexOf('\n');
            if (newline < 0) return new JsonArray();
            content = content.substring(newline + 1);
        }
        String[] lines = content.split("\n");
        JsonArray summary = new JsonArray();
        for (int i = lines.length - 1; i >= 0 && summary.size() < 20; i--) {
            try {
                JsonObject action = JsonParser.parseString(lines[i]).getAsJsonObject();
                String type = action.get("type").getAsString();
                String label = switch (type) {
                    case "navigate" -> "打开页面" + navigationHost(action.getAsJsonObject("params"));
                    case "transition" -> "页面跳转" + navigationHost(action.getAsJsonObject("params"));
                    case "back" -> "后退";
                    case "forward" -> "前进";
                    default -> null;
                };
                if (label == null) continue;
                long time = action.get("ts").getAsLong();
                JsonObject entry = new JsonObject();
                entry.addProperty("ts", time);
                entry.addProperty("label", label);
                summary.add(entry);
            } catch (RuntimeException ignored) {
                // Skip incomplete or malformed entries.
            }
        }
        return summary;
    }

    private static String navigationHost(JsonObject params) {
        if (params == null) return "";
        if (params.has("host")) return " · " + params.get("host").getAsString();
        if (!params.has("url")) return "";
        try {
            String host = URI.create(params.get("url").getAsString()).getHost();
            return host == null ? "" : " · " + host;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public long getRecordCount(String sessionId) {
        Path file = getLogFile(sessionId);
        if (!Files.exists(file)) return 0;
        try {
            return Files.lines(file, StandardCharsets.UTF_8).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
