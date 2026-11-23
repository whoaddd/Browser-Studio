import { selectReference } from './reference-selection.js';

const dialog = document.createElement('aside');
dialog.className = 'session-task ai-task-panel';
dialog.id = 'ai-task-panel';
dialog.hidden = true;
dialog.setAttribute('aria-labelledby', 'task-heading');
dialog.innerHTML = `
  <header class="task-header">
    <h2 id="task-heading">浏览器 Agent</h2>
    <button type="button" id="close-task-panel">收起</button>
  </header>
  <p id="task-session"></p>
  <form id="task-form">
    <label for="task-requirement">你的需求</label>
    <textarea id="task-requirement" required maxlength="4000" rows="4"
      placeholder="例如：找 300 元以内、适合通勤的高性价比耳机。"></textarea>
    <label for="task-source">去哪里找</label>
    <input id="task-source" maxlength="1000" placeholder="可选。填写网站、网址或关键词">
    <div class="task-actions reference-actions">
      <button type="button" id="select-reference">在画面上圈选</button>
      <button type="button" id="clear-reference" hidden>移除参考</button>
    </div>
    <div id="task-reference" hidden>
      <img alt="圈选参考区域">
      <label for="reference-label">圈中的内容代表什么</label>
      <input id="reference-label" maxlength="160" placeholder="例如：商品主标题">
    </div>
    <p>确认计划后，Agent 会在目标网站执行任务，核对结果并附上来源。分析会使用页面截图。</p>
    <div class="task-actions">
      <button type="button" id="save-task">保存草稿</button>
      <button>导出任务和截图</button>
      <button class="primary" type="button" id="analyze-task">分析需求</button>
    </div>
    <p id="task-feedback" role="status"></p>
    <dl id="task-plan" hidden></dl>
    <p id="task-proposal" hidden></p>
    <button type="button" id="apply-task" class="primary" hidden>开始执行任务</button>
    <button type="button" id="stop-task" hidden>停止查找</button>
    <button type="button" id="resume-task" class="primary" hidden>继续执行</button>
    <section id="task-answer" aria-labelledby="task-answer-heading" aria-live="polite" hidden>
      <h3 id="task-answer-heading">搜索答复</h3><p></p><ul></ul>
    </section>
    <details id="task-trace" hidden><summary>执行记录</summary><ol></ol></details>
    <section id="task-findings" hidden><h3>核对到的资料</h3><ol></ol></section>
    <ol id="task-products" hidden></ol>
    <section id="task-visual" hidden>
      <h3>视觉定位结果</h3><ol></ol>
      <button type="button" id="export-visual">导出定位 JSON</button>
    </section>
  </form>`;

const requirement = dialog.querySelector('#task-requirement');
const source = dialog.querySelector('#task-source');
const feedback = dialog.querySelector('#task-feedback');
const selectReferenceButton = dialog.querySelector('#select-reference');
const referencePanel = dialog.querySelector('#task-reference');
const referenceLabel = dialog.querySelector('#reference-label');
const clearReferenceButton = dialog.querySelector('#clear-reference');
const visualPanel = dialog.querySelector('#task-visual');
let reference = null;
let cancelSelection;
let visualMatches = [];
let activeTask;
const analyzeButton = dialog.querySelector('#analyze-task');
const applyButton = dialog.querySelector('#apply-task');
const stopButton = dialog.querySelector('#stop-task');
const resumeButton = dialog.querySelector('#resume-task');
const productsList = dialog.querySelector('#task-products');
const trace = dialog.querySelector('#task-trace');
const answerPanel = dialog.querySelector('#task-answer');
const findings = dialog.querySelector('#task-findings');
const planDetails = dialog.querySelector('#task-plan');
const proposalLabel = dialog.querySelector('#task-proposal');
let proposalId;
let pendingRequest;
let responseController;
let responseTimer;
let busy = false;

function setBusy(value) {
  busy = value;
  analyzeButton.disabled = resumeButton.disabled = busy;
  requirement.readOnly = source.readOnly = busy;
  selectReferenceButton.disabled = clearReferenceButton.disabled = busy;
  referenceLabel.readOnly = busy;
}

function clearPlan() {
  proposalId = null;
  applyButton.hidden = proposalLabel.hidden = planDetails.hidden = productsList.hidden = stopButton.hidden = true;
  resumeButton.hidden = true;
  productsList.replaceChildren();
  trace.open = false;
  trace.hidden = findings.hidden = true;
  answerPanel.hidden = true;
  visualPanel.hidden = true;
  visualMatches = [];
}

function showReference() {
  referencePanel.hidden = clearReferenceButton.hidden = !reference;
  referencePanel.querySelector('img').src = reference?.crop || '';
  referenceLabel.value = reference?.label || '';
}

selectReferenceButton.addEventListener('click', () => {
  cancelSelection?.();
  try {
    feedback.textContent = '在浏览器画面上拖动画圈，Esc 取消';
    cancelSelection = selectReference(activeTask.view, selection => {
      cancelSelection = null;
      if (!selection) {
        feedback.textContent = '已取消圈选';
        return;
      }
      reference = { ...selection, label: referenceLabel.value.trim() };
      clearPlan();
      showReference();
      feedback.textContent = '请为参考区域填写含义';
      referenceLabel.focus();
    });
  } catch (error) {
    feedback.textContent = error.message;
  }
});
clearReferenceButton.addEventListener('click', () => {
  reference = null;
  clearPlan();
  showReference();
});
referenceLabel.addEventListener('input', () => {
  if (reference) reference.label = referenceLabel.value.trim();
  clearPlan();
});

function showPlan(plan) {
  planDetails.hidden = !plan;
  if (!plan) return;
  const fields = [
    ['查找目标', plan.goal],
    ['目标网站', plan.site + ' · ' + plan.url],
    ['选择理由', plan.reason],
    ['站内搜索词', plan.query],
    ['预算上限', plan.maxPriceCny == null ? '' : plan.maxPriceCny + ' 元'],
  ];
  planDetails.replaceChildren(...fields.filter(([, value]) => value).flatMap(([label, value]) => {
    const title = document.createElement('dt');
    const description = document.createElement('dd');
    title.textContent = label;
    description.textContent = value;
    return [title, description];
  }));
}

function draft() {
  return {
    sessionId: activeTask.sessionId,
    sessionName: activeTask.sessionName,
    requirement: requirement.value.trim(),
    source: source.value.trim(),
    reference,
  };
}

function saveDraft() {
  const task = draft();
  try {
    localStorage.setItem('rb-task:' + task.sessionId, JSON.stringify(task));
  } catch {
    feedback.textContent = '本机存储空间不足，本次参考仍可使用';
  }
  return task;
}

export function openTask({ sessionId, sessionName, view, role }) {
  if (!dialog.isConnected) document.body.append(dialog);
  dialog.hidden = false;
  document.body.classList.add('ai-task-open');
  if (activeTask?.sessionId === sessionId && activeTask.view === view) return;
  if (busy) {
    feedback.textContent = '当前会话的任务仍在运行，请先停止或等待完成。';
    return;
  }
  activeTask = { sessionId, sessionName, view };
  cancelSelection?.();
  const saved = JSON.parse(localStorage.getItem('rb-task:' + sessionId) || '{}');
  requirement.value = saved.requirement || '';
  source.value = saved.source || '';
  reference = saved.reference || null;
  showReference();
  dialog.querySelector('#task-session').textContent = role + ' · ' + sessionName;
  feedback.textContent = '';
  clearPlan();
  setBusy(false);
  requirement.focus({ preventScroll: true });
  sendAi('ai_checkpoint', {});
}

function sendAi(type, payload) {
  const view = activeTask.view;
  if (!view || new URLSearchParams(view.location.search).get('sessionId') !== activeTask.sessionId) {
    feedback.textContent = '找不到当前浏览器会话';
    return;
  }
  const target = view.document;
  pendingRequest = Math.random().toString(36).slice(2);
  responseController?.abort();
  responseController = new AbortController();
  clearTimeout(responseTimer);
  if (type !== 'ai_checkpoint') setBusy(true);
  if (type !== 'ai_checkpoint') feedback.textContent = {
    ai_plan: 'DeepSeek 正在分析需求并选择来源…',
    ai_apply: '正在启动自动查找…',
    ai_resume: '正在恢复任务…',
  }[type];
  target.addEventListener('rb-ai-result', event => {
    const result = event.detail;
    if (result.requestId !== pendingRequest) return;
    if (result.steps) renderEvidence(result);
    if (result.visualMatches?.length) renderVisualMatches(result.visualMatches);
    if (result.running) {
      resumeButton.hidden = true;
      stopButton.hidden = result.status === 'synthesizing';
      feedback.textContent = result.message;
      if (result.products) renderProducts(result.products);
      return;
    }
    stopButton.hidden = true;
    if (result.answer?.points?.length) renderAnswer(result.answer);
    else answerPanel.hidden = true;
    if (result.products) renderProducts(result.products);
    clearTimeout(responseTimer);
    responseController.abort();
    setBusy(false);
    resumeButton.hidden = !result.canResume;
    const resolution = { completed: '已完成', partial: '部分完成', needs_user: '需要处理', stopped: '已停止' }[result.status];
    feedback.textContent = resolution ? resolution + ' · ' + result.message : result.message;
    if (result.plan) showPlan(result.plan);
    proposalId = result.proposalId;
    proposalLabel.textContent = result.action || '';
    proposalLabel.hidden = applyButton.hidden = !proposalId;

  }, { signal: responseController.signal });
  responseTimer = setTimeout(() => {
    responseController.abort();
    setBusy(false);
    stopButton.hidden = true;
    if (type !== 'ai_checkpoint') feedback.textContent = '响应超时，请重试';
  }, ['ai_apply', 'ai_resume'].includes(type) ? 255000 : type === 'ai_checkpoint' ? 3000 : 55000);
  target.dispatchEvent(new CustomEvent('rb-ai-request', {
    detail: { type, requestId: pendingRequest, ...payload },
  }));
}

analyzeButton.addEventListener('click', () => {
  if (!requirement.value.trim()) {
    requirement.focus();
    feedback.textContent = '请填写任务需求';
    return;
  }
  clearPlan();
  cancelSelection?.();
  if (reference && !reference.label) {
    referenceLabel.focus();
    feedback.textContent = '请填写圈选区域的含义';
    return;
  }
  const task = saveDraft();
  const visualReference = reference ? { label: reference.label, image: reference.crop.split(',')[1] } : undefined;
  sendAi('ai_plan', { requirement: task.requirement, source: task.source, visualReference });
});
applyButton.addEventListener('click', () => {
  if (!proposalId) return;
  applyButton.hidden = proposalLabel.hidden = true;
  sendAi('ai_apply', { proposalId });
  proposalId = null;
});
stopButton.addEventListener('click', () => {
  activeTask.view.document.dispatchEvent(new CustomEvent('rb-ai-request', { detail: { type: 'ai_stop' } }));
  feedback.textContent = '正在停止查找…';
});
resumeButton.addEventListener('click', () => {
  stopButton.hidden = true;
  resumeButton.hidden = true;
  sendAi('ai_resume', {});
});
dialog.querySelector('#close-task-panel').addEventListener('click', () => {
  cancelSelection?.();
  dialog.hidden = true;
  document.body.classList.remove('ai-task-open');
});

function renderVisualMatches(matches) {
  visualMatches = matches;
  visualPanel.hidden = false;
  visualPanel.querySelector('ol').replaceChildren(...matches.map(match => {
    const item = document.createElement('li');
    const region = match.region;
    item.textContent = match.text + ' · 截图坐标 (' + region.x + ', ' + region.y + ') '
      + region.width + ' × ' + region.height + ' · 置信度 ' + Math.round(match.confidence * 100) + '%';
    const link = document.createElement('a');
    link.href = match.sourceUrl;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.textContent = ' 来源';
    item.append(link);
    return item;
  }));
}

dialog.querySelector('#export-visual').addEventListener('click', () => {
  const link = document.createElement('a');
  link.href = URL.createObjectURL(new Blob([JSON.stringify({
    sessionId: activeTask.sessionId, reference, matches: visualMatches,
  }, null, 2)], { type: 'application/json' }));
  link.download = activeTask.sessionName + '-视觉定位.json';
  link.click();
  setTimeout(() => URL.revokeObjectURL(link.href), 1000);
});

function renderProducts(products) {
  productsList.hidden = products.length === 0;
  productsList.replaceChildren(...products.map(product => {
    const row = document.createElement('li');
    const title = document.createElement('strong');
    title.textContent = product.title + ' · ' + product.priceCny + ' 元';
    const reason = document.createElement('p');
    reason.textContent = product.reason;
    row.append(title, reason);
    try {
      const url = new URL(product.sourceUrl);
      if (['http:', 'https:'].includes(url.protocol)) {
        const link = document.createElement('a');
        link.href = url.href;
        link.textContent = '查看来源页面';
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        row.append(link);
      }
    } catch {}
    return row;
  }));
}

function renderEvidence(result) {
  trace.hidden = result.steps.length === 0;
  trace.querySelector('ol').replaceChildren(...result.steps.map(step => {
    const item = document.createElement('li');
    item.textContent = step;
    return item;
  }));
  findings.hidden = result.findings.length === 0;
  findings.querySelector('ol').replaceChildren(...result.findings.map(finding => {
    const item = document.createElement('li');
    const heading = document.createElement('strong');
    heading.textContent = finding.title;
    const quote = document.createElement('p');
    quote.textContent = finding.quote;
    item.append(heading, quote);
    try {
      const url = new URL(finding.sourceUrl);
      if (['http:', 'https:'].includes(url.protocol)) {
        const link = document.createElement('a');
        link.href = url.href;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.textContent = '查看原文';
        item.append(link);
      }
    } catch {}
    return item;
  }));
}

function renderAnswer(answer) {
  answerPanel.querySelector('p').textContent = answer.summary;
  answerPanel.querySelector('ul').replaceChildren(...answer.points.map(point => {
    const item = document.createElement('li');
    item.textContent = point.text;
    for (const [index, sourceUrl] of point.sources.entries()) {
      try {
        const url = new URL(sourceUrl);
        if (!['http:', 'https:'].includes(url.protocol)) continue;
        const link = document.createElement('a');
        link.href = url.href;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.textContent = '来源 ' + (index + 1);
        item.append(' ', link);
      } catch {}
    }
    return item;
  }));
  answerPanel.hidden = false;
}

for (const field of [requirement, source]) {
  field.addEventListener('input', () => {
    responseController?.abort();
    clearTimeout(responseTimer);
    clearPlan();
    feedback.textContent = '';
  });
}

dialog.querySelector('#save-task').addEventListener('click', () => {
  saveDraft();
  feedback.textContent = '草稿已保存';
});

dialog.querySelector('#task-form').addEventListener('submit', event => {
  event.preventDefault();
  if (!requirement.value.trim()) {
    feedback.textContent = '请填写任务需求';
    return;
  }
  const task = saveDraft();
  const view = activeTask.view;
  const canvas = view?.document.getElementById('remote-canvas');
  if (!canvas?.dataset.frameAt || new URLSearchParams(view.location.search).get('sessionId') !== task.sessionId) {
    feedback.textContent = '任务已保存。请等待此会话的实时画面加载后再导出。';
    return;
  }
  const context = {
    ...task,
    screenshot: {
      receivedAt: canvas.dataset.frameAt,
      width: canvas.width,
      height: canvas.height,
      viewportWidth: Number(canvas.dataset.viewportWidth),
      viewportHeight: Number(canvas.dataset.viewportHeight),
      image: canvas.toDataURL('image/png'),
    },
  };
  const url = URL.createObjectURL(new Blob([JSON.stringify(context, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = task.sessionName + '-AI任务.json';
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  feedback.textContent = '任务和当前页面截图已导出';
});
