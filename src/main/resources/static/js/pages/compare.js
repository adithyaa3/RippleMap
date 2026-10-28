(function () {
  'use strict';
  const { el, icon, api, fmt, replace, kindLabel } = RM;
  const baseSelect = document.getElementById('base-select');
  const targetSelect = document.getElementById('target-select');
  const resultRoot = document.getElementById('compare-result');
  const state = { layer: (RM.param('layer') || 'TYPE').toUpperCase() === 'METHOD' ? 'METHOD' : 'TYPE', options: [] };
  let request = 0;

  function label(a) {
    return '#' + a.sequenceNumber + (a.versionNumber ? ' · v' + a.versionNumber : '') + ' · ' + fmt.date(a.finishedAt) + (a.status === 'PARTIAL' ? ' · partial' : '');
  }

  function fill(select, value) {
    replace(select, state.options.map((a) => el('option', { value: a.id, text: label(a), selected: String(a.id) === String(value) })));
  }

  function delta(value) {
    if (value === 0) return el('span', { class: 'faint', text: '0' });
    return el('span', { class: value > 0 ? 'delta-pos' : 'delta-neg', text: (value > 0 ? '+' : '−') + fmt.number(Math.abs(value)) });
  }

  function entityList(items, total, added) {
    if (!total) return el('p', { class: 'small faint', text: added ? 'Nothing added.' : 'Nothing removed.' });
    return el('div', null, el('ul', { class: 'list' }, items.map((e) => el('li', { class: 'list-item' },
      el('span', { class: 'change-tag ' + (added ? 'diff-added' : 'diff-removed'), 'aria-label': added ? 'added' : 'removed', text: added ? '+' : '−' }),
      kindLabel(e.kind),
      el('div', { class: 'truncate' }, el('div', { class: 'cell-title truncate', text: e.name, title: e.id }),
        el('div', { class: 'cell-sub truncate', text: e.filePath || '' }))))),
    total > items.length ? el('p', { class: 'xsmall faint', text: 'Showing ' + items.length + ' of ' + total + '.' }) : null);
  }

  function edgeList(items, total, added) {
    if (!total) return el('p', { class: 'small faint', text: added ? 'No new dependencies.' : 'No dependencies removed.' });
    return el('div', null, el('ul', { class: 'list' }, items.map((e) => el('li', { class: 'list-item' },
      el('span', { class: 'change-tag ' + (added ? 'diff-added' : 'diff-removed'), 'aria-label': added ? 'added' : 'removed', text: added ? '+' : '−' }),
      el('div', { class: 'stack', style: null },
        el('div', { class: 'chain' }, el('span', { class: 'step', title: e.source, text: e.sourceName }), el('span', { class: 'arrow', text: '→' }),
          el('span', { class: 'step', title: e.target, text: e.targetName })),
        el('div', { class: 'cell-sub', text: e.kinds.map(fmt.relationship).join(', ') }))))),
    total > items.length ? el('p', { class: 'xsmall faint', text: 'Showing ' + items.length + ' of ' + total + '.' }) : null);
  }

  function card(title, iconName, body, extra) {
    return el('section', { class: 'card' }, el('div', { class: 'card-header' }, el('h2', null, icon(iconName), title), extra || null), el('div', { class: 'card-body' }, body));
  }

  function render(r) {
    const units = r.layer === 'TYPE' ? 'types' : 'methods & constructors';
    const query = RM.qs({ base: r.base.id, target: r.target.id, layer: r.layer });
    replace(resultRoot, el('div', { class: 'stack' },
      el('div', { class: 'row-between' },
        el('p', { class: 'small muted', text: 'Snapshot #' + r.base.sequenceNumber + ' → #' + r.target.sequenceNumber + ' (' + units + ')' }),
        el('div', { class: 'row' },
          RM.downloadLink(RM.project.path('/reports/comparison.html' + query), 'HTML'),
          RM.downloadLink(RM.project.path('/reports/comparison.csv' + query), 'CSV'))),
      el('div', { class: 'stats' },
        el('div', { class: 'stat' }, el('div', { class: 'stat-label', text: 'Added ' + units }), el('div', { class: 'stat-value diff-added', text: '+' + fmt.number(r.addedEntityCount) })),
        el('div', { class: 'stat' }, el('div', { class: 'stat-label', text: 'Removed ' + units }), el('div', { class: 'stat-value diff-removed', text: '−' + fmt.number(r.removedEntityCount) })),
        el('div', { class: 'stat' }, el('div', { class: 'stat-label', text: 'Unchanged ' + units }), el('div', { class: 'stat-value', text: fmt.number(r.unchangedEntityCount) })),
        el('div', { class: 'stat' }, el('div', { class: 'stat-label', text: 'Added dependencies' }), el('div', { class: 'stat-value diff-added', text: '+' + fmt.number(r.addedEdgeCount) })),
        el('div', { class: 'stat' }, el('div', { class: 'stat-label', text: 'Removed dependencies' }), el('div', { class: 'stat-value diff-removed', text: '−' + fmt.number(r.removedEdgeCount) })),
        el('div', { class: 'stat' }, el('div', { class: 'stat-label', text: 'Relationship changes' }),
          el('div', { class: 'stat-value', text: '+' + fmt.number(r.addedRelationshipCount) + ' / −' + fmt.number(r.removedRelationshipCount) }),
          el('div', { class: 'stat-hint', text: 'dependency pairs by kind' }))),
      r.truncated ? el('div', { class: 'alert alert-info' }, icon('info'), el('div', { text: 'Lists show at most ' + r.listLimit + ' items each; counts are exact. Download a report for everything.' })) : null,
      el('div', { class: 'grid grid-2' },
        card('Added ' + units, 'plus', entityList(r.addedEntities, r.addedEntityCount, true)),
        card('Removed ' + units, 'minus', entityList(r.removedEntities, r.removedEntityCount, false))),
      el('div', { class: 'grid grid-2' },
        card('Added dependencies', 'git-branch', edgeList(r.addedEdges, r.addedEdgeCount, true)),
        card('Removed dependencies', 'git-branch', edgeList(r.removedEdges, r.removedEdgeCount, false))),
      el('div', { class: 'grid grid-2' },
        card('Metrics', 'list-tree', el('div', { class: 'table-wrap' }, el('table', { class: 'table table-compact' },
          el('thead', null, el('tr', null, el('th', { text: 'Metric' }), el('th', { class: 'num', text: '#' + r.base.sequenceNumber }),
            el('th', { class: 'num', text: '#' + r.target.sequenceNumber }), el('th', { class: 'num', text: 'Change' }))),
          el('tbody', null, r.metrics.map((m) => el('tr', null, el('td', { text: m.metric }), el('td', { class: 'num', text: fmt.number(m.base) }),
            el('td', { class: 'num', text: fmt.number(m.target) }), el('td', { class: 'num' }, delta(m.delta)))))))),
        card('Diagnostics by category', 'triangle-alert', r.diagnostics.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table table-compact' },
          el('thead', null, el('tr', null, el('th', { text: 'Category' }), el('th', { class: 'num', text: '#' + r.base.sequenceNumber }),
            el('th', { class: 'num', text: '#' + r.target.sequenceNumber }), el('th', { class: 'num', text: 'Change' }))),
          el('tbody', null, r.diagnostics.map((m) => el('tr', null, el('td', { class: 'small', text: m.metric.replace(/_/g, ' ').toLowerCase() }),
            el('td', { class: 'num', text: fmt.number(m.base) }), el('td', { class: 'num', text: fmt.number(m.target) }), el('td', { class: 'num' }, delta(m.delta)))))))
          : el('p', { class: 'small faint', text: 'Neither snapshot reported diagnostics.' })))));
  }

  async function compare() {
    if (!baseSelect.value || !targetSelect.value) return;
    const current = ++request;
    RM.setParams({ base: baseSelect.value, target: targetSelect.value, layer: state.layer === 'TYPE' ? null : state.layer });
    replace(resultRoot, el('section', { class: 'card' }, RM.loading('Comparing snapshots…')));
    try {
      const r = await api(RM.project.path('/compare' + RM.qs({ base: baseSelect.value, target: targetSelect.value, layer: state.layer })));
      if (current === request) render(r);
    } catch (e) {
      if (current === request) replace(resultRoot, el('section', { class: 'card' }, RM.errorState(e, compare)));
    }
  }

  document.getElementById('compare-form').addEventListener('submit', (e) => { e.preventDefault(); compare(); });
  document.getElementById('swap-button').addEventListener('click', () => {
    const base = baseSelect.value;
    baseSelect.value = targetSelect.value;
    targetSelect.value = base;
    compare();
  });
  document.querySelectorAll('[data-layer]').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.dataset.layer === state.layer));
    b.addEventListener('click', () => {
      state.layer = b.dataset.layer;
      document.querySelectorAll('[data-layer]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
      compare();
    });
  });

  RM.project.init().then(() => {
    state.options = RM.project.analyses.filter((a) => a.hasResults);
    if (state.options.length < 2) {
      document.getElementById('compare-form').classList.add('hidden');
      replace(resultRoot, el('section', { class: 'card' }, RM.empty('git-compare', 'Two snapshots are needed',
        'Upload a new version or re-analyze the project to create a second snapshot, then compare them here.',
        el('a', { class: 'btn', href: '/projects/' + RM.project.id + '/history' }, 'Go to history'))));
      return;
    }
    const target = RM.param('target') || (RM.project.selected ? RM.project.selected.id : state.options[0].id);
    const targetEntry = state.options.find((a) => String(a.id) === String(target)) || state.options[0];
    const older = state.options.find((a) => a.sequenceNumber < targetEntry.sequenceNumber);
    const base = RM.param('base') || (older ? older.id : state.options[state.options.length - 1].id);
    fill(baseSelect, base);
    fill(targetSelect, targetEntry.id);
    compare();
  }).catch((e) => replace(resultRoot, RM.errorState(e)));
})();
