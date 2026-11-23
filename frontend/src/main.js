import { openTask } from './session-task.js';

const canvas = document.getElementById('remote-canvas');
const ctx = canvas.getContext('2d', { alpha: false });
const statusEl = document.getElementById('status');
const sessionLabel = document.getElementById('session-label');
const newSessionBtn = document.getElementById('new-session');
const resetBtn = document.getElementById('reset');
const exportBtn = document.getElementById('export-btn');
const viewportSelect = document.getElementById('viewport-select');
const kbHook = document.getElementById('kb-hook');
const shareBtn = document.getElementById('share-btn');

// ===== 解析当前模式 =====
const urlParams = new URLSearchParams(location.search);
const isMonitorMode = urlParams.get('mode') === 'monitor';
const isEmbedded = urlParams.get('embedded') === '1';
if (isEmbedded) document.body.classList.add('embedded');

if (isMonitorMode) {
    if(newSessionBtn) newSessionBtn.style.display = 'none';
    if(resetBtn) resetBtn.style.display = 'none';
    if(shareBtn) shareBtn.style.display = 'none';
    const monitorPanel = document.getElementById('monitor-panel');
    if(monitorPanel) monitorPanel.style.display = 'block';
}

function resolveEndpoints() {
  const isDev = location.port === '5173' || location.port === '5174';
  const host = location.hostname;
  if (isDev) {
    return {
      CONTROL_PLANE: `http://${host}:8080`,
      DATA_PLANE_HTTP: `http://${host}:8081`,
      DATA_PLANE_WS: `ws://${host}:8081`,
    };
  }
  const isHttps = location.protocol === 'https:';
  const origin = `${location.protocol}//${location.host}`;
  return {
    CONTROL_PLANE: origin,
    DATA_PLANE_HTTP: origin,
    DATA_PLANE_WS: isHttps ? `wss://${location.host}` : `ws://${location.host}`,
  };
}

const { CONTROL_PLANE, DATA_PLANE_HTTP, DATA_PLANE_WS } = resolveEndpoints();

function uuid() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

let USER_ID;

const accountSelect = document.getElementById('account-select');
const accountStatus = document.getElementById('account-status');
let selectedAccount = urlParams.get('accountId') || localStorage.getItem('rb-account') || 'default';
const switchAccountBtn = document.getElementById('switch-account');
newSessionBtn.disabled = true;
document.getElementById('empty-create').disabled = true;
document.getElementById('add-account').disabled = true;
switchAccountBtn.disabled = true;
async function loadAccounts() {
  try {
    const response = await fetch(CONTROL_PLANE + '/api/accounts', { headers: { 'X-User-Id': USER_ID } });
    if (!response.ok) throw new Error('加载账号失败');
    const accounts = await response.json();
    accountSelect.replaceChildren(...accounts.map(account => new Option(account.name, account.id)));
    accountSelect.value = accounts.some(account => account.id === selectedAccount) ? selectedAccount : 'default';
    selectedAccount = accountSelect.value;
  } catch (error) { accountStatus.textContent = error.message; }
}
accountSelect.addEventListener('change', () => {
  selectedAccount = accountSelect.value;
  localStorage.setItem('rb-account', selectedAccount);
  accountStatus.textContent = '点击切换账号使用所选账号，当前会话将关闭';
});
document.getElementById('add-account').addEventListener('click', async () => {
  const name = prompt('请输入账号名称');
  if (!name?.trim()) return;
  try {
    const response = await fetch(CONTROL_PLANE + '/api/accounts', {
      method: 'POST', headers: { 'X-User-Id': USER_ID, 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name.trim() }),
    });
    const account = await response.json();
    if (!response.ok) throw new Error(account.error || '添加账号失败');
    selectedAccount = account.id;
    localStorage.setItem('rb-account', selectedAccount);
    await loadAccounts();
    accountStatus.textContent = '账号已添加，点击新建会话后选择站点登录';
  } catch (error) { accountStatus.textContent = error.message; }
});
if (isMonitorMode) {
  document.getElementById('account-pool').hidden = true;
  viewportSelect.disabled = true;
  viewportSelect.title = '分辨率由主窗口设置';
  document.getElementById('view-label').textContent = '远程监控与控制';
}
const accountsReady = isMonitorMode ? Promise.resolve() : (async () => {
  try {
    const response = await fetch(CONTROL_PLANE + '/api/accounts/workspace');
    if (!response.ok) throw new Error('加载工作台失败，请刷新重试');
    USER_ID = (await response.json()).userId;
    localStorage.setItem('rb-user', USER_ID);
    await loadAccounts();
    newSessionBtn.disabled = false;
    document.getElementById('empty-create').disabled = false;
    document.getElementById('add-account').disabled = false;
    switchAccountBtn.disabled = false;
  } catch (error) { accountStatus.textContent = error.message; }
})();
switchAccountBtn.addEventListener('click', () => createSession(true));

const isMobile = /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent) || window.innerWidth < 768;

let ws = null;
let reconnectTimer = null;
let sessionId = urlParams.get('sessionId');
let sessionName = '浏览器会话';
const monitorTaskBtn = document.getElementById('monitor-ai-task');
if (isMonitorMode && !isEmbedded && sessionId) {
  monitorTaskBtn.hidden = false;
  monitorTaskBtn.addEventListener('click', () => {
    if (sessionId) openTask({ sessionId, sessionName, view: window, role: '监控端' });
  });
}
let logicalViewport = { width: 1280, height: 720, dsf: 1 };
let moveThrottle = 0; // 【修复】补全缺失的变量

if (shareBtn && sessionId && !isMonitorMode) {
    shareBtn.style.display = 'inline-block';
}

function setStatus(t) {
  if (statusEl.textContent !== t) statusEl.textContent = t;
  statusEl.parentElement.dataset.connected = String(t === '已连接' || t === '监控中 · 可控制');
}
const emptyState = document.getElementById('empty-state');
const taskStatus = document.getElementById('task-status');
const historyPanel = document.createElement('dialog');
historyPanel.className = 'session-task action-history';
historyPanel.setAttribute('aria-labelledby', 'history-heading');
historyPanel.innerHTML = `
  <form method="dialog" class="task-header">
    <h2 id="history-heading">操作记录</h2>
    <button>关闭</button>
  </form>
  <p role="status"></p>
  <ol></ol>`;
document.body.append(historyPanel);
const historyStatus = historyPanel.querySelector('p');
const historyList = historyPanel.querySelector('ol');
const historyTime = new Intl.DateTimeFormat('zh-CN', { timeStyle: 'medium', hour12: false });
let historyTimer;

function requestHistory() {
  if (ws?.readyState === WebSocket.OPEN) send({ type: 'recent_actions' });
  else historyStatus.textContent = '等待会话连接';
}

document.getElementById('action-history-btn').addEventListener('click', () => {
  historyPanel.showModal();
  historyStatus.textContent = '正在读取操作记录';
  requestHistory();
  historyTimer = setInterval(requestHistory, 3000);
});
historyPanel.addEventListener('close', () => clearInterval(historyTimer));

function showHistory({ entries = [], error }) {
  if (!historyPanel.open) return;
  const items = entries.map(({ ts, label }) => {
    const item = document.createElement('li');
    const time = document.createElement('time');
    time.textContent = historyTime.format(ts);
    item.append(time, label);
    return item;
  });
  historyList.replaceChildren(...items);
  historyStatus.textContent = error || (items.length ? '' : '暂无操作记录');
}

let receivedViewport = { width: 1280, height: 720 };
let latestFrame = null, decoding = false, renderedFrames = 0, statsAt = performance.now();
async function renderLatest() {
  if (decoding || !latestFrame) return;
  decoding = true;
  const data = latestFrame; latestFrame = null;
  try {
    const bitmap = await createImageBitmap(new Blob([data.jpeg], { type: 'image/jpeg' }));
    logicalViewport.width = data.width;
    logicalViewport.height = data.height;
    if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
      canvas.width = bitmap.width;
      canvas.height = bitmap.height;
    }
    fitCanvas();
    ctx.drawImage(bitmap, 0, 0);
    canvas.dataset.frameAt = data.receivedAt;
    canvas.dataset.viewportWidth = data.width;
    canvas.dataset.viewportHeight = data.height;
    bitmap.close(); renderedFrames++;
    const elapsed = performance.now() - statsAt;
    if (elapsed >= 1000) {
      document.getElementById('stream-stats').textContent = `${Math.round(renderedFrames * 1000 / elapsed)} fps · 实时画面`;
      renderedFrames = 0; statsAt = performance.now();
    }
  } catch (_) { setStatus('解码失败'); }
  finally { decoding = false; if (latestFrame) requestAnimationFrame(renderLatest); }
}


function getTargetViewport() {
  if (isMobile) {
    const w = Math.round(window.innerWidth);
    const maxH = Math.max(window.innerHeight, screen.height * 0.85);
    const toolbarH = document.getElementById('toolbar')?.offsetHeight || 44;
    return { width: w, height: Math.round(maxH - toolbarH), dsf: 2 };
  }
  const [w, h] = viewportSelect.value.split('x').map(Number);
  return { width: w, height: h, dsf: 1 };
}

const stage = document.getElementById('stage');
function fitCanvas() {
  const style = getComputedStyle(stage);
  const width = stage.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
  const height = stage.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom);
  const scale = Math.min(width / logicalViewport.width, height / logicalViewport.height);
  canvas.style.width = Math.max(1, logicalViewport.width * scale) + 'px';
  canvas.style.height = Math.max(1, logicalViewport.height * scale) + 'px';
}
new ResizeObserver(fitCanvas).observe(stage);
const expandViewBtn = document.getElementById('expand-view');
expandViewBtn.addEventListener('click', () => {
  const expanded = document.querySelector('.browser-shell').classList.toggle('expanded');
  expandViewBtn.textContent = expanded ? '还原窗口' : '放大画面';
  expandViewBtn.setAttribute('aria-pressed', String(expanded));
  fitCanvas();
});


function applyCanvasSize() {
  fitCanvas();
}

async function createSession(replaceCurrent = false) {
  await accountsReady;
  if (!USER_ID || newSessionBtn.disabled) return;
  newSessionBtn.disabled = true;
  switchAccountBtn.disabled = true;
  try {
    const vp = getTargetViewport();
    if (replaceCurrent === true && sessionId) {
      const closed = await fetch(CONTROL_PLANE + '/api/sessions/' + sessionId, {
        method: 'DELETE', headers: { 'X-User-Id': USER_ID },
      });
      if (!closed.ok && closed.status !== 404) throw new Error('当前会话关闭失败，请重试');
    }
    const resp = await fetch(`${CONTROL_PLANE}/api/sessions`, {
      method: 'POST',
      headers: { 'X-User-Id': USER_ID, 'X-Request-Id': uuid(), 'X-Account-Id': selectedAccount },
    });

    if (resp.status === 502) { alert('服务正在启动或维护中，请等待 30 秒后重试。'); return; }
    if (resp.status === 429) { alert('配额超限，点重置按钮清零'); return; }
    if (!resp.ok) {
      let detail = '';
      try {
        const body = await resp.json();
        detail = body && (body.error || body.message) ? `\n${body.error || body.message}` : '';
      } catch (_) {}
      alert(`创建失败: ${resp.status}${detail}`);
      return;
    }

    const info = await resp.json();
    let token = null;
    if (info.token) token = info.token;
    else if (info.wsPath) token = new URLSearchParams(info.wsPath.split('?')[1] || '').get('token');

    if (!info.sessionId || !token) {
      alert('返回数据异常');
      return;
    }

    const url = new URL(location.origin);
    url.searchParams.set('sessionId', info.sessionId);
    url.searchParams.set('accountId', selectedAccount);
    url.searchParams.set('accountName', accountSelect.selectedOptions[0]?.textContent || '默认账号');
    url.searchParams.set('token', token);
    url.searchParams.set('tokenExpiresAt', info.tokenExpiresAt);
    url.searchParams.set('vw', vp.width);
    url.searchParams.set('vh', vp.height);
    url.searchParams.set('dsf', vp.dsf);
    location.href = url.toString();
  } catch (e) {
    alert('创建异常: ' + e.message);
  } finally { newSessionBtn.disabled = false; switchAccountBtn.disabled = false; }
}

async function resetAll() {
  if (!confirm('重置会关闭当前所有会话并清零配额，确定？')) return;
  try { await fetch(`${CONTROL_PLANE}/api/sessions/reset`, { method: 'POST', headers: { 'X-User-Id': USER_ID } }); } catch (e) {}

  location.href = location.origin;
}

async function exportData() {
  const sid = new URLSearchParams(location.search).get('sessionId');
  if (!sid) { alert('当前没有活跃会话'); return; }
  try {
    const resp = await fetch(`${DATA_PLANE_HTTP}/api/export/${sid}`);
    if (!resp.ok) { alert('导出失败: ' + resp.status); return; }
    const blob = await resp.blob();
    const count = (await blob.text()).trim().split('\n').filter(l => l).length;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = `session-${sid}.jsonl`;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    URL.revokeObjectURL(url);
    alert(`已导出 ${count} 条记录`);
  } catch (e) { alert('导出异常: ' + e.message); }
}

if (shareBtn) {
    shareBtn.addEventListener('click', () => {
        const params = new URLSearchParams(location.search);
        const sid = params.get('sessionId');
        const token = params.get('token');
        const exp = params.get('tokenExpiresAt');
        const vw = params.get('vw');
        const vh = params.get('vh');
        const dsf = params.get('dsf');

        if (!sid || !token) {
            alert('当前没有活跃的会话，无法生成链接');
            return;
        }

        const monitorUrl = `${location.origin}/?sessionId=${sid}&token=${token}&tokenExpiresAt=${exp}&vw=${vw}&vh=${vh}&dsf=${dsf}&mode=monitor`;

        const textArea = document.createElement("textarea");
        textArea.value = monitorUrl;
        textArea.style.position = "fixed";
        textArea.style.left = "-9999px";
        document.body.appendChild(textArea);
        textArea.focus();
        textArea.select();

        try {
            const successful = document.execCommand('copy');
            if (successful) {
                alert('✅ 监控链接已复制到剪贴板！\n\n发给其他人或粘贴到无痕窗口即可同步观看并控制浏览器：\n' + monitorUrl);
            } else {
                prompt('自动复制失败，请手动复制以下链接：', monitorUrl);
            }
        } catch (err) {
            prompt('复制异常，请手动复制以下链接：', monitorUrl);
        }
        document.body.removeChild(textArea);
    });
}

function connect() {
  const params = new URLSearchParams(location.search);
  sessionId = params.get('sessionId');
  delete canvas.dataset.frameAt;
  const token = params.get('token');
  const tokenExpiresAt = parseInt(params.get('tokenExpiresAt') || '0');
  const vw = parseInt(params.get('vw') || '1280');
  const vh = parseInt(params.get('vh') || '720');
  const dsf = parseInt(params.get('dsf') || '1');

  document.body.classList.toggle('monitor', isMonitorMode);
  document.body.classList.toggle('has-session', Boolean(sessionId && token));
  emptyState.hidden = Boolean(sessionId && token);
  emptyState.style.display = sessionId && token ? 'none' : 'flex';
  if (!sessionId || !token) { setStatus('未连接'); return; }
  const role = isEmbedded ? '控制端' : isMonitorMode ? '监控端' : '控制端';
  const showSessionName = name => {
    sessionName = name;
    document.title = role + ' · ' + name + ' · Browser Studio';
    document.getElementById('view-label').textContent = role + ' · ' + name;
    sessionLabel.textContent = (urlParams.get('accountName') || '默认账号') + ' · ' + name;
  };
  showSessionName('浏览器会话');

  logicalViewport = { width: vw, height: vh, dsf };
  receivedViewport = { width: vw, height: vh };
  applyCanvasSize();

  if (tokenExpiresAt && Date.now() > tokenExpiresAt) { setStatus('会话已过期'); return; }

  const wsUrl = `${DATA_PLANE_WS}/ws?sessionId=${sessionId}&token=${token}`;
  setStatus('连接中...');
  ws = new WebSocket(wsUrl);
  ws.binaryType = 'arraybuffer';

  ws.onopen = () => {
    setStatus(isMonitorMode ? '监控中 · 可控制' : '已连接');
    if (!isMonitorMode) send({ type: 'viewport', width: vw, height: vh, deviceScaleFactor: dsf });

    if (isMonitorMode) {
        const monitorLog = document.getElementById('monitor-log');
        if(monitorLog) monitorLog.innerHTML += `[${new Date().toLocaleTimeString()}] 画面已连接，可通过画面操作浏览器<br>`;
    }

    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  };

  ws.onmessage = (event) => {
    if (!(event.data instanceof ArrayBuffer)) {
      try { const msg = JSON.parse(event.data);
      if (msg.type === 'viewport' && msg.width > 0 && msg.height > 0) {
        receivedViewport = { width: msg.width, height: msg.height };
        return;
      }
      if (msg.type === 'session_identity' && Number.isSafeInteger(msg.sessionNumber) && msg.sessionNumber > 0) {
        showSessionName('会话 ' + msg.sessionNumber);
        return;
      }
      if (msg.type === 'automation_state') {
        document.getElementById('automation-state').textContent = msg.running ? '站点自动运行中' : '站点自动化空闲';
        return;
      }
      if (msg.type === 'recent_actions') {
        showHistory(msg);
        return;
      }
      if (msg.type === 'ai_result') {
        document.dispatchEvent(new CustomEvent('rb-ai-result', { detail: msg }));
        return;
      }
      if (msg.type === 'error' || msg.type === 'automation_status') {
        const text = msg.message || '';
        if (text.includes('undefined')) return;
        taskStatus.textContent = text.includes('RISK_BLOCKED') ? '站点要求安全验证，请在画面中手动完成' : text.includes('QR_READY') || text.includes('WAITING_FOR_QR') ? '请使用对应手机 App 扫码，并在手机上确认登录' : text.includes('LOGIN_STATE_UNKNOWN') ? '请点击网页中的登录按钮，再用手机扫码' : text;
      } } catch (_) {}
      return;
    }
    if (document.getElementById('stream-stats').textContent === '等待画面') {
      statsAt = performance.now(); renderedFrames = 0;
      document.getElementById('stream-stats').textContent = '画面已同步';
    }
    latestFrame = { jpeg: event.data, ...receivedViewport, receivedAt: new Date().toISOString() };
    // Decode the initial frame even if a newly opened monitor window is in the background.
    if (!canvas.dataset.frameAt) renderLatest();
    else if (!decoding) requestAnimationFrame(renderLatest);
  };

  ws.onclose = (e) => {
    delete canvas.dataset.frameAt;
    latestFrame = null;
    document.getElementById('stream-stats').textContent = '等待画面';
    document.getElementById('automation-state').textContent = '';
    if (e.code === 1008) {
      setStatus('会话已失效');
      const url = new URL(location.href);
      ['sessionId','token','tokenExpiresAt','vw','vh','dsf'].forEach(k => url.searchParams.delete(k));
      history.replaceState(null, '', url.toString());
      sessionId = null;
      monitorTaskBtn.disabled = true;
      return;
    }
    setStatus('断开重连');
    reconnectTimer = setTimeout(connect, 3000);
  };
  ws.onerror = () => setStatus('连接错误');
}

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

document.addEventListener('rb-ai-request', event => {
  if (ws?.readyState === WebSocket.OPEN) {
    send(event.detail);
  } else {
    document.dispatchEvent(new CustomEvent('rb-ai-result', {
      detail: { requestId: event.detail.requestId, message: '浏览器尚未连接' },
    }));
  }
});

function toRemotePoint(clientX, clientY) {
  const rect = canvas.getBoundingClientRect();
  return {
    x: Math.round((clientX - rect.left) * (logicalViewport.width / rect.width)),
    y: Math.round((clientY - rect.top) * (logicalViewport.height / rect.height)),
  };
}

// ===== 手机触摸事件 =====
let touchState = { startX: 0, startY: 0, startTime: 0, lastX: 0, lastY: 0, moved: false };
let lastTapTime = 0;

canvas.addEventListener('touchstart', (e) => {
  e.preventDefault();
  canvas.focus({preventScroll:true});
  if (e.touches.length === 1) {
    const t = e.touches[0];
    const p = toRemotePoint(t.clientX, t.clientY);
    touchState = { startX: p.x, startY: p.y, startTime: Date.now(), lastX: p.x, lastY: p.y, moved: false };
  }
}, { passive: false });

canvas.addEventListener('touchmove', (e) => {
  e.preventDefault();
  if (e.touches.length !== 1) return;
  const t = e.touches[0];
  const p = toRemotePoint(t.clientX, t.clientY);
  const dx = Math.abs(p.x - touchState.startX);
  const dy = Math.abs(p.y - touchState.startY);
  if (dx > 10 || dy > 10) touchState.moved = true;

  if (touchState.moved) {
    send({ type: 'mouse', action: 'moved', x: p.x, y: p.y, buttons: 1 });
  }
  touchState.lastX = p.x;
  touchState.lastY = p.y;
}, { passive: false });

canvas.addEventListener('touchend', (e) => {
  e.preventDefault();
  const now = Date.now();
  if (touchState.moved) {
    const xDist = touchState.startX - touchState.lastX;
    const yDist = touchState.startY - touchState.lastY;
    if (Math.abs(xDist) > 20 || Math.abs(yDist) > 20) {
      send({ type: 'scrollGesture', x: touchState.startX, y: touchState.startY, xDistance: Math.round(xDist), yDistance: Math.round(yDist) });
    }
  } else {
    const isDouble = (now - lastTapTime) < 350;
    lastTapTime = isDouble ? 0 : now;
    const clickCount = isDouble ? 2 : 1;
    const p = { x: touchState.startX, y: touchState.startY };
    send({ type: 'mouse', action: 'moved', x: p.x, y: p.y, buttons: 0 });
    send({ type: 'mouse', action: 'pressed', x: p.x, y: p.y, button: 'left', clickCount, buttons: 1 });
    setTimeout(() => send({ type: 'mouse', action: 'released', x: p.x, y: p.y, button: 'left', clickCount, buttons: 0 }), 30);
  }
}, { passive: false });

// Mouse events also work in embedded browsers that do not emit PointerEvents.
let clickCount = 1, lastClickAt = 0, lastClickPoint = {x:0,y:0}, mouseDown = false, heldButton = 'left';
const mouseButton = e => ['left', 'middle', 'right'][e.button] || 'left';
canvas.addEventListener('mousedown', e => {
  if (e.sourceCapabilities?.firesTouchEvents) return;
  e.preventDefault();
  // Capture coordinates before focus; focusing must never scroll the canvas under the pointer.
  const p = toRemotePoint(e.clientX, e.clientY), now = performance.now();
  canvas.focus({preventScroll:true});
  clickCount = now-lastClickAt < 400 && Math.hypot(p.x-lastClickPoint.x,p.y-lastClickPoint.y)<8 ? 2 : 1;
  lastClickAt = clickCount === 2 ? 0 : now; lastClickPoint = p;
  mouseDown = true; heldButton = mouseButton(e);
  send({type:'mouse',action:'pressed',...p,button:heldButton,clickCount,buttons:e.buttons || 1});
  const feedback = document.getElementById('input-feedback');
  if (feedback) feedback.textContent = ws?.readyState === WebSocket.OPEN ? '点击已发送' : '连接已断开';
});
window.addEventListener('mousemove', e => {
  if (e.sourceCapabilities?.firesTouchEvents || (!mouseDown && e.target !== canvas)) return;
  const now=performance.now(); if(now-moveThrottle<16) return; moveThrottle=now;
  send({type:'mouse',action:'moved',...toRemotePoint(e.clientX,e.clientY),buttons:mouseDown ? e.buttons : 0});
});
window.addEventListener('mouseup', e => {
  if (!mouseDown) return;
  mouseDown = false;
  send({type:'mouse',action:'released',...toRemotePoint(e.clientX,e.clientY),button:heldButton,clickCount,buttons:0});
});
window.addEventListener('blur',()=>{
  if(!mouseDown) return; mouseDown=false;
  send({type:'mouse',action:'released',...lastClickPoint,button:heldButton,clickCount,buttons:0});
});
canvas.addEventListener('contextmenu',e=>e.preventDefault());
let wheelX=0,wheelY=0,wheelPoint=null,wheelScheduled=false;
canvas.addEventListener('wheel',e=>{
  e.preventDefault();
  const scale=e.deltaMode===1?16:e.deltaMode===2?logicalViewport.height:1;
  wheelX+=e.deltaX*scale;wheelY+=e.deltaY*scale;wheelPoint=toRemotePoint(e.clientX,e.clientY);
  if(!wheelScheduled){wheelScheduled=true;requestAnimationFrame(()=>{
    send({type:'wheel',...wheelPoint,deltaX:wheelX,deltaY:wheelY});wheelX=0;wheelY=0;wheelScheduled=false;
  });}
},{passive:false});

// ===== 键盘事件 =====
document.addEventListener('keydown', (e) => {
  if (['INPUT','BUTTON','SELECT','TEXTAREA'].includes(e.target.tagName) && e.target !== kbHook) return;
  e.preventDefault();
  send({ type: 'key', action: 'down', key: e.key, code: e.code, modifiers: (e.ctrlKey?2:0)|(e.shiftKey?8:0)|(e.altKey?1:0)|(e.metaKey?4:0) });
});

document.addEventListener('keyup', (e) => {
  if (['INPUT','BUTTON','SELECT','TEXTAREA'].includes(e.target.tagName) && e.target !== kbHook) return;
  e.preventDefault();
  send({ type: 'key', action: 'up', key: e.key, code: e.code, modifiers: (e.ctrlKey?2:0)|(e.shiftKey?8:0)|(e.altKey?1:0)|(e.metaKey?4:0) });
});

// ===== 虚拟键盘 =====
let kbLastValue = '';
kbHook.addEventListener('input', () => {
  const val = kbHook.value;
  if (val.length > kbLastValue.length) {
    for (const ch of val.substring(kbLastValue.length)) {
      send({ type: 'key', action: 'down', key: ch, code: '', modifiers: 0 });
      send({ type: 'key', action: 'up', key: ch, code: '', modifiers: 0 });
    }
  }
  kbLastValue = val;
  if (val.length > 20) { kbHook.value = ''; kbLastValue = ''; }
});

kbHook.addEventListener('keydown', (e) => {
  if (e.key === 'Backspace') {
    e.preventDefault();
    send({ type: 'key', action: 'down', key: 'Backspace', code: 'Backspace', modifiers: 0 });
    send({ type: 'key', action: 'up', key: 'Backspace', code: 'Backspace', modifiers: 0 });
    if (kbHook.value.length > 0) { kbHook.value = kbHook.value.slice(0, -1); kbLastValue = kbHook.value; }
  } else if (e.key === 'Enter') {
    e.preventDefault();
    send({ type: 'key', action: 'down', key: 'Enter', code: 'Enter', modifiers: 0 });
    send({ type: 'key', action: 'up', key: 'Enter', code: 'Enter', modifiers: 0 });
  }
});

// ===== 按钮绑定 =====
document.getElementById('kb-btn')?.addEventListener('click', () => { try { kbHook.focus(); } catch (_) {} });
document.getElementById('back-btn')?.addEventListener('click', () => { send({ type: 'back' }); });
document.getElementById('forward-btn')?.addEventListener('click', () => { send({ type: 'forward' }); });
if(newSessionBtn) newSessionBtn.addEventListener('click', createSession);
document.getElementById('empty-create').addEventListener('click',createSession);
document.getElementById('address-form').addEventListener('submit',e=>{
 e.preventDefault();
 let value=document.getElementById('address-input').value.trim();
 if(!value) return; if(!/^https?:\/\//i.test(value)) value='https://'+value;
 try { const u=new URL(value); if(!['https:','http:'].includes(u.protocol)) return; send({type:'navigate',url:u.href}); } catch(_){taskStatus.textContent='请输入有效网址';}
});
if(resetBtn) resetBtn.addEventListener('click', resetAll);
if(exportBtn) exportBtn.addEventListener('click', exportData);

const monitorExportBtn = document.getElementById('monitor-export-btn');
if (isMonitorMode && monitorExportBtn) {
    monitorExportBtn.addEventListener('click', exportData);
}

// ===== 自动化下拉框逻辑 =====
const autoSelect = document.getElementById('auto-select');
if (autoSelect) {
    if (sessionId) {
        autoSelect.style.display = 'inline-block';
    }

    autoSelect.addEventListener('change', () => {
        const siteName = autoSelect.value;
        if (!siteName) return;
        console.log('>>> [前端调试] 准备发送自动化任务:', siteName);
        if (!ws || ws.readyState !== WebSocket.OPEN) { taskStatus.textContent = '请先创建并连接会话'; autoSelect.value = ''; return; }
        taskStatus.textContent = `正在打开${autoSelect.selectedOptions[0].textContent}，请在画面中完成扫码`;
        send({ type: 'auto_task', siteName: siteName });
        autoSelect.value = "";
    });
}

// ===== 视口切换（PC端） =====
viewportSelect.addEventListener('change', () => {
  if (isMobile || isMonitorMode) return;

  const vp = getTargetViewport();
  logicalViewport = { width: vp.width, height: vp.height, dsf: vp.dsf };
  applyCanvasSize();

  const url = new URL(location.href);
  url.searchParams.set('vw', vp.width);
  url.searchParams.set('vh', vp.height);
  url.searchParams.set('dsf', vp.dsf);
  history.replaceState(null, '', url.toString());

  send({ type: 'viewport', width: vp.width, height: vp.height, deviceScaleFactor: vp.dsf });
});

// The canvas scales with CSS. Resizing the window must not restart the remote screencast.
connect();
