package com.rb.data.ws;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rb.data.redis.SessionStore;
import com.rb.data.session.ActionLogger;
import com.rb.data.session.BrowserSession;
import com.rb.data.session.BrowserResearchRun;
import com.rb.data.session.DeepSeekBrowserAssistant;
import com.rb.data.session.SessionRegistry;
import com.rb.data.session.StorageStateManager;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

@ServerEndpoint(value = "/ws", configurator = AuthConfigurator.class)
@Component
public class BrowserEndpoint {
    private static DeepSeekBrowserAssistant assistant;
    private final java.util.concurrent.ArrayBlockingQueue<String> outgoingMessages =
            new java.util.concurrent.ArrayBlockingQueue<>(64);
    private final java.util.concurrent.atomic.AtomicBoolean analyzing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile DeepSeekBrowserAssistant.Proposal proposal;
    private volatile PlannedTask task;
    private volatile boolean stopResearch;
    private volatile Thread researchWorker;

    private record PlannedTask(DeepSeekBrowserAssistant.Plan plan, String requirement, String source,
                               DeepSeekBrowserAssistant.VisualReference reference) {}

    @Autowired
    public void setAssistant(DeepSeekBrowserAssistant service) { assistant = service; }

    private static final Map<String, java.util.Set<String>> viewers = new java.util.HashMap<>();
    private static ActionLogger actionLogger;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> lastActivityUpdates
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static SessionRegistry sessionRegistry;
    private static SessionStore sessionStore;
    private static StorageStateManager storageStateManager;

    @Autowired
    public void setSessionRegistry(SessionRegistry registry) {
        BrowserEndpoint.sessionRegistry = registry;
    }

    @Autowired
    public void setSessionStore(SessionStore store) {
        BrowserEndpoint.sessionStore = store;
    }

    @Autowired
    public void setStorageStateManager(StorageStateManager manager) {
        BrowserEndpoint.storageStateManager = manager;
    }

    @Autowired
    public void setActionLogger(ActionLogger logger) {
        BrowserEndpoint.actionLogger = logger;
    }

    @OnOpen
    public void onOpen(Session session) {
        session.setMaxTextMessageBufferSize(150000);
        String authError = (String) session.getUserProperties().get("auth_error");
        if (authError != null) {
            System.out.println("握手鉴权失败: ws=" + session.getId() + " reason=" + authError);
            closeQuietly(session, authError);
            return;
        }

        String sessionId = (String) session.getUserProperties().get("sessionId");
        if (sessionId == null) {
            System.out.println("userProperties 缺少 sessionId，ws=" + session.getId());
            closeQuietly(session, "missing sessionId");
            return;
        }

        Map<String, String> meta = sessionStore.get(sessionId);
        if (meta == null) {
            System.out.println("Redis 中没有 sessionId: " + sessionId + "，ws=" + session.getId());
            closeQuietly(session, "unknown session");
            return;
        }

        BrowserSession bs;
        synchronized (sessionRegistry) {
        bs = sessionRegistry.get(sessionId);
        if (bs == null) {
            String cdpUrl = meta.get("cdpUrl");
            String userId = meta.get("userId");
            String containerId = meta.get("containerId");
            if (cdpUrl == null || userId == null || containerId == null) {
                System.out.println("Redis 元数据不完整，sessionId=" + sessionId + " meta=" + meta);
                closeQuietly(session, "incomplete metadata");
                return;
            }

            try {
                bs = new BrowserSession(sessionId, userId, containerId, cdpUrl, storageStateManager.forAccount(userId, meta.getOrDefault("accountId", "default")), actionLogger);
                bs.start();

                sessionRegistry.register(bs);
                System.out.println("现场创建 BrowserSession: session=" + sessionId + " cdpUrl=" + cdpUrl);

            } catch (Exception e) {
                System.out.println("现场创建 BrowserSession 失败: session=" + sessionId + " err=" + e.getMessage());
                e.printStackTrace();
                closeQuietly(session, "browser session init failed");
                return;
            }
        }

        viewers.computeIfAbsent(sessionId, key -> new java.util.HashSet<>()).add(session.getId());
        bs.markActive();
        sessionStore.updateState(sessionId, "ACTIVE");
        }

        System.out.println("前端 WebSocket 已连接: ws=" + session.getId() + " session=" + sessionId);

        final BrowserSession finalBs = bs;
        new Thread(() -> {
            try {
                long sequence = 0;
                Boolean automationRunning = null;
                int frameWidth = 0, frameHeight = 0;
                session.setMaxIdleTimeout(600_000);
                session.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 5000L);
                String sessionNumber = meta.get("sessionNumber");
                if (sessionNumber != null) {
                    JsonObject identity = new JsonObject();
                    identity.addProperty("type", "session_identity");
                    identity.addProperty("sessionNumber", Long.parseLong(sessionNumber));
                    session.getBasicRemote().sendText(identity.toString());
                }
                while (session.isOpen()) {
                    for (int count = 0; count < 16; count++) {
                        String message = outgoingMessages.poll();
                        if (message == null) break;
                        session.getBasicRemote().sendText(message);
                    }
                    var frame = finalBs.frameAfter(sequence, outgoingMessages.isEmpty() ? 150 : 0);
                    boolean running = finalBs.isAutomationRunning();
                    if (automationRunning == null || automationRunning != running) {
                        JsonObject state = new JsonObject();
                        state.addProperty("type", "automation_state");
                        state.addProperty("running", running);
                        session.getBasicRemote().sendText(state.toString());
                        automationRunning = running;
                    }
                    if (frame == null) continue;
                    sequence = frame.sequence();
                    byte[] jpeg = frame.jpeg();
                    if (!session.isOpen()) break;
                    if (frameWidth != frame.width() || frameHeight != frame.height()) {
                        JsonObject viewport = new JsonObject();
                        viewport.addProperty("type", "viewport");
                        viewport.addProperty("width", frame.width());
                        viewport.addProperty("height", frame.height());
                        session.getBasicRemote().sendText(viewport.toString());
                        frameWidth = frame.width();
                        frameHeight = frame.height();
                    }
                    session.getBasicRemote().sendBinary(ByteBuffer.wrap(jpeg));
                }
            } catch (Exception e) {
                if (session.isOpen()) {
                    System.out.println("帧推送异常: " + e.getMessage());
                    closeStream(session, "monitor stream interrupted");
                }
            }
            System.out.println("帧推送线程退出: ws=" + session.getId() + " session=" + sessionId);
        }, "frame-pusher-" + session.getId()).start();
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        String sessionId = (String) session.getUserProperties().get("sessionId");
        if (sessionId == null) return;
        BrowserSession bs = sessionRegistry.get(sessionId);
        if (bs == null) return;

        @SuppressWarnings("unchecked")
        List<String> allowedOps = (List<String>) session.getUserProperties().get("allowed_ops");
        if (allowedOps == null) allowedOps = List.of();

        JsonObject json = JsonParser.parseString(message).getAsJsonObject();
        String type = json.get("type").getAsString();
        if ("recent_actions".equals(type)) {
            JsonObject history = new JsonObject();
            history.addProperty("type", "recent_actions");
            try {
                history.add("entries", actionLogger.recentSummary(sessionId));
            } catch (java.io.IOException e) {
                history.addProperty("error", "暂时无法读取操作记录");
            }
            enqueueText(session, history);
            return;
        }
        // Refresh the control plane's activity clock, not just BrowserSession's local clock.
        String activityKey = sessionId + ":activity";
        long activityNow = System.currentTimeMillis();
        Long previousActivity = lastActivityUpdates.get(activityKey);
        if (previousActivity == null || activityNow - previousActivity >= 5000) {
            lastActivityUpdates.put(activityKey, activityNow);
            sessionStore.touch(sessionId);
        }

        if (List.of("ai_plan", "ai_apply", "ai_stop", "ai_resume", "ai_checkpoint").contains(type)) {
            if (!allowedOps.containsAll(List.of("NAVIGATE", "MOUSE", "KEYBOARD"))) return;
            handleAssistant(session, bs, json, type);
            return;
        }

        if ("auto_task".equals(type)) {
            if (!allowedOps.contains("NAVIGATE")) { sendError(session, "NAVIGATE not allowed"); return; }
            String siteName = json.has("siteName") ? json.get("siteName").getAsString() : "baidu";
            bs.autoExecute(siteName, messageText -> {
                JsonObject status = new JsonObject(); status.addProperty("type", "automation_status");
                status.addProperty("message", messageText);
                enqueueText(session, status);
            });
            return;
        }

        if ("navigate".equals(type)) {
            if (!allowedOps.contains("NAVIGATE")) { sendError(session, "NAVIGATE not allowed"); return; }
            String url = json.get("url").getAsString();
            bs.navigate(url);
        } else if ("back".equals(type)) {
            if (!allowedOps.contains("NAVIGATE")) { sendError(session, "NAVIGATE not allowed"); return; }
            bs.goBack();
        } else if ("forward".equals(type)) {
            if (!allowedOps.contains("NAVIGATE")) { sendError(session, "NAVIGATE not allowed"); return; }
            bs.goForward();
        } else if ("mouse".equals(type)) {
            if (!allowedOps.contains("MOUSE")) { sendError(session, "MOUSE not allowed"); return; }
            String action = json.get("action").getAsString();
            // 修复：正确传递 buttons 参数
            bs.dispatchMouse(action,
                    json.get("x").getAsDouble(),
                    json.get("y").getAsDouble(),
                    json.has("button") ? json.get("button").getAsString() : null,
                    json.has("clickCount") ? json.get("clickCount").getAsInt() : 1,
                    json.has("buttons") ? json.get("buttons").getAsInt() : 0
            );
        } else if ("key".equals(type)) {
            if (!allowedOps.contains("KEYBOARD")) { sendError(session, "KEYBOARD not allowed"); return; }
            bs.dispatchKey(
                    json.get("action").getAsString(),
                    json.get("key").getAsString(),
                    json.get("code").getAsString(),
                    json.get("modifiers").getAsInt());
        } else if ("wheel".equals(type)) {
            if (!allowedOps.contains("MOUSE")) { sendError(session, "MOUSE not allowed"); return; }
            bs.dispatchWheel(
                    json.get("x").getAsDouble(),
                    json.get("y").getAsDouble(),
                    json.get("deltaX").getAsDouble(),
                    json.get("deltaY").getAsDouble());
        } else if ("scrollGesture".equals(type)) {
            if (!allowedOps.contains("MOUSE")) { sendError(session, "MOUSE not allowed"); return; }
            bs.dispatchScrollGesture(
                    json.get("x").getAsDouble(),
                    json.get("y").getAsDouble(),
                    json.get("xDistance").getAsDouble(),
                    json.get("yDistance").getAsDouble());
        } else if ("viewport".equals(type)) {
            if (!allowedOps.contains("NAVIGATE")) { sendError(session, "NAVIGATE not allowed"); return; }
            int w = json.get("width").getAsInt();
            int h = json.get("height").getAsInt();
            int dsf = json.has("deviceScaleFactor") ? json.get("deviceScaleFactor").getAsInt() : 1;
            bs.setViewport(w, h, dsf);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        stopResearchWorker();
        String sessionId = (String) session.getUserProperties().get("sessionId");
        if (sessionId != null) {
            synchronized (sessionRegistry) {
                java.util.Set<String> connections = viewers.get(sessionId);
                if (connections != null) {
                    connections.remove(session.getId());
                    if (!connections.isEmpty()) return;
                    viewers.remove(sessionId);
                }
                BrowserSession bs = sessionRegistry.get(sessionId);
                Map<String, String> meta = sessionStore.get(sessionId);
                if (bs != null && meta != null && "ACTIVE".equals(meta.get("state"))) {
                    bs.markDisconnected();
                    sessionStore.updateState(sessionId, "DISCONNECTED");
                }
            }
        }
        System.out.println("前端 WebSocket 断开: ws=" + session.getId()
                + " session=" + sessionId + " code=" + reason.getCloseCode());
    }

    private void handleAssistant(Session session, BrowserSession browser, JsonObject message, String type) {
        if ("ai_stop".equals(type)) {
            stopResearchWorker();
            return;
        }
        String requestId = message.get("requestId").getAsString();
        if (requestId.length() > 80) return;
        if ("ai_checkpoint".equals(type)) {
            var checkpoint = browser.getResearchCheckpoint();
            JsonObject result = new JsonObject();
            result.addProperty("type", "ai_result");
            result.addProperty("requestId", requestId);
            boolean canResume = checkpoint != null && !browser.isAssistantRunning();
            result.addProperty("canResume", canResume);
            result.addProperty("message", canResume ? "上次任务可以从当前页面继续" : "");
            if (canResume) result.add("plan", planDetails(checkpoint.plan()));
            enqueueText(session, result);
            return;
        }
        if (!analyzing.compareAndSet(false, true)) {
            sendAssistant(session, requestId, "当前任务正在处理", null);
            return;
        }
        var pending = proposal;
        proposal = null;
        stopResearch = false;
        Thread.startVirtualThread(() -> {
            try {
                if ("ai_plan".equals(type)) {
                    task = null;
                    String requirement = message.get("requirement").getAsString();
                    String source = message.get("source").getAsString();
                    DeepSeekBrowserAssistant.VisualReference reference = null;
                    if (message.has("visualReference")) {
                        JsonObject visual = message.getAsJsonObject("visualReference");
                        reference = new DeepSeekBrowserAssistant.VisualReference(
                                visual.get("label").getAsString(), visual.get("image").getAsString());
                    }
                    var plan = assistant.plan(requirement, source, reference);
                    task = new PlannedTask(plan, requirement, source, reference);
                    proposal = plan.openSource(reference != null);
                    sendAssistant(session, requestId, "已制定查找计划", proposal);
                } else if ("ai_resume".equals(type)) {
                    var checkpoint = browser.getResearchCheckpoint();
                    if (checkpoint == null) sendAssistant(session, requestId, "没有可继续的任务，请先分析需求", null);
                    else runResearch(session, browser, requestId, checkpoint);
                } else if (task == null || pending == null || pending.expiresAt() <= System.currentTimeMillis()
                        || !pending.id().equals(message.get("proposalId").getAsString())) {
                    sendAssistant(session, requestId, "查找计划已过期，请重新分析", null);
                } else {
                    runResearch(session, browser, requestId, new BrowserSession.ResearchCheckpoint(
                            new BrowserResearchRun(assistant), task.plan(), task.requirement(), task.source(),
                            pending, task.reference()));
                }
            } catch (IllegalStateException e) {
                sendAssistant(session, requestId, e.getMessage(), null);
            } catch (Exception e) {
                sendAssistant(session, requestId, "页面读取失败或 DeepSeek 响应异常，请重试", null);
            } finally {
                analyzing.set(false);
            }
        });
    }

    private void stopResearchWorker() {
        stopResearch = true;
        Thread worker = researchWorker;
        if (worker != null) worker.interrupt();
    }

    private void runResearch(Session session, BrowserSession browser, String requestId,
                             BrowserSession.ResearchCheckpoint checkpoint) {
        if (!browser.startAssistantRun()) {
            sendAssistant(session, requestId, "此浏览器已经有任务在运行", null);
            return;
        }
        try {
            researchWorker = Thread.currentThread();
            browser.setResearchCheckpoint(checkpoint);
            java.util.function.BooleanSupplier active = () -> !stopResearch && session.isOpen();
            java.util.function.Consumer<BrowserResearchRun.Update> report = update -> {
                sessionStore.touch(browser.getSessionId());
                sendResearch(session, requestId, update, null);
            };
            var finalResult = checkpoint.reference() != null && checkpoint.plan().productReference() == null
                    ? checkpoint.run().runVisual(browser, checkpoint, active, report)
                    : checkpoint.run().run(browser, checkpoint, active, report);
            DeepSeekBrowserAssistant.Answer answer = assistant.evidenceOnly(finalResult);
            if (session.isOpen() && !stopResearch && !answer.points().isEmpty()) {
                sendResearch(session, requestId, new BrowserResearchRun.Update("synthesizing",
                        "正在根据核对到的搜索结果整理答复", finalResult.steps(),
                        finalResult.products(), finalResult.findings(), finalResult.visualMatches()), null);
                try {
                    answer = assistant.synthesize(checkpoint.requirement(), checkpoint.plan(), finalResult);
                } catch (Exception e) {
                    // Keep the verified excerpts available when the final model call fails.
                }
            }
            if ("completed".equals(finalResult.status())) browser.setResearchCheckpoint(null);
            sendResearch(session, requestId, finalResult, answer);
        } finally {
            researchWorker = null;
            browser.endAssistantRun();
        }
    }

    private void sendResearch(Session session, String requestId, BrowserResearchRun.Update update,
                              DeepSeekBrowserAssistant.Answer answer) {
        if (!session.isOpen()) return;
        JsonObject result = new com.google.gson.Gson().toJsonTree(update).getAsJsonObject();
        result.addProperty("type", "ai_result");
        result.addProperty("requestId", requestId);
        result.addProperty("running", List.of("running", "synthesizing").contains(update.status()));
        result.addProperty("canResume", List.of("stopped", "partial", "needs_user").contains(update.status()));
        if (answer != null && !answer.points().isEmpty()) result.add("answer", new com.google.gson.Gson().toJsonTree(answer));
        enqueueText(session, result);
    }

    private void sendAssistant(Session session, String requestId, String message, DeepSeekBrowserAssistant.Proposal action) {
        if (!session.isOpen()) return;
        JsonObject result = new JsonObject();
        result.addProperty("type", "ai_result");
        result.addProperty("requestId", requestId);
        result.addProperty("message", message);
        if (task != null) result.add("plan", planDetails(task.plan()));
        if (action != null) {
            result.addProperty("proposalId", action.id());
            String detail = switch (action.type()) {
                case "click" -> "点击画面位置 " + Math.round(action.x() / 10) + "%, " + Math.round(action.y() / 10) + "%";
                case "scroll" -> action.direction() < 0 ? "向上滚动" : "向下滚动";
                case "type" -> "输入「" + action.text() + "」";
                case "navigate", "open_source" -> "前往 " + action.text();
                case "submit_search" -> "站内搜索「" + action.text() + "」";
                default -> "";
            };
            result.addProperty("action", action.label() + " · " + detail);
            result.addProperty("opensSource", "open_source".equals(action.type()));
        }
        enqueueText(session, result);
    }

    private JsonObject planDetails(DeepSeekBrowserAssistant.Plan plan) {
        JsonObject details = new JsonObject();
        details.addProperty("goal", plan.goal());
        details.addProperty("site", plan.site());
        details.addProperty("url", plan.productReference() == null ? plan.url() : plan.entryUrl());
        details.addProperty("reason", plan.reason());
        details.addProperty("query", plan.query());
        if (plan.maxPriceCny() != null) details.addProperty("maxPriceCny", plan.maxPriceCny());
        return details;
    }

    @OnError
    public void onError(Session session, Throwable t) {
        System.out.println("WebSocket 错误: ws=" + session.getId() + " err=" + t.getMessage());
        t.printStackTrace();
    }

    private void sendError(Session session, String msg) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", msg);
        enqueueText(session, error);
    }

    // Tomcat permits only one writer at a time per WebSocket. The frame pusher drains this queue.
    private void enqueueText(Session session, JsonObject message) {
        if (session.isOpen() && !outgoingMessages.offer(message.toString()))
            closeStream(session, "monitor control queue full");
    }

    private void closeStream(Session session, String reason) {
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, reason));
        } catch (Exception ignored) {}
    }

    private void closeQuietly(Session session, String reason) {
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
        } catch (Exception ignored) {}
    }
}
