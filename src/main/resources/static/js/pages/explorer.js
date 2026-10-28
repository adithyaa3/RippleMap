(function () {
  'use strict';
  const { el, icon, api, fmt, replace, clear, kindLabel, levelBadge, toast } = RM;

  const NODE_KINDS = { TYPE: ['CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION'], METHOD: ['METHOD', 'CONSTRUCTOR'] };
  const REL_KINDS = {
    TYPE: ['EXTENDS', 'IMPLEMENTS', 'FIELD_TYPE', 'METHOD_PARAMETER_TYPE', 'METHOD_RETURN_TYPE', 'CONSTRUCTOR_PARAMETER_TYPE',
      'OBJECT_CREATION', 'METHOD_CALL', 'METHOD_REFERENCE', 'CONSTRUCTOR_CALL', 'FIELD_ACCESS', 'ANNOTATION_USAGE', 'TYPE_REFERENCE'],
    METHOD: ['METHOD_CALL', 'METHOD_REFERENCE', 'OBJECT_CREATION', 'CONSTRUCTOR_CALL']
  };

  const cyContainer = document.getElementById('cy');
  const overlay = document.getElementById('canvas-overlay');
  const banner = document.getElementById('canvas-banner');
  const details = document.getElementById('details-panel');
  const summary = document.getElementById('graph-summary');
  const searchInput = document.getElementById('node-search');
  const searchResults = document.getElementById('search-results');
  const scopeSelect = document.getElementById('scope-select');
  const depthSelect = document.getElementById('depth-select');
  const layoutSelect = document.getElementById('layout-select');
  const filterPanel = document.getElementById('filter-panel');
  const filterButton = document.getElementById('filter-button');

  const state = {
    analysis: null,
    layer: (RM.param('layer') || 'TYPE').toUpperCase() === 'METHOD' ? 'METHOD' : 'TYPE',
    scope: (RM.param('scope') || '').toUpperCase(),
    focus: RM.param('focus'),
    depth: Number(RM.param('depth')) || 1,
    layout: RM.param('layout') || 'force',
    nodeKinds: null,
    relKinds: null,
    includeInferred: RM.param('inferred') !== 'false',
    selected: null
  };
  if (!['ALL', 'NEIGHBORHOOD', 'DEPENDENTS', 'DEPENDENCIES'].includes(state.scope)) state.scope = state.focus ? 'NEIGHBORHOOD' : 'ALL';
  let cy = null;
  let graphRequest = 0;
  let detailRequest = 0;
  let lastResponse = null;

  // ---------------------------------------------------------------- URL / controls

  function syncUrl() {
    RM.setParams({ layer: state.layer === 'TYPE' ? null : state.layer, scope: state.scope === 'ALL' ? null : state.scope,
      focus: state.focus, depth: state.depth === 1 ? null : state.depth, layout: state.layout === 'force' ? null : state.layout,
      inferred: state.includeInferred ? null : 'false' });
  }

  function syncControls() {
    document.querySelectorAll('[data-layer]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.layer === state.layer)));
    scopeSelect.value = state.scope;
    depthSelect.value = String(state.depth);
    depthSelect.disabled = state.scope !== 'NEIGHBORHOOD';
    layoutSelect.value = state.layout;
    for (const option of scopeSelect.options) {
      option.disabled = option.value !== 'ALL' && !state.focus;
    }
    const active = (state.nodeKinds ? 1 : 0) + (state.relKinds ? 1 : 0) + (state.includeInferred ? 0 : 1);
    const badge = document.getElementById('filter-count');
    badge.textContent = String(active);
    badge.classList.toggle('hidden', active === 0);
  }

  // ---------------------------------------------------------------- graph loading

  function showOverlay(node) {
    replace(overlay, node ? el('div', { class: 'card' }, node) : null);
    overlay.classList.toggle('hidden', !node);
  }

  async function loadGraph() {
    if (!state.analysis) return;
    const request = ++graphRequest;
    syncControls();
    syncUrl();
    showOverlay(RM.loading('Loading graph…'));
    banner.classList.add('hidden');
    const query = {
      analysisId: state.analysis.id, layer: state.layer, scope: state.scope,
      focus: state.scope === 'ALL' ? null : state.focus, depth: state.scope === 'NEIGHBORHOOD' ? state.depth : null,
      nodeKinds: state.nodeKinds, relationshipKinds: state.relKinds, includeInferred: state.includeInferred
    };
    let response;
    try {
      response = await api(RM.project.path('/graph' + RM.qs(query)));
    } catch (e) {
      if (request !== graphRequest) return;
      if (e.code === 'NODE_NOT_FOUND' || e.code === 'NODE_FILTERED_OUT') {
        toast('warning', 'Component unavailable', e.message);
        state.focus = null;
        state.scope = 'ALL';
        return loadGraph();
      }
      showOverlay(RM.errorState(e, loadGraph));
      return;
    }
    if (request !== graphRequest) return;
    lastResponse = response;
    render(response);
  }

  function render(response) {
    if (cy) {
      cy.destroy();
      cy = null;
    }
    const layerName = state.layer === 'TYPE' ? 'types' : 'methods';
    const snapshotNote = (RM.project.isLatest() ? '' : 'Historical snapshot #' + state.analysis.sequenceNumber + ' · ')
      + (state.analysis.status === 'PARTIAL' ? 'Partial snapshot · ' : '');
    summary.textContent = snapshotNote + fmt.number(response.nodes.length) + ' of ' + fmt.number(response.totalNodes) + ' ' + layerName
      + ' · ' + fmt.number(response.edges.length) + ' dependencies shown';
    if (response.truncated) {
      replace(banner, icon('info', 'icon-sm'), el('span', { text: response.truncationReason + ' (' + fmt.number(response.matchedNodes) + ' matched, limit ' + response.limit + ').' }));
      banner.classList.remove('hidden');
    }
    if (!response.nodes.length) {
      showOverlay(RM.empty('network', response.totalNodes ? 'No components match the filters' : 'This snapshot has no ' + layerName,
        response.totalNodes ? 'Adjust the node-type and relationship filters.' : (state.layer === 'METHOD' ? 'No methods or constructors were found.' : 'No types were found in the analyzed sources.'),
        response.totalNodes ? el('button', { type: 'button', class: 'btn', onclick: resetFilters }, 'Reset filters') : null));
      return;
    }
    showOverlay(null);
    try {
      cy = RM.graph.create(cyContainer, response, { layout: state.layout, focus: state.focus });
    } catch (e) {
      showOverlay(RM.errorState(e));
      return;
    }
    cy.on('tap', 'node', (evt) => selectNode(evt.target.id(), false));
    cy.on('tap', 'edge', (evt) => selectEdge(evt.target.data('source'), evt.target.data('target')));
    cy.on('tap', (evt) => {
      if (evt.target === cy) {
        RM.graph.clearHighlight(cy);
        state.selected = null;
        renderDetailsEmpty();
      }
    });
    if (state.focus && cy.getElementById(state.focus).nonempty()) {
      selectNode(state.focus, false);
    } else if (!state.selected) {
      renderDetailsEmpty();
    }
  }

  // ---------------------------------------------------------------- selection and details

  function selectNode(id, center) {
    state.selected = id;
    state.focus = id;
    syncControls();
    syncUrl();
    if (cy) {
      const node = cy.getElementById(id);
      if (node.nonempty()) {
        RM.graph.highlightNeighborhood(cy, id);
        cy.$(':selected').unselect();
        node.select();
        if (center) cy.animate({ center: { eles: node }, zoom: Math.max(cy.zoom(), 1) }, { duration: 250 });
      }
    }
    loadNodeDetails(id);
  }

  function renderDetailsEmpty() {
    replace(details, el('div', { class: 'details-section' },
      RM.empty('crosshair', 'Select a component', 'Click a node, or search above. Arrows point from a component to what it depends on.')),
      el('div', { class: 'details-section small muted' },
        el('h3', { text: 'Reading the graph' }),
        el('ul', null,
          el('li', { text: 'A → B means A depends on B.' }),
          el('li', { text: 'Blue: what the selection depends on. Amber: what depends on the selection (potentially affected by a change).' }),
          el('li', { text: 'Dashed edges are inferred; red dashed borders mark components in a cycle.' }))));
  }

  function neighborList(items, total, kind) {
    if (!items.length) return el('p', { class: 'small faint', text: kind === 'dependencies' ? 'Depends on no other component.' : 'No component depends on this one.' });
    return el('div', null,
      el('ul', { class: 'list neighbor-list' }, items.map((n) => el('li', null,
        el('button', { type: 'button', class: 'list-button', onclick: () => goToNode(n.entity.id) },
          kindLabel(n.entity.kind),
          el('span', { class: 'truncate', title: n.entity.qualifiedName || n.entity.name, text: n.entity.name }),
          el('span', { class: 'spacer' }),
          n.resolution === 'INFERRED' ? el('span', { class: 'badge badge-outline', text: 'inferred' }) : null),
        el('div', { class: 'neighbor-kinds', text: n.kinds.map(fmt.relationship).join(', ') + ' · ' + n.referenceCount + ' reference(s)' })))),
      total > items.length ? el('p', { class: 'xsmall faint', text: 'Showing ' + items.length + ' of ' + total + '.' }) : null);
  }

  async function loadNodeDetails(id) {
    const request = ++detailRequest;
    replace(details, RM.loading('Loading details…'));
    let d;
    try {
      d = await api(RM.project.path('/node' + RM.qs({ analysisId: state.analysis.id, nodeId: id })));
    } catch (e) {
      if (request === detailRequest) replace(details, el('div', { class: 'details-section' }, RM.errorState(e, () => loadNodeDetails(id))));
      return;
    }
    if (request !== detailRequest) return;
    const e = d.entity;
    const m = e.metrics;
    const inView = cy && cy.getElementById(id).nonempty();
    const impactHref = '/projects/' + RM.project.id + '/impact?analysisId=' + state.analysis.id + '&node=' + encodeURIComponent(id)
      + (d.layer === 'METHOD' ? '&layer=METHOD' : '');
    replace(details,
      el('div', { class: 'details-section' },
        el('div', { class: 'row' }, kindLabel(e.kind), e.testSource ? el('span', { class: 'badge badge-outline', text: 'test' }) : null,
          m && m.inCycle ? el('span', { class: 'badge badge-danger', text: 'in cycle' }) : null,
          e.visibility ? el('span', { class: 'badge badge-outline', text: e.visibility }) : null),
        el('div', { class: 'details-title', text: e.kind === 'METHOD' || e.kind === 'CONSTRUCTOR' ? (e.declaringTypeName ? e.declaringTypeName + '.' : '') + (e.signature || e.name) : e.name }),
        el('div', { class: 'details-id', text: e.id }),
        e.filePath ? el('div', { class: 'small muted mono break', text: e.filePath + (e.line ? ':' + e.line : '') }) : null,
        !inView ? el('div', { class: 'alert alert-info', role: 'note' }, icon('info', 'icon-sm'), el('span', { text: 'This component is not in the current view.' })) : null),
      m ? el('div', { class: 'details-section' },
        el('div', { class: 'metric-row' },
          el('div', { class: 'metric' }, el('b', { text: fmt.number(m.directDependencies) }), el('span', { text: 'depends on' })),
          el('div', { class: 'metric' }, el('b', { text: fmt.number(m.directDependents) }), el('span', { text: 'direct dependents' })),
          el('div', { class: 'metric' }, el('b', { text: m.transitiveDependents === null ? '–' : fmt.number(m.transitiveDependents) }), el('span', { text: 'transitive dependents' }))),
        m.impactScore !== null ? el('div', { class: 'row' },
          el('span', { class: 'small muted', text: 'Stored impact score' }), el('strong', { text: fmt.decimal(m.impactScore) }), m.impactLevel ? levelBadge(m.impactLevel) : null) : null) : null,
      el('div', { class: 'details-section' },
        el('div', { class: 'row' },
          el('button', { type: 'button', class: 'btn btn-sm', onclick: () => setScope('NEIGHBORHOOD', id) }, icon('crosshair', 'icon-sm'), 'Focus'),
          el('button', { type: 'button', class: 'btn btn-sm', onclick: () => setScope('DEPENDENTS', id) }, icon('arrow-left-right', 'icon-sm'), 'Dependents'),
          el('button', { type: 'button', class: 'btn btn-sm', onclick: () => setScope('DEPENDENCIES', id) }, icon('git-branch', 'icon-sm'), 'Dependencies'),
          el('a', { class: 'btn btn-sm btn-primary', href: impactHref }, icon('zap', 'icon-sm'), 'Analyze impact'),
          el('button', { type: 'button', class: 'btn btn-sm btn-ghost', 'aria-label': 'Copy identifier', title: 'Copy identifier', onclick: () => copy(e.id) }, icon('copy', 'icon-sm')))),
      el('div', { class: 'details-section' }, el('h3', null, 'Depends on', el('span', { class: 'badge', text: String(d.dependencyCount) })), neighborList(d.dependencies, d.dependencyCount, 'dependencies')),
      el('div', { class: 'details-section' }, el('h3', null, 'Dependents', el('span', { class: 'badge', text: String(d.dependentCount) })), neighborList(d.dependents, d.dependentCount, 'dependents')),
      d.members.length ? el('div', { class: 'details-section' }, el('h3', null, 'Members', el('span', { class: 'badge', text: String(d.members.length) })),
        el('ul', { class: 'list neighbor-list' }, d.members.map((mem) => el('li', null,
          mem.kind === 'FIELD'
            ? el('div', { class: 'list-button' }, kindLabel('FIELD'), el('span', { class: 'truncate', text: mem.name }))
            : el('button', { type: 'button', class: 'list-button', title: 'Show in method graph', onclick: () => openMethod(mem.id) },
              kindLabel(mem.kind), el('span', { class: 'truncate', text: mem.qualifiedName ? mem.qualifiedName.slice(mem.qualifiedName.lastIndexOf('.') + 1) : mem.name }),
              el('span', { class: 'spacer' }), el('span', { class: 'xsmall faint', text: 'L' + mem.line })))))) : null,
      d.diagnosticCount ? el('div', { class: 'details-section' }, el('h3', null, 'Diagnostics', el('span', { class: 'badge badge-warning', text: String(d.diagnosticCount) })),
        el('ul', { class: 'list' }, d.diagnostics.map((g) => el('li', { class: 'small' },
          el('div', { class: 'cell-title', text: g.category.replace(/_/g, ' ').toLowerCase() + (g.line ? ' · line ' + g.line : '') }),
          el('div', { class: 'cell-sub', text: g.message }))))) : null);
  }

  async function selectEdge(source, target) {
    const request = ++detailRequest;
    replace(details, RM.loading('Loading relationship…'));
    try {
      const d = await api(RM.project.path('/edge' + RM.qs({ analysisId: state.analysis.id, layer: state.layer, source, target })));
      if (request !== detailRequest) return;
      replace(details,
        el('div', { class: 'details-section' },
          el('h3', { text: 'Dependency' }),
          el('div', { class: 'chain' },
            el('button', { type: 'button', class: 'link-button step', onclick: () => goToNode(d.source.id), text: d.source.name }),
            el('span', { class: 'arrow', text: 'depends on →' }),
            el('button', { type: 'button', class: 'link-button step step-target', onclick: () => goToNode(d.target.id), text: d.target.name })),
          el('p', { class: 'small muted', text: d.referenceCount + ' source reference(s) in ' + d.relationships.length + ' relationship kind(s).' })),
        d.relationships.map((r) => el('div', { class: 'details-section' },
          el('h3', null, r.kindLabel, el('span', { class: 'badge ' + (r.resolution === 'RESOLVED' ? 'badge-success' : 'badge-accent'), text: r.resolution.toLowerCase() })),
          el('p', { class: 'xsmall faint', text: r.referenceCount + ' reference(s): ' + r.resolvedCount + ' resolved, ' + r.inferredCount + ' inferred'
            + (r.samples.length < r.referenceCount ? '; first ' + r.samples.length + ' shown' : '') }),
          el('ul', { class: 'list' }, r.samples.map((s) => el('li', { class: 'small' },
            el('div', { class: 'mono break', text: s.filePath + ':' + s.line }),
            el('div', { class: 'cell-sub', text: s.detail + (s.rule ? ' · rule ' + s.rule : '') })))))));
    } catch (e) {
      if (request === detailRequest) replace(details, el('div', { class: 'details-section' }, RM.errorState(e)));
    }
  }

  function goToNode(id) {
    if (cy && cy.getElementById(id).nonempty()) {
      selectNode(id, true);
    } else {
      state.focus = id;
      state.scope = 'NEIGHBORHOOD';
      state.selected = id;
      loadGraph();
    }
  }

  function openMethod(id) {
    state.layer = 'METHOD';
    state.nodeKinds = null;
    state.relKinds = null;
    state.focus = id;
    state.scope = 'NEIGHBORHOOD';
    renderFilters();
    loadGraph();
  }

  function setScope(scope, focus) {
    state.scope = scope;
    state.focus = focus;
    loadGraph();
  }

  async function copy(text) {
    try {
      await navigator.clipboard.writeText(text);
      toast('success', 'Identifier copied', null, 2500);
    } catch (e) {
      toast('warning', 'Copy failed', 'The clipboard is not available in this browser context.');
    }
  }

  // ---------------------------------------------------------------- search

  let searchRequest = 0;
  let activeIndex = -1;
  const runSearch = RM.debounce(async () => {
    const q = searchInput.value.trim();
    if (!q || !state.analysis) {
      closeSearch();
      return;
    }
    const request = ++searchRequest;
    try {
      const result = await api(RM.project.path('/nodes' + RM.qs({ analysisId: state.analysis.id, layer: state.layer, q, limit: 15 })));
      if (request !== searchRequest) return;
      activeIndex = -1;
      replace(searchResults, result.items.length ? result.items.map((item, i) => el('button', {
        type: 'button', class: 'list-button', role: 'option', id: 'search-option-' + i, 'aria-selected': 'false',
        onclick: () => { closeSearch(); goToNode(item.entity.id); }
      }, el('span', { class: 'row' }, kindLabel(item.entity.kind), el('strong', { class: 'truncate', text: item.entity.name })),
        el('span', { class: 'cell-sub', text: item.entity.qualifiedName || item.entity.id })))
        : el('div', { class: 'small muted', text: 'No matches for “' + q + '”.' }));
      if (result.truncated) searchResults.appendChild(el('div', { class: 'xsmall faint', text: 'Showing ' + result.items.length + ' of ' + result.total + ' matches; refine the search.' }));
      searchResults.classList.remove('hidden');
      searchInput.setAttribute('aria-expanded', 'true');
    } catch (e) {
      if (request === searchRequest) RM.errorToast(e, 'Search failed');
    }
  }, 200);

  function closeSearch() {
    searchResults.classList.add('hidden');
    searchInput.setAttribute('aria-expanded', 'false');
    searchInput.removeAttribute('aria-activedescendant');
  }

  searchInput.addEventListener('input', runSearch);
  searchInput.addEventListener('keydown', (e) => {
    const options = Array.from(searchResults.querySelectorAll('[role=option]'));
    if (e.key === 'Escape') { closeSearch(); return; }
    if (!options.length) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      activeIndex = (activeIndex + (e.key === 'ArrowDown' ? 1 : options.length - 1)) % options.length;
      options.forEach((o, i) => o.setAttribute('aria-selected', String(i === activeIndex)));
      searchInput.setAttribute('aria-activedescendant', options[activeIndex].id);
      options[activeIndex].scrollIntoView({ block: 'nearest' });
    } else if (e.key === 'Enter') {
      e.preventDefault();
      (options[activeIndex] || options[0]).click();
    }
  });
  document.addEventListener('click', (e) => {
    if (!searchResults.contains(e.target) && e.target !== searchInput) closeSearch();
  });

  // ---------------------------------------------------------------- filters

  function renderFilters() {
    const nodeBox = document.getElementById('node-kind-filters');
    const relBox = document.getElementById('relationship-filters');
    replace(nodeBox, NODE_KINDS[state.layer].map((k) => el('label', { class: 'check' },
      el('input', { type: 'checkbox', value: k, checked: !state.nodeKinds || state.nodeKinds.includes(k) }), kindLabel(k))));
    replace(relBox, REL_KINDS[state.layer].map((k) => el('label', { class: 'check' },
      el('input', { type: 'checkbox', value: k, checked: !state.relKinds || state.relKinds.includes(k) }), fmt.relationship(k))));
    document.getElementById('include-inferred').checked = state.includeInferred;
  }

  function toggleFilters(open) {
    filterPanel.classList.toggle('hidden', !open);
    filterButton.setAttribute('aria-expanded', String(open));
    if (open) {
      renderFilters();
      const first = filterPanel.querySelector('input');
      if (first) first.focus();
    }
  }

  function selected(box) {
    return Array.from(box.querySelectorAll('input:checked')).map((i) => i.value);
  }

  function applyFilters() {
    const nodes = selected(document.getElementById('node-kind-filters'));
    const rels = selected(document.getElementById('relationship-filters'));
    if (!nodes.length) {
      toast('warning', 'Select at least one node type');
      return;
    }
    if (!rels.length) {
      toast('warning', 'Select at least one relationship kind');
      return;
    }
    state.nodeKinds = nodes.length === NODE_KINDS[state.layer].length ? null : nodes;
    state.relKinds = rels.length === REL_KINDS[state.layer].length ? null : rels;
    state.includeInferred = document.getElementById('include-inferred').checked;
    toggleFilters(false);
    filterButton.focus();
    loadGraph();
  }

  function resetFilters() {
    state.nodeKinds = null;
    state.relKinds = null;
    state.includeInferred = true;
    renderFilters();
    toggleFilters(false);
    loadGraph();
  }

  filterButton.addEventListener('click', () => toggleFilters(filterPanel.classList.contains('hidden')));
  document.getElementById('filters-apply').addEventListener('click', applyFilters);
  document.getElementById('filters-reset').addEventListener('click', resetFilters);
  document.getElementById('filters-close').addEventListener('click', () => { toggleFilters(false); filterButton.focus(); });
  filterPanel.addEventListener('keydown', (e) => { if (e.key === 'Escape') { toggleFilters(false); filterButton.focus(); } });
  document.getElementById('kinds-all').addEventListener('click', () => document.querySelectorAll('#relationship-filters input').forEach((i) => { i.checked = true; }));
  document.getElementById('kinds-none').addEventListener('click', () => document.querySelectorAll('#relationship-filters input').forEach((i) => { i.checked = false; }));

  // ---------------------------------------------------------------- toolbar

  document.querySelectorAll('[data-layer]').forEach((button) => button.addEventListener('click', () => {
    if (state.layer === button.dataset.layer) return;
    state.layer = button.dataset.layer;
    state.nodeKinds = null;
    state.relKinds = null;
    state.focus = null;
    state.selected = null;
    state.scope = 'ALL';
    renderDetailsEmpty();
    loadGraph();
  }));
  scopeSelect.addEventListener('change', () => {
    state.scope = scopeSelect.value;
    if (state.scope !== 'ALL' && !state.focus) {
      toast('info', 'Select a component first');
      state.scope = 'ALL';
    }
    loadGraph();
  });
  depthSelect.addEventListener('change', () => { state.depth = Number(depthSelect.value); loadGraph(); });
  layoutSelect.addEventListener('change', () => {
    state.layout = layoutSelect.value;
    syncUrl();
    if (cy) RM.graph.runLayout(cy, state.layout, state.focus);
  });
  document.getElementById('zoom-in').addEventListener('click', () => cy && cy.zoom({ level: cy.zoom() * 1.25, renderedPosition: { x: cyContainer.clientWidth / 2, y: cyContainer.clientHeight / 2 } }));
  document.getElementById('zoom-out').addEventListener('click', () => cy && cy.zoom({ level: cy.zoom() / 1.25, renderedPosition: { x: cyContainer.clientWidth / 2, y: cyContainer.clientHeight / 2 } }));
  document.getElementById('fit-graph').addEventListener('click', () => cy && cy.fit(undefined, 40));
  document.getElementById('reset-layout').addEventListener('click', () => {
    if (!cy) return;
    RM.graph.runLayout(cy, state.layout, state.focus);
    if (state.selected) RM.graph.highlightNeighborhood(cy, state.selected);
  });

  // ---------------------------------------------------------------- init

  async function start(analysis) {
    state.analysis = analysis;
    if (!analysis) {
      showOverlay(RM.empty('history', 'No completed analysis', 'This project has no snapshot with results yet. Check the project overview for the analysis status.',
        el('a', { class: 'btn', href: '/projects/' + RM.project.id }, 'Open overview')));
      summary.textContent = '';
      renderDetailsEmpty();
      return;
    }
    await loadGraph();
  }

  RM.project.onChange((analysis) => {
    state.selected = null;
    renderDetailsEmpty();
    start(analysis);
  });
  renderDetailsEmpty();
  syncControls();
  RM.project.init().then(start).catch((e) => showOverlay(RM.errorState(e)));
})();
