package com.rb.data.session;

import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class BrowserResearchRun {
    private static final int MAX_STEPS = 12;
    private static final Pattern LOGIN = Pattern.compile("(?i)passport\\.|login\\.|/login|/captcha|/verify|/risk_handler");
    private final DeepSeekBrowserAssistant assistant;
    private final List<String> steps = new ArrayList<>();
    private final LinkedHashMap<String, DeepSeekBrowserAssistant.Product> products = new LinkedHashMap<>();
    private final LinkedHashMap<String, DeepSeekBrowserAssistant.Finding> findings = new LinkedHashMap<>();
    private final List<DeepSeekBrowserAssistant.VisualMatch> visualMatches = new ArrayList<>();
    private boolean sourceOpened;
    private boolean searchChecked;

    public record Update(String status, String message, List<String> steps,
                         List<DeepSeekBrowserAssistant.Product> products,
                         List<DeepSeekBrowserAssistant.Finding> findings,
                         List<DeepSeekBrowserAssistant.VisualMatch> visualMatches) {}

    public BrowserResearchRun(DeepSeekBrowserAssistant assistant) {
        this.assistant = assistant;
    }

    public Update runVisual(BrowserSession browser, BrowserSession.ResearchCheckpoint task,
                            BooleanSupplier active, Consumer<Update> report) {
        try {
            if (!active.getAsBoolean()) return update("stopped", "已停止视觉定位");
            if (!sourceOpened) {
                browser.applyAssistantAction(task.firstStep());
                sourceOpened = true;
                steps.add("打开 " + task.plan().site());
            } else {
                steps.add("继续视觉定位");
            }
            String previous = null;
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(150).toNanos();
            for (int screen = 1; screen <= 6 && System.nanoTime() < deadline; screen++) {
                Thread.sleep(1200);
                if (!active.getAsBoolean()) return update("stopped", "已停止视觉定位");
                String url = browser.assistantVisualUrl();
                if (LOGIN.matcher(url).find() || !task.plan().acceptsSite(url))
                    return update("needs_user", "页面需要登录、验证或确认来源，请手动处理后继续");
                String screenshot = browser.assistantScreenshot();
                if (screenshot.equals(previous)) break;
                report.accept(update("running", "正在用圈选参考比对第 " + screen + " 屏"));
                var result = assistant.locateVisual(task.reference(), task.requirement(), url, screenshot);
                if (!active.getAsBoolean()) return update("stopped", "已停止视觉定位");
                if (!url.equals(browser.assistantVisualUrl())) continue;
                if ("blocked".equals(result.status()))
                    return update("needs_user", "页面要求登录或验证，请手动处理后继续");
                if (result.match() != null) {
                    var match = result.match();
                    visualMatches.add(match);
                    findings.put(match.text(), new DeepSeekBrowserAssistant.Finding(match.label(), match.text(), url));
                    steps.add("视觉定位、裁图 OCR 并核对字段语义");
                    return update("completed", "已找到与圈选示例语义相同的区域");
                }
                previous = screenshot;
                if (screen < 6 && System.nanoTime() < deadline) browser.scrollVisualPage();
            }
            return update("partial", "已检查可见页面，未确认符合圈选含义的区域");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return update("stopped", "已停止视觉定位");
        } catch (Exception e) {
            return update("partial", "视觉定位或 OCR 未能完成，请重试");
        }
    }

    public Update run(BrowserSession browser, BrowserSession.ResearchCheckpoint task,
                      BooleanSupplier active, Consumer<Update> report) {
        var plan = task.plan();
        var firstStep = task.firstStep();
        String entry = plan.entryUrl();
        boolean entersSearch = firstStep.text().equals(entry) && !plan.url().equals(entry);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(150).toNanos();
        var attempted = new HashSet<String>();
        var dismissed = new HashSet<String>();
        int repeatedActions = 0;
        try {
            if (!active.getAsBoolean()) return update("stopped", "已停止任务");
            if (!sourceOpened) {
                String opening = entersSearch ? "站内搜索「" + plan.query() + "」" : "打开 " + plan.site();
                report.accept(update("running", "正在" + opening));
                browser.applyAssistantAction(firstStep);
                sourceOpened = true;
                steps.add(opening);
            } else {
                steps.add("从当前页面继续执行");
                report.accept(update("running", "正在从当前页面继续"));
            }

            for (int step = 1; step <= MAX_STEPS && System.nanoTime() < deadline; step++) {
                Thread.sleep(1200);
                if (!active.getAsBoolean()) return update("stopped", "已停止任务");
                String page = browser.assistantPageState();
                String url = JsonParser.parseString(page).getAsJsonArray().get(0).getAsString();
                if (LOGIN.matcher(url).find()) {
                    searchChecked = false;
                    return update("needs_user", "目标网站要求登录或验证，请在浏览器中处理后继续执行");
                }
                if (!plan.acceptsSite(url))
                    return update("needs_user", "页面离开了本次确认的网站，请确认来源后重新开始");

                if (dismissed.size() < 4) {
                    String overlay = browser.dismissResearchOverlay(page, dismissed);
                    if ("protected".equals(overlay)) {
                        searchChecked = false;
                        return update("needs_user", "页面弹窗要求登录、验证或确认，请手动处理后继续执行");
                    }
                    if (!overlay.isEmpty()) {
                        dismissed.add(overlay);
                        steps.add("尝试关闭遮挡页面的弹窗");
                        report.accept(update("running", "正在清理页面遮挡"));
                        continue;
                    }
                }

                if (!searchChecked) {
                    // After login the platform can send the browser to its home page.
                    String path = java.net.URI.create(url).getPath();
                    boolean returnedHome = "".equals(path) || "/".equals(path) || "/index.html".equals(path);
                    String searchUrl = entersSearch && returnedHome ? entry : browser.siteSearchUrl(plan);
                    if (searchUrl != null && !searchUrl.equals(url)) {
                        browser.applyResearchAction(new DeepSeekBrowserAssistant.Proposal("", "", "navigate", "",
                                0, 0, 0, searchUrl, page, 0), plan);
                        steps.add("站内搜索「" + plan.query() + "」");
                        searchChecked = true;
                        continue;
                    }
                    searchChecked = true;
                }

                report.accept(update("running", "正在执行任务 · 第 " + step + " 步"));
                var result = assistant.analyze(browser, task.requirement(), task.source(), plan, memory(), task.reference());
                if (!active.getAsBoolean()) return update("stopped", "已停止任务");
                String currentPage = browser.assistantPageState();
                String currentUrl = JsonParser.parseString(currentPage).getAsJsonArray().get(0).getAsString();
                if (LOGIN.matcher(currentUrl).find()) {
                    searchChecked = false;
                    return update("needs_user", "目标网站要求登录或验证，请在浏览器中处理后继续执行");
                }
                var action = result.proposal();
                if (!action.page().equals(currentPage)) {
                    steps.add("页面状态变化，重新观察");
                    continue;
                }
                result.products().forEach(product -> products.putIfAbsent(product.title(), product));
                result.findings().forEach(finding -> findings.putIfAbsent(finding.quote(), finding));

                if ("needs_user".equals(result.status())) return update("needs_user", action.answer());
                if ("completed".equals(result.status()) && action.id().isEmpty()) {
                    if (plan.maxPriceCny() != null && products.isEmpty())
                        return update("partial", "未找到售价可核对且在预算内的商品");
                    if (findings.isEmpty() && products.isEmpty())
                        return update("partial", plan.productReference() == null ? "尚未核对到可引用的页面证据。"
                                : "已搜索候选商品，尚未从详情页核实同款。");
                    return update("completed", plan.productReference() == null ? "已核对相关搜索结果。" : "已在商品详情页核对同款。");
                }
                if (action.id().isEmpty())
                    return update("partial", "任务未能继续。已保留核对到的结果。");

                String key = action.type() + "|" + action.page() + "|" + action.text()
                        + "|" + action.x() + "|" + action.y() + "|" + action.direction();
                if (!attempted.add(key)) {
                    if (++repeatedActions >= 3) return update("partial", findings.isEmpty() && products.isEmpty()
                            ? "尝试了其他办法，仍未读取到相关搜索结果。"
                            : "页面没有进一步变化，已整理本次能核对到的结果。");
                    steps.add("跳过重复操作「" + action.label() + "」。检查当前可见结果，或换一种操作。");
                    continue;
                }
                repeatedActions = 0;
                try {
                    browser.applyResearchAction(action, plan);
                    steps.add(action.label());
                } catch (IllegalStateException blocked) {
                    steps.add("未执行「" + action.label() + "」：" + blocked.getMessage() + "。请选择其他办法。");
                }
            }
        } catch (IllegalStateException e) {
            return update("needs_user", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return update("stopped", "任务已中止");
        } catch (Exception e) {
            return update("partial", "任务暂时无法继续。已保留核对到的结果。");
        }
        return update("partial", "已达到本次任务的执行范围。以下是已核对的结果。");
    }

    private String memory() {
        StringBuilder text = new StringBuilder(String.join("\n", steps));
        findings.values().stream().limit(18).forEach(finding -> text.append("\n页面证据：")
                .append(finding.quote()).append(" 来源：").append(finding.sourceUrl()));
        products.values().stream().limit(12).forEach(product -> text.append("\n已核对商品：")
                .append(product.title()).append("，").append(product.priceCny()).append(" 元。").append(product.reason()));
        return text.toString();
    }

    private Update update(String status, String message) {
        return new Update(status, message, List.copyOf(steps),
                products.values().stream().limit(12).toList(), findings.values().stream().limit(18).toList(),
                List.copyOf(visualMatches));
    }
}
