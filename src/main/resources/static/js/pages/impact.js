(function () {
  'use strict';
  const { el, icon, api, fmt, replace, clear, kindLabel, levelBadge, toast } = RM;

  const form = document.getElementById('impact-form');
  const resultRoot = document.getElementById('impact-result');
  const notesRoot = document.getElementById('snapshot-notes');
  const searchInput = document.getElementById('component-search');
  const results = document.getElementById('component-results');
  const picker = document.getElementById('component-picker');
  const selectedBox = document.getElementById('selected-component');
  const analyzeButton = document.getElementById('analyze-button');
  const inferredBox = document.getElementById('impact-inferred');

  const state = {
    analysis: null,
    layer: (RM.param('layer') || 'TYPE').toUpperCase() === 'METHOD' ? 'METHOD' : 'TYPE',
    component: null,
    includeInferred: true,
    result: null
  };
  let analyzeRequest = 0;
  let miniGraph = null;

  // ---------------------------------------------------------------- selection

  function renderSelected() {
    const c = state.component;
    selectedBox.classList.toggle('hidden', !c);
    picker.classList.toggle('hidden', !!c);
    analyzeButton.disabled = !c || !state.analysis;
    if (!c) {
      clear(selectedBox);
      return;
    }
    replace(selectedBox, el('div', { class: 'selected-component' },
      kindLabel(c.kind),
      el('div', { class: 'truncate' }, el('div', { class: 'cell-title truncate', text: c.name, title: c.qualifiedName || c.name }),
        el('div', { class: 'cell-sub truncate', text: c.filePath ? c.filePath + ':' + c.line : c.id })),
      el('span', { class: 'spacer' }),
      el('button', { type: 'button', class: 'btn btn-sm btn-ghost', onclick: () => { setComponent(null); searchInput.focus(); } }, 'Change')));
  }

  /** Any change of the question invalidates the displayed answer. */
  function invalidate(reason) {
    analyzeRequest++;
    state.result = null;
    if (miniGraph) {
      miniGraph.destroy();
      miniGraph = null;
    }
    replace(resultRoot, idleState(reason));
  }

  function setComponent(component) {
    state.component = component;
    RM.setParams({ node: component ? component.id : null, layer: state.layer === 'TYPE' ? null : state.layer });
    renderSelected();
    invalidate(component ? 'Component selected. Run the analysis to see what may be affected.' : null);
  }

  function idleState(message) {
    return el('section', { class: 'card' }, RM.empty('zap', state.component ? 'Ready to analyze' : 'Choose a component',
      message || 'Pick the class, interface or method you plan to change. RippleMap follows the dependency graph backwards to find everything that depends on it, directly or transitively.'));
  }

  // ---------------------------------------------------------------- search

  let searchRequest = 0;
  let activeIndex = -1;
  const search = RM.debounce(async () => {
    const q = searchInput.value.trim();
    if (!q || !state.analysis) {
      closeResults();
      return;
    }
    const request = ++searchRequest;
    try {
      const data = await api(RM.project.path('/nodes' + RM.qs({ analysisId: state.analysis.id, layer: state.layer, q, limit: 12 })));
      if (request !== searchRequest) return;
      activeIndex = -1;
      replace(results, data.items.length ? data.items.map((item, i) => el('button', {
        type: 'button', class: 'list-button', role: 'option', id: 'component-option-' + i, 'aria-selected': 'false',
        onclick: () => { closeResults(); searchInput.value = ''; setComponent(item.entity); }
      }, el('span', { class: 'row' }, kindLabel(item.entity.kind), el('strong', { class: 'truncate', text: item.entity.name })),
        el('span', { class: 'cell-sub', text: (item.directDependents !== null ? item.directDependents + ' direct dependent(s) · ' : '') + (item.entity.qualifiedName || '') })))
        : el('div', { class: 'small muted', text: 'No matches for “' + q + '”.' }));
      results.classList.remove('hidden');
      searchInput.setAttribute('aria-expanded', 'true');
    } catch (e) {
      if (request === searchRequest) RM.errorToast(e, 'Search failed');
    }
  }, 200);

  function closeResults() {
    results.classList.add('hidden');
    searchInput.setAttribute('aria-expanded', 'false');
    searchInput.removeAttribute('aria-activedescendant');
  }

  searchInput.addEventListener('input', search);
  searchInput.addEventListener('keydown', (e) => {
    const options = Array.from(results.querySelectorAll('[role=option]'));
    if (e.key === 'Escape') { closeResults(); return; }
    if (!options.length) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      activeIndex = (activeIndex + (e.key === 'ArrowDown' ? 1 : options.length - 1)) % options.length;
      options.forEach((o, i) => o.setAttribute('aria-selected', String(i === activeIndex)));
      searchInput.setAttribute('aria-activedescendant', options[activeIndex].id);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      (options[activeIndex] || options[0]).click();
    }
  });
  document.addEventListener('click', (e) => { if (!results.contains(e.target) && e.target !== searchInput) closeResults(); });

  document.querySelectorAll('[data-layer]').forEach((b) => b.addEventListener('click', () => {
    if (state.layer === b.dataset.layer) return;
    state.layer = b.dataset.layer;
    document.querySelectorAll('[data-layer]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    searchInput.placeholder = state.layer === 'TYPE' ? 'Search classes, interfaces, enums…' : 'Search methods and constructors…';
    setComponent(null);
  }));
  inferredBox.addEventListener('change', () => {
    state.includeInferred = inferredBox.checked;
    if (state.component) invalidate('Options changed. Run the analysis again.');
  });

  // ---------------------------------------------------------------- analysis

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    analyze();
  });

  async function analyze() {
    if (!state.component || !state.analysis) return;
    const request = ++analyzeRequest;
    const component = state.component;
    analyzeButton.disabled = true;
    replace(resultRoot, el('section', { class: 'card' }, RM.loading('Following dependents of ' + component.name + '…')));
    try {
      const result = await api(RM.project.path('/impact-analysis'), { method: 'POST', json: {
        analysisId: state.analysis.id, nodeId: component.id, layer: state.layer, includeInferred: state.includeInferred } });
      if (request !== analyzeRequest || state.component !== component) return; // a newer question was asked
      state.result = result;
      renderResult(result);
    } catch (e) {
      if (request !== analyzeRequest) return;
      replace(resultRoot, el('section', { class: 'card' }, RM.errorState(e, analyze)));
    } finally {
      analyzeButton.disabled = !state.component;
    }
  }

  function gauge(result) {
    const radius = 54;
    const circumference = 2 * Math.PI * radius;
    const svgNS = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(svgNS, 'svg');
    svg.setAttribute('viewBox', '0 0 132 132');
    svg.setAttribute('aria-hidden', 'true');
    for (const cls of ['track', 'value']) {
      const circle = document.createElementNS(svgNS, 'circle');
      circle.setAttribute('class', cls);
      circle.setAttribute('cx', '66');
      circle.setAttribute('cy', '66');
      circle.setAttribute('r', String(radius));
      circle.setAttribute('fill', 'none');
      circle.setAttribute('stroke-width', '12');
      circle.setAttribute('stroke-linecap', 'round');
      if (cls === 'value') {
        circle.setAttribute('stroke-dasharray', String(circumference));
        circle.setAttribute('stroke-dashoffset', String(circumference * (1 - Math.min(100, result.score) / 100)));
      }
      svg.appendChild(circle);
    }
    return el('div', { class: 'gauge level-' + result.level, role: 'img', 'aria-label': 'Impact score ' + fmt.decimal(result.scoreRounded, 1) + ' of 100, ' + result.level },
      svg, el('div', { class: 'gauge-label' }, el('b', { text: fmt.decimal(result.scoreRounded, 1) }), el('span', { text: 'of 100' })));
  }

  function chain(path) {
    const parts = [];
    path.forEach((p, i) => {
      if (i > 0) parts.push(el('span', { class: 'arrow', 'aria-hidden': 'true', text: '→' }));
      parts.push(el('span', { class: 'step' + (i === path.length - 1 ? ' step-target' : ''), title: p.qualifiedName || p.name, text: p.name }));
    });
    return el('div', { class: 'chain', 'aria-label': path.map((p) => p.name).join(' depends on ') }, parts);
  }

  function renderResult(r) {
    const s = r.selected;
    const reportQuery = RM.qs({ analysisId: r.analysisId, nodeId: s.id, layer: r.layer, includeInferred: r.includeInferred });
    const reportBase = RM.project.path('/reports/impact');
    const affectedRows = r.affected.map((a) => el('tr', null,
      el('td', { class: 'break' }, el('div', { class: 'row' }, kindLabel(a.entity.kind), el('span', { class: 'cell-title', text: a.entity.name })),
        el('div', { class: 'cell-sub mono hide-sm', text: a.entity.filePath ? a.entity.filePath + ':' + a.entity.line : a.entity.id })),
      el('td', { class: 'num', text: String(a.distance) }),
      el('td', { class: 'hide-sm' }, el('span', { class: 'badge ' + (a.direct ? 'badge-warning' : 'badge-outline'), text: a.direct ? 'direct' : 'transitive' })),
      el('td', null, chain(a.path))));
    const factorRows = r.factors.map((f) => el('tr', null,
      el('td', null, el('div', { class: 'cell-title', text: f.name }), el('div', { class: 'cell-sub mono', text: f.formula })),
      el('td', { class: 'num', text: fmt.decimal(f.value * 100) + ' %' }),
      el('td', { class: 'hide-sm' }, el('div', { class: 'factor-bar', role: 'presentation' }, el('span', { class: 'bar-fill', dataset: { width: String(Math.round(f.value * 100)) } }))),
      el('td', { class: 'num', text: '× ' + f.weight }),
      el('td', { class: 'num', text: fmt.decimal(f.contribution) })));
    const content = el('div', { class: 'stack' },
      el('section', { class: 'card' },
        el('div', { class: 'card-header' }, el('h2', null, 'Impact of changing ', el('span', { class: 'break', text: s.name })),
          el('div', { class: 'row' },
            RM.downloadLink(reportBase + '.html' + reportQuery, 'HTML'),
            RM.downloadLink(reportBase + '.csv' + reportQuery, 'CSV'),
            RM.downloadLink(reportBase + '.json' + reportQuery, 'JSON'))),
        el('div', { class: 'card-body stack' },
          el('div', { class: 'score-card' }, gauge(r),
            el('div', { class: 'stack' },
              el('div', { class: 'row' }, levelBadge(r.level), el('span', { class: 'small muted', text: r.layer === 'TYPE' ? 'type-level analysis' : 'method-level analysis' }),
                el('span', { class: 'small muted', text: '· snapshot #' + r.analysisSequence }), r.includeInferred ? null : el('span', { class: 'badge badge-outline', text: 'resolved only' })),
              el('div', { class: 'stats' },
                el('div', { class: 'stat' }, el('div', { class: 'stat-label', text: 'Direct dependents (D)' }), el('div', { class: 'stat-value', text: fmt.number(r.directCount) })),
                el('div', { class: 'stat' }, el('div', { class: 'stat-label', text: 'Potentially affected (T)' }), el('div', { class: 'stat-value', text: fmt.number(r.transitiveCount) }),
                  el('div', { class: 'stat-hint', text: fmt.decimal(r.percentageRounded, 1) + ' % of ' + fmt.number(Math.max(0, r.eligibleCount - 1)) + ' other ' + (r.layer === 'TYPE' ? 'types' : 'callables') })),
                el('div', { class: 'stat' }, el('div', { class: 'stat-label', text: 'Max distance (H)' }), el('div', { class: 'stat-value', text: fmt.number(r.maxDistance) })),
                el('div', { class: 'stat' }, el('div', { class: 'stat-label', text: 'Affected files' }), el('div', { class: 'stat-value', text: fmt.number(r.affectedFileCount) }),
                  r.layer === 'METHOD' ? el('div', { class: 'stat-hint', text: r.affectedTypeCount + ' declaring type(s)' }) : null)))),
          el('div', { class: 'alert alert-info' }, icon('info'), el('div', { text: r.disclaimer })))),
      el('section', { class: 'card' },
        el('div', { class: 'card-header' }, el('h2', null, icon('list-tree'), 'Score explanation')),
        el('div', { class: 'card-body stack' },
          el('div', { class: 'formula formula-wrap', text: r.formula + '  with N = ' + r.eligibleCount }),
          el('div', { class: 'table-wrap' }, el('table', { class: 'table table-compact' },
            el('thead', null, el('tr', null, el('th', { text: 'Factor' }), el('th', { class: 'num', text: 'Signal' }), el('th', { class: 'hide-sm', text: '' }),
              el('th', { class: 'num', text: 'Weight' }), el('th', { class: 'num', text: 'Points' }))),
            el('tbody', null, factorRows,
              el('tr', null, el('td', { class: 'cell-title', text: 'Score' }), el('td'), el('td', { class: 'hide-sm' }), el('td'), el('td', { class: 'num cell-title', text: fmt.decimal(r.scoreRounded, 1) }))))),
          el('p', { class: 'small muted', text: 'LOW below ' + r.lowThreshold + ', MEDIUM from ' + r.lowThreshold + ', HIGH from ' + r.highThreshold
            + '. The score is computed from unrounded values and rounded for display.' }))),
      r.limitations.length ? el('section', { class: 'card' },
        el('div', { class: 'card-header' }, el('h2', null, icon('triangle-alert'), 'Limitations of this result')),
        el('div', { class: 'card-body small' }, el('ul', null, r.limitations.map((l) => el('li', { text: l }))),
          r.relatedUnresolved.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table table-compact' },
            el('thead', null, el('tr', null, el('th', { text: 'Unresolved reference' }), el('th', { text: 'Location' }))),
            el('tbody', null, r.relatedUnresolved.map((d) => el('tr', null, el('td', { class: 'small break', text: d.message }),
              el('td', { class: 'mono small break', text: (d.filePath || '') + (d.line ? ':' + d.line : '') })))))) : null)) : null,
      el('section', { class: 'card' },
        el('div', { class: 'card-header' }, el('h2', null, icon('network'), 'Affected region'),
          el('a', { class: 'btn btn-sm', href: '/projects/' + RM.project.id + '/explorer' + RM.qs({ analysisId: r.analysisId, layer: r.layer === 'TYPE' ? null : r.layer, scope: 'DEPENDENTS', focus: s.id, inferred: r.includeInferred ? null : 'false' }) },
            icon('network', 'icon-sm'), 'Open in explorer')),
        el('div', { class: 'card-body' }, r.transitiveCount ? el('div', { class: 'mini-graph' }, el('div', { class: 'cy', id: 'impact-graph' })) : RM.empty('circle-check', 'Nothing depends on this component', 'No other analyzed component depends on it, so the change has no structural ripple inside the analyzed sources.'))),
      r.transitiveCount ? el('section', { class: 'card' },
        el('div', { class: 'card-header' }, el('h2', null, 'Potentially affected components', el('span', { class: 'badge', text: fmt.number(r.transitiveCount) }))),
        el('div', { class: 'card-body flush' }, el('div', { class: 'table-wrap table-scroll', tabindex: '0', role: 'region', 'aria-label': 'Potentially affected components' }, el('table', { class: 'table' },
          el('thead', null, el('tr', null, el('th', { text: 'Component' }), el('th', { class: 'num', text: 'Distance' }), el('th', { class: 'hide-sm', text: 'Relation' }), el('th', { text: 'Why: dependency path' }))),
          el('tbody', null, affectedRows)))),
        r.affectedTruncated ? el('div', { class: 'card-footer', text: 'The list is truncated; the counts above are exact. Download a report for the complete list.' }) : null) : null,
      r.affectedFiles.length ? el('section', { class: 'card' },
        el('div', { class: 'card-header' }, el('h2', null, icon('file-code'), 'Affected source files', el('span', { class: 'badge', text: String(r.affectedFileCount) }))),
        el('div', { class: 'card-body' }, el('ul', { class: 'small mono' }, r.affectedFiles.map((f) => el('li', { class: 'break', text: f }))))) : null);
    replace(resultRoot, content);
    resultRoot.querySelectorAll('.bar-fill').forEach((bar) => { bar.style.width = bar.dataset.width + '%'; });
    if (r.transitiveCount) renderMiniGraph(r);
  }

  async function renderMiniGraph(r) {
    const container = document.getElementById('impact-graph');
    const request = analyzeRequest;
    try {
      const graph = await api(RM.project.path('/graph' + RM.qs({ analysisId: r.analysisId, layer: r.layer, scope: 'DEPENDENTS',
        focus: r.selected.id, includeInferred: r.includeInferred, limit: 150 })));
      if (request !== analyzeRequest || !document.body.contains(container)) return;
      miniGraph = RM.graph.create(container, graph, { layout: 'hierarchy', focus: r.selected.id });
      miniGraph.nodes().forEach((n) => n.addClass(n.id() === r.selected.id ? 'selected-target' : 'affected'));
      miniGraph.edges().addClass('path');
      if (graph.truncated) {
        container.parentElement.after(el('p', { class: 'xsmall faint', text: graph.truncationReason }));
      }
    } catch (e) {
      replace(container, RM.errorState(e));
    }
  }

  // ---------------------------------------------------------------- init

  async function start(analysis) {
    state.analysis = analysis;
    replace(notesRoot, RM.project.banner());
    if (!analysis) {
      replace(resultRoot, el('section', { class: 'card' }, RM.empty('history', 'No completed analysis', 'Impact analysis needs a completed snapshot.')));
      analyzeButton.disabled = true;
      return;
    }
    const nodeId = RM.param('node');
    if (nodeId) {
      try {
        const detail = await api(RM.project.path('/node' + RM.qs({ analysisId: analysis.id, nodeId })));
        const e = detail.entity;
        if ((state.layer === 'TYPE') !== (e.kind !== 'METHOD' && e.kind !== 'CONSTRUCTOR')) {
          state.layer = e.kind === 'METHOD' || e.kind === 'CONSTRUCTOR' ? 'METHOD' : 'TYPE';
          document.querySelectorAll('[data-layer]').forEach((x) => x.setAttribute('aria-pressed', String(x.dataset.layer === state.layer)));
        }
        state.component = { id: e.id, name: e.kind === 'METHOD' || e.kind === 'CONSTRUCTOR' ? (e.declaringTypeName ? e.declaringTypeName + '.' : '') + e.name : e.name,
          kind: e.kind, qualifiedName: e.qualifiedName, filePath: e.filePath, line: e.line };
        renderSelected();
        await analyze();
        return;
      } catch (err) {
        toast('warning', 'Component not found in this snapshot', err.message);
        RM.setParams({ node: null });
        state.component = null;
      }
    }
    renderSelected();
    invalidate(state.component ? 'Run the analysis for the selected snapshot.' : null);
  }

  RM.api('/api/settings').then((settings) => {
    state.includeInferred = RM.param('inferred') === 'false' ? false : settings.includeInferred;
    inferredBox.checked = state.includeInferred;
    document.getElementById('threshold-note').textContent = 'LOW below ' + settings.lowThreshold + ', MEDIUM from ' + settings.lowThreshold
      + ', HIGH from ' + settings.highThreshold + ' (configurable in Settings).';
  }).catch(() => { inferredBox.checked = true; }).finally(() => {
    document.querySelectorAll('[data-layer]').forEach((x) => x.setAttribute('aria-pressed', String(x.dataset.layer === state.layer)));
    RM.project.onChange((analysis) => {
      // a different snapshot answers a different question: never show results of the previous one
      invalidate('Snapshot changed. Run the analysis again for snapshot #' + (analysis ? analysis.sequenceNumber : '?') + '.');
      start(analysis);
    });
    RM.project.init().then(start).catch((e) => replace(resultRoot, RM.errorState(e)));
  });
})();
