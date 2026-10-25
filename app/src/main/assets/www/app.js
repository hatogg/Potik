(function () {
  'use strict';
  const A = window.Android;
  const $ = (id) => document.getElementById(id);
  const root = document.documentElement;

  let S = null;              // стан з нативу
  let running = false;
  let phase = 'idle';
  let dir = 'down';          // напрям поточного вимірювання
  let target = 0;            // цільова швидкість, Мбіт/с
  let shown = 0;             // показана (згладжена) швидкість
  let vel = 0;
  let scaleSet = null;
  const series = { down: [], up: [] };
  let peak = 1;
  let lastResult = null;
  let sheetKind = null;

  // ---------- одиниці та формат ----------
  const SCALES = {
    mbps: [[0, 5, 10, 50, 100, 250, 500, 1000], [0, 10, 50, 100, 500, 1000, 2500, 5000]],
    mbs: [[0, 1, 2, 5, 10, 25, 50, 125], [0, 2, 5, 10, 50, 125, 300, 625]],
  };
  const unitName = () => (S && S.prefs.units === 'mbs' ? 'МБ/с' : 'Мбіт/с');
  const conv = (mbps) => (S && S.prefs.units === 'mbs' ? mbps / 8 : mbps);
  function fmt(v) {
    if (v == null || isNaN(v)) return '—';
    if (v >= 1000) return Math.round(v).toLocaleString('uk-UA').replace(/ /g, ' ');
    if (v >= 100) return v.toFixed(1).replace('.', ',');
    if (v >= 10) return v.toFixed(1).replace('.', ',');
    return v.toFixed(2).replace('.', ',');
  }
  const fmtMs = (v) => (v == null || isNaN(v) ? '—' : v < 10 ? v.toFixed(1).replace('.', ',') : String(Math.round(v)));
  function fmtBytes(b) {
    if (b >= 1e9) return (b / 1e9).toFixed(2).replace('.', ',') + ' ГБ';
    if (b >= 1e6) return Math.round(b / 1e6) + ' МБ';
    return Math.round(b / 1e3) + ' КБ';
  }
  function plural(n, a, b, c) { const m10 = n % 10, m100 = n % 100; if (m10 === 1 && m100 !== 11) return a; if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return b; return c; }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }
  function toast(msg) { const t = $('toast'); t.textContent = msg; t.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), 2600); }
  function when(t) {
    const d = new Date(t), n = new Date(), p = (x) => String(x).padStart(2, '0');
    const hm = p(d.getHours()) + ':' + p(d.getMinutes());
    if (d.toDateString() === n.toDateString()) return 'сьогодні о ' + hm;
    const y = new Date(n); y.setDate(n.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return 'учора о ' + hm;
    return p(d.getDate()) + '.' + p(d.getMonth() + 1) + (d.getFullYear() !== n.getFullYear() ? '.' + d.getFullYear() : '') + ' о ' + hm;
  }

  // ---------- іконки ----------
  const IC = {
    wifi: '<svg viewBox="0 0 24 24"><path d="M2.5 9a14 14 0 0 1 19 0M5.5 12.3a9.5 9.5 0 0 1 13 0M8.6 15.5a5 5 0 0 1 6.8 0"/><circle cx="12" cy="19" r="1.2"/></svg>',
    cell: '<svg viewBox="0 0 24 24"><path d="M5 19v-3M9.7 19v-6M14.3 19v-9M19 19V6"/></svg>',
    eth: '<svg viewBox="0 0 24 24"><path d="M5 9h14v8H5zM8 17v3M12 17v3M16 17v3M9 9V6h6v3"/></svg>',
    none: '<svg viewBox="0 0 24 24"><path d="M4 4l16 16M2.5 9a14 14 0 0 1 6-3.4M21.5 9a14 14 0 0 0-8.3-3.9M8.6 15.5a5 5 0 0 1 4.6-1.3"/></svg>',
    down: '<svg viewBox="0 0 24 24" style="stroke:var(--down)"><path d="M12 4v15M6 13l6 6 6-6"/></svg>',
    up: '<svg viewBox="0 0 24 24" style="stroke:var(--up)"><path d="M12 20V5M6 11l6-6 6 6"/></svg>',
    video: '<svg viewBox="0 0 24 24"><rect x="3" y="6" width="13" height="12" rx="3"/><path d="M16 10.5l5-3v9l-5-3"/></svg>',
    call: '<svg viewBox="0 0 24 24"><circle cx="12" cy="9" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>',
    game: '<svg viewBox="0 0 24 24"><path d="M7.5 8h9a4.5 4.5 0 0 1 4.2 6.1l-.9 2.4a2.3 2.3 0 0 1-3.9.6L14.2 15H9.8l-1.7 2.1a2.3 2.3 0 0 1-3.9-.6l-.9-2.4A4.5 4.5 0 0 1 7.5 8zM8 10.5v3M6.5 12h3"/><circle cx="15.5" cy="11" r=".6"/><circle cx="17" cy="13" r=".6"/></svg>',
  };

  // ---------- геометрія приладу (одиниці viewBox 300) ----------
  const C = 150, R = 100, A0 = 135, SWEEP = 270;
  const rad = (d) => (d * Math.PI) / 180;
  const pt = (r, a) => [C + r * Math.cos(rad(a)), C + r * Math.sin(rad(a))];
  function frac(v) {
    const s = scaleSet;
    if (v <= 0) return 0;
    const n = s.length - 1;
    for (let i = 0; i < n; i++) {
      if (v <= s[i + 1]) return (i + (v - s[i]) / (s[i + 1] - s[i])) / n;
    }
    return 1;
  }
  function drawScale() {
    const svg = $('scale');
    let h = '';
    const n = scaleSet.length - 1;
    for (let i = 0; i <= n * 4; i++) {
      const a = A0 + (SWEEP * i) / (n * 4);
      const major = i % 4 === 0;
      const [x1, y1] = pt(major ? 115 : 116.5, a), [x2, y2] = pt(major ? 121 : 119, a);
      h += `<line x1="${x1 + 0.7}" y1="${y1 + 0.7}" x2="${x2 + 0.7}" y2="${y2 + 0.7}" stroke="var(--sl)" stroke-width="${major ? 2 : 1.2}"/>`;
      h += `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="var(--ink3)" stroke-width="${major ? 2 : 1.2}"/>`;
    }
    for (let i = 0; i <= n; i++) {
      const a = A0 + (SWEEP * i) / n;
      const [x, y] = pt(133, a);
      const v = scaleSet[i];
      const label = v >= 1000 ? (v / 1000).toString().replace('.', ',') + 'k' : String(v).replace('.', ',');
      h += `<text class="eng-l" x="${x + 0.8}" y="${y + 0.9}">${label}</text><text class="eng-d" x="${x}" y="${y}">${label}</text>`;
    }
    svg.innerHTML = h;
  }
  function setScaleFor(maxMbps) {
    const sets = SCALES[S && S.prefs.units === 'mbs' ? 'mbs' : 'mbps'];
    const want = conv(maxMbps) > sets[0][sets[0].length - 1] * 0.95 ? sets[1] : sets[0];
    if (want !== scaleSet) { scaleSet = want; drawScale(); }
  }

  function renderDial(v) {
    const f = Math.max(0, Math.min(1, frac(conv(v))));
    const a = A0 + SWEEP * f;
    const arc = $('flowArc');
    if (f < 0.002 || (!running && f < 0.02)) { arc.setAttribute('d', ''); } else {
      const [x0, y0] = pt(R, A0), [x1, y1] = pt(R, a);
      arc.setAttribute('d', `M${x0.toFixed(2)} ${y0.toFixed(2)} A${R} ${R} 0 ${SWEEP * f > 180 ? 1 : 0} 1 ${x1.toFixed(2)} ${y1.toFixed(2)}`);
    }
    const [bx, by] = pt(R, a);
    const b = $('bead');
    b.style.left = (bx / 3) + '%';
    b.style.top = (by / 3) + '%';
    $('puckNum').textContent = fmt(conv(v));
  }

  // Плавне наближення стрілки до цілі (критично демпфована пружина)
  // Інтегруємо дрібними кроками за реальний час — на повільних екранах стрілка не «відстає»
  let lastT = 0;
  function loop(t) {
    let dt = lastT ? Math.min(0.5, (t - lastT) / 1000) : 0.016;
    lastT = t;
    const k = 60, d = 2 * Math.sqrt(k);
    while (dt > 0) {
      const h = Math.min(0.016, dt);
      vel += (k * (target - shown) - d * vel) * h;
      shown += vel * h;
      dt -= h;
    }
    if (shown < 0) { shown = 0; vel = 0; }
    if (!running && target === 0 && shown < 0.05) { shown = 0; vel = 0; }
    renderDial(shown);
    requestAnimationFrame(loop);
  }

  // ---------- графік ----------
  function drawGraph() {
    const W = 320, H = 90;
    const all = series.down.concat(series.up);
    $('graphEmpty').classList.toggle('hidden', all.length > 0);
    const max = Math.max(peak, 1) * 1.08;
    const path = (arr, x0, x1) => {
      if (!arr.length) return ['', ''];
      let line = '';
      arr.forEach((p, i) => {
        const x = x0 + (x1 - x0) * p.t;
        const y = H - (H - 4) * Math.min(1, p.v / max);
        line += (i ? 'L' : 'M') + x.toFixed(1) + ' ' + y.toFixed(1);
      });
      const last = arr[arr.length - 1];
      const area = line + `L${(x0 + (x1 - x0) * last.t).toFixed(1)} ${H}L${(x0 + (x1 - x0) * arr[0].t).toFixed(1)} ${H}Z`;
      return [line, area];
    };
    const [ld, ad] = path(series.down, 0, 156);
    const [lu, au] = path(series.up, 164, 320);
    $('gLineDown').setAttribute('d', ld); $('gAreaDown').setAttribute('d', ad);
    $('gLineUp').setAttribute('d', lu); $('gAreaUp').setAttribute('d', au);
  }

  // ---------- мережа ----------
  function renderNet() {
    const n = (S && S.net) || {};
    $('connIc').innerHTML = IC[n.type] || IC.none;
    const m = S && S.meta;
    let name = n.title || 'Немає з\'єднання';
    let sub = n.sub || '';
    if (m && m.isp) { name = m.isp; sub = [n.title, n.sub].filter(Boolean).join(', '); }
    $('connName').textContent = name;
    $('connSub').textContent = sub;
    const foot = [];
    if (m && m.colo) foot.push('Сервер Cloudflare: ' + (m.coloCity ? m.coloCity + ' (' + m.colo + ')' : m.colo));
    if (m && m.ip) foot.push('Ваша IP-адреса ' + m.ip);
    $('foot').innerHTML = foot.map(esc).join('<br>');
  }

  // ---------- показники ----------
  function setActive(k) {
    document.querySelectorAll('.cell').forEach((c) => c.classList.toggle('active', c.dataset.k === k));
  }
  function renderUnits() {
    $('uDown').textContent = unitName();
    $('uUp').textContent = unitName();
    $('puckUnit').textContent = unitName();
    $('dialFoot').textContent = unitName();
  }

  // ---------- оцінка «чого вистачить» (методика Cloudflare AIM) ----------
  const th = (dom, rng) => (v) => { let i = 0; while (i < dom.length && v >= dom[i]) i++; return rng[i]; };
  const SC = {
    latency: th([10, 20, 50, 100, 500], [20, 10, 5, 0, -10, -20]),
    loaded: th([10, 20, 50, 100, 500], [20, 10, 5, 0, -10, -20]),
    jitter: th([10, 20, 100, 500], [10, 5, 0, -10, -20]),
    down: th([1e6, 10e6, 50e6, 100e6], [0, 5, 10, 20, 30]),
  };
  const VERDICT = ['погано', 'слабо', 'нормально', 'добре', 'чудово'];
  function scores(r) {
    const inc = Math.max(0, Math.max(r.loadedDown || r.ping, r.loadedUp || r.ping) - r.ping);
    const p = { latency: SC.latency(r.ping), loaded: SC.loaded(inc), jitter: SC.jitter(r.jitter), down: SC.down(r.down * 1e6) };
    const cls = (sum, t) => { let i = 0; sum = Math.max(0, sum); while (i < t.length && sum >= t[i]) i++; return i; };
    return {
      stream: cls(p.latency + p.down + p.loaded, [15, 20, 40, 60]),
      rtc: cls(p.latency + p.jitter + p.loaded, [5, 15, 25, 40]),
      game: cls(p.latency + p.loaded, [5, 15, 25, 30]),
    };
  }
  function renderAfter(r) {
    const s = scores(r);
    const item = (ic, name, idx) => {
      const c = idx >= 3 ? 'g' : idx === 2 ? 'm' : 'b';
      const segs = [1, 2, 3, 4].map((i) => `<i class="${i <= idx ? 'on' : ''}"></i>`).join('');
      return `<div class="use ${c}">${IC[ic]}<b>${name}</b><div class="meter">${segs}</div><span class="verdict">${VERDICT[idx]}</span></div>`;
    };
    $('uses').innerHTML = item('video', 'Відео й стрімінг', s.stream) + item('call', 'Відео&shy;дзвінки', s.rtc) + item('game', 'Онлайн-ігри', s.game);
    const ex = [];
    if (r.loadedDown) ex.push('Пінг під час завантаження <b>' + fmtMs(r.loadedDown) + ' мс</b>, під час вивантаження <b>' + fmtMs(r.loadedUp) + ' мс</b>');
    if (r.bytes) ex.push('Витрачено трафіку: <b>' + fmtBytes(r.bytes) + '</b>');
    $('extra').innerHTML = ex.join('<br>');
    $('after').classList.remove('hidden');
  }

  // ---------- керування ----------
  function resetView() {
    series.down = []; series.up = []; peak = 1;
    ['vPing', 'vJitter', 'vDown', 'vUp'].forEach((id) => ($(id).textContent = '—'));
    $('after').classList.add('hidden');
    drawGraph();
  }
  function start() {
    if (running) return;
    resetView();
    running = true;
    target = 0;
    setScaleFor(0);
    $('puck').classList.add('latched');
    $('bead').classList.add('on');
    $('puckDir').innerHTML = '';
    $('phase').innerHTML = 'З\'єднуюсь із сервером… <button class="stop" id="stopBtn">Зупинити</button>';
    $('stopBtn').onclick = (e) => { e.stopPropagation(); A.stop(); };
    A.haptic('press');
    A.start();
  }
  function finish(ok) {
    running = false;
    target = 0;
    $('puck').classList.remove('latched');
    $('bead').classList.remove('on');
    setActive(null);
    $('puckIdle').textContent = ok ? 'Ще раз' : 'Почати';
  }

  $('puck').onclick = () => { if (!running) start(); };

  // ---------- аркуші ----------
  function openSheet(kind, html) { sheetKind = kind; $('sheetBody').innerHTML = html; $('sheet').classList.remove('hidden'); }
  function closeSheet() { sheetKind = null; $('sheet').classList.add('hidden'); }
  $('sheet').addEventListener('click', (e) => { if (e.target.hasAttribute('data-close')) closeSheet(); });

  function histRow(r, i) {
    return `<button class="row" data-i="${i}">${IC[r.net && r.net.type] ? IC[r.net.type].replace('<svg', '<svg class="ic"') : ''}
      <span class="grow"><b>${esc(r.meta && r.meta.isp ? r.meta.isp : (r.net && r.net.title) || 'Мережа')}</b><small>${when(r.time)}</small></span>
      <span class="nums"><span><i class="dot d"></i>${fmt(conv(r.down))}</span><span><i class="dot u"></i>${fmt(conv(r.up))}</span><span class="p">${fmtMs(r.ping)}</span></span></button>`;
  }
  function historySheet() {
    const list = JSON.parse(A.getHistory());
    if (!list.length) {
      openSheet('hist', '<h3>Історія</h3><div class="well"><div class="empty">Тут з\'являтимуться ваші вимірювання. Натисніть «Почати» на головному екрані.</div></div>');
      return;
    }
    openSheet('hist', `<h3>Історія</h3><p class="sub">${list.length} ${plural(list.length, 'вимірювання', 'вимірювання', 'вимірювань')}. Швидкість у ${unitName()}, пінг у мс.</p><div class="well">${list.map(histRow).join('')}</div>
      <div class="btn-row"><button class="pill danger" id="clearHist">Очистити історію</button></div>`);
    $('sheetBody').querySelectorAll('.row').forEach((b) => (b.onclick = () => detailSheet(list[+b.dataset.i])));
    $('clearHist').onclick = () => {
      openSheet('confirm', '<h3>Очистити історію?</h3><p class="sub">Усі збережені вимірювання буде видалено з телефона.</p><div class="btn-row"><button class="pill" data-close>Скасувати</button><button class="pill danger" id="doClear">Очистити</button></div>');
      $('doClear').onclick = () => { A.clearHistory(); closeSheet(); toast('Історію очищено'); };
    };
  }
  function detailSheet(r) {
    const kv = [];
    kv.push(['Коли', when(r.time)]);
    kv.push(['Мережа', [r.net && r.net.title, r.net && r.net.sub].filter(Boolean).join(', ') || '—']);
    if (r.meta && r.meta.isp) kv.push(['Провайдер', r.meta.isp]);
    if (r.meta && r.meta.colo) kv.push(['Сервер', 'Cloudflare, ' + (r.meta.coloCity || r.meta.colo)]);
    kv.push(['Пінг', fmtMs(r.ping) + ' мс, джитер ' + fmtMs(r.jitter) + ' мс']);
    if (r.loadedDown) kv.push(['Пінг під навантаженням', fmtMs(r.loadedDown) + ' / ' + fmtMs(r.loadedUp) + ' мс']);
    if (r.bytes) kv.push(['Трафік', fmtBytes(r.bytes)]);
    openSheet('detail', `<h3>Результат</h3><p class="sub">${esc(when(r.time))}</p>
      <div class="detail-big"><div><span><i class="dot d"></i>Завантаження</span><b>${fmt(conv(r.down))}</b><small>${unitName()}</small></div>
      <div><span><i class="dot u"></i>Вивантаження</span><b>${fmt(conv(r.up))}</b><small>${unitName()}</small></div></div>
      <div class="kv">${kv.map(([k, v]) => `<div><span>${esc(k)}:</span> ${esc(v)}</div>`).join('')}</div>
      <div class="btn-row"><button class="pill" id="dShare">Поділитися</button><button class="pill danger" id="dDel">Видалити</button></div>`);
    $('dShare').onclick = () => share(r);
    $('dDel').onclick = () => { A.deleteResult(String(r.id)); historySheet(); toast('Вимірювання видалено'); };
  }
  function settingsSheet() {
    const p = S.prefs;
    const seg = (key, opts) => `<div class="seg" data-key="${key}">${opts.map(([v, t]) => `<button data-v="${v}" class="${String(p[key]) === String(v) ? 'on' : ''}">${t}</button>`).join('')}</div>`;
    openSheet('set', `<h3>Налаштування</h3>
      <div class="lab">Одиниці швидкості</div>${seg('units', [['mbps', 'Мбіт/с'], ['mbs', 'МБ/с']])}
      <div class="lab">Тема</div>${seg('theme', [['system', 'Як у системі'], ['light', 'Світла'], ['dark', 'Темна']])}
      <div class="lab">На мобільному інтернеті<small>Економний режим вимірює коротше й витрачає до 60 МБ</small></div>${seg('mobileMode', [['full', 'Повний тест'], ['eco', 'Економний']])}
      <div class="lab">Вібровідгук</div>${seg('haptics', [['true', 'Увімкнено'], ['false', 'Вимкнено']])}
      <p class="sub">Потік ${esc(S.version)}. Вимірювання через сервери Cloudflare speed.cloudflare.com. Без реклами й трекерів.</p>`);
    $('sheetBody').querySelectorAll('.seg').forEach((sg) => {
      sg.onclick = (e) => {
        const b = e.target.closest('button'); if (!b) return;
        const key = sg.dataset.key, v = b.dataset.v;
        A.setPref(key, v);
        S.prefs[key] = key === 'haptics' ? v === 'true' : v;
        sg.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b));
        if (key === 'theme') applyTheme();
        if (key === 'units') { scaleSet = null; setScaleFor(peak); renderUnits(); if (lastResult) showResult(lastResult, false); }
      };
    });
  }
  $('histBtn').onclick = historySheet;
  $('setBtn').onclick = settingsSheet;

  // ---------- поділитися: картка-зображення ----------
  function share(r) {
    const W = 1080, H = 1080, c = document.createElement('canvas');
    c.width = W; c.height = H;
    const x = c.getContext('2d');
    const css = getComputedStyle(root);
    const col = (n) => css.getPropertyValue(n).trim();
    x.fillStyle = col('--clay'); x.fillRect(0, 0, W, H);
    const soft = (fn, inset) => {
      x.save(); x.shadowColor = col('--sd'); x.shadowBlur = 40; x.shadowOffsetX = 18; x.shadowOffsetY = 18; fn(); x.restore();
      x.save(); x.shadowColor = col('--sl'); x.shadowBlur = 40; x.shadowOffsetX = -18; x.shadowOffsetY = -18; fn(); x.restore();
    };
    const rr = (X, Y, w, h, r) => { x.beginPath(); x.roundRect(X, Y, w, h, r); x.fillStyle = col('--clay'); x.fill(); };
    soft(() => rr(90, 250, 420, 400, 56)); soft(() => rr(570, 250, 420, 400, 56));
    x.fillStyle = col('--ink'); x.font = '760 72px Geo'; x.fillText('Потік', 90, 160);
    x.fillStyle = col('--ink2'); x.font = '450 34px Geo'; x.fillText(when(r.time), 90, 210);
    const big = (X, label, v, colr) => {
      x.fillStyle = colr; x.beginPath(); x.arc(X + 52, 330, 11, 0, 7); x.fill();
      x.fillStyle = col('--ink2'); x.font = '500 34px Geo'; x.fillText(label, X + 76, 342);
      x.fillStyle = col('--ink'); x.font = '280 128px Geo'; x.fillText(fmt(conv(v)), X + 40, 510);
      x.fillStyle = col('--ink3'); x.font = '500 34px Geo'; x.fillText(unitName(), X + 44, 580);
    };
    big(90, 'Завантаження', r.down, col('--down')); big(570, 'Вивантаження', r.up, col('--up'));
    x.fillStyle = col('--ink'); x.font = '500 40px Geo';
    x.fillText('Пінг ' + fmtMs(r.ping) + ' мс   Джитер ' + fmtMs(r.jitter) + ' мс', 90, 760);
    x.fillStyle = col('--ink2'); x.font = '450 34px Geo';
    const who = [r.meta && r.meta.isp, r.net && r.net.title].filter(Boolean).join(', ');
    if (who) x.fillText(who, 90, 820);
    if (r.meta && r.meta.colo) x.fillText('Сервер Cloudflare: ' + (r.meta.coloCity || r.meta.colo), 90, 870);
    x.fillStyle = col('--ink3'); x.font = '450 30px Geo'; x.fillText('Виміряно в застосунку «Потік»', 90, 990);
    const text = `Потік: ↓ ${fmt(conv(r.down))} ↑ ${fmt(conv(r.up))} ${unitName()}, пінг ${fmtMs(r.ping)} мс` + (who ? ` (${who})` : '');
    A.share(c.toDataURL('image/png').split(',')[1], text);
  }
  $('shareBtn').onclick = () => lastResult && share(lastResult);

  // ---------- тема ----------
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  function applyTheme() {
    const t = S ? S.prefs.theme : 'system';
    const dark = t === 'dark' || (t === 'system' && mq.matches);
    root.classList.toggle('dark', dark);
    A.setBars && A.setBars(dark);
  }
  mq.addEventListener && mq.addEventListener('change', applyTheme);

  // ---------- результат ----------
  function showResult(r, fresh) {
    lastResult = r;
    $('vPing').textContent = fmtMs(r.ping);
    $('vJitter').textContent = fmtMs(r.jitter);
    $('vDown').textContent = fmt(conv(r.down));
    $('vUp').textContent = fmt(conv(r.up));
    renderAfter(r);
    if (fresh) {
      $('phase').innerHTML = 'Готово. <b>↓ ' + fmt(conv(r.down)) + '</b> і <b>↑ ' + fmt(conv(r.up)) + '</b> ' + unitName();
    }
  }

  // ---------- події з нативу ----------
  window.Potik = {
    onEvent(type, d) {
      switch (type) {
        case 'net': S.net = d; renderNet(); break;
        case 'meta': S.meta = d; renderNet(); break;
        case 'phase':
          phase = d.phase;
          if (d.phase === 'latency') { setActive('ping'); $('phase').firstChild.textContent = 'Вимірюю затримку… '; }
          if (d.phase === 'download' || d.phase === 'upload') {
            dir = d.phase === 'download' ? 'down' : 'up';
            setActive(dir);
            target = 0;
            $('flowArc').setAttribute('stroke', dir === 'down' ? 'url(#gDown)' : 'url(#gUp)');
            $('bead').classList.toggle('up', dir === 'up');
            $('puckDir').innerHTML = IC[dir];
            $('phase').firstChild.textContent = (dir === 'down' ? 'Завантаження' : 'Вивантаження') + ', ' + d.streams + ' ' + plural(d.streams, 'потік', 'потоки', 'потоків') + ' ';
            A.haptic('tick');
          }
          break;
        case 'ping':
          $('vPing').textContent = fmtMs(d.ping);
          $('vJitter').textContent = fmtMs(d.jitter);
          break;
        case 'speed': {
          if (d.dir === 'up' && d.progress < 0.08) break; // на старті вивантаження буфери дають хибний сплеск
          target = d.mbps;
          if (d.mbps > peak) peak = d.mbps;
          setScaleFor(Math.max(peak, d.mbps));
          const arr = series[d.dir];
          arr.push({ t: d.progress, v: d.mbps });
          drawGraph();
          $(d.dir === 'down' ? 'vDown' : 'vUp').textContent = fmt(conv(d.avg || d.mbps));
          break;
        }
        case 'result':
          finish(true);
          showResult(d, true);
          A.haptic('done');
          break;
        case 'error':
          finish(false);
          $('phase').textContent = d.message;
          break;
        case 'stopped':
          finish(false);
          $('phase').textContent = 'Вимірювання зупинено';
          break;
      }
    },
    back() {
      if (sheetKind) { closeSheet(); return true; }
      return false;
    },
  };

  // ---------- старт ----------
  S = JSON.parse(A.getState());
  applyTheme();
  renderUnits();
  setScaleFor(0);
  renderNet();
  drawGraph();
  if (S.last) showResult(S.last, false);
  if (S.last) $('phase').textContent = 'Останнє вимірювання ' + when(S.last.time);
  requestAnimationFrame(loop);
})();
