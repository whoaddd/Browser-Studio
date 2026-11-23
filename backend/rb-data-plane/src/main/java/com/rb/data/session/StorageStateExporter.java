package com.rb.data.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rb.data.redis.SessionStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 凭证导出调度。
 *
 * 旧实现里每个会话每 30 秒都会把 storage_state 覆盖写进 default.json，
 * 多会话并行时互相踩踏（抖音的 Cookie 会覆盖百度的登录态）。这里改为：
 *   1. 会话快照写入 <sessionId>.json（内容不变则跳过）；
 *   2. 只有“确认已登录”的凭证才写入对应站点文件（baidu.json / douyin.json /
 *      taobao.json / jd.json / bilibili.json），互不影响。
 */
@Component
public class StorageStateExporter {

    private final SessionRegistry registry;
    private final SessionStore sessionStore;
    private final StorageStateManager storageStateManager;

    public StorageStateExporter(SessionRegistry registry,
                                SessionStore sessionStore,
                                StorageStateManager storageStateManager) {
        this.registry = registry;
        this.sessionStore = sessionStore;
        this.storageStateManager = storageStateManager;
    }

    @Scheduled(fixedDelay = 30_000)
    public void exportPeriodic() {
        for (BrowserSession bs : registry.all()) {
            try {
                String state = bs.exportStorageState();
                if (state == null) continue;
                bs.getStorage().saveSessionSnapshot(bs.getSessionId(), state);
                bs.getStorage().persistIfLoggedIn(hintSite(bs, state), state);
            } catch (Exception e) {
                System.err.println("定期导出失败 " + bs.getSessionId() + ": " + e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 2_000)
    public void drainDetect() {
        for (BrowserSession bs : registry.all()) {
            try {
                Map<String, String> meta = sessionStore.get(bs.getSessionId());
                if (meta == null) continue;
                if (!"DRAINING".equals(meta.get("state"))) continue;

                System.out.println("[" + bs.getSessionId() + "] 检测到 DRAINING，导出最终 storage_state");
                String state = bs.exportStorageState();
                if (state != null) {
                    bs.getStorage().saveSessionSnapshot(bs.getSessionId(), state);
                    bs.getStorage().persistIfLoggedIn(hintSite(bs, state), state);
                }
                bs.close();
                registry.remove(bs.getSessionId());
            } catch (Exception e) {
                System.err.println("DRAINING 导出失败 " + bs.getSessionId() + ": " + e.getMessage());
            }
        }
    }

    /** 当前页面所属站点优先，其次由 Cookie 推断 */
    private String hintSite(BrowserSession bs, String stateJson) {
        try {
            JsonObject state = JsonParser.parseString(stateJson).getAsJsonObject();
            if (state.has("url")) {
                String site = SiteProfiles.siteFromUrl(state.get("url").getAsString());
                if (site != null) return site;
            }
        } catch (Exception ignored) {}
        return bs.currentSite();
    }
}
