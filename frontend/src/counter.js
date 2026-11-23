const canvas = document.getElementById('remote-canvas');
const ctx = canvas.getContext('2d');
const statusEl = document.getElementById('status');
const sessionLabel = document.getElementById('session-label');
const newSessionBtn = document.getElementById('new-session');

const CONTROL_PLANE = 'http://127.0.0.1:8080';
const DATA_PLANE_WS = 'ws://127.0.0.1:8081';

const USER_ID = localStorage.getItem('rb-user') || ('user-' + Math.random().toString(36).slice(2, 8));
localStorage.setItem('rb-user', USER_ID);

let ws = null;
let reconnectTimer = null;
let sessionId = null;

function setStatus(text) {
  statusEl.textContent = text;
}

async function createSession() {
  const resp = await fetch(`${CONTROL_PLANE}/api/sessions`, {
    method: 'POST',
    headers: {
      'X-User-Id': USER_ID,
      'X-Request-Id': crypto.randomUUID(),
    },
  });
  if (resp.status === 429) {
    alert('配额超限，请等待旧会话超时回收后重试');
    return;
  }
  if (!resp.ok) {
    alert('创建会话失败: ' + resp.status);
    return;
  }
  const info = await resp.json();
  const url = new URL(location.href);
  url.searchParams.set('sessionId', info.sessionId);
  url.searchParams.set('wsPath', info.wsPath);
  url.searchParams.set('tokenExpiresAt', info.tokenExpiresAt);
  location.href = url.toString();
}

function connect() {
  const params = new URLSearchParams(location.search);
  sessionId = params.get('sessionId');
  const wsPath = params.get('wsPath');
  const tokenExpiresAt = parseInt(params.get('tokenExpiresAt') || '0');

  if (!sessionId || !wsPath) {
    setStatus('未连接');
    sessionLabel.textContent = '点击右上角新建会话';
    return;
  }
  sessionLabel.textContent = 'session: ' + sessionId;

  if (tokenExpiresAt && Date.now() > tokenExpiresAt) {
    setStatus('会话已过期，请新建会话');
    return;
  }

  const wsUrl = DATA_PLANE_WS + wsPath;
  ws = new WebSocket(wsUrl);
  ws.binaryType = 'arraybuffer';

  ws.onopen = () => {
    setStatus('已连接');
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  };

  ws.onmessage = async (event) => {
    if (!(event.data instanceof ArrayBuffer)) return;
    try {
      const blob = new Blob([event.data], { type: 'image/jpeg' });
      const bitmap = await createImageBitmap(blob);
      ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      bitmap.close();
    } catch (e) {
      console.error('[ws] frame decode failed', e);
    }
  };

  ws.onclose = (e) => {
    if (e.code === 1008) {
      setStatus('会话已失效');
      sessionLabel.textContent = '会话已失效';
      const url = new URL(location.href);
      url.searchParams.delete('sessionId');
      url.searchParams.delete('wsPath');
      url.searchParams.delete('tokenExpiresAt');
      history.replaceState(null, '', url.toString());
      sessionId = null;
      return;
    }
    setStatus('连接断开，3 秒后重连');
    reconnectTimer = setTimeout(connect, 3000);
  };

  ws.onerror = () => setStatus('连接错误');
}

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function toRemotePoint(e) {
  const rect = canvas.getBoundingClientRect();
  return {
    x: Math.round((e.clientX - rect.left) * (canvas.width / rect.width)),
    y: Math.round((e.clientY - rect.top) * (canvas.height / rect.height)),
  };
}

let clickState = { lastTime: 0, count: 0, lastX: 0, lastY: 0 };

canvas.addEventListener('mousedown', (e) => {
  e.preventDefault();
  canvas.focus();
  const p = toRemotePoint(e);
  const now = Date.now();
  const dx = Math.abs(p.x - clickState.lastX);
  const dy = Math.abs(p.y - clickState.lastY);
  if (now - clickState.lastTime < 500 && dx < 10 && dy < 10) {
    clickState.count = Math.min(clickState.count + 1, 3);
  } else {
    clickState.count = 1;
  }
  clickState.lastTime = now;
  clickState.lastX = p.x;
  clickState.lastY = p.y;
  send({ type: 'mouse', action: 'moved', x: p.x, y: p.y });
  send({ type: 'mouse', action: 'pressed', x: p.x, y: p.y, button: 'left', clickCount: clickState.count });
});

canvas.addEventListener('mouseup', (e) => {
  e.preventDefault();
  const p = toRemotePoint(e);
  send({ type: 'mouse', action: 'released', x: p.x, y: p.y, button: 'left', clickCount: clickState.count });
});

let moveThrottle = 0;
canvas.addEventListener('mousemove', (e) => {
  const now = Date.now();
  if (now - moveThrottle < 33) return;
  moveThrottle = now;
  const p = toRemotePoint(e);
  send({ type: 'mouse', action: 'moved', x: p.x, y: p.y });
});

canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  const p = toRemotePoint(e);
  send({ type: 'wheel', x: p.x, y: p.y, deltaX: e.deltaX, deltaY: e.deltaY });
}, { passive: false });

document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'BUTTON') return;
  e.preventDefault();
  send({
    type: 'key', action: 'down', key: e.key, code: e.code,
    modifiers: (e.ctrlKey ? 2 : 0) | (e.shiftKey ? 8 : 0) | (e.altKey ? 1 : 0) | (e.metaKey ? 4 : 0),
  });
});

document.addEventListener('keyup', (e) => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'BUTTON') return;
  e.preventDefault();
  send({
    type: 'key', action: 'up', key: e.key, code: e.code,
    modifiers: (e.ctrlKey ? 2 : 0) | (e.shiftKey ? 8 : 0) | (e.altKey ? 1 : 0) | (e.metaKey ? 4 : 0),
  });
});

newSessionBtn.addEventListener('click', createSession);

connect();