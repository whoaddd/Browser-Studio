package com.rb.control.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.PullImageResultCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器容器管理。
 *
 * 关键约定：
 *   - 浏览器容器加入 rb-net，数据面直接通过“容器 IP + relay 端口”访问 CDP，
 *     不再依赖宿主机端口映射（Windows Docker Desktop 上端口映射 + host.docker.internal 不可靠）。
 *   - 镜像必须是“自带启动脚本的浏览器运行时”（默认 rb-chromium:131）。
 *     如果换成 chromedp/headless-shell 这类镜像，需要把 cdp-port 与 internal-cdp-port 都设为 9222。
 */
@Component
public class ContainerManager {

    private final DockerClient docker;
    private final String networkName;
    private final String image;
    private final long shmSize;
    private final boolean usePortMapping;
    private final int cdpPort;          // 平台访问的端口（浏览器容器 relay 端口）
    private final int internalCdpPort;  // 容器内 Chromium DevTools 端口
    private final String proxy;
    private final boolean headless;
    private final boolean autoPull;
    private final long memoryBytes;

    public ContainerManager(DockerClient docker,
                            @Value("${rb.docker.network}") String networkName,
                            @Value("${rb.chromium.image}") String image,
                            @Value("${rb.chromium.shm-size}") long shmSize,
                            @Value("${rb.docker.use-port-mapping:false}") boolean usePortMapping,
                            @Value("${rb.chromium.cdp-port:9223}") int cdpPort,
                            @Value("${rb.chromium.internal-cdp-port:9222}") int internalCdpPort,
                            @Value("${rb.chromium.proxy:}") String proxy,
                            @Value("${rb.chromium.headless:false}") boolean headless,
                            @Value("${rb.chromium.auto-pull:false}") boolean autoPull,
                            @Value("${rb.chromium.memory-bytes:0}") long memoryBytes) {
        this.docker = docker;
        this.networkName = networkName;
        this.image = image;
        this.shmSize = shmSize;
        this.usePortMapping = usePortMapping;
        this.cdpPort = cdpPort;
        this.internalCdpPort = internalCdpPort;
        this.proxy = proxy;
        this.headless = headless;
        this.autoPull = autoPull;
        this.memoryBytes = memoryBytes;
    }

    public String getImage() {
        return image;
    }

    public void ensureNetwork() {
        List<Network> networks = docker.listNetworksCmd().withNameFilter(networkName).exec();
        boolean exists = networks.stream().anyMatch(n -> networkName.equals(n.getName()));
        if (!exists) {
            docker.createNetworkCmd()
                    .withName(networkName)
                    .withDriver("bridge")
                    .withAttachable(true)
                    .exec();
            System.out.println("已创建 Docker 网络: " + networkName);
        }
    }

    /** 启动前检查镜像；缺失时给出可直接执行的构建/拉取指引，而不是抛一个看不懂的 500 */
    public void ensureImage() {
        try {
            docker.inspectImageCmd(image).exec();
            return;
        } catch (NotFoundException ignored) {
            // fall through
        } catch (DockerException e) {
            throw new IllegalStateException("无法访问 Docker 守护进程: " + e.getMessage(), e);
        }

        if (autoPull) {
            System.out.println("本地缺少浏览器镜像，尝试拉取: " + image);
            try {
                docker.pullImageCmd(image).start()
                        .awaitCompletion(180, TimeUnit.SECONDS);
                docker.inspectImageCmd(image).exec();
                return;
            } catch (Exception e) {
                throw new ImageUnavailableException(
                        "浏览器镜像拉取失败: " + image + "（" + e.getMessage() + "）", e);
            }
        }

        throw new ImageUnavailableException(
                "浏览器镜像不存在: " + image + "。本地构建: docker build -t " + image
                        + " -f docker/chromium/Dockerfile docker/chromium"
                        + "；或设置 RB_CHROMIUM_AUTO_PULL=true 允许自动拉取。");
    }

    public ContainerInfo create(String sessionId) {
        return create(sessionId, null);
    }

    /**
     * @param proxyOverride 单个会话可覆盖全局代理（住宅/移动代理是淘宝、京东登录成败的关键），
     *                      传 null 使用 rb.chromium.proxy
     */
    public ContainerInfo create(String sessionId, String proxyOverride) {
        ensureImage();
        ensureNetwork();

        String effectiveProxy = proxyOverride != null && !proxyOverride.isBlank()
                ? proxyOverride.trim() : (proxy == null ? "" : proxy.trim());

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode(networkName)
                .withShmSize(shmSize)
                .withAutoRemove(true);

        if (usePortMapping) {
            hostConfig.withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                            ExposedPort.tcp(cdpPort))
            );
        }
        if (memoryBytes > 0) {
            hostConfig.withMemory(memoryBytes).withMemorySwap(memoryBytes);
        }

        Map<String, String> env = new LinkedHashMap<>();
        env.put("RB_CDP_PORT", String.valueOf(internalCdpPort));
        env.put("RB_CDP_RELAY_PORT", String.valueOf(cdpPort));
        env.put("RB_HEADLESS", headless ? "1" : "0");

        Map<String, String> labels = Map.of(
                "rb.role", "chromium",
                "rb.session", sessionId
        );

        List<String> envList = new ArrayList<>();
        env.forEach((k, v) -> envList.add(k + "=" + v));

        CreateContainerCmd create = docker.createContainerCmd(image)
                .withName("rb-session-" + sessionId)
                .withHostConfig(hostConfig)
                .withLabels(labels)
                .withEnv(envList)
                .withExposedPorts(ExposedPort.tcp(cdpPort))
                .withCmd(buildChromeArgs(effectiveProxy));

        CreateContainerResponse response = create.exec();
        String containerId = response.getId();

        try {
            docker.startContainerCmd(containerId).exec();
        } catch (DockerException e) {
            throw new IllegalStateException(
                    "浏览器容器启动失败: " + e.getMessage()
                            + "（镜像 " + image + " 可能缺少入口脚本或参数不受支持，"
                            + "请确认使用 docker/chromium 构建的镜像）", e);
        }

        String cdpUrl = waitForEndpoint(containerId, 20);
        System.out.println("容器已启动: " + containerId + " cdpUrl=" + cdpUrl + " proxy="
                + (effectiveProxy.isEmpty() ? "无" : effectiveProxy));

        if (!usePortMapping) {
            verifyCdpReachable(cdpUrl, 15);
        }
        return new ContainerInfo(containerId, cdpUrl);
    }

    private List<String> buildChromeArgs(String proxy) {
        List<String> args = new ArrayList<>();
        args.add("--remote-debugging-port=" + internalCdpPort);
        args.add("--remote-debugging-address=127.0.0.1");
        args.add("--no-sandbox");
        args.add("--disable-dev-shm-usage");
        args.add("--disable-gpu-sandbox");
        // 【反风控】一定不能删除：抹掉 navigator.webdriver / 自动化标志
        args.add("--disable-blink-features=AutomationControlled");
        args.add("--user-data-dir=/tmp/rb-chrome-profile");
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--disable-default-apps");
        args.add("--lang=zh-CN");
        // 容器内没有 GPU，用 SwiftShader 保证 WebGL 可用（否则淘宝/京东会直接判定环境异常）
        args.add("--use-gl=angle");
        args.add("--use-angle=swiftshader");
        args.add("--enable-unsafe-swiftshader");
        args.add("--ignore-gpu-blocklist");
        args.add("--window-size=1280,720");
        args.add("--window-position=0,0");
        args.add("--disable-backgrounding-occluded-windows");
        args.add("--hide-crash-restore-bubble");
        if (proxy != null && !proxy.isBlank()) {
            args.add("--proxy-server=" + proxy);
            // 避免 WebRTC 泄露真实（机房）IP
            args.add("--force-webrtc-ip-handling-policy=disable_non_proxied_udp");
        }
        args.add("about:blank");
        return args;
    }

    private String waitForEndpoint(String containerId, int maxSeconds) {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            InspectContainerResponse inspect = docker.inspectContainerCmd(containerId).exec();
            if (inspect.getState() != null && Boolean.FALSE.equals(inspect.getState().getRunning())) {
                throw new IllegalStateException(
                        "浏览器容器启动后立即退出: " + containerId
                                + "（执行 docker logs rb-session-? 查看原因）");
            }

            if (usePortMapping) {
                NetworkSettings settings = inspect.getNetworkSettings();
                if (settings != null && settings.getPorts() != null
                        && settings.getPorts().getBindings() != null) {
                    Ports.Binding[] bindings = settings.getPorts().getBindings().get(ExposedPort.tcp(cdpPort));
                    if (bindings != null && bindings.length > 0 && bindings[0].getHostPortSpec() != null) {
                        return "http://host.docker.internal:" + bindings[0].getHostPortSpec();
                    }
                }
            } else {
                NetworkSettings settings = inspect.getNetworkSettings();
                if (settings != null && settings.getNetworks() != null) {
                    ContainerNetwork network = settings.getNetworks().get(networkName);
                    if (network != null && network.getIpAddress() != null
                            && !network.getIpAddress().isEmpty()) {
                        return "http://" + network.getIpAddress() + ":" + cdpPort;
                    }
                }
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        throw new IllegalStateException("容器启动后 " + maxSeconds + " 秒内未拿到 CDP 地址: " + containerId);
    }

    /**
     * 控制面自己先探活一次：镜像/入口/端口/网络任何一个配错，
     * 都能在“新建会话”阶段给出明确错误，而不是等前端连 WebSocket 时才失败。
     */
    private void verifyCdpReachable(String cdpUrl, int maxSeconds) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        String lastError = "";
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(cdpUrl + "/json/version"))
                        .timeout(Duration.ofSeconds(2))
                        .GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body().contains("webSocketDebuggerUrl")) {
                    return;
                }
                lastError = "HTTP " + resp.statusCode();
            } catch (Exception e) {
                lastError = e.getMessage();
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        throw new IllegalStateException(
                "浏览器容器已启动但 CDP 不可达: " + cdpUrl + "（" + lastError + "）"
                        + "。检查浏览器镜像是否监听 " + internalCdpPort
                        + " 并暴露 relay 端口 " + cdpPort + "。");
    }

    public void destroy(String containerId) {
        if (containerId == null) return;
        try {
            docker.stopContainerCmd(containerId).withTimeout(5).exec();
        } catch (Exception ignored) {}
        try {
            docker.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception ignored) {}
        System.out.println("容器已销毁: " + containerId);
    }

    public boolean isAlive(String containerId) {
        try {
            InspectContainerResponse inspect = docker.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(inspect.getState().getRunning());
        } catch (Exception e) {
            return false;
        }
    }

    /** 镜像缺失：属于“环境问题”，对外应返回 503 而不是 500 */
    public static class ImageUnavailableException extends RuntimeException {
        public ImageUnavailableException(String message) {
            super(message);
        }

        public ImageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
