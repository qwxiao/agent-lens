'use strict';

const state = { timer: null };

function esc(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function fmtDuration(ms) {
  if (ms == null) return '—';
  if (ms < 1) return `${(ms * 1000).toFixed(0)} µs`;
  if (ms < 1000) return `${ms.toFixed(ms < 10 ? 1 : 0)} ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(2)} s`;
  return `${Math.floor(ms / 60000)}m ${((ms % 60000) / 1000).toFixed(0)} s`;
}

function fmtCost(cost) {
  if (cost == null) return '—';
  const n = Number(cost);
  if (n === 0) return '$0';
  if (n < 0.01) return `$${n.toFixed(6)}`;
  if (n < 1) return `$${n.toFixed(4)}`;
  return `$${n.toFixed(2)}`;
}

function fmtTokens(n) {
  return n >= 10000 ? `${(n / 1000).toFixed(1)}k` : String(n);
}

function fmtTime(iso) {
  return new Date(iso).toLocaleString(undefined, {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

async function getJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url} responded ${res.status}`);
  return res.json();
}

function chip(cls, text) {
  return `<span class="chip ${cls}">${esc(text)}</span>`;
}

/* ---------- trace list ---------- */

async function loadList() {
  const errorEl = document.querySelector('#list-error');
  try {
    const data = await getJson('/api/traces');
    errorEl.hidden = true;
    const tbody = document.querySelector('#traces-body');
    tbody.innerHTML = '';
    document.querySelector('#empty-hint').hidden = data.traces.length > 0;
    document.querySelector('#trace-count').textContent = `${data.traces.length} of ${data.total}`;
    for (const t of data.traces) {
      const row = document.createElement('tr');
      row.className = 'trace-row';
      row.onclick = () => { location.hash = `#/traces/${encodeURIComponent(t.traceId)}`; };
      row.innerHTML = `
        <td class="mono">${esc(fmtTime(t.startTime))}</td>
        <td>${esc((t.services || []).join(', '))}</td>
        <td>${esc(t.rootSpanName ?? '')}</td>
        <td class="num">${t.spanCount}</td>
        <td class="num">${esc(fmtDuration(t.durationMs))}</td>
        <td class="num">${fmtTokens(t.inputTokens)} / ${fmtTokens(t.outputTokens)}</td>
        <td class="num ${t.costUsd == null ? 'unknown' : ''}">${esc(fmtCost(t.costUsd))}</td>`;
      tbody.appendChild(row);
    }
  } catch (err) {
    errorEl.textContent = `Cannot reach the API: ${err.message}`;
    errorEl.hidden = false;
  }
}

/* ---------- trace detail ---------- */

async function loadDetail(traceId) {
  const title = document.querySelector('#detail-title');
  const chips = document.querySelector('#detail-chips');
  try {
    const t = await getJson(`/api/traces/${encodeURIComponent(traceId)}`);
    title.textContent = t.rootSpanName ?? 'trace';
    chips.innerHTML = [
      chip('mono', t.traceId),
      chip('', `started ${fmtTime(t.startTime)}`),
      chip('', fmtDuration(t.durationMs)),
      chip('', `${t.spanCount} spans`),
      chip('', `${fmtTokens(t.inputTokens)} in / ${fmtTokens(t.outputTokens)} out tokens`),
      chip('cost', fmtCost(t.costUsd)),
      ...(t.models || []).map((m) => chip('model', m)),
    ].join('');
    renderWaterfall(t);
  } catch (err) {
    title.textContent = 'trace not found';
    chips.innerHTML = '';
    renderWaterfall({ spans: [] });
  }
}

function renderWaterfall(trace) {
  const container = document.querySelector('#waterfall');
  container.innerHTML = '';
  const spans = trace.spans || [];
  if (spans.length === 0) {
    container.textContent = 'No spans.';
    return;
  }
  const t0 = Date.parse(trace.startTime);
  const total = Math.max(Date.parse(trace.endTime) - t0, 1);

  const byId = new Map(spans.map((s) => [s.spanId, s]));
  const children = new Map();
  const roots = [];
  for (const s of spans) {
    const parent = s.parentSpanId && byId.has(s.parentSpanId) ? s.parentSpanId : null;
    if (parent == null) {
      roots.push(s);
    } else {
      children.set(parent, [...(children.get(parent) ?? []), s]);
    }
  }
  const depthOf = new Map();
  const ordered = [];
  const walk = (span, depth) => {
    depthOf.set(span.spanId, depth);
    ordered.push(span);
    for (const child of children.get(span.spanId) ?? []) walk(child, depth + 1);
  };
  roots.forEach((root) => walk(root, 0));

  for (const s of ordered) {
    const depth = depthOf.get(s.spanId);
    const left = ((Date.parse(s.startTime) - t0) / total) * 100;
    const width = Math.max((s.durationMs / total) * 100, 0.4);
    const badges = s.category !== 'span'
      ? `<span class="badge ${esc(s.category)}">${s.category === 'llm' ? 'LLM' : 'TOOL'}</span>`
      : '';
    const svc = s.serviceName && trace.services.length > 1
      ? `<span class="svc">${esc(s.serviceName)}</span>`
      : '';
    const row = document.createElement('div');
    row.className = 'wf-row';
    row.innerHTML = `
      <div class="wf-name" style="padding-left:${depth * 16}px" title="${esc(s.name)}">
        <span class="wf-label">${esc(s.name)}</span>${badges}${svc}
      </div>
      <div class="wf-track">
        <div class="wf-bar ${esc(s.category)}" style="left:${left}%;width:${width}%"></div>
      </div>
      <div class="wf-time">${esc(fmtDuration(s.durationMs))}</div>
      <div class="wf-cost">${s.costUsd != null ? esc(fmtCost(s.costUsd)) : ''}</div>`;
    row.onclick = () => {
      const panel = row.nextElementSibling;
      panel.hidden = !panel.hidden;
      row.classList.toggle('open', !panel.hidden);
    };

    const attrs = document.createElement('pre');
    attrs.className = 'wf-attrs';
    attrs.hidden = true;
    attrs.textContent = prettySpan(s);

    container.appendChild(row);
    container.appendChild(attrs);
  }
}

function prettySpan(span) {
  const attrs = span.attributes ?? {};
  const ordered = {};
  Object.keys(attrs)
    .sort((a, b) => {
      const ai = a.startsWith('gen_ai.') ? 0 : 1;
      const bi = b.startsWith('gen_ai.') ? 0 : 1;
      return ai - bi || a.localeCompare(b);
    })
    .forEach((k) => { ordered[k] = attrs[k]; });
  return JSON.stringify({
    span_id: span.spanId,
    kind: span.kind,
    status: span.statusCode,
    ...ordered,
  }, null, 2);
}

/* ---------- evals: datasets & runs ---------- */

const pickedRuns = new Set();

function fmtPassRate(rate) {
  return `${Math.round((rate ?? 0) * 100)}%`;
}

async function loadEvals() {
  pickedRuns.clear();
  const compareBtn = document.querySelector('#compare-btn');
  compareBtn.disabled = true;
  const [datasets, runs] = await Promise.all([getJson('/api/datasets'), getJson('/api/eval-runs')]);

  const dsBody = document.querySelector('#datasets-body');
  dsBody.innerHTML = '';
  document.querySelector('#datasets-empty').hidden = datasets.length > 0;
  for (const d of datasets) {
    const row = document.createElement('tr');
    row.innerHTML = `
      <td>${esc(d.name)}</td>
      <td class="muted">${esc(d.description ?? '')}</td>
      <td class="num">${d.caseCount}</td>
      <td class="mono">${esc(fmtTime(d.createdAt))}</td>`;
    dsBody.appendChild(row);
  }

  const runBody = document.querySelector('#runs-body');
  runBody.innerHTML = '';
  document.querySelector('#runs-empty').hidden = runs.length > 0;
  for (const r of runs) {
    const row = document.createElement('tr');
    const passRate = r.caseCount > 0 ? r.passedCount / r.caseCount : 0;
    const statusBadge = r.status === 'completed' ? 'pass'
      : r.status === 'failed' ? 'fail' : 'running';
    row.innerHTML = `
      <td><input type="checkbox" class="run-pick" data-run="${esc(r.id)}"></td>
      <td>${esc(r.name)}<div class="muted small mono">${esc(r.targetUrl)}</div></td>
      <td><span class="badge ${statusBadge}">${esc(r.status)}</span></td>
      <td class="num">${fmtPassRate(passRate)}</td>
      <td class="num">${r.passedCount} / ${r.caseCount}</td>
      <td class="num">${r.caseCount > 0 ? Math.round(r.avgLatencyMs ?? 0) + ' ms' : '—'}</td>
      <td class="mono">${esc(fmtTime(r.startedAt))}</td>`;
    runBody.appendChild(row);
  }
  runBody.querySelectorAll('.run-pick').forEach((box) => {
    box.addEventListener('change', () => {
      if (box.checked) {
        pickedRuns.add(box.dataset.run);
        if (pickedRuns.size > 2) {
          const oldest = pickedRuns.values().next().value;
          pickedRuns.delete(oldest);
          runBody.querySelector(`[data-run="${oldest}"]`).checked = false;
        }
      } else {
        pickedRuns.delete(box.dataset.run);
      }
      compareBtn.disabled = pickedRuns.size !== 2;
    });
  });
  compareBtn.onclick = () => {
    const [a, b] = [...pickedRuns];
    location.hash = `#/evals/compare/${encodeURIComponent(a)}/${encodeURIComponent(b)}`;
  };
}

/* ---------- evals: run comparison ---------- */

function caseVerdict(cell, passed, label) {
  if (passed === true) cell.innerHTML += `<span class="badge pass">${label} pass</span>`;
  else if (passed === false) cell.innerHTML += `<span class="badge fail">${label} fail</span>`;
  else cell.innerHTML += `<span class="badge muted-badge">${label} —</span>`;
}

async function loadCompare(a, b) {
  const comparison = await getJson(`/api/eval-runs/${encodeURIComponent(a)}/compare/${encodeURIComponent(b)}`);
  const totalsA = comparison.runA.totals ?? {};
  const totalsB = comparison.runB.totals ?? {};
  document.querySelector('#compare-totals').innerHTML = [
    chip('mono', `${comparison.runA.run.name ?? 'run A'} → ${comparison.runB.run.name ?? 'run B'}`),
    chip('pass', `pass rate ${fmtPassRate(totalsA.passRate)} → ${fmtPassRate(totalsB.passRate)}`),
    chip('', `${fmtTokens(totalsA.inputTokens ?? 0)}/${fmtTokens(totalsA.outputTokens ?? 0)} → ${fmtTokens(totalsB.inputTokens ?? 0)}/${fmtTokens(totalsB.outputTokens ?? 0)} tokens`),
    chip('cost', `${fmtCost(totalsA.costUsd)} → ${fmtCost(totalsB.costUsd)}`),
    chip('', `avg latency ${fmtDuration(totalsA.avgLatencyMs)} → ${fmtDuration(totalsB.avgLatencyMs)}`),
  ].join('');

  const body = document.querySelector('#compare-body');
  body.innerHTML = '';
  for (const c of comparison.cases) {
    const row = document.createElement('tr');
    const latencyA = c.latencyMsA ?? '—';
    const latencyB = c.latencyMsB ?? '—';
    const costA = c.costA != null ? fmtCost(c.costA) : '—';
    const costB = c.costB != null ? fmtCost(c.costB) : '—';
    const error = c.errorA ?? c.errorB ?? '';
    let note = error;
    if (c.passedA === true && c.passedB === false) note = `${note ? note + ' · ' : ''}regressed vs A`;
    if (c.passedA === false && c.passedB === true) note = `${note ? note + ' · ' : ''}improved vs A`;
    row.innerHTML = `
      <td class="mono">${esc(c.caseId)}</td>
      <td class="num"></td>
      <td class="num"></td>
      <td class="num muted">${esc(String(latencyA))} / ${esc(String(latencyB))} ms</td>
      <td class="num">${esc(costA)} / ${esc(costB)}</td>
      <td class="muted small">${esc(note)}</td>`;
    caseVerdict(row.cells[1], c.passedA, 'A');
    caseVerdict(row.cells[2], c.passedB, 'B');
    body.appendChild(row);
  }
}

/* ---------- routing & polling ---------- */

function show(view) {
  for (const id of ['list-view', 'detail-view', 'evals-view', 'compare-view']) {
    document.querySelector(`#${id}`).hidden = id !== view;
  }
  const tab = view === 'detail-view' || view === 'list-view' ? 'traces' : 'evals';
  document.querySelectorAll('.nav a').forEach((link) => {
    link.classList.toggle('active', link.dataset.nav === tab);
  });
}

function route() {
  const traceMatch = location.hash.match(/^#\/traces\/(.+)$/);
  const compareMatch = location.hash.match(/^#\/evals\/compare\/([^/]+)\/([^/]+)$/);
  if (traceMatch) {
    show('detail-view');
    loadDetail(decodeURIComponent(traceMatch[1]));
  } else if (compareMatch) {
    show('compare-view');
    loadCompare(decodeURIComponent(compareMatch[1]), decodeURIComponent(compareMatch[2]));
  } else if (location.hash.startsWith('#/evals')) {
    show('evals-view');
    loadEvals();
  } else {
    show('list-view');
    loadList();
  }
}

function setAutoRefresh(on) {
  if (state.timer) {
    clearInterval(state.timer);
    state.timer = null;
  }
  if (on) {
    state.timer = setInterval(() => {
      if (location.hash === '' || location.hash === '#') loadList();
    }, 5000);
  }
}

window.addEventListener('hashchange', route);
document.querySelector('#refresh-btn').addEventListener('click', loadList);
document.querySelector('#auto-refresh').addEventListener('change', (e) => setAutoRefresh(e.target.checked));
route();
