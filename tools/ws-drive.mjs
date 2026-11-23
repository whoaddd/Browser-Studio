#!/usr/bin/env node
/**
 * 平台 WebSocket 驱动脚本：连接数据面、下发一条消息、统计推流帧、可选保存最后一帧。
 * 用于端到端验证（自动任务、画面推流、键鼠通道）。
 *
 * 用法：
 *   node tools/ws-drive.mjs "ws://127.0.0.1:8081/ws?sessionId=xxx&token=yyy" \
 *        '{"type":"auto_task","siteName":"taobao"}' 45000 last.jpg
 */
import fs from 'node:fs';

const [, , wsUrl, jsonMessage, durationMs = '8000', outFile] = process.argv;
if (!wsUrl || !jsonMessage) {
  console.error('用法: node ws-drive.mjs <ws-url> <json-message> [durationMs] [outFile]');
  process.exit(1);
}

const ws = new WebSocket(wsUrl);
ws.binaryType = 'arraybuffer';
let frames = 0;
let lastBuffer = null;
const started = Date.now();

ws.onopen = () => {
  console.log('WS open, 下发:', jsonMessage);
  ws.send(jsonMessage);
};

ws.onmessage = (ev) => {
  if (typeof ev.data === 'string') {
    console.log('text:', ev.data.slice(0, 300));
    return;
  }
  frames++;
  lastBuffer = Buffer.from(ev.data);
};

ws.onerror = (e) => console.error('WS error:', e.message || e);

const timer = setTimeout(() => {
  console.log(`结束: frames=${frames}, 耗时=${Date.now() - started}ms`);
  if (outFile && lastBuffer) {
    fs.writeFileSync(outFile, lastBuffer);
    console.log('最后一帧已保存:', outFile, lastBuffer.length, 'bytes');
  }
  ws.close();
  process.exit(0);
}, Number(durationMs));

ws.onclose = (e) => {
  clearTimeout(timer);
  console.log('WS close:', e.code, e.reason);
  process.exit(0);
};
