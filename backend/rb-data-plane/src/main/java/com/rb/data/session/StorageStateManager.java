package com.rb.data.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * storage_state 持久化。
 *
 * 目录结构（挂载到宿主机 ./data/sessions）：
 *   <sessionId>.json   会话快照（审计/导出用，内容变化时才落盘）
 *   baidu.json         百度免密凭证（登录成功后自动更新）
 *   douyin.json        抖音免密凭证
 *   taobao.json        淘宝/天猫免密凭证
 *   jd.json            京东免密凭证
 *   bilibili.json      B 站免密凭证
 *
 * 关键点：只有“确认已登录”的凭证才会写入站点文件，避免匿名会话把有效凭证覆盖掉。
 */
@Component
public class StorageStateManager {

    private final Path dir;
    private final String workspaceUserId;
    private final StorageStateManager workspaceStorage;
    private final Map<String, String> lastSessionSnapshot = new ConcurrentHashMap<>();

    @Autowired
    public StorageStateManager(@Value("${rb.storage.dir}") String dir,
                               @Value("${rb.workspace.user-id:local-workspace}") String workspaceUserId) throws IOException {
        this(Path.of(dir).toAbsolutePath().normalize(), workspaceUserId, null);
    }

    private StorageStateManager(Path dir, String workspaceUserId, StorageStateManager workspaceStorage) throws IOException {
        this.dir = dir;
        this.workspaceUserId = workspaceUserId;
        this.workspaceStorage = workspaceStorage;
        Files.createDirectories(this.dir);
        System.out.println("[storage] 凭证目录: " + this.dir);
    }

    public StorageStateManager forAccount(String userId, String accountId) throws IOException {
        boolean workspace = workspaceUserId != null && workspaceUserId.equals(userId);
        boolean defaultAccount = "default".equals(accountId);
        if (workspace && defaultAccount) return this;
        if (!defaultAccount && (accountId == null || !accountId.matches("[a-f0-9-]{36}")))
            throw new IllegalArgumentException("Invalid account id");
        String owner = java.util.UUID.nameUUIDFromBytes(userId.getBytes(StandardCharsets.UTF_8)).toString();
        return new StorageStateManager(dir.resolve("accounts").resolve(owner).resolve(accountId),
                null, workspace ? this : null);
    }

    private StorageStateManager sharedStore(String site) {
        if (!"baidu".equals(site) && !"jd".equals(site)) return null;
        return workspaceUserId != null ? this : workspaceStorage;
    }

    public Path getDir() {
        return dir;
    }

    public String sitePath(String site) {
        String file = SiteProfiles.stateFileName(site);
        StorageStateManager shared = sharedStore(site);
        return file == null ? null : (shared == null ? dir : shared.dir).resolve(file).toString();
    }

    /** 保存会话快照（内容与上次写入相同则跳过，避免每 30 秒重复写大文件） */
    public void saveSessionSnapshot(String sessionId, String json) {
        if (json == null) return;
        if (json.equals(lastSessionSnapshot.get(sessionId))) return;
        try {
            writeAtomically(dir.resolve(sessionId + ".json"), json);
            lastSessionSnapshot.put(sessionId, json);
        } catch (IOException e) {
            System.err.println("[storage] 保存会话快照失败 " + sessionId + ": " + e.getMessage());
        }
    }

    /** 保存某个站点的免密凭证 */
    public boolean saveSite(String site, String json) {
        String file = SiteProfiles.stateFileName(site);
        if (file == null || json == null) return false;
        StorageStateManager shared = sharedStore(site);
        return (shared == null ? this : shared).writeSite(file, site, json);
    }

    private boolean writeSite(String file, String site, String json) {
        try {
            String scoped = scopeToSite(site, json);
            writeAtomically(dir.resolve(file), scoped);
            System.out.println("[storage] 已更新 " + file + "（" + site + " 登录态，"
                    + scoped.length() + " 字节）");
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[storage] 保存 " + file + " 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 从一份 storage_state 中识别站点和登录态：
     *   - 优先使用传入的站点提示（当前页面域名）
     *   - 登录态成立时写入对应的 <site>.json
     *
     * @return 实际写入的站点名，未登录返回 null
     */
    public String persistIfLoggedIn(String hintSite, String stateJson) {
        return persistIfLoggedIn(hintSite, stateJson, false);
    }

    String persistIfLoggedIn(String hintSite, String stateJson, boolean completedLogin) {
        if (stateJson == null) return null;
        try {
            JsonObject state = JsonParser.parseString(stateJson).getAsJsonObject();
            JsonArray cookies = state.has("cookies") && state.get("cookies").isJsonArray()
                    ? state.getAsJsonArray("cookies") : new JsonArray();

            String site = hintSite;
            if (!completedLogin && (site == null || !SiteProfiles.isLoggedIn(site, cookies))) {
                String detected = SiteProfiles.siteFromCookies(cookies);
                if (detected != null) site = detected;
            }
            if (site == null || !SiteProfiles.isLoggedIn(site, cookies)) return null;

            StorageStateManager shared = sharedStore(site);
            if (shared == null) return saveSite(site, stateJson) ? site : null;
            synchronized (shared) {
                // Background exports from another browser can carry an older shared login.
                if (!completedLogin && hasLogin(site, shared.readSite(site))) return null;
                return saveSite(site, stateJson) ? site : null;
            }
        } catch (Exception e) {
            System.err.println("[storage] 解析 storage_state 失败: " + e.getMessage());
            return null;
        }
    }

    public String loadSite(String site) {
        StorageStateManager shared = sharedStore(site);
        if (shared == null || shared == this) return readSite(site);
        String current = shared.readSite(site);
        if (hasLogin(site, current)) return current;
        String previous = readSite(site);
        return hasLogin(site, previous) ? previous : current;
    }

    private String readSite(String site) {
        String file = SiteProfiles.stateFileName(site);
        if (file == null) return null;
        String json = loadFile(dir.resolve(file));
        try {
            return json == null ? null : scopeToSite(site, json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 兼容旧版本：百度凭证曾经保存在 default.json */
    public String loadLegacyDefault() {
        StorageStateManager shared = sharedStore("baidu");
        String json = (shared == null ? this : shared).loadDefault();
        try {
            return json == null ? null : scopeToSite("baidu", json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void saveDefault(String json) {
        try {
            writeAtomically(dir.resolve("default.json"), json);
        } catch (IOException e) {
            System.err.println("[storage] 保存 default.json 失败: " + e.getMessage());
        }
    }

    public String loadDefault() {
        return loadFile(dir.resolve("default.json"));
    }

    public String load(String sessionId) {
        return loadFile(dir.resolve(sessionId + ".json"));
    }

    private static boolean hasLogin(String site, String json) {
        return json != null && SiteProfiles.isLoggedIn(site,
                JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("cookies"));
    }

    private static String scopeToSite(String site, String json) {
        JsonObject state = JsonParser.parseString(json).getAsJsonObject();
        JsonObject scoped = new JsonObject();
        JsonArray cookies = new JsonArray();
        if (state.has("cookies") && state.get("cookies").isJsonArray()) {
            for (var value : state.getAsJsonArray("cookies")) {
                if (!value.isJsonObject()) continue;
                var domain = value.getAsJsonObject().get("domain");
                if (domain != null && domain.isJsonPrimitive()
                        && site.equals(SiteProfiles.siteFromUrl("https://" + domain.getAsString().replaceFirst("^\\.", "")))) {
                    cookies.add(value);
                }
            }
        }
        scoped.add("cookies", cookies);
        var url = state.get("url");
        if (url != null && url.isJsonPrimitive() && site.equals(SiteProfiles.siteFromUrl(url.getAsString()))) {
            scoped.add("url", url);
            if (state.has("localStorage")) scoped.add("localStorage", state.get("localStorage"));
            if (state.has("sessionStorage")) scoped.add("sessionStorage", state.get("sessionStorage"));
        }
        if (state.has("exportedAt")) scoped.add("exportedAt", state.get("exportedAt"));
        scoped.addProperty("site", site);
        return scoped.toString();
    }

    private String loadFile(Path path) {
        if (!Files.exists(path)) return null;
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private synchronized void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
