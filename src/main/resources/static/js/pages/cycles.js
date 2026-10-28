(function () {
  'use strict';
  const { el, icon, api, fmt, replace, kindLabel } = RM;
  const listRoot = document.getElementById('cycle-list');
  const detailRoot = document.getElementById('cycle-detail');
  const notesRoot = document.getElementById('snapshot-notes');
  const inferredBox = document.getElementById('cycles-inferred');
  const state = { analysis: null, layer: (RM.param('layer') || 'TYPE').toUpperCase() === 'METHOD' ? 'METHOD' : 'TYPE', data: null, selected: null };
  let request = 0;
  let graph = null;

  function stepRow(step) {
    const ref = step.firstReference;
    return el('li', { class: 'list-item' },
      el('div', { class: 'stack', style: null },
        el('div', { class: 'chain' }, el('span', { class: 'step', text: step.from.name }), el('span', { class: 'arrow', text: 'depends on →' }),
          el('span', { class: 'step step-target', text: step.to.name })),
        el('div', { class: 'cell-sub', text: step.kinds.map(fmt.relationship).join(', ')
          + (ref ? ' · ' + ref.filePath + ':' + ref.line + ' (' + ref.detail + ')' : '') })));
  }

  async function showDetail(component) {
    state.selected = component;
    listRoot.querySelectorAll('[data-index]').forEach((b) => {
      if (Number(b.dataset.index) === component.index) b.setAttribute('aria-current', 'true'); else b.removeAttribute('aria-current');
    });
    if (graph) {
      graph.destroy();
      graph = null;
    }
    const container = el('div', { class: 'cy' });
    replace(detailRoot,
      el('div', { class: 'stack' },
        el('div', { class: 'row' }, el('span', { class: 'badge badge-danger', text: component.selfLoop ? 'self-reference' : component.size + ' members' }),
          el('span', { class: 'small muted', text: 'Every member (transitively) depends on every other member.' })),
        el('div', null, el('h3', { class: 'small', text: 'Representative cycle' }),
          el('ul', { class: 'list' }, component.steps.map(stepRow))),
        el('div', { class: 'mini-graph' }, container),
        el('div', null, el('h3', { class: 'small', text: 'Members' }),
          el('ul', { class: 'list' }, component.members.map((m) => el('li', { class: 'list-item' }, kindLabel(m.kind),
            el('a', { class: 'truncate', title: m.qualifiedName || m.name, href: '/projects/' + RM.project.id + '/explorer' + RM.qs({ analysisId: state.analysis.id, layer: state.layer === 'TYPE' ? null : state.layer, focus: m.id, scope: 'NEIGHBORHOOD' }), text: m.name }),
            el('span', { class: 'xsmall faint mono truncate fill-end', title: m.filePath || '', text: m.filePath ? m.filePath.split('/').pop() + ':' + m.line : '' })))),
          component.membersTruncated ? el('p', { class: 'xsmall faint', text: 'Only the first members are listed.' }) : null)));
    const current = ++request;
    try {
      const data = await api(RM.project.path('/graph' + RM.qs({ analysisId: state.analysis.id, layer: state.layer, scope: 'CYCLE',
        component: component.index, includeInferred: inferredBox.checked, limit: 200 })));
      if (current !== request) return;
      graph = RM.graph.create(container, data, { layout: 'circle' });
      const cycleIds = component.cycle.map((c) => c.id);
      graph.edges().forEach((edge) => {
        for (let i = 0; i + 1 < cycleIds.length; i++) {
          if (edge.data('source') === cycleIds[i] && edge.data('target') === cycleIds[i + 1]) edge.addClass('path');
        }
      });
    } catch (e) {
      replace(container, RM.errorState(e));
    }
  }

  async function load() {
    if (!state.analysis) return;
    const current = ++request;
    replace(listRoot, RM.loading('Detecting cycles…'));
    replace(detailRoot, el('p', { class: 'muted small', text: 'Select a cyclic component to inspect it.' }));
    const query = RM.qs({ analysisId: state.analysis.id, layer: state.layer, includeInferred: inferredBox.checked });
    replace(document.getElementById('cycle-downloads'),
      RM.downloadLink(RM.project.path('/reports/cycles.csv' + RM.qs({ analysisId: state.analysis.id, layer: state.layer })), 'CSV'));
    try {
      const data = await api(RM.project.path('/cycles' + query));
      if (current !== request) return;
      state.data = data;
      document.getElementById('cycles-count').textContent = data.componentCount + ' component(s) · ' + data.nodesInCycles + ' ' + (state.layer === 'TYPE' ? 'types' : 'callables') + ' involved';
      document.getElementById('cycles-note').textContent = data.note;
      if (!data.components.length) {
        replace(listRoot, RM.empty('shield-check', 'No circular dependencies', state.layer === 'TYPE'
          ? 'No group of types depends on itself in this snapshot.' : 'No recursive or mutually recursive calls were found.'));
        replace(detailRoot, el('p', { class: 'muted small', text: 'Nothing to inspect.' }));
        return;
      }
      replace(listRoot, el('ul', { class: 'list', 'aria-labelledby': 'cycles-heading' }, data.components.map((c) => el('li', null,
        el('button', { type: 'button', class: 'list-button', dataset: { index: String(c.index) }, onclick: () => showDetail(c) },
          el('span', { class: 'badge badge-danger', text: String(c.size) }),
          el('span', { class: 'truncate', text: c.cycle.map((m) => m.name).join(' → ') }))))),
        data.truncated ? el('p', { class: 'xsmall faint', text: 'Only the largest components are listed.' }) : null);
      const requested = Number(RM.param('component'));
      showDetail(data.components.find((c) => c.index === requested) || data.components[0]);
    } catch (e) {
      if (current === request) replace(listRoot, RM.errorState(e, load));
    }
  }

  document.querySelectorAll('[data-layer]').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.dataset.layer === state.layer));
    b.addEventListener('click', () => {
      state.layer = b.dataset.layer;
      document.querySelectorAll('[data-layer]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
      RM.setParams({ layer: state.layer === 'TYPE' ? null : state.layer });
      load();
    });
  });
  inferredBox.addEventListener('change', load);

  function start(analysis) {
    state.analysis = analysis;
    replace(notesRoot, RM.project.banner());
    if (!analysis) {
      replace(listRoot, RM.empty('history', 'No completed analysis', 'Cycle detection needs a completed snapshot.'));
      return;
    }
    load();
  }
  RM.project.onChange(start);
  RM.project.init().then(start).catch((e) => replace(listRoot, RM.errorState(e)));
})();
