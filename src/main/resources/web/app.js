// The console. Everything that came from the server is inserted with textContent, never as HTML.
'use strict';

function el(tag, text) {
  const e = document.createElement(tag);
  if (text !== undefined) {
    e.textContent = text;
  }
  return e;
}

function cell(v) {
  if (v === null || v === undefined) {
    return 'null';
  }
  return typeof v === 'object' ? JSON.stringify(v) : String(v);
}

function table(rows, columns) {
  const t = el('table');
  const head = el('tr');
  columns.forEach(c => head.appendChild(el('th', c)));
  t.appendChild(el('thead')).appendChild(head);
  const body = t.appendChild(el('tbody'));
  // Each row is an array with one value per column (docs/API.md).
  rows.forEach(r => {
    const tr = el('tr');
    columns.forEach((c, i) => tr.appendChild(el('td', cell(r[i]))));
    body.appendChild(tr);
  });
  return t;
}

function render(data) {
  const out = document.getElementById('result');
  out.replaceChildren();
  if (data && data.type === 'rows') {
    out.appendChild(table(data.rows, data.columns));
    const n = data.rows.length;
    out.appendChild(el('p', n + ' row' + (n === 1 ? '' : 's') + (data.truncated ? ' (truncated)' : '')));
  } else if (data && data.type === 'write') {
    out.appendChild(el('p', data.affected + ' affected'));
  } else {
    out.appendChild(el('pre', JSON.stringify(data, null, 2)));
  }
}

async function run(ev) {
  ev.preventDefault();
  const err = document.getElementById('error');
  err.textContent = '';
  const started = performance.now();
  const res = await fetch('/v1/query', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({query: document.getElementById('query').value}),
  });
  document.getElementById('elapsed').textContent = Math.round(performance.now() - started) + ' ms';
  const data = await res.json().catch(() => ({error: 'HTTP ' + res.status}));
  if (!res.ok) {
    err.textContent = data.error || ('HTTP ' + res.status);
    document.getElementById('result').replaceChildren();
    return;
  }
  render(data);
}

async function status() {
  const res = await fetch('/v1/status');
  if (!res.ok) {
    return;
  }
  const data = await res.json();
  const dl = document.getElementById('status');
  dl.replaceChildren();
  Object.entries(data).forEach(([k, v]) => {
    dl.appendChild(el('dt', k));
    dl.appendChild(el('dd', cell(v)));
  });
}

document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('query-form').addEventListener('submit', run);
  status();
});
