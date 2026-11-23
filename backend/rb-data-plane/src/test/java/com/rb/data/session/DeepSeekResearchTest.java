package com.rb.data.session;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekResearchTest {
    @Test
    void mapsNormalizedVisualBoxToScreenshotPixelsAndRejectsOverflow() {
        JsonObject valid = JsonParser.parseString("""
                {"x":125,"y":250,"width":500,"height":125}
                """).getAsJsonObject();
        var region = DeepSeekBrowserAssistant.toPixelRegion(valid, 1280, 720);
        assertEquals(new DeepSeekBrowserAssistant.VisualRegion(160, 180, 640, 90, 1280, 720), region);
        valid.addProperty("x", 800);
        assertNull(DeepSeekBrowserAssistant.toPixelRegion(valid, 1280, 720));
    }

    @Test
    void keepsOnlyBudgetCompliantProductsWithPageEvidence() {
        JsonObject model = JsonParser.parseString("""
                {"maxPriceCny":500,"products":[
                  {"title":"通勤蓝牙耳机","priceCny":199,"reason":"通勤款"},
                  {"title":"专业降噪耳机","priceCny":399,"reason":"降噪款"},
                  {"title":"虚构的低价耳机","priceCny":99,"reason":"未经验证"},
                  {"title":"无线运动耳机","priceCny":249,"reason":"页面价格不一致"}
                ]}
                """).getAsJsonObject();
        BigDecimal budget = DeepSeekBrowserAssistant.priceLimit("找300元以内的耳机", model);
        assertEquals(new BigDecimal("300"), budget);
        var plan = new DeepSeekBrowserAssistant.Plan("挑耳机", "商城", "https://example.com/",
                "站内搜索", "耳机", budget);

        var products = DeepSeekBrowserAssistant.products(model, plan, "https://example.com/results",
                "通勤蓝牙耳机 ￥199.00\n专业降噪耳机 ￥399\n无线运动耳机 ￥279");
        assertEquals(1, products.size());
        assertEquals("通勤蓝牙耳机", products.getFirst().title());
        assertEquals("https://example.com/results", products.getFirst().sourceUrl());
    }

    @Test
    void usesPlatformSearchAndKeepsExplicitProductPage() {
        var home = new DeepSeekBrowserAssistant.Plan("挑耳机", "京东", "https://www.jd.com/",
                "站内查找", "蓝牙耳机", null);
        var product = new DeepSeekBrowserAssistant.Plan("查看耳机", "京东", "https://item.jd.com/123.html",
                "用户指定", "蓝牙耳机", null);
        assertTrue(home.entryUrl().startsWith("https://search.jd.com/Search?keyword="));
        assertTrue(home.acceptsSite(home.entryUrl()));
        assertFalse(home.acceptsSite("https://jd.com.example.com/"));
        assertEquals(product.url(), product.entryUrl());
    }

    @Test
    void sendsSelectedProductToJdSearchAndKeepsFieldLocalizationReadOnly() {
        JsonObject model = JsonParser.parseString("""
                {"goal":"核对耳机","site":"京东","url":"https://www.jd.com/","reason":"用户指定京东",
                 "query":"索尼 WH-1000XM5 黑色","workflow":"find_same_product",
                 "referenceIdentity":"索尼 WH-1000XM5 黑色","referenceIdentifiers":["WH-1000XM5","黑色"]}
                """).getAsJsonObject();
        var match = DeepSeekBrowserAssistant.readPlan("在京东找这个商品", true, model);
        var target = java.net.URI.create(match.openSource(true).text());
        assertEquals("search.jd.com", target.getHost());
        assertEquals("keyword=索尼 WH-1000XM5 黑色",
                java.net.URLDecoder.decode(target.getRawQuery(), java.nio.charset.StandardCharsets.UTF_8));

        model.addProperty("workflow", "locate_field");
        assertNotNull(DeepSeekBrowserAssistant.readPlan("在京东找同款", true, model).productReference());
        var field = DeepSeekBrowserAssistant.readPlan("定位语义相同的商品主标题", true, model);
        assertNull(field.productReference());
        assertEquals(field.url(), field.openSource(true).text());
        model.remove("referenceIdentifiers");
        assertThrows(IllegalStateException.class,
                () -> DeepSeekBrowserAssistant.readPlan("在京东找同款", true, model));
    }

    @Test
    void verifiesJdProductIdentityOnlyOnDetailWithExactEvidence() {
        var reference = new DeepSeekBrowserAssistant.ProductReference("索尼 WH-1000XM5 黑色",
                java.util.List.of("WH-1000XM5", "黑色"));
        var plan = new DeepSeekBrowserAssistant.Plan("找同款", "京东", "https://www.jd.com/",
                "京东站内搜索", "索尼 WH-1000XM5 黑色", null, reference);
        JsonObject model = new JsonObject();
        model.addProperty("sameProduct", true);
        String evidence = "索尼 WH-1000XM5 无线耳机\n颜色 黑色";
        String detail = "https://item.jd.com/12345.html";
        assertFalse(DeepSeekBrowserAssistant.isVerifiedProductMatch(model, plan, plan.url(), evidence));
        assertFalse(DeepSeekBrowserAssistant.isVerifiedProductMatch(model, plan, plan.entryUrl(), evidence));
        assertFalse(DeepSeekBrowserAssistant.isVerifiedProductMatch(model, plan, detail, evidence.replace("XM5", "XM5A")));
        assertFalse(DeepSeekBrowserAssistant.isVerifiedProductMatch(model, plan, detail, evidence.replace("黑色", "白色")));
        assertTrue(DeepSeekBrowserAssistant.isVerifiedProductMatch(model, plan, detail, evidence));
        model.addProperty("sameProduct", false);
        assertFalse(DeepSeekBrowserAssistant.isVerifiedProductMatch(model, plan, detail, evidence));
    }

    @Test
    void sendsBilibiliChineseKeywordsToOfficialSearch() {
        var plan = new DeepSeekBrowserAssistant.Plan("查找视频", "哔哩哔哩", "https://www.bilibili.com/",
                "站内搜索", "雾山五行", null);
        assertEquals("https://search.bilibili.com/all?keyword=%E9%9B%BE%E5%B1%B1%E4%BA%94%E8%A1%8C", plan.entryUrl());
        assertTrue(plan.acceptsSite(plan.entryUrl()));
    }

    @Test
    void keepsObservedBilibiliVideoResultsEvenWithoutModelQuotes() {
        var plan = new DeepSeekBrowserAssistant.Plan("查找视频", "哔哩哔哩", "https://search.bilibili.com/",
                "站内搜索", "雾山五行", null);
        var links = java.util.List.of(
                new DeepSeekBrowserAssistant.BrowserLink("雾山五行官方预告", "https://www.bilibili.com/video/BV-test"),
                new DeepSeekBrowserAssistant.BrowserLink("雾山五行", "https://www.bilibili.com/"),
                new DeepSeekBrowserAssistant.BrowserLink("其他作品预告", "https://www.bilibili.com/video/BV-other"));
        var findings = DeepSeekBrowserAssistant.bilibiliSearchFindings(plan, plan.entryUrl(), links);
        assertEquals(1, findings.size());
        assertEquals(links.getFirst().url(), findings.getFirst().sourceUrl());
        assertTrue(plan.acceptsSite(links.getFirst().url()));
        assertTrue(DeepSeekBrowserAssistant.bilibiliSearchFindings(plan,
                "https://search.bilibili.com/all?keyword=other", links).isEmpty());
    }

    @Test
    void citesOnlyEvidenceFoundOnTheObservedPage() {
        JsonObject model = JsonParser.parseString("""
                {"findings":[
                  {"title":"续航","quote":"声澜 A 型标称续航 24 小时","sourceUrl":"https://unrelated.example/"},
                  {"title":"虚构规格","quote":"标称续航 80 小时"}
                ]}
                """).getAsJsonObject();
        var findings = DeepSeekBrowserAssistant.findings(model, "https://example.com/model-a",
                "声澜 A 型标称续航\n24 小时");
        assertEquals(1, findings.size());
        assertEquals("https://example.com/model-a", findings.getFirst().sourceUrl());
    }
}
