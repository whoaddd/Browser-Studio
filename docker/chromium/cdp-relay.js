'use strict';

// 极简 TCP 转发：把 0.0.0.0:9223 上的连接转发到容器内 127.0.0.1:9222。
// 目的：Playwright 版 Chromium 的 DevTools 只监听回环地址，而平台需要从
// 其它容器（rb-net 网络）远程访问，因此用 relay 暴露一个可跨容器访问的端口。
const net = require('net');

const LISTEN_HOST = process.env.RB_CDP_RELAY_ADDR || '0.0.0.0';
const LISTEN_PORT = Number(process.env.RB_CDP_RELAY_PORT || 9223);
const TARGET_HOST = process.env.RB_CDP_TARGET_ADDR || '127.0.0.1';
const TARGET_PORT = Number(process.env.RB_CDP_PORT || 9222);

const server = net.createServer((client) => {
  const upstream = net.connect(TARGET_PORT, TARGET_HOST);
  const shutdown = () => {
    client.destroy();
    upstream.destroy();
  };
  client.on('error', shutdown);
  upstream.on('error', shutdown);
  client.on('close', shutdown);
  upstream.on('close', shutdown);
  upstream.pipe(client);
  client.pipe(upstream);
});

server.on('error', (err) => {
  console.error('[cdp-relay] 监听失败: ' + err.message);
  process.exit(1);
});

server.listen(LISTEN_PORT, LISTEN_HOST, () => {
  console.log('[cdp-relay] 就绪 ' + LISTEN_HOST + ':' + LISTEN_PORT +
              ' -> ' + TARGET_HOST + ':' + TARGET_PORT);
});

const stop = () => server.close(() => process.exit(0));
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
