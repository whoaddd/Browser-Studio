export function selectReference(view, onSelection) {
  const canvas = view?.document.getElementById('remote-canvas');
  if (!canvas?.dataset.frameAt) throw new Error('请等待参考页面的画面加载');
  const doc = view.document;
  doc.querySelector('#reference-selection')?.remove();
  const screen = canvas.getBoundingClientRect();
  if (screen.width < 10 || screen.height < 10) throw new Error('当前画面太小，无法圈选');

  const frozen = doc.createElement('canvas');
  frozen.width = canvas.width;
  frozen.height = canvas.height;
  frozen.getContext('2d').drawImage(canvas, 0, 0);

  const overlay = doc.createElement('canvas');
  overlay.id = 'reference-selection';
  overlay.width = frozen.width;
  overlay.height = frozen.height;
  overlay.tabIndex = 0;
  overlay.setAttribute('aria-label', '拖动画圈，按 Escape 取消');
  Object.assign(overlay.style, {
    position: 'fixed', left: screen.left + 'px', top: screen.top + 'px',
    width: screen.width + 'px', height: screen.height + 'px', zIndex: '10000',
    cursor: 'crosshair', touchAction: 'none',
  });
  const context = overlay.getContext('2d');
  context.drawImage(frozen, 0, 0);

  const position = event => ({
    x: Math.max(0, Math.min(overlay.width, (event.clientX - screen.left) * overlay.width / screen.width)),
    y: Math.max(0, Math.min(overlay.height, (event.clientY - screen.top) * overlay.height / screen.height)),
  });
  let origin, pointer;
  let cancel;
  const cleanup = () => {
    overlay.remove();
    view.removeEventListener('resize', cancel);
    doc.removeEventListener('scroll', cancel, true);
  };
  cancel = () => { cleanup(); onSelection(null); };
  overlay.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    event.preventDefault();
    event.stopPropagation();
    cancel();
  });
  overlay.addEventListener('pointerdown', event => {
    if (!event.isPrimary || event.button !== 0) return;
    event.preventDefault();
    origin = position(event);
    pointer = event.pointerId;
    overlay.setPointerCapture(pointer);
  });
  overlay.addEventListener('pointermove', event => {
    if (!origin || event.pointerId !== pointer) return;
    const end = position(event);
    context.drawImage(frozen, 0, 0);
    context.beginPath();
    context.ellipse((origin.x + end.x) / 2, (origin.y + end.y) / 2,
      Math.abs(origin.x - end.x) / 2, Math.abs(origin.y - end.y) / 2, 0, 0, Math.PI * 2);
    context.strokeStyle = '#e33b39';
    context.lineWidth = 3 * (overlay.width / screen.width);
    context.stroke();
  });
  overlay.addEventListener('pointerup', event => {
    if (!origin || event.pointerId !== pointer) return;
    const end = position(event);
    cleanup();
    const x = Math.floor(Math.min(origin.x, end.x));
    const y = Math.floor(Math.min(origin.y, end.y));
    const width = Math.ceil(Math.max(origin.x, end.x)) - x;
    const height = Math.ceil(Math.max(origin.y, end.y)) - y;
    if (width < 8 || height < 8) return onSelection(null);
    onSelection({
      screenshot: encodeRegion(doc, frozen, 0, 0, frozen.width, frozen.height, 900),
      crop: encodeRegion(doc, frozen, x, y, width, height, 440),
      region: { x, y, width, height, imageWidth: frozen.width, imageHeight: frozen.height },
    });
  });
  overlay.addEventListener('pointercancel', cancel);
  view.addEventListener('resize', cancel, { once: true });
  doc.addEventListener('scroll', cancel, { capture: true, once: true });
  doc.body.append(overlay);
  overlay.focus({ preventScroll: true });
  return cancel;
}

function encodeRegion(doc, canvas, x, y, width, height, limit) {
  const scale = Math.min(1, limit / Math.max(width, height));
  const image = doc.createElement('canvas');
  image.width = Math.max(1, Math.round(width * scale));
  image.height = Math.max(1, Math.round(height * scale));
  image.getContext('2d').drawImage(canvas, x, y, width, height, 0, 0, image.width, image.height);
  return image.toDataURL('image/jpeg', 0.66);
}
