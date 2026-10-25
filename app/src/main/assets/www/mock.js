// Заглушка нативного мосту: імітує вимірювання, щоб інтерфейс можна було відкрити в браузері.
(function () {
  const prefs = { units: 'mbps', theme: 'system', mobileMode: 'full', haptics: true };
  const params = new URLSearchParams(location.search);
  if (params.get('theme')) prefs.theme = params.get('theme');
  const net = { type: 'wifi', title: 'Wi‑Fi 5 ГГц', sub: 'зв\'язок 866 Мбіт/с, сигнал добрий' };
  const meta = { ip: '93.170.44.12', isp: 'Київстар', city: 'Київ', colo: 'WAW', coloCity: 'Варшава', country: 'UA' };
  const history = [
    { id: 3, time: Date.now() - 3600e3 * 26, ping: 14.2, jitter: 2.1, down: 212.4, up: 88.6, loadedDown: 41, loadedUp: 63, bytes: 412e6, net, meta },
    { id: 2, time: Date.now() - 3600e3 * 50, ping: 38, jitter: 9.4, down: 42.7, up: 11.3, loadedDown: 160, loadedUp: 240, bytes: 96e6, net: { type: 'cell', title: 'Мобільний 4G', sub: 'lifecell' }, meta: { ...meta, isp: 'lifecell' } },
  ];
  const emit = (t, d) => setTimeout(() => window.Potik && Potik.onEvent(t, d), 0);
  let timer = null;
  function run() {
    let t = 0;
    const steps = [];
    emit('meta', meta);
    emit('phase', { phase: 'latency' });
    for (let i = 1; i <= 10; i++) steps.push([i * 120, () => emit('ping', { ping: 15 - i * 0.08, jitter: 2 + Math.random() })]);
    const bw = (name, peak, start, dur, n) => {
      steps.push([start, () => emit('phase', { phase: name, streams: n })]);
      let avg = 0, k = 0;
      for (let i = 1; i <= dur / 100; i++) {
        steps.push([start + i * 100, () => {
          const p = i / (dur / 100);
          const v = peak * (1 - Math.exp(-p * 7)) * (0.9 + Math.random() * 0.15);
          k++; avg += (v - avg) / k;
          emit('speed', { dir: name === 'download' ? 'down' : 'up', mbps: v, avg: avg, progress: p });
        }]);
      }
    };
    bw('download', 228, 1400, 5000, 6);
    bw('upload', 91, 6800, 4500, 4);
    steps.push([11600, () => {
      const r = { id: Date.now(), time: Date.now(), ping: 14.2, jitter: 2.4, down: 224.6, up: 89.1, loadedDown: 38, loadedUp: 71, bytes: 438e6, net, meta };
      history.unshift(r);
      emit('result', r);
    }]);
    const t0 = Date.now();
    timer = setInterval(() => {
      const now = Date.now() - t0;
      while (steps.length && steps[0][0] <= now) steps.shift()[1]();
      if (!steps.length) clearInterval(timer);
    }, 30);
  }
  window.Android = {
    hello: () => true,
    getState: () => JSON.stringify({ prefs, net, meta: null, version: '1.0 (браузер)', last: params.get('last') ? history[0] : null }),
    start: () => run(),
    stop: () => { clearInterval(timer); emit('stopped', {}); },
    getHistory: () => JSON.stringify(history),
    deleteResult: (id) => { const i = history.findIndex((h) => String(h.id) === id); if (i >= 0) history.splice(i, 1); },
    clearHistory: () => { history.length = 0; },
    setPref: (k, v) => { prefs[k] = k === 'haptics' ? v === 'true' : v; },
    share: () => {},
    haptic: () => {},
    setBars: () => {},
  };
})();
