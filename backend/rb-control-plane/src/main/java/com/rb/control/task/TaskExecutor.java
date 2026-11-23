package com.rb.control.task;

import com.github.dockerjava.api.DockerClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rb.control.container.ContainerInfo;
import com.rb.control.container.ContainerManager;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.*;

@Component("browserTaskExecutor")
public class TaskExecutor {

    private final ContainerManager containerManager;
    private final DockerClient docker;

    public TaskExecutor(ContainerManager containerManager, DockerClient docker) {
        this.containerManager = containerManager;
        this.docker = docker;
    }

    public void execute(Task task) {
        task.setStatus("RUNNING");
        String sessionId = "task-" + task.getTaskId();
        ContainerInfo container = null;
        WebSocket cdpWs = null;
        try {
            container = containerManager.create(sessionId);

            // 拿 page target
            String pageWsUrl = fetchPageTargetUrl(container.getCdpUrl());
            System.out.println("[" + task.getTaskId() + "] CDP: " + pageWsUrl);

            // 连接 CDP
            CdpClient cdp = new CdpClient(pageWsUrl);
            cdpWs = cdp.getWs();
            cdpWs.request(1);
            cdp.send("Page.enable", null);

            // 导航
            JsonObject nav = new JsonObject();
            nav.addProperty("url", task.getUrl());
            cdp.send("Page.navigate", nav);

            // 等页面加载完成
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && !cdp.loaded) {
                Thread.sleep(200);
            }
            Thread.sleep(1500);  // 额外等 JS 渲染

            // 执行脚本
            JsonObject evalParams = new JsonObject();
            evalParams.addProperty("expression", task.getScript());
            evalParams.addProperty("returnByValue", true);
            evalParams.addProperty("awaitPromise", true);
            JsonObject result = cdp.sendAndWait("Runtime.evaluate", evalParams);

            String value = "null";
            if (result.has("result") && result.getAsJsonObject("result").has("value")) {
                var valueElement = result.getAsJsonObject("result").get("value");
                if (valueElement.isJsonPrimitive() && valueElement.getAsJsonPrimitive().isString()) {
                    value = valueElement.getAsString();
                } else {
                    value = valueElement.toString();
                }
            }

            task.setResult(value);
            task.setStatus("DONE");
            task.setFinishedAt(System.currentTimeMillis());
            System.out.println("[" + task.getTaskId() + "] 完成, result=" + value);

        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setError(e.getMessage());
            task.setFinishedAt(System.currentTimeMillis());
            System.err.println("[" + task.getTaskId() + "] 失败: " + e.getMessage());
        } finally {
            if (cdpWs != null && !cdpWs.isOutputClosed()) {
                try { cdpWs.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) {}
            }
            if (container != null) {
                try { containerManager.destroy(container.getContainerId()); } catch (Exception ignored) {}
            }
        }
    }

    private String fetchPageTargetUrl(String cdpUrl) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("curl", "-s", "--max-time", "10", cdpUrl + "/json/list");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String json;
        try (var is = p.getInputStream()) {
            json = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        p.waitFor();
        var arr = JsonParser.parseString(json).getAsJsonArray();
        for (var el : arr) {
            var obj = el.getAsJsonObject();
            if ("page".equals(obj.get("type").getAsString()) && obj.has("webSocketDebuggerUrl")) {
                return obj.get("webSocketDebuggerUrl").getAsString();
            }
        }
        throw new IllegalStateException("/json/list 里没有 page target");
    }

    /**
     * 极简 CDP 客户端
     */
    static class CdpClient implements WebSocket.Listener {
        private final WebSocket ws;
        private final StringBuilder buf = new StringBuilder();
        private final ConcurrentHashMap<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
        private int messageId = 0;
        volatile boolean loaded = false;

        CdpClient(String url) throws Exception {
            this.ws = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(url), this)
                    .get(10, TimeUnit.SECONDS);
        }

        WebSocket getWs() { return ws; }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                handleMessage(text);
            }
            webSocket.request(1);
            return null;
        }

        void handleMessage(String text) {
            JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
            if (msg.has("id")) {
                int id = msg.get("id").getAsInt();
                CompletableFuture<JsonObject> f = pending.remove(id);
                if (f != null) {
                    if (msg.has("error")) f.completeExceptionally(new RuntimeException(msg.get("error").toString()));
                    else f.complete(msg.has("result") ? msg.getAsJsonObject("result") : new JsonObject());
                }
                return;
            }
            if (msg.has("method")) {
                String m = msg.get("method").getAsString();
                if ("Page.loadEventFired".equals(m)) {
                    loaded = true;
                }
            }
        }

        synchronized void send(String method, JsonObject params) {
            JsonObject cmd = new JsonObject();
            cmd.addProperty("id", ++messageId);
            cmd.addProperty("method", method);
            if (params != null) cmd.add("params", params);
            ws.sendText(cmd.toString(), true).join();
        }

        synchronized JsonObject sendAndWait(String method, JsonObject params) throws Exception {
            int id = ++messageId;
            CompletableFuture<JsonObject> f = new CompletableFuture<>();
            pending.put(id, f);
            JsonObject cmd = new JsonObject();
            cmd.addProperty("id", id);
            cmd.addProperty("method", method);
            if (params != null) cmd.add("params", params);
            ws.sendText(cmd.toString(), true).join();
            return f.get(30, TimeUnit.SECONDS);
        }
    }
}