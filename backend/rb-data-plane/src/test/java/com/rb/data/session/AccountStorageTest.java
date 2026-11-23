package com.rb.data.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AccountStorageTest {
    @TempDir Path dir;

    @Test void twoAccountsRemainIsolatedAfterReloadAndAnonymousExport() throws Exception {
        String firstId = "11111111-1111-1111-1111-111111111111";
        String secondId = "22222222-2222-2222-2222-222222222222";
        StorageStateManager root = new StorageStateManager(dir.toString(), "owner");
        StorageStateManager first = root.forAccount("owner", firstId);
        StorageStateManager second = root.forAccount("owner", secondId);
        assertEquals("taobao", first.persistIfLoggedIn("taobao", taobaoState("first")));
        assertNull(second.loadSite("taobao"));
        assertEquals("taobao", second.persistIfLoggedIn("taobao", taobaoState("second")));
        assertNull(first.persistIfLoggedIn("taobao", "{\"cookies\":[]}"));
        StorageStateManager reloaded = new StorageStateManager(dir.toString(), "owner");
        assertTrue(reloaded.forAccount("owner", firstId).loadSite("taobao").contains("first"));
        assertTrue(reloaded.forAccount("owner", secondId).loadSite("taobao").contains("second"));
        assertNull(reloaded.forAccount("other-owner", firstId).loadSite("taobao"));
        assertNull(root.loadSite("taobao"));
        assertThrows(IllegalArgumentException.class, () -> root.forAccount("owner", "../escape"));
    }

    @Test void workspaceAccountsShareOnlyBaiduAndJdSiteState() throws Exception {
        StorageStateManager root = new StorageStateManager(dir.toString(), "owner");
        String firstId = "11111111-1111-1111-1111-111111111111";
        StorageStateManager first = root.forAccount("owner", firstId);
        StorageStateManager second = root.forAccount("owner", "22222222-2222-2222-2222-222222222222");
        Files.writeString(dir.resolve("jd.json"), "{\"cookies\":[" + cookie("thor", "historical", "jd.com")
                + "," + cookie("unb", "private-account", "taobao.com")
                + "],\"url\":\"https://www.taobao.com/\",\"localStorage\":{\"private\":\"value\"}}");
        root.saveDefault(state("BDUSS", "legacy-baidu", "baidu.com"));

        assertTrue(first.loadSite("jd").contains("historical"));
        assertFalse(second.loadSite("jd").contains("private"));
        assertTrue(first.loadLegacyDefault().contains("legacy-baidu"));
        assertNull(root.forAccount("other-owner", firstId).loadSite("jd"));
        assertNull(root.forAccount("other-owner", "default").loadLegacyDefault());
        assertEquals("jd", second.persistIfLoggedIn("jd", state("thor", "renewed", "jd.com"), true));
        assertNull(first.persistIfLoggedIn("jd", state("thor", "older-session", "jd.com")));
        assertTrue(root.loadSite("jd").contains("renewed"));
        assertTrue(first.loadSite("jd").contains("renewed"));
        assertEquals("baidu", first.persistIfLoggedIn("baidu", state("BDUSS", "current-baidu", "baidu.com"), true));
        assertTrue(second.loadSite("baidu").contains("current-baidu"));
    }

    private static String taobaoState(String value) {
        return "{\"cookies\":[" + cookie("cookie2", value, "taobao.com") + "," + cookie("unb", value, "taobao.com") + "]}";
    }

    private static String state(String name, String value, String site) {
        return "{\"cookies\":[" + cookie(name, value, site) + "]}";
    }

    private static String cookie(String name, String value, String site) {
        return "{\"name\":\"" + name + "\",\"value\":\"" + value + "\",\"domain\":\"." + site + "\",\"expires\":-1}";
    }
}
