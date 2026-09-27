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

/* ---------- routing & polling ---------- */

function route() {
  const match = location.hash.match(/^#\/traces\/(.+)$/);
  if (match) {
    document.querySelector('#list-view').hidden = true;
    document.querySelector('#detail-view').hidden = false;
    loadDetail(decodeURIComponent(match[1]));
  } else {
    document.querySelector('#detail-view').hidden = true;
    document.querySelector('#list-view').hidden = false;
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
