# 远程浏览器运行时镜像（Chrome + Xvfb + CDP relay）
#
# 基础镜像使用 Playwright Ubuntu 22.04 镜像：
#   - 内置完整 Chromium 131.0.6778.33（非 headless-shell，可在 Xvfb 下以“有头”模式运行）
#   - 已预装 Xvfb / ffmpeg
#
# 构建：
#   docker build -t rb-chromium:131 -f docker/chromium/Dockerfile docker/chromium
#
# 说明：
#   Playwright 自带的 Chromium 只监听 127.0.0.1（--remote-debugging-address 对其无效），
#   所以容器内额外启动 cdp-relay.js，把 0.0.0.0:9223 转发到 127.0.0.1:9222。
#   平台（control/data-plane）连接的是 9223。
ARG CHROMIUM_BASE=mcr.microsoft.com/playwright:v1.49.1-jammy
FROM ${CHROMIUM_BASE}

USER root

COPY start-chromium.sh /opt/rb/start-chromium.sh
COPY cdp-relay.js /opt/rb/cdp-relay.js
COPY browser-policy.json /etc/chromium/policies/managed/remote-browser.json
COPY browser-policy.json /etc/opt/chrome/policies/managed/remote-browser.json

RUN chmod 0755 /opt/rb/start-chromium.sh \
    && mkdir -p /tmp/rb-chrome-profile

ENV RB_CHROME_BIN=/ms-playwright/chromium-1148/chrome-linux/chrome \
    RB_CDP_PORT=9222 \
    RB_CDP_RELAY_PORT=9223 \
    RB_SCREEN=1920x1080x24 \
    RB_PROFILE_DIR=/tmp/rb-chrome-profile \
    TZ=Asia/Shanghai

# 只暴露 relay 端口；内部 9222 仅容器内可见
EXPOSE 9223

ENTRYPOINT ["/opt/rb/start-chromium.sh"]
