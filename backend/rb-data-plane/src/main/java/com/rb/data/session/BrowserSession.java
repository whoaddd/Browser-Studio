package com.rb.data.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rb.common.SessionState;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单个浏览器会话：CDP 连接、推流、键鼠注入、自动化执行、免密凭证注入/导出。
 *
 * 风控相关设计（淘宝/京东等重点站点）：
 *   1. Chromium 以“有头”模式运行在 Xvfb 中（不是 headless-shell），配合 stealth.js 抹平自动化特征；
 *   2. 通过 CDP 统一 UA / UA-CH / 时区 / 语言，保证浏览器自报信息前后一致；
 *   3. 免密凭证按站点隔离（taobao.json / jd.json ...），先访问站点再注入 Cookie + localStorage；
 *   4. 只有确认已登录的状态才会写回站点凭证，避免匿名态覆盖有效凭证。
 */
public class BrowserSession {

    private static final String DEFAULT_STORAGE_DIR = "/app/data/sessions";
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String DEFAULT_VERSION = "131.0.6778.33";

    private static final String STORAGE_DIR =
            System.getenv().getOrDefault("RB_STORAGE_DIR", DEFAULT_STORAGE_DIR);
    private static final String USER_AGENT =
            System.getenv().getOrDefault("RB_CHROMIUM_USER_AGENT", DEFAULT_UA);
    private static final String BROWSER_VERSION =
            System.getenv().getOrDefault("RB_CHROMIUM_VERSION", DEFAULT_VERSION);
    private static final String TIMEZONE =
            System.getenv().getOrDefault("RB_TIMEZONE", "Asia/Shanghai");
    private static final String LOCALE =
            System.getenv().getOrDefault("RB_LOCALE", "zh-CN");
    private static final String START_SITE =
            System.getenv().getOrDefault("RB_START_SITE", "baidu");
    private static final String START_URL =
            System.getenv().getOrDefault("RB_START_URL", "https://www.baidu.com");
    private static final boolean ANTI_DETECT =
            !"false".equalsIgnoreCase(System.getenv().getOrDefault("RB_ANTI_DETECT", "true"));

    private static final String STEALTH_JS = loadClasspathText("stealth.js");
    private static final String JD_RESULT_CARDS = """
            const jdSearch = location.protocol === 'https:' && location.hostname === 'search.jd.com'
              && ['/search', '/search/'].includes(location.pathname.toLowerCase());
            const jdCardSelector = '.plugin_goodsCardWrapper[data-sku], li.gl-item[data-sku]';
            const jdProduct = card => {
              if (!jdSearch || !card?.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})) return null;
              const sku = card.getAttribute('data-sku');
              const title = card.querySelector('[class*="_goods_title_container_"], .p-name');
              const label = title?.innerText.trim();
              if (!/^[0-9]{5,20}$/.test(sku) || !label) return null;
              return {label: label.slice(0,160), url: 'https://item.jd.com/' + sku + '.html'};
            };
            """;

    private static final Set<String> COOKIE_PARAM_KEYS = Set.of(
            "name", "value", "domain", "path", "expires", "httpOnly", "secure",
            "sameSite", "priority", "sameParty", "sourceScheme", "sourcePort", "partitionKey");

    private final String sessionId;
    private final String userId;
    private final String containerId;
    private final String cdpUrl;
    private final Path storageDir;
    private final StorageStateManager storage;

    private final LatestFrame frames = new LatestFrame();
    private final java.util.concurrent.ExecutorService ackSender = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "frame-ack"); t.setDaemon(true); return t;
    });
    private volatile byte[] lastFrame;
    private volatile int viewportWidth, viewportHeight, viewportScale;
    private int browserChromeWidth, browserChromeHeight;
    private final ReentrantLock inputLock = new ReentrantLock();

    private WebSocket cdpWs;
    private final java.util.concurrent.atomic.AtomicInteger messageId = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean automationRunning = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile SessionState state = SessionState.CREATING;

    private final long createdAt = System.currentTimeMillis();
    private volatile long lastActiveAt = System.currentTimeMillis();
    private volatile long disconnectedAt = 0;
    private volatile String lastUrl = START_URL;

    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    private final ActionLogger actionLogger;
    private volatile String mainFrameId;
    private final Object navigationLogLock = new Object();
    private String recordedPage;

    public BrowserSession(String sessionId, String userId, String containerId,
                          String cdpUrl, StorageStateManager storage, ActionLogger actionLogger) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.containerId = containerId;
        this.cdpUrl = cdpUrl;
        this.storage = storage;
        this.actionLogger = actionLogger;
        this.storageDir = storage != null
                ? storage.getDir()
                : Path.of(STORAGE_DIR).toAbsolutePath().normalize();
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    public void start() {
        try {
            String pageWsUrl = fetchPageTargetUrl();
            System.out.println("[" + sessionId + "] page target: " + pageWsUrl);

            cdpWs = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(pageWsUrl), new CdpListener())
                    .get(10, TimeUnit.SECONDS);

            sendAndWait("Page.enable", null);
            JsonObject frame = sendAndWait("Page.getFrameTree", null)
                    .getAsJsonObject("frameTree").getAsJsonObject("frame");
            mainFrameId = frame.get("id").getAsString();
            recordNavigation(frame.get("url").getAsString());
            sendCommand("Network.enable", null);

            applyBrowserProfile();
            addNewDocumentScript("stealth", STEALTH_JS);
            addNewDocumentScript("blank-interceptor", blankInterceptorScript());

            setViewport(1280, 720, 1);

            // 先访问站点，再注入凭证并刷新，保证 cookie 作用域和 localStorage 的 origin 正确
            String startSite = SiteProfiles.siteFromUrl(START_URL);
            if (startSite == null) startSite = normalizeSite(START_SITE);
            String existingUrl = currentDocumentUrl();
            boolean needsStartNavigation = existingUrl == null || existingUrl.isBlank() || "about:blank".equals(existingUrl);
            // Restore saved logins for direct visits from the address bar or an AI task.
            for (String site : java.util.List.of("baidu", "jd")) {
                if (!(needsStartNavigation && site.equals(startSite)) && !hasLoginCookies(site)) injectSiteState(site);
            }
            if (needsStartNavigation) {
                navigate(START_URL);
                sleep(1200);
                injectSiteState(startSite);
                sleep(200);
                reloadPage();
                sleep(1500);
            }

            lastUrl = currentDocumentUrl();
            state = SessionState.ACTIVE;
            System.out.println("[" + sessionId + "] 会话已启动，起始站点=" + startSite);
        } catch (Exception e) {
            state = SessionState.FAILED;
            throw new IllegalStateException("会话启动失败: " + sessionId, e);
        }
    }

    /** 统一浏览器身份：UA、UA-CH、Accept-Language、时区、Locale */
    private void applyBrowserProfile() {
        if (!ANTI_DETECT) return;

        JsonObject ua = new JsonObject();
        ua.addProperty("userAgent", USER_AGENT);
        ua.addProperty("acceptLanguage", "zh-CN,zh,en");
        ua.addProperty("platform", "Win32");

        String major = BROWSER_VERSION.contains(".")
                ? BROWSER_VERSION.substring(0, BROWSER_VERSION.indexOf('.')) : BROWSER_VERSION;
        JsonArray brands = new JsonArray();
        brands.add(brand("Google Chrome", major));
        brands.add(brand("Chromium", major));
        brands.add(brand("Not_A Brand", "24"));
        JsonArray fullVersionList = new JsonArray();
        fullVersionList.add(brand("Google Chrome", BROWSER_VERSION));
        fullVersionList.add(brand("Chromium", BROWSER_VERSION));
        fullVersionList.add(brand("Not_A Brand", "24.0.0.0"));

        JsonObject meta = new JsonObject();
        meta.add("brands", brands);
        meta.add("fullVersionList", fullVersionList);
        meta.addProperty("fullVersion", BROWSER_VERSION);
        meta.addProperty("platform", "Windows");
        meta.addProperty("platformVersion", "10.0.0");
        meta.addProperty("architecture", "x86");
        meta.addProperty("bitness", "64");
        meta.addProperty("model", "");
        meta.addProperty("mobile", false);
        meta.addProperty("wow64", false);
        ua.add("userAgentMetadata", meta);
        sendCommand("Emulation.setUserAgentOverride", ua);

        try {
            JsonObject tz = new JsonObject();
            tz.addProperty("timezoneId", TIMEZONE);
            sendAndWait("Emulation.setTimezoneOverride", tz);
        } catch (Exception e) {
            System.out.println("[" + sessionId + "] 时区覆盖失败（忽略）: " + e.getMessage());
        }
        try {
            JsonObject locale = new JsonObject();
            locale.addProperty("locale", LOCALE);
            sendAndWait("Emulation.setLocaleOverride", locale);
        } catch (Exception e) {
            System.out.println("[" + sessionId + "] Locale 覆盖失败（忽略）: " + e.getMessage());
        }
    }

    private static JsonObject brand(String name, String version) {
        JsonObject b = new JsonObject();
        b.addProperty("brand", name);
        b.addProperty("version", version);
        return b;
    }

    private void addNewDocumentScript(String label, String source) {
        if (source == null || source.isBlank()) return;
        try {
            JsonObject params = new JsonObject();
            params.addProperty("source", source);
            sendCommand("Page.addScriptToEvaluateOnNewDocument", params);
        } catch (Exception e) {
            System.out.println("[" + sessionId + "] 注入脚本失败(" + label + "): " + e.getMessage());
        }
    }

    private static String blankInterceptorScript() {
        return "(function(){" +
                "  document.addEventListener('click', function(e){" +
                "    var a = e.target && e.target.closest && e.target.closest('a');" +
                "    if (a && a.getAttribute('target') === '_blank') {" +
                "      a.setAttribute('target', '_self');" +
                "    }" +
                "  }, true);" +
                "  var nativeOpen = window.open;" +
                "  window.open = function(url, target, features) {" +
                // Some links open an empty window first and navigate its WindowProxy later.
                // An empty URL reuses the current document and keeps the streamed tab alive.
                "    if (url === 'about:blank' && String(target).toLowerCase() !== '_self') url = '';" +
                "    return nativeOpen.call(window, url, '_self', features);" +
                "  };" +
                "})();";
    }

    // ==================================================================
    // 免密凭证：按站点读取 / 注入 / 导出
    // ==================================================================

    /** 兼容旧接口：注入指定站点的免密凭证 */
    public void injectCookies(String siteName) {
        injectSiteState(siteName);
    }

    /** 注入站点凭证（Cookie + localStorage + sessionStorage），并输出诊断日志 */
    public void injectSiteState(String siteName) {
        String site = normalizeSite(siteName);
        String stateJson = loadSiteState(site);
        if (stateJson == null) {
            System.out.println("[" + sessionId + "] 站点 " + site + " 暂无免密凭证，以未登录状态启动");
            return;
        }

        try {
            JsonObject state = JsonParser.parseString(stateJson).getAsJsonObject();
            JsonArray cookies = state.has("cookies") && state.get("cookies").isJsonArray()
                    ? state.getAsJsonArray("cookies") : new JsonArray();
            int applied = injectCookieParams(cookies);

            String stateUrl = state.has("url") ? state.get("url").getAsString() : null;
            String currentUrl = currentDocumentUrl();
            boolean sameOrigin = stateUrl != null && currentUrl != null
                    && sameHost(stateUrl, currentUrl);

            int lsCount = 0;
            int ssCount = 0;
            if (sameOrigin) {
                if (state.has("localStorage") && state.get("localStorage").isJsonObject()) {
                    lsCount = restoreWebStorage("localStorage", state.getAsJsonObject("localStorage"));
                }
                if (state.has("sessionStorage") && state.get("sessionStorage").isJsonObject()) {
                    ssCount = restoreWebStorage("sessionStorage", state.getAsJsonObject("sessionStorage"));
                }
            }

            System.out.println("[" + sessionId + "] 已注入 " + site + " 凭证: cookies=" + applied
                    + ", localStorage=" + lsCount + (sameOrigin ? "" : "（origin 不匹配，跳过 WebStorage）")
                    + ", sessionStorage=" + ssCount);
        } catch (Exception e) {
            System.err.println("[" + sessionId + "] 注入 " + site + " 凭证失败: " + e.getMessage());
        }
    }

    private String loadSiteState(String site) {
        String json = storage != null ? storage.loadSite(site) : loadSiteFile(site);
        // 兼容历史版本：百度凭证可能还是 default.json
        if (json == null && "baidu".equals(site)) {
            json = storage != null ? storage.loadLegacyDefault() : loadFile("default.json");
            if (json != null) {
                System.out.println("[" + sessionId + "] 使用历史 default.json 作为百度凭证");
            }
        }
        return json;
    }

    private String loadSiteFile(String site) {
        String file = SiteProfiles.stateFileName(site);
        return file == null ? null : loadFile(file);
    }

    private String loadFile(String fileName) {
        Path path = storageDir.resolve(fileName);
        try {
            if (!Files.exists(path) || Files.size(path) < 10) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[" + sessionId + "] 读取凭证失败 " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static String normalizeSite(String siteName) {
        if (siteName == null || siteName.isBlank()) return "baidu";
        return siteName.trim().toLowerCase();
    }

    /** 把 storage_state 里的 Cookie 转换为 CDP CookieParam，并按需降级重试 */
    private int injectCookieParams(JsonArray cookies) {
        if (cookies == null || cookies.size() == 0) return 0;

        JsonArray sanitized = new JsonArray();
        for (JsonElement el : cookies) {
            if (!el.isJsonObject()) continue;
            JsonObject src = el.getAsJsonObject();
            String name = src.has("name") ? src.get("name").getAsString() : "";
            if (name == null || name.isBlank()) continue;  // 历史状态里有空名 Cookie，会让 setCookies 失败

            JsonObject dst = new JsonObject();
            for (String key : COOKIE_PARAM_KEYS) {
                if (!src.has(key) || src.get(key).isJsonNull()) continue;
                JsonElement v = src.get(key);
                if ("expires".equals(key)) {
                    try {
                        double expires = v.getAsDouble();
                        if (expires <= 0) continue;  // session cookie，不传 expires
                        dst.addProperty("expires", expires);
                    } catch (Exception ignored) {}
                    continue;
                }
                dst.add(key, v.deepCopy());
            }
            sanitized.add(dst);
        }
        if (sanitized.size() == 0) return 0;

        JsonObject params = new JsonObject();
        params.add("cookies", sanitized);
        try {
            sendAndWait("Network.setCookies", params);
            return sanitized.size();
        } catch (Exception e) {
            System.out.println("[" + sessionId + "] setCookies 失败，降级为最小字段重试: " + e.getMessage());
        }

        JsonArray minimal = new JsonArray();
        for (JsonElement el : sanitized) {
            JsonObject src = el.getAsJsonObject();
            JsonObject dst = new JsonObject();
            dst.addProperty("name", src.get("name").getAsString());
            dst.addProperty("value", src.has("value") ? src.get("value").getAsString() : "");
            if (src.has("domain")) dst.addProperty("domain", src.get("domain").getAsString());
            if (src.has("path")) dst.addProperty("path", src.get("path").getAsString());
            minimal.add(dst);
        }
        JsonObject retry = new JsonObject();
        retry.add("cookies", minimal);
        try {
            sendAndWait("Network.setCookies", retry);
            return minimal.size();
        } catch (Exception e) {
            System.err.println("[" + sessionId + "] setCookies 降级仍失败: " + e.getMessage());
            return 0;
        }
    }

    private int restoreWebStorage(String kind, JsonObject data) {
        if (data == null || data.size() == 0) return 0;
        try {
            String expr = "(function(){var d=" + data.toString() + ";var n=0;" +
                    "for(var k in d){try{" + kind + ".setItem(k,String(d[k]));n++;}catch(e){}}return n;})()";
            JsonObject params = new JsonObject();
            params.addProperty("expression", expr);
            params.addProperty("returnByValue", true);
            JsonObject result = sendAndWait("Runtime.evaluate", params);
            if (result.has("result") && result.getAsJsonObject("result").has("value")) {
                return result.getAsJsonObject("result").get("value").getAsInt();
            }
        } catch (Exception e) {
            System.out.println("[" + sessionId + "] 恢复 " + kind + " 失败: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 导出 storage_state（cookies + localStorage + sessionStorage + 当前页面信息）。
     * 返回 null 代表 CDP 不可用。
     */
    public String exportStorageState() {
        try {
            JsonObject cookieRes = sendAndWait("Network.getAllCookies", null);
            JsonArray cookies = cookieRes.has("cookies") ? cookieRes.getAsJsonArray("cookies") : new JsonArray();

            JsonObject params = new JsonObject();
            params.addProperty("expression",
                    "JSON.stringify({" +
                    "  url: location.href," +
                    "  title: document.title," +
                    "  localStorage: (function(){try{return Object.fromEntries(Object.entries(localStorage))}catch(e){return {}}})()" +
                    "})");
            params.addProperty("returnByValue", true);
            JsonObject result = sendAndWait("Runtime.evaluate", params);
            String payload = result.getAsJsonObject("result").get("value").getAsString();
            JsonObject stateObj = JsonParser.parseString(payload).getAsJsonObject();

            if (stateObj.has("url")) lastUrl = stateObj.get("url").getAsString();
            stateObj.add("cookies", cookies);
            stateObj.addProperty("exportedAt", System.currentTimeMillis());

            System.out.println("[" + sessionId + "] 导出 storage_state (cookies=" + cookies.size() + ")");
            return stateObj.toString();
        } catch (Exception e) {
            System.err.println("[" + sessionId + "] 导出 storage_state 失败: " + e.getMessage());
            return null;
        }
    }

    public void restoreStorageState(String json) {
        try {
            JsonObject state = JsonParser.parseString(json).getAsJsonObject();
            if (state.has("cookies") && state.get("cookies").isJsonArray()) {
                injectCookieParams(state.getAsJsonArray("cookies"));
            }
            if (state.has("localStorage") && state.get("localStorage").isJsonObject()) {
                restoreWebStorage("localStorage", state.getAsJsonObject("localStorage"));
            }
        } catch (Exception ignored) {}
    }

    // ==================================================================
    // 自动化执行
    // ==================================================================

    /**
     * 执行 automation-config.json 中某个站点的步骤。
     * 流程：预热页 → 注入凭证 → 刷新生效 → 目标页 → 执行步骤 → 登录成功则立即持久化凭证。
     */
    public boolean isAutomationRunning() { return automationRunning.get(); }

    public void autoExecute(String siteName) { autoExecute(siteName, message -> {}); }

    public void autoExecute(String siteName, java.util.function.Consumer<String> notify) {
        if (!automationRunning.compareAndSet(false, true)) { notify.accept("已有登录任务进行中，请先完成当前扫码"); return; }
        new Thread(() -> {
            String site = normalizeSite(siteName);
            try {
                System.out.println("========== [自动化] 开始执行: " + site + " ==========");

                notify.accept("正在打开 " + site + " 登录页面");
                JsonObject config = loadAutomationConfig();
                if (config == null || !config.has(site)) {
                    System.err.println("[自动化] ❌ 配置中不存在站点: " + site);
                    return;
                }

                JsonObject siteConfig = config.getAsJsonObject(site);
                String url = siteConfig.get("url").getAsString();
                String prewarmUrl = siteConfig.has("prewarmUrl")
                        ? siteConfig.get("prewarmUrl").getAsString() : url;

                navigate(prewarmUrl);
                sleep(1500);
                injectSiteState(site);
                sleep(200);
                if ("taobao".equals(site)) navigate(prewarmUrl);
                else reloadPage();
                sleep(2000);

                // 已有有效免密凭证：直接回到站点首页，不必再走登录页
                if (hasLoginCookies(site) && (!"taobao".equals(site) || waitForTaobaoPersonalPage())) {
                    navigate(prewarmUrl);
                    sleep(1200);
                    System.out.println("[自动化] " + site + " 检测到登录凭证，仍需以站点页面显示为准");
                    persistNow(site, false);
                    notify.accept("已恢复 " + site + " 登录凭证，请核对页面账号");
                    return;
                }

                if (!prewarmUrl.equals(url)) {
                    navigate(url);
                    sleep(1500);
                    injectCookies(site);
                    sleep(200);
                    reloadPage();
                    sleep(2500);

                    if ("taobao".equals(site) && hasLoginCookies(site) && waitForTaobaoPersonalPage()) {
                        persistNow(site, false);
                        notify.accept("已恢复淘宝账号，个人中心已确认登录");
                        return;
                    }

                    // 淘宝/京东登录页在“未登录但环境可疑”时会把请求重定向回首页，
                    // 导致自动化在错误的页面上找二维码。这里做一次有边界的重试。
                    String loginHost = hostOf(url);
                    String actual = currentDocumentUrl();
                    if (loginHost != null && actual != null && !actual.contains(loginHost)) {
                        System.out.println("[自动化] 目标页被重定向: " + actual + "，重新打开登录页");
                        navigate(url);
                        sleep(2500);
                    }
                }

                JsonArray steps = siteConfig.getAsJsonArray("steps");
                for (int i = 0; i < steps.size(); i++) {
                    String script = steps.get(i).getAsString();
                    System.out.println("[自动化] 步骤 " + (i + 1) + "/" + steps.size() + " 执行中");
                    JsonObject evalParams = new JsonObject();
                    evalParams.addProperty("expression", script);
                    evalParams.addProperty("returnByValue", true);
                    evalParams.addProperty("awaitPromise", true);
                    try {
                        // 自动化步骤里可能包含 50 秒级的等待（扫码登录轮询），这里给足超时
                        JsonObject res = sendAndWait("Runtime.evaluate", evalParams, 75);
                        String stepResult = summarizeResult(res);
                        System.out.println("[自动化] 步骤 " + (i + 1) + " 返回: " + stepResult);
                        notify.accept(stepResult);
                        if (stepResult.contains("RISK_BLOCKED")) return;
                    } catch (Exception e) {
                        System.err.println("[自动化] ❌ 步骤 " + (i + 1) + " 异常: " + e.getMessage());
                    }
                    sleep(800);
                }

                if ("taobao".equals(site) || "jd".equals(site) || "douyin".equals(site)) {
                    // Poll from Java: navigation destroys promises in the old document's JS context.
                    for (int attempt = 0; attempt < 120; attempt++) {
                        if (hasLoginCookies(site) && (!"taobao".equals(site) || isTaobaoPersonalPage())) break;
                        sleep(1000);
                    }
                    System.out.println("[自动化] " + site + (hasLoginCookies(site)
                            ? " LOGIN_CREDENTIALS_DETECTED（请核对页面昵称）" : "WAITING_FOR_USER_LOGIN"));
                }
                boolean loggedIn = hasLoginCookies(site) && (!"taobao".equals(site) || isTaobaoPersonalPage());
                persistNow(site, loggedIn);
                notify.accept(loggedIn ? "已检测到登录凭证并保存，请核对页面账号" : "等待扫码：请在页面中扫码确认；成功后会自动保存登录状态");
                System.out.println("========== [自动化] 执行完毕: " + site + " ==========");
            } catch (Exception e) {
                System.err.println("[自动化] ❌ 发生全局异常: " + e.getMessage());
                e.printStackTrace();
            } finally {
                automationRunning.set(false);
            }
        }, "auto-task-" + sessionId).start();
    }

    private boolean waitForTaobaoPersonalPage() {
        for (int attempt = 0; attempt < 15; attempt++) {
            if (isTaobaoPersonalPage()) return true;
            sleep(1000);
        }
        return false;
    }

    private boolean isTaobaoPersonalPage() {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("expression", "location.hostname === 'i.taobao.com' && "
                    + "/收货地址/.test(document.body?.innerText || '') && "
                    + "/我的订单/.test(document.body?.innerText || '')");
            params.addProperty("returnByValue", true);
            return sendAndWait("Runtime.evaluate", params).getAsJsonObject("result").get("value").getAsBoolean();
        } catch (Exception e) { return false; }
    }

    /** 当前浏览器 Cookie 是否已满足该站点登录态 */
    private boolean hasLoginCookies(String site) {
        try {
            JsonObject res = sendAndWait("Network.getAllCookies", null);
            JsonArray cookies = res.has("cookies") ? res.getAsJsonArray("cookies") : new JsonArray();
            return SiteProfiles.isLoggedIn(site, cookies);
        } catch (Exception e) {
            return false;
        }
    }

    /** 立即导出并（在登录态成立时）写回站点凭证 */
    public StorageStateManager getStorage() { return storage; }

    private void persistNow(String site, boolean completedLogin) {
        String stateJson = exportStorageState();
        if (stateJson == null || storage == null) return;
        String savedSite = storage.persistIfLoggedIn(site, stateJson, completedLogin);
        storage.saveSessionSnapshot(sessionId, stateJson);
        if (savedSite != null) {
            System.out.println("[" + sessionId + "] ✅ 已保存 " + savedSite + " 免密凭证");
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject loadAutomationConfig() {
        String configPathStr = System.getenv().getOrDefault("RB_AUTOMATION_CONFIG",
                "/app/data/automation-config.json");
        Path configPath = Path.of(configPathStr);
        System.out.println("[自动化] 读取配置: " + configPath.toAbsolutePath());
        try {
            if (!Files.exists(configPath)) {
                System.err.println("[自动化] ❌ 找不到配置文件，请检查挂载: " + configPath);
                return null;
            }
            String configJson = Files.readString(configPath, StandardCharsets.UTF_8);
            return JsonParser.parseString(configJson).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("[自动化] ❌ 解析配置失败: " + e.getMessage());
            return null;
        }
    }

    private static String summarizeResult(JsonObject res) {
        if (res == null) return "null";
        if (res.has("exceptionDetails")) {
            JsonObject detail = res.getAsJsonObject("exceptionDetails");
            String text = detail.has("text") ? detail.get("text").getAsString() : "exception";
            return "EXCEPTION: " + text;
        }
        if (res.has("result") && res.getAsJsonObject("result").has("value")) {
            JsonElement value = res.getAsJsonObject("result").get("value");
            String raw = value.toString();
            return raw.length() > 300 ? raw.substring(0, 300) + "..." : raw;
        }
        return res.toString();
    }

    /** 当前页面 URL（失败返回 null） */
    public String currentDocumentUrl() {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("expression", "location.href");
            params.addProperty("returnByValue", true);
            JsonObject res = sendAndWait("Runtime.evaluate", params);
            if (res.has("result") && res.getAsJsonObject("result").has("value")) {
                String url = res.getAsJsonObject("result").get("value").getAsString();
                if (url != null) lastUrl = url;
                return url;
            }
        } catch (Exception ignored) {}
        return lastUrl;
    }

    public String currentSite() {
        return SiteProfiles.siteFromUrl(lastUrl);
    }

    // ==================================================================
    // CDP 目标发现 / 传输
    // ==================================================================

    private String fetchPageTargetUrl() throws IOException {
        HttpClient http = HttpClient.newHttpClient();
        String lastErr = "";

        URI base = URI.create(cdpUrl);
        for (int i = 0; i < 30; i++) {
            try {
                java.net.http.HttpRequest.Builder requestBuilder =
                        java.net.http.HttpRequest.newBuilder(URI.create(cdpUrl + "/json/list"))
                                .timeout(java.time.Duration.ofSeconds(2));
                if (base.getHost() != null && base.getHost().contains("docker.internal")) {
                    requestBuilder.header("Host", "localhost:" + base.getPort());
                }

                String json = http.send(requestBuilder.build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString()).body();

                if (json != null && !json.isBlank() && !"[]".equals(json.replaceAll("\\s", ""))) {
                    JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                    for (JsonElement el : arr) {
                        JsonObject obj = el.getAsJsonObject();
                        if ("page".equals(obj.get("type").getAsString()) && obj.has("webSocketDebuggerUrl")) {
                            String wsUrl = obj.get("webSocketDebuggerUrl").getAsString();
                            String rebuilt = rebuildWsUrl(wsUrl, base);
                            System.out.println("[" + sessionId + "] 成功拿到 page target: " + rebuilt);
                            return rebuilt;
                        }
                    }
                    lastErr = "json 有内容但没有 page target: "
                            + json.substring(0, Math.min(200, json.length()));
                } else {
                    lastErr = "json 为空";
                }
            } catch (Exception e) {
                lastErr = e.getMessage();
            }
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("[" + sessionId + "] fetchPageTargetUrl 失败, cdpUrl=" + cdpUrl
                + ", lastErr=" + lastErr);
    }

    /**
     * Chromium 返回的 webSocketDebuggerUrl 里的 host/port 未必是外部可达的
     * （例如经 relay、host.docker.internal 或端口映射时）。统一用 cdpUrl 的
     * authority 加上原始 path 重建，保证一定能连上。
     */
    private static String rebuildWsUrl(String rawWsUrl, URI cdpBase) {
        try {
            URI ws = URI.create(rawWsUrl);
            String path = ws.getRawPath();
            if (path == null || path.isBlank()) return rawWsUrl;
            String authority = cdpBase.getHost();
            int port = cdpBase.getPort() > 0 ? cdpBase.getPort() : 80;
            return "ws://" + authority + ":" + port + path;
        } catch (Exception e) {
            return rawWsUrl;
        }
    }

    private class CdpListener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

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

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.out.println("[" + sessionId + "] CDP WebSocket 错误: " + error.getMessage());
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("[" + sessionId + "] CDP WebSocket 关闭: " + statusCode + " " + reason);
            cdpWs = null;
            state = SessionState.FAILED;
            return null;
        }

        private void handleMessage(String text) {
            JsonObject msg;
            try {
                msg = JsonParser.parseString(text).getAsJsonObject();
            } catch (Exception e) {
                return;
            }

            if (msg.has("id")) {
                int id = msg.get("id").getAsInt();
                CompletableFuture<JsonObject> future = pending.remove(id);
                if (future != null) {
                    if (msg.has("error")) {
                        future.completeExceptionally(new RuntimeException(msg.get("error").toString()));
                    } else {
                        future.complete(msg.has("result") ? msg.getAsJsonObject("result") : new JsonObject());
                    }
                }
                return;
            }

            if (!msg.has("method")) return;
            String method = msg.get("method").getAsString();

            if ("Page.frameNavigated".equals(method)) {
                JsonObject frame = msg.getAsJsonObject("params").getAsJsonObject("frame");
                if (!frame.has("parentId")) {
                    mainFrameId = frame.get("id").getAsString();
                    recordNavigation(frame.get("url").getAsString());
                }
            } else if ("Page.navigatedWithinDocument".equals(method)) {
                JsonObject params = msg.getAsJsonObject("params");
                if (params.get("frameId").getAsString().equals(mainFrameId)) {
                    recordNavigation(params.get("url").getAsString());
                }
            } else if ("Page.screencastFrame".equals(method)) {
                JsonObject params = msg.getAsJsonObject("params");
                byte[] jpeg = Base64.getDecoder().decode(params.get("data").getAsString());
                int frameSessionId = params.get("sessionId").getAsInt();
                lastFrame = jpeg;
                JsonObject metadata = params.getAsJsonObject("metadata");
                int frameWidth = metadata.get("deviceWidth").getAsInt();
                int frameHeight = metadata.get("deviceHeight").getAsInt();
                frames.publish(jpeg, frameWidth, frameHeight);
                JsonObject ack = new JsonObject();
                ack.addProperty("sessionId", frameSessionId);
                // 在独立线程里回 ack，避免在 WebSocket 回调线程上 join 发送 Future 造成阻塞
                if (!ackSender.isShutdown()) {
                    try { ackSender.execute(() -> sendCommand("Page.screencastFrameAck", ack)); }
                    catch (java.util.concurrent.RejectedExecutionException ignored) {}
                }
            }
        }
    }

    private void recordNavigation(String url) {
        java.net.URI page;
        try {
            page = java.net.URI.create(url);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (page.getHost() == null || !("http".equals(page.getScheme()) || "https".equals(page.getScheme()))) return;

        synchronized (navigationLogLock) {
            if (url.equals(recordedPage)) return;
            actionLogger.appendNavigation(sessionId, recordedPage == null ? "navigate" : "transition", page.getHost());
            recordedPage = url;
        }
    }

    private synchronized void sendCommand(String method, JsonObject params) {
        if (cdpWs == null || cdpWs.isOutputClosed()) {
            System.err.println("[" + sessionId + "] CDP 未连接，跳过: " + method);
            return;
        }
        JsonObject cmd = new JsonObject();
        cmd.addProperty("id", messageId.incrementAndGet());
        cmd.addProperty("method", method);
        if (params != null) cmd.add("params", params);
        cdpWs.sendText(cmd.toString(), true).join();
    }

    private JsonObject sendAndWait(String method, JsonObject params) throws Exception {
        return sendAndWait(method, params, 8);
    }

    private JsonObject sendAndWait(String method, JsonObject params, long timeoutSeconds) throws Exception {
        if (cdpWs == null || cdpWs.isOutputClosed()) {
            throw new IllegalStateException("[" + sessionId + "] CDP 未连接，无法执行 " + method);
        }
        int id;
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        synchronized (this) {
            id = messageId.incrementAndGet();
            pending.put(id, future);
        }
        JsonObject cmd = new JsonObject();
        cmd.addProperty("id", id);
        cmd.addProperty("method", method);
        if (params != null) cmd.add("params", params);
        synchronized (this) { cdpWs.sendText(cmd.toString(), true).join(); }
        try { return future.get(timeoutSeconds, TimeUnit.SECONDS); }
        finally { pending.remove(id); }
    }

    // ==================================================================
    // 视口 / 推流
    // ==================================================================

    public void setViewport(int width, int height, int deviceScaleFactor) {
        if (width < 320 || width > 3840 || height < 240 || height > 2160 || deviceScaleFactor < 1 || deviceScaleFactor > 2) return;
        inputLock.lock();
        try {
            if (width == viewportWidth && height == viewportHeight && deviceScaleFactor == viewportScale) return;
            JsonObject window = sendAndWait("Browser.getWindowForTarget", null);
            if (viewportWidth == 0) {
                JsonObject measure = new JsonObject();
                measure.addProperty("expression", "({width:innerWidth,height:innerHeight})");
                measure.addProperty("returnByValue", true);
                JsonObject chrome = sendAndWait("Runtime.evaluate", measure).getAsJsonObject("result").getAsJsonObject("value");
                browserChromeWidth = Math.max(0, window.getAsJsonObject("bounds").get("width").getAsInt() - chrome.get("width").getAsInt());
                browserChromeHeight = Math.max(0, window.getAsJsonObject("bounds").get("height").getAsInt() - chrome.get("height").getAsInt());
            }
            JsonObject bounds = new JsonObject();
            bounds.addProperty("width", width + browserChromeWidth);
            bounds.addProperty("height", height + browserChromeHeight);
            JsonObject resize = new JsonObject();
            resize.add("windowId", window.get("windowId"));
            resize.add("bounds", bounds);
            sendAndWait("Browser.setWindowBounds", resize);
            viewportWidth = width; viewportHeight = height; viewportScale = deviceScaleFactor;
            JsonObject vp = new JsonObject();
            vp.addProperty("width", width);
            vp.addProperty("height", height);
            vp.addProperty("deviceScaleFactor", deviceScaleFactor);
            vp.addProperty("mobile", deviceScaleFactor > 1);
            vp.addProperty("screenWidth", width);
            vp.addProperty("screenHeight", height);
            sendCommand("Emulation.setDeviceMetricsOverride", vp);
            JsonObject visible = new JsonObject();
            visible.addProperty("width", width);
            visible.addProperty("height", height);
            sendCommand("Emulation.setVisibleSize", visible);

            sendCommand("Page.stopScreencast", null);
            sleep(100);

            JsonObject sc = new JsonObject();
            sc.addProperty("format", "jpeg");
            sc.addProperty("quality", 65);
            sc.addProperty("maxWidth", Math.min(width * deviceScaleFactor, 1600));
            sc.addProperty("maxHeight", Math.min(height * deviceScaleFactor, 1000));
            // A static page can paint just once after navigation.
            sc.addProperty("everyNthFrame", 1);
            sendCommand("Page.startScreencast", sc);
        } catch (Exception e) {
            throw new IllegalStateException("调整浏览器视口失败", e);
        } finally {
            inputLock.unlock();
        }
    }

    public String assistantPageState() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("expression", """
                (() => {
                  const field = document.activeElement;
                  const search = field?.matches('input[type=search],input[type=text],input:not([type])')
                    && /search|keyword|搜索|搜一搜|关键字|关键词/i.test(
                      [field.type,field.name,field.id,field.placeholder,field.getAttribute('aria-label')].join(' '));
                  return JSON.stringify([location.href,scrollX,scrollY,innerWidth,innerHeight,
                    search ? [field.name, field.value.slice(0,240)] : null]);
                })()
                """);
        params.addProperty("returnByValue", true);
        return sendAndWait("Runtime.evaluate", params).getAsJsonObject("result").get("value").getAsString();
    }

    public String assistantScreenshot() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("format", "jpeg");
        params.addProperty("quality", 70);
        params.addProperty("captureBeyondViewport", false);
        return sendAndWait("Page.captureScreenshot", params).get("data").getAsString();
    }

    private final java.util.concurrent.atomic.AtomicBoolean assistantRunning = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile ResearchCheckpoint researchCheckpoint;

    public record ResearchCheckpoint(BrowserResearchRun run, DeepSeekBrowserAssistant.Plan plan,
                                     String requirement, String source, DeepSeekBrowserAssistant.Proposal firstStep,
                                     DeepSeekBrowserAssistant.VisualReference reference) {}

    public String assistantVisualUrl() throws Exception {
        return sendAndWait("Page.getFrameTree", null).getAsJsonObject("frameTree")
                .getAsJsonObject("frame").get("url").getAsString();
    }

    public void scrollVisualPage() throws Exception {
        var viewport = sendAndWait("Page.getLayoutMetrics", null).getAsJsonObject("cssVisualViewport");
        double width = viewport.get("clientWidth").getAsDouble();
        double height = viewport.get("clientHeight").getAsDouble();
        dispatchWheel(width / 2, height / 2, 0, height * 0.72);
    }

    public boolean startAssistantRun() { return assistantRunning.compareAndSet(false, true); }
    public void endAssistantRun() { assistantRunning.set(false); }
    public boolean isAssistantRunning() { return assistantRunning.get(); }
    public ResearchCheckpoint getResearchCheckpoint() { return researchCheckpoint; }
    public void setResearchCheckpoint(ResearchCheckpoint checkpoint) { researchCheckpoint = checkpoint; }

    public String assistantPageText() throws Exception {
        return assistantEvaluate("(document.body?.innerText || '').slice(0, 80000)").getAsString();
    }

    public String dismissResearchOverlay(String expectedPage, java.util.Set<String> attempted) throws Exception {
        inputLock.lock();
        try {
            if (!expectedPage.equals(assistantPageState()) || isAutomationRunning()) return "";
            String skipped = new com.google.gson.Gson().toJson(attempted);
            var result = assistantEvaluate("""
                    (() => {
                      const attempted = new Set(%s);
                      const protectedContent = /登录|登陆|注册|验证|验证码|人机|扫码|授权|隐私|同意|付款|支付|订单|checkout|consent|captcha|sign[ -]?in|log[ -]?in|verify/i;
                      const closeOnly = /^(关闭(?:广告|弹窗|提示|公告)?|跳过广告|以后再说|稍后再说|下次再说|不再提示|close(?: ad| dialog| popup| notification)?|dismiss|not now|no thanks|skip ad|[×✕✖x])$/i;
                      const panels = document.querySelectorAll(
                        'dialog[open],[role="dialog"],[aria-modal="true"],[class*="modal"],[class*="popup"],[class*="overlay"]');
                      for (const panel of panels) {
                        if (!panel.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})) continue;
                        const bounds = panel.getBoundingClientRect();
                        if (bounds.bottom <= 0 || bounds.top >= innerHeight || bounds.right <= 0 || bounds.left >= innerWidth) continue;
                        const style = getComputedStyle(panel);
                        const modal = panel.matches('dialog[open],[role="dialog"],[aria-modal="true"]');
                        if (!modal && ((style.position !== 'fixed' && style.position !== 'absolute')
                            || bounds.width * bounds.height < innerWidth * innerHeight * 0.12)) continue;
                        const text = (panel.innerText || '').trim().slice(0,600);
                        if (protectedContent.test(text)) {
                          if (modal) return 'protected';
                          continue;
                        }
                        const owner = panel.parentElement?.closest('dialog[open],[role="dialog"],[aria-modal="true"]');
                        if (owner && protectedContent.test((owner.innerText || '').slice(0,1200))) continue;
                        for (const button of panel.querySelectorAll('button,[role="button"],[aria-label],[title]')) {
                          if (button.closest('a[href]') || button.disabled || button.getAttribute('aria-disabled') === 'true'
                              || (button.form && button.type === 'submit')
                              || !button.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})) continue;
                          const label = [button.getAttribute('aria-label'),button.title,button.innerText]
                            .filter(Boolean).map(value => value.trim()).find(value => closeOnly.test(value));
                          if (!label) continue;
                          const rect = button.getBoundingClientRect();
                          const at = document.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2);
                          if (!at || !button.contains(at)) continue;
                          const key = JSON.stringify([location.pathname,text,label,Math.round(rect.left),Math.round(rect.top)]);
                          if (attempted.has(key)) continue;
                          button.click();
                          return key;
                        }
                      }
                      return '';
                    })()
                    """.formatted(skipped));
            return result == null || result.isJsonNull() ? "" : result.getAsString();
        } finally {
            inputLock.unlock();
        }
    }

    public String assistantVisibleText() throws Exception {
        return assistantEvaluate("""
                (() => {
                  if (!document.body) return '';
                  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                  const range = document.createRange();
                  const visible = [];
                  let size = 0;
                  for (let count = 0, node; count < 2000 && size < 3500 && (node = walker.nextNode()); count++) {
                    const text = node.textContent.trim();
                    if (!text || node.parentElement.closest('script,style,noscript')) continue;
                    if (!node.parentElement.checkVisibility({checkOpacity: true, checkVisibilityCSS: true})) continue;
                    range.selectNodeContents(node);
                    const rect = range.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0 || rect.bottom <= 0 || rect.top >= innerHeight
                        || rect.right <= 0 || rect.left >= innerWidth) continue;
                    visible.push(text.slice(0,3500-size));
                    size += text.length + 1;
                  }
                  return visible.join(String.fromCharCode(10));
                })()
                """).getAsString();
    }

    public java.util.List<DeepSeekBrowserAssistant.BrowserLink> assistantLinks(DeepSeekBrowserAssistant.Plan plan)
            throws Exception {
        var links = assistantEvaluate("""
                (() => {
                  %s
                  const visible = element => {
                    const r = element.getBoundingClientRect();
                    return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight
                      && r.right > 0 && r.left < innerWidth;
                  };
                  const products = jdSearch ? Array.from(document.querySelectorAll(jdCardSelector))
                    .filter(visible).map(jdProduct).filter(Boolean) : [];
                  const anchors = Array.from(document.querySelectorAll('a[href]')).filter(visible).flatMap(link => {
                    const label = (link.title || link.innerText || link.getAttribute('aria-label')
                      || link.querySelector('img[alt]')?.alt || '').trim();
                    return label ? [{label: label.slice(0, 160), url: link.href}] : [];
                  });
                  return [...products, ...anchors].slice(0, 200);
                })()
                """.formatted(JD_RESULT_CARDS));
        var result = new java.util.LinkedHashMap<String, DeepSeekBrowserAssistant.BrowserLink>();
        if (links == null || !links.isJsonArray()) return java.util.List.of();
        for (var link : links.getAsJsonArray()) {
            var candidate = link.getAsJsonObject();
            String url = candidate.get("url").getAsString();
            if (!(url.startsWith("https://") || url.startsWith("http://")) || url.length() > 2048) continue;
            if (!readableResearchUrl(url, plan)) continue;
            result.putIfAbsent(url, new DeepSeekBrowserAssistant.BrowserLink(candidate.get("label").getAsString(), url));
        }
        String keyword = plan.query().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        return result.values().stream().sorted(java.util.Comparator.comparing(link ->
                        !link.label().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT).contains(keyword)))
                .limit(25).toList();
    }

    private com.google.gson.JsonElement assistantEvaluate(String expression) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("expression", expression);
        params.addProperty("returnByValue", true);
        return sendAndWait("Runtime.evaluate", params).getAsJsonObject("result").get("value");
    }

    public String siteSearchUrl(DeepSeekBrowserAssistant.Plan plan) throws Exception {
        if (plan.query().isBlank() || !plan.entryUrl().equals(plan.url())) return null;
        var form = assistantEvaluate("""
                (() => {
                  const matches = Array.from(document.querySelectorAll('form input[name]')).filter(input => {
                    const rect = input.getBoundingClientRect();
                    return input.form?.method === 'get' && !input.disabled && rect.width > 0 && rect.height > 0
                      && /^(search|text)$/.test(input.type)
                      && /search|keyword|搜索|关键字|关键词/i.test(
                        [input.type,input.name,input.id,input.placeholder,input.getAttribute('aria-label')].join(' '));
                  });
                  if (matches.length !== 1) return null;
                  return [matches[0].form.action, matches[0].name];
                })()
                """);
        if (form == null || form.isJsonNull()) return null;
        var fields = form.getAsJsonArray();
        String url = fields.get(0).getAsString();
        String name = fields.get(1).getAsString();
        if (!readableResearchUrl(url, plan) || !name.matches("[a-zA-Z][a-zA-Z0-9_-]{0,40}")) return null;
        var target = org.springframework.web.util.UriComponentsBuilder.fromUriString(url)
                .fragment(null).replaceQueryParam(name, "{keyword}").encode().buildAndExpand(plan.query());
        return target.toUriString();
    }

    private boolean readableResearchUrl(String url, DeepSeekBrowserAssistant.Plan plan) {
        var address = java.net.URI.create(url);
        String path = address.getPath();
        return plan.acceptsSite(url) && path != null && !java.util.regex.Pattern
                .compile("(?i)buy|cart|order|checkout|pay|login|logout|delete")
                .matcher(path).find();
    }

    public void applyResearchAction(DeepSeekBrowserAssistant.Proposal proposal,
                                    DeepSeekBrowserAssistant.Plan plan) throws Exception {
        inputLock.lock();
        try {
            if (isAutomationRunning()) throw new IllegalStateException("站点自动化正在运行，请稍后再试");
            if (!proposal.page().equals(assistantPageState()))
                throw new IllegalStateException("页面已变化，已停止查找");
            if ("navigate".equals(proposal.type()) && !readableResearchUrl(proposal.text(), plan))
                throw new IllegalStateException("链接超出本次资料查找范围");
            if ("click".equals(proposal.type())) {
                var viewport = JsonParser.parseString(proposal.page()).getAsJsonArray();
                double x = proposal.x() * viewport.get(3).getAsDouble() / 1000;
                double y = proposal.y() * viewport.get(4).getAsDouble() / 1000;
                var target = assistantEvaluate("""
                        (() => {
                          %s
                          const element = document.elementFromPoint(%s, %s);
                          if (element?.matches('input[type=search],input[type=text],input:not([type])')
                              && /search|keyword|搜索|搜一搜|关键字|关键词/i.test(
                                [element.type,element.name,element.id,element.placeholder,element.getAttribute('aria-label')].join(' '))) {
                            element.focus();
                            return '';
                          }
                          const link = element?.closest('a[href]');
                          if (link && /^https?:/.test(link.href) && !link.getAttribute('href').startsWith('#'))
                            return link.href;
                          if (element?.closest('button,input,select,textarea,[role=button]')) return null;
                          return jdProduct(element?.closest(jdCardSelector))?.url ?? null;
                        })()
                        """.formatted(JD_RESULT_CARDS, Double.toString(x), Double.toString(y)));
                if (target == null || target.isJsonNull())
                    throw new IllegalStateException("未在该位置识别到可打开的商品或链接");
                String url = target.getAsString();
                if (url.isBlank()) return;
                if (!readableResearchUrl(url, plan)) {
                    throw new IllegalStateException("链接超出本次资料查找范围");
                }
                applyAssistantAction(new DeepSeekBrowserAssistant.Proposal("", "", "navigate", "", 0, 0, 0,
                        url, proposal.page(), 0));
                return;
            }
            if ("type".equals(proposal.type()) || "submit_search".equals(proposal.type())) {
                ensureSearchField();
                if (!plan.query().equals(proposal.text()) || plan.query().isBlank())
                    throw new IllegalStateException("搜索词与已确认的计划不一致");
            }
            applyAssistantAction(proposal);
        } finally {
            inputLock.unlock();
        }
    }

    private void ensureSearchField() throws Exception {
        var search = assistantEvaluate("""
                (() => {
                  const e = document.activeElement;
                  return Boolean(e?.matches('input[type=search],input[type=text],input:not([type])') &&
                    /search|keyword|搜索|搜一搜|关键字|关键词/i.test(
                      [e.type,e.name,e.id,e.placeholder,e.getAttribute('aria-label')].join(' ')));
                })()
                """);
        if (search == null || !search.getAsBoolean()) throw new IllegalStateException("未识别到站内搜索框");
    }

    public void applyAssistantAction(DeepSeekBrowserAssistant.Proposal proposal) throws Exception {
        inputLock.lock();
        try {
            if (isAutomationRunning())
                throw new IllegalStateException("站点自动化正在运行，请稍后再试");
            if ("open_source".equals(proposal.type()) || "navigate".equals(proposal.type())) {
                JsonObject destination = new JsonObject();
                destination.addProperty("url", DeepSeekBrowserAssistant.publicDestination(proposal.text()).toASCIIString());
                JsonObject navigation = sendAndWait("Page.navigate", destination);
                if (navigation.has("errorText"))
                    throw new IllegalStateException("浏览器无法打开该网址，请尝试其他来源");
                touch();
                return;
            }
            if (!proposal.page().equals(assistantPageState()))
                throw new IllegalStateException("页面状态已变化，请重新分析");

            var viewport = JsonParser.parseString(proposal.page()).getAsJsonArray();
            double width = viewport.get(3).getAsDouble(), height = viewport.get(4).getAsDouble();
            switch (proposal.type()) {
                case "click" -> {
                    double x = proposal.x() * width / 1000;
                    double y = proposal.y() * height / 1000;
                    dispatchMouse("pressed", x, y, "left", 1, 1);
                    dispatchMouse("released", x, y, "left", 1, 0);
                }
                case "scroll" -> dispatchWheel(width / 2, height / 2, 0, proposal.direction() * height * 0.65);
                case "type", "submit_search" -> {
                    JsonObject check = new JsonObject();
                    check.addProperty("expression", """
                            document.activeElement?.matches(
                              'textarea, input:not([type]), input[type=text], input[type=search]')
                            && !/password|one-time-code|cc-/i.test(document.activeElement.autocomplete || '')
                            ? document.activeElement.value : null
                            """);
                    check.addProperty("returnByValue", true);
                    var value = sendAndWait("Runtime.evaluate", check).getAsJsonObject("result").get("value");
                    if (value == null || value.isJsonNull())
                        throw new IllegalStateException("请先聚焦普通文本输入框，再重新分析");
                    if ("submit_search".equals(proposal.type())) {
                        if (!proposal.text().equals(value.getAsString().strip()))
                            throw new IllegalStateException("搜索框内容与计划词不同，请重新分析");
                        dispatchKey("down", "Enter", "Enter", 0);
                        dispatchKey("up", "Enter", "Enter", 0);
                        return;
                    }
                    JsonObject input = new JsonObject();
                    input.addProperty("text", proposal.text());
                    sendAndWait("Input.insertText", input);
                }
                default -> throw new IllegalArgumentException("Unsupported action");
            }
        } finally {
            inputLock.unlock();
        }
    }

    public JsonObject snapshot() {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("expression",
                    "JSON.stringify({" +
                    "url: location.href," +
                    "title: document.title," +
                    "text: (document.body && document.body.innerText ? document.body.innerText : '').substring(0, 500)," +
                    "activeElement: (function(){" +
                    "  var el = document.activeElement;" +
                    "  if (!el || el === document.body) return '';" +
                    "  var tag = el.tagName || '';" +
                    "  var val = '';" +
                    "  if (tag === 'INPUT' || tag === 'TEXTAREA') {" +
                    "    val = el.value || '';" +
                    "  } else {" +
                    "    val = (el.innerText || '').substring(0, 80);" +
                    "  }" +
                    "  return tag + ':' + val.substring(0, 100);" +
                    "})()," +
                    "readyState: document.readyState" +
                    "})");
            params.addProperty("returnByValue", true);
            JsonObject result = sendAndWait("Runtime.evaluate", params);
            if (!result.has("result")) return null;
            JsonObject inner = result.getAsJsonObject("result");
            if (!inner.has("value")) return null;
            return JsonParser.parseString(inner.get("value").getAsString()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    public JsonObject getElementAt(double x, double y) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("expression",
                    "(function(){" +
                    "var el = document.elementFromPoint(" + x + "," + y + ");" +
                    "if (!el) return 'null';" +
                    "return JSON.stringify({" +
                    "tag: el.tagName," +
                    "text: (el.innerText || '').substring(0, 50).replace(/\\s+/g, ' ').trim()," +
                    "id: el.id || ''," +
                    "cls: (typeof el.className === 'string' ? el.className : '')" +
                    "});" +
                    "})()");
            params.addProperty("returnByValue", true);
            JsonObject result = sendAndWait("Runtime.evaluate", params);
            if (!result.has("result") || !result.getAsJsonObject("result").has("value")) return null;
            String val = result.getAsJsonObject("result").get("value").getAsString();
            if ("null".equals(val)) return null;
            return JsonParser.parseString(val).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================================================================
    // 页面操作
    // ==================================================================

    private void reloadPage() {
        inputLock.lock();
        try {
            JsonObject p = new JsonObject();
            p.addProperty("ignoreCache", false);
            sendCommand("Page.reload", p);
        } finally {
            inputLock.unlock();
        }
    }

    public void navigate(String url) {
        touch();
        inputLock.lock();
        try {
            JsonObject p = new JsonObject();
            p.addProperty("url", url);
            sendCommand("Page.navigate", p);
        } finally {
            inputLock.unlock();
        }
    }

    public boolean goBack() { return navigateHistory(-1); }
    public boolean goForward() { return navigateHistory(1); }

    private boolean navigateHistory(int delta) {
        inputLock.lock();
        try {
            JsonObject result = sendAndWait("Page.getNavigationHistory", null);
            if (result.has("currentIndex") && result.has("entries")) {
                int currentIndex = result.get("currentIndex").getAsInt();
                var entries = result.getAsJsonArray("entries");
                int target = currentIndex + delta;

                if (target >= 0 && target < entries.size()) {
                    int entryId = entries.get(target).getAsJsonObject().get("id").getAsInt();
                    JsonObject params = new JsonObject();
                    params.addProperty("entryId", entryId);
                    sendCommand("Page.navigateToHistoryEntry", params);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            inputLock.unlock();
        }
    }

    public void dispatchMouse(String action, double x, double y, String button, int clickCount, int buttons) {
        touch();
        inputLock.lock();
        try {
            String type = switch (action) {
                case "pressed" -> "mousePressed";
                case "released" -> "mouseReleased";
                default -> "mouseMoved";
            };
            JsonObject p = new JsonObject();
            p.addProperty("type", type);
            p.addProperty("x", x);
            p.addProperty("y", y);

            if ("mouseMoved".equals(type)) {
                p.addProperty("button", "none");
                p.addProperty("buttons", buttons);
                p.addProperty("clickCount", 0);
            } else if ("mousePressed".equals(type)) {
                p.addProperty("button", button != null ? button : "left");
                p.addProperty("buttons", 1);
                p.addProperty("clickCount", Math.max(1, clickCount));
            } else {
                p.addProperty("button", button != null ? button : "left");
                p.addProperty("buttons", 0);
                p.addProperty("clickCount", Math.max(1, clickCount));
            }

            sendCommand("Input.dispatchMouseEvent", p);
        } finally {
            inputLock.unlock();
        }
    }

    public void dispatchWheel(double x, double y, double deltaX, double deltaY) {
        touch();
        inputLock.lock();
        try {
            JsonObject p = new JsonObject();
            p.addProperty("type", "mouseWheel");
            p.addProperty("x", x);
            p.addProperty("y", y);
            p.addProperty("deltaX", deltaX);
            p.addProperty("deltaY", deltaY);
            sendCommand("Input.dispatchMouseEvent", p);
        } finally {
            inputLock.unlock();
        }
    }

    public void dispatchScrollGesture(double x, double y, double xDistance, double yDistance) {
        touch();
        inputLock.lock();
        try {
            JsonObject p = new JsonObject();
            p.addProperty("x", (int) x);
            p.addProperty("y", (int) y);
            p.addProperty("xDistance", (int) xDistance);
            p.addProperty("yDistance", (int) yDistance);
            p.addProperty("speed", 800);
            p.addProperty("gestureSourceType", "touch");
            sendCommand("Input.synthesizeScrollGesture", p);
        } finally {
            inputLock.unlock();
        }
    }

    public void dispatchKey(String action, String key, String code, int modifiers) {
        touch();
        inputLock.lock();
        try {
            boolean printable = key != null && key.length() == 1;
            String type = "down".equals(action) ? (printable ? "keyDown" : "rawKeyDown") : "keyUp";
            JsonObject p = new JsonObject();
            p.addProperty("type", type);
            p.addProperty("key", key);
            p.addProperty("code", code);
            p.addProperty("modifiers", modifiers);

            int vk = getVirtualKeyCode(key);
            if (vk > 0) {
                p.addProperty("windowsVirtualKeyCode", vk);
                p.addProperty("nativeVirtualKeyCode", vk);
            }

            if (printable && "keyDown".equals(type)) {
                p.addProperty("text", key);
            }
            sendCommand("Input.dispatchKeyEvent", p);
        } finally {
            inputLock.unlock();
        }
    }

    private int getVirtualKeyCode(String key) {
        if (key == null) return 0;
        return switch (key) {
            case "Backspace" -> 8;
            case "Tab" -> 9;
            case "Enter" -> 13;
            case "Shift" -> 16;
            case "Control" -> 17;
            case "Alt" -> 18;
            case "CapsLock" -> 20;
            case "Escape" -> 27;
            case " " -> 32;
            case "PageUp" -> 33;
            case "PageDown" -> 34;
            case "End" -> 35;
            case "Home" -> 36;
            case "ArrowLeft" -> 37;
            case "ArrowUp" -> 38;
            case "ArrowRight" -> 39;
            case "ArrowDown" -> 40;
            case "Delete" -> 46;
            default -> {
                if (key.length() == 1) {
                    char c = key.charAt(0);
                    if (c >= 'a' && c <= 'z') yield c - 32;
                    if (c >= 'A' && c <= 'Z') yield c;
                    if (c >= '0' && c <= '9') yield c;
                    yield (int) c;
                }
                yield 0;
            }
        };
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static boolean sameHost(String urlA, String urlB) {
        try {
            String hostA = URI.create(urlA).getHost();
            String hostB = URI.create(urlB).getHost();
            if (hostA == null || hostB == null) return false;
            URI a = URI.create(urlA), b = URI.create(urlB);
            int portA = a.getPort() >= 0 ? a.getPort() : ("https".equalsIgnoreCase(a.getScheme()) ? 443 : 80);
            int portB = b.getPort() >= 0 ? b.getPort() : ("https".equalsIgnoreCase(b.getScheme()) ? 443 : 80);
            return hostA.equalsIgnoreCase(hostB) && a.getScheme().equalsIgnoreCase(b.getScheme()) && portA == portB;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripWww(String host) {
        return host != null && host.toLowerCase().startsWith("www.") ? host.substring(4) : host;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String loadClasspathText(String name) {
        try (InputStream in = BrowserSession.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                System.err.println("[stealth] 未找到 classpath 资源: " + name);
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[stealth] 读取失败: " + e.getMessage());
            return "";
        }
    }

    public LatestFrame.Frame frameAfter(long sequence, long millis) throws InterruptedException {
        return frames.after(sequence, millis);
    }

    public byte[] getLastFrame() { return lastFrame; }

    public void touch() {
        lastActiveAt = System.currentTimeMillis();
    }

    public void markDisconnected() {
        disconnectedAt = System.currentTimeMillis();
        if (state == SessionState.ACTIVE) state = SessionState.DISCONNECTED;
    }

    public void markActive() {
        state = SessionState.ACTIVE;
        disconnectedAt = 0;
        lastActiveAt = System.currentTimeMillis();
    }

    public void close() {
        state = SessionState.TERMINATED;
        ackSender.shutdownNow();
        pending.values().forEach(f -> f.completeExceptionally(new RuntimeException("session closed")));
        pending.clear();
        if (cdpWs != null && !cdpWs.isOutputClosed()) {
            try { cdpWs.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) {}
        }
    }

    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public String getContainerId() { return containerId; }
    public String getCdpUrl() { return cdpUrl; }
    public SessionState getState() { return state; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActiveAt() { return lastActiveAt; }
    public long getDisconnectedAt() { return disconnectedAt; }
}
