package com.rb.data.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SiteProfilesTest {
    private JsonObject cookie(String name, String domain, double expiry) {
        JsonObject c = new JsonObject();
        c.addProperty("name", name); c.addProperty("value", "test");
        c.addProperty("domain", domain); c.addProperty("expires", expiry);
        return c;
    }
    @Test void parsesActualHostRatherThanQueryOrSuffix() {
        assertNull(SiteProfiles.siteFromUrl("https://evil.example/?next=taobao.com"));
        assertNull(SiteProfiles.siteFromUrl("https://jd.com.evil.example"));
        assertEquals("taobao", SiteProfiles.siteFromUrl("https://login.taobao.com/"));
    }
    @Test void rejectsWrongDomainAndExpiredCookies() {
        JsonArray cookies = new JsonArray();
        cookies.add(cookie("thor", ".evil.example", -1));
        cookies.add(cookie("pt_key", ".jd.com", 100));
        assertFalse(SiteProfiles.isLoggedIn("jd", cookies));
        cookies.add(cookie("thor", ".jd.com", -1));
        assertTrue(SiteProfiles.isLoggedIn("jd", cookies));
    }
    @Test void anonymousAndNicknameOnlyAreNotTaobaoSessions() {
        JsonArray cookies = new JsonArray();
        cookies.add(cookie("cookie2", ".taobao.com", -1));
        cookies.add(cookie("_tb_token_", ".taobao.com", -1));
        assertFalse(SiteProfiles.isLoggedIn("taobao", cookies));
        cookies.add(cookie("tracknick", ".taobao.com", -1));
        assertFalse(SiteProfiles.isLoggedIn("taobao", cookies));
        cookies.add(cookie("unb", ".taobao.com", -1));
        assertTrue(SiteProfiles.isLoggedIn("taobao", cookies));
        cookies.remove(0);
        assertFalse(SiteProfiles.isLoggedIn("taobao", cookies));
    }
}
