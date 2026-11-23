#!/usr/bin/env node
/**
 * CDP 调试探针（不经过平台 WebSocket，直接连浏览器容器的 relay 端口）。
 *
 * 用法：
 *   node tools/cdp-probe.mjs http://172.21.0.8:9223 "location.href"
 *   node tools/cdp-probe.mjs http://172.21.0.8:9223 "document.title" --screenshot out.png
 *   node tools/cdp-probe.mjs http://172.21.0.8:9223 --navigate https://www.taobao.com
 *
 * 依赖 Node 22+ 自带的全局 WebSocket / fetch。
 */
import fs from 'node:fs';

const args = process.argv.slice(2);
const base = args.find((a) => a.startsWith('http'));
if (!base) {
  console.error('用法: node cdp-probe.mjs <http://container-ip:9223> [表达式] [--screenshot out.png] [--navigate url]');
  process.exit(1);
}
const expression = args.find((a) => !a.startsWith('http') && !a.startsWith('--') && !isFlagValue(a));
const screenshotIdx = args.indexOf('--screenshot');
const screenshotPath = screenshotIdx >= 0 ? args[screenshotIdx + 1] : null;
const navigateIdx = args.indexOf('--navigate');
const navigateUrl = navigateIdx >= 0 ? args[navigateIdx + 1] : null;

function isFlagValue(value) {
  const flags = ['--screenshot', '--navigate'];
  return flags.some((f) => args[args.indexOf(f) + 1] === value);
}

const listResp = await fetch(`${base}/json/list`);
const targets = await listResp.json();
const page = targets.find((t) => t.type === 'page');
if (!page) {
  console.error('没有 page target:', JSON.stringify(targets, null, 2));
  process.exit(1);
}

const baseUrl = new URL(base);
const wsUrl = `ws://${baseUrl.host}${new URL(page.webSocketDebuggerUrl).pathname}`;
const ws = new WebSocket(wsUrl);
let nextId = 1;
const pending = new Map();

const send = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error(`CDP timeout: ${method}`));
      }
    }, 10000);
  });

ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const p = pending.get(msg.id);
    pending.delete(msg.id);
    if (msg.error) p.reject(new Error(JSON.stringify(msg.error)));
    else p.resolve(msg.result);
  }
};

await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = () => reject(new Error('WebSocket 连接失败: ' + wsUrl));
});

await send('Runtime.enable');

if (navigateUrl) {
  await send('Page.navigate', { url: navigateUrl });
  await new Promise((r) => setTimeout(r, 4000));
}

if (expression) {
  const result = await send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (result.exceptionDetails) {
    console.error('页面执行异常:', JSON.stringify(result.exceptionDetails, null, 2));
    process.exitCode = 1;
  } else {
    const value = result.result && result.result.value;
    console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 2));
  }
}

if (screenshotPath) {
  const shot = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(screenshotPath, Buffer.from(shot.data, 'base64'));
  console.log('截图已保存:', screenshotPath);
}

ws.close();
