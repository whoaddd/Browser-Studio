package com.rb.data.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 站点画像：站点识别、登录态判定、凭证文件名映射。
 *
 * 登录态判定必须基于“登录后才会出现且值非空”的 Cookie。真实踩坑记录：
 * 淘宝匿名访问首页就会下发 cookie2 / _tb_token_ / t / tfstk，
 * 早期版本把 cookie2 + _tb_token_ 当作登录标记，结果把匿名态当成登录态，
 * 写进 taobao.json 并在后续会话里误判“已登录”。因此这里统一：
 *   - 只认登录后才出现的 Cookie；
 *   - 值必须非空。
 */
public final class SiteProfiles {

    private SiteProfiles() {}

    /** 站点 → 凭证文件名（相对于 RB_STORAGE_DIR） */
    private static final Map<String, String> STATE_FILES = new LinkedHashMap<>();

    /** 站点 → 登录态 Cookie 标记（任意一个“值非空”即视为已登录） */
    private static final Map<String, String[]> LOGIN_MARKERS = new LinkedHashMap<>();

    private static final Map<String, String> HOST_MARKERS = new LinkedHashMap<>();

    static {
        STATE_FILES.put("baidu", "baidu.json");
        STATE_FILES.put("douyin", "douyin.json");
        STATE_FILES.put("taobao", "taobao.json");
        STATE_FILES.put("jd", "jd.json");
        STATE_FILES.put("bilibili", "bilibili.json");

        // 淘宝退出登录后可能保留昵称，必须同时具有会话 Cookie 与用户 ID。
        LOGIN_MARKERS.put("baidu", new String[]{"BDUSS"});
        LOGIN_MARKERS.put("douyin", new String[]{"sessionid", "sid_guard"});
        LOGIN_MARKERS.put("taobao", new String[]{"tracknick", "lgc", "unb"});
        LOGIN_MARKERS.put("jd", new String[]{"thor", "pt_key"});
        LOGIN_MARKERS.put("bilibili", new String[]{"SESSDATA"});

        HOST_MARKERS.put("taobao.com", "taobao");
        HOST_MARKERS.put("tmall.com", "taobao");
        HOST_MARKERS.put("jd.com", "jd");
        HOST_MARKERS.put("baidu.com", "baidu");
        HOST_MARKERS.put("douyin.com", "douyin");
        HOST_MARKERS.put("bilibili.com", "bilibili");
    }

    public static boolean isKnownSite(String site) {
        return site != null && STATE_FILES.containsKey(site);
    }

    /** 站点对应的凭证文件名；未知站点返回 null */
    public static String stateFileName(String site) {
        return site == null ? null : STATE_FILES.get(site);
    }

    /** 从 URL 推断站点（返回 taobao / jd / baidu / douyin / bilibili，未知返回 null） */
    public static String siteFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String host;
        try { host = java.net.URI.create(url).getHost(); }
        catch (IllegalArgumentException e) { return null; }
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : HOST_MARKERS.entrySet()) {
            if (host.equals(e.getKey()) || host.endsWith("." + e.getKey())) return e.getValue();
        }
        return null;
    }

    /** 是否已登录 */
    public static boolean isLoggedIn(String site, JsonArray cookies) {
        if (site == null || cookies == null || cookies.size() == 0) return false;

        Map<String, String> values = new LinkedHashMap<>();
        for (var el : cookies) {
            if (!el.isJsonObject()) continue;
            JsonObject c = el.getAsJsonObject();
            if (!c.has("name") || !c.get("name").isJsonPrimitive()) continue;
            if (!c.has("domain") || !c.get("domain").isJsonPrimitive()) continue;
            String domain = c.get("domain").getAsString().replaceFirst("^\\.", "");
            if (!site.equals(siteFromUrl("https://" + domain))) continue;
            if (c.has("expires") && !c.get("expires").isJsonNull()) {
                try {
                    double expires = c.get("expires").getAsDouble();
                    if (expires > 0 && expires <= System.currentTimeMillis() / 1000.0) continue;
                } catch (RuntimeException e) { continue; }
            }
            String name = c.get("name").getAsString();
            if (name == null || name.isBlank()) continue;
            String value = c.has("value") && c.get("value").isJsonPrimitive()
                    ? c.get("value").getAsString() : "";
            // 只记录非空值：匿名态常见“有名字但为空”的 Cookie，不能当作登录标记
            if (value != null && !value.isBlank()) {
                values.putIfAbsent(name, value);
            }
        }

        String[] markers = LOGIN_MARKERS.get(site);
        if (markers == null) return false;
        if ("taobao".equals(site)) return values.containsKey("cookie2") && values.containsKey("unb");
        for (String marker : markers) {
            if (values.containsKey(marker)) return true;
        }
        return false;
    }

    /**
     * 根据 Cookie 集合推断“这份凭证属于哪个站点”。
     * 返回 null 表示没有命中任何已登录站点。
     */
    public static String siteFromCookies(JsonArray cookies) {
        if (cookies == null) return null;
        for (String site : STATE_FILES.keySet()) {
            if (isLoggedIn(site, cookies)) return site;
        }
        return null;
    }
}
