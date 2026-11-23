import { openTask } from './session-task.js';

const api = location.port === '5173' || location.port === '5174'
  ? location.protocol + '//' + location.hostname + ':8080' : location.origin;
document.body.className = 'dashboard';
document.body.innerHTML = `
<header class="app-header"><a class="brand" href="/">Browser Studio <small>多会话工作台</small></a>
<div class="header-actions"><label>登录账号 <select id="accounts"></select></label><button id="add-account">添加账号</button><button class="primary" id="create">＋ 新建浏览器</button></div></header>
<main class="session-workspace">
<section class="session-management" aria-labelledby="session-management-title">
  <header><h2 id="session-management-title">会话管理</h2><span id="session-count">正在加载</span></header>
  <ul id="session-slots"></ul>
  <p id="dashboard-status" role="status"></p>
</section><section id="sessions" class="session-grid"></section><p id="no-sessions" hidden>暂无浏览器会话，选择账号后点击“新建浏览器”。</p></main>`;
const status = document.getElementById('dashboard-status');
const select = document.getElementById('accounts');
const create = document.getElementById('create');
const cards = new Map();
let userId;
let accounts = [];
let refreshing = false;
let refreshQueued = false;
let creating = false;
let sessionCount = 0;
const closing = new Set();
create.disabled = true;
async function request(path, options = {}) {
  const response = await fetch(api + path, { ...options, headers: { 'X-User-Id': userId, ...options.headers } });
  if (!response.ok) {
    let error;
    try { error = (await response.json()).error; } catch {}
    throw new Error(error || '请求失败 ' + response.status);
  }
  return response.status === 204 ? null : response.json();
}
async function loadAccounts() {
  accounts = await request('/api/accounts');
  const selected = select.value || localStorage.getItem('rb-account');
  select.replaceChildren(...accounts.map(a => new Option(a.name, a.id)));
  if (accounts.some(a => a.id === selected)) select.value = selected;
}
function sessionUrl(info, meta) {
  const url = new URL('/', location.origin);
  url.search = info.wsPath.split('?')[1];
  url.searchParams.set('tokenExpiresAt', info.tokenExpiresAt);
  url.searchParams.set('mode', 'monitor');
  url.searchParams.set('accountId', meta.accountId);
  url.searchParams.set('accountName', accounts.find(a => a.id === meta.accountId)?.name || '默认账号');
  return url;
}
function updateCreateButton() {
  create.disabled = !userId || creating || sessionCount >= 3;
}

async function closeSession(sessionId, button) {
  if (closing.has(sessionId)) return;
  closing.add(sessionId);
  button.disabled = true;
  status.textContent = '';
  try {
    const response = await fetch(api + '/api/sessions/' + sessionId, {
      method: 'DELETE', headers: { 'X-User-Id': userId },
    });
    if (!response.ok && response.status !== 404) throw new Error('关闭会话失败，请重试');
    await refresh();
  } catch (error) {
    status.textContent = error.message;
  } finally {
    closing.delete(sessionId);
    button.disabled = false;
  }
}

function closeButton(session, accessibleName) {
  const button = document.createElement('button');
  button.textContent = '关闭会话';
  button.setAttribute('aria-label', '关闭' + accessibleName);
  button.disabled = closing.has(session.sessionId) || session.state === 'DRAINING';
  button.addEventListener('click', () => closeSession(session.sessionId, button));
  return button;
}

function renderSessionManager(sessions) {
  sessionCount = sessions.length;
  updateCreateButton();
  document.getElementById('session-count').textContent = sessionCount + ' / 3 个会话';
  const numbered = [1, 2, 3].map(number => [number, sessions.find(s => s.sessionNumber === number)]);
  const overflow = sessions.filter(s => s.sessionNumber === 0).map(session => [0, session]);
  const items = [...numbered, ...overflow].map(([number, session]) => {
    const item = document.createElement('li');
    const name = document.createElement('strong');
    name.textContent = number ? '会话 ' + number : '旧会话';
    const state = document.createElement('span');
    state.textContent = session
      ? ({ CREATING: '启动中', ACTIVE: '运行中', DISCONNECTED: '未连接', DRAINING: '关闭中' }[session.state] || '已占用')
      : '空闲';
    item.append(name, state);
    if (session) item.append(closeButton(session, name.textContent));
    return item;
  });
  document.getElementById('session-slots').replaceChildren(...items);
}

function addCard(meta) {
  const article = document.createElement('article');
  article.className = 'session-window';
  article.dataset.sessionId = meta.sessionId;
  const header = document.createElement('header');
  const title = document.createElement('strong');
  title.textContent = accounts.find(a => a.id === meta.accountId)?.name || '默认账号';
  const id = document.createElement('code');
  const sessionName = '会话 ' + meta.sessionNumber;
  id.textContent = '控制端 · ' + sessionName;
  const task = document.createElement('button');
  task.textContent = 'AI 任务';
  task.addEventListener('click', () => openTask({
    sessionId: meta.sessionId, sessionName, view: iframe.contentWindow, role: '控制端',
  }));
  const open = document.createElement('button');
  open.textContent = '打开监控';
  open.disabled = true;
  const close = closeButton(meta, sessionName);
  const iframe = document.createElement('iframe');
  iframe.title = sessionName;
  const feedback = document.createElement('p');
  feedback.className = 'window-status';
  feedback.textContent = '正在连接浏览器';
  header.append(title, id, task, open, close);
  article.append(header, feedback, iframe);
  document.getElementById('sessions').append(article);
  const card = { article, iframe, open, feedback, expiresAt: 0, url: null, number: meta.sessionNumber };
  cards.set(meta.sessionId, card);
  open.addEventListener('click', () => {
    const popup = window.open(card.url, 'monitor-' + meta.sessionId, 'popup=yes,width=1280,height=850,resizable=yes,scrollbars=yes');
    if (popup) popup.focus();
    else feedback.textContent = '请允许此站点打开弹出窗口，然后再次点击打开监控';
  });
  return card;
}
async function refresh() {
  if (!userId) return;
  if (refreshing) {
    refreshQueued = true;
    return;
  }
  refreshing = true;
  try {
    const allSessions = await request('/api/sessions');
    renderSessionManager(allSessions);
    const sessions = allSessions.filter(s => ['ACTIVE', 'DISCONNECTED'].includes(s.state) && s.sessionNumber > 0)
      .sort((a, b) => a.sessionNumber - b.sessionNumber);
    const ids = new Set(sessions.map(s => s.sessionId));
    for (const [id, card] of cards) {
      if (!ids.has(id) || sessions.find(session => session.sessionId === id).sessionNumber !== card.number) {
        card.article.remove(); cards.delete(id);
      }
    }
    for (const meta of sessions) {
      const card = cards.get(meta.sessionId) || addCard(meta);
      card.article.style.order = meta.sessionNumber;
      if (card.expiresAt - Date.now() > 60000) continue;
      try {
        const info = await request('/api/sessions/' + meta.sessionId + '/connect', { method: 'POST' });
        card.url = sessionUrl(info, meta).toString();
        card.expiresAt = info.tokenExpiresAt;
        card.iframe.src = card.url + '&embedded=1';
        card.open.disabled = false;
        card.feedback.textContent = '实时画面 · 可控制';
      } catch (error) { card.feedback.textContent = error.message; }
    }
    document.getElementById('no-sessions').hidden = sessions.length > 0;
  } catch (error) { status.textContent = error.message; }
  finally {
    refreshing = false;
    if (refreshQueued) {
      refreshQueued = false;
      await refresh();
    }
  }
}
create.addEventListener('click', async () => {
  creating = true;
  updateCreateButton();
  status.textContent = '';
  try {
    localStorage.setItem('rb-account', select.value);
    await request('/api/sessions', { method: 'POST', headers: { 'X-Account-Id': select.value } });
    await refresh();
  } catch (error) { status.textContent = error.message; }
  finally { creating = false; updateCreateButton(); }
});
document.getElementById('add-account').addEventListener('click', async () => {
  const name = prompt('请输入账号名称');
  if (!name?.trim()) return;
  try {
    const account = await request('/api/accounts', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: name.trim() }) });
    await loadAccounts(); select.value = account.id;
  } catch (error) { status.textContent = error.message; }
});
try {
  const response = await fetch(api + '/api/accounts/workspace');
  if (!response.ok) throw new Error('工作台加载失败');
  userId = (await response.json()).userId;
  await loadAccounts();
  await refresh();
  setInterval(refresh, 10000);
} catch (error) { status.textContent = error.message; }
