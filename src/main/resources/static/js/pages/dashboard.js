(function () {
  'use strict';
  const { el, icon, api, fmt, replace, statusBadge, levelBadge, kindLabel } = RM;
  const root = document.getElementById('dashboard-root');
  let pollTimer = null;

  function stat(label, value, hint, variant, iconName) {
    return el('div', { class: 'stat' + (variant ? ' stat-' + variant : '') },
      el('div', { class: 'stat-label' }, iconName ? icon(iconName, 'icon-sm') : null, label),
      el('div', { class: 'stat-value', text: fmt.number(value) }),
      hint ? el('div', { class: 'stat-hint', text: hint }) : null);
  }

  function card(title, iconName, body, footer) {
    return el('section', { class: 'card' },
      el('div', { class: 'card-header' }, el('h2', null, icon(iconName), title)),
      el('div', { class: 'card-body flush' }, body),
      footer ? el('div', { class: 'card-footer' }, footer) : null);
  }

  function projectUrl(projectId, section, analysisId) {
    return '/projects/' + projectId + (section ? '/' + section : '') + (analysisId ? '?analysisId=' + analysisId : '');
  }

  function recentTable(recent) {
    if (!recent.length) return RM.empty('history', 'No analyses yet', 'Analyses appear here after you import a project.');
    return el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('thead', null, el('tr', null, el('th', { text: 'Project' }), el('th', { text: 'Snapshot' }), el('th', { text: 'Status' }),
        el('th', { class: 'num hide-sm', text: 'Types' }), el('th', { class: 'hide-sm', text: 'When' }))),
      el('tbody', null, recent.map((r) => {
        const a = r.analysis;
        return el('tr', null,
          el('td', null, el('a', { href: projectUrl(r.projectId, null, a.hasResults ? a.id : null), class: 'cell-title', text: r.projectName })),
          el('td', null, '#' + a.sequenceNumber, a.versionNumber ? el('span', { class: 'faint', text: ' · v' + a.versionNumber }) : null),
          el('td', null, statusBadge(a.status), a.stage && (a.status === 'RUNNING') ? el('div', { class: 'cell-sub', text: a.stage }) : null,
            a.status === 'FAILED' && a.errorMessage ? el('div', { class: 'cell-sub', text: a.errorMessage }) : null),
          el('td', { class: 'num hide-sm', text: fmt.number(a.counts.typeCount) }),
          el('td', { class: 'hide-sm nowrap muted', text: fmt.relative(a.finishedAt || a.createdAt), title: fmt.date(a.finishedAt || a.createdAt) }));
      }))));
  }

  function hotspotTable(hotspots) {
    if (!hotspots.length) {
      return RM.empty('zap', 'No impact hotspots', 'Types with dependents appear here once a project has been analyzed.');
    }
    return el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('thead', null, el('tr', null, el('th', { text: 'Component' }), el('th', { class: 'num', text: 'Dependents' }),
        el('th', { class: 'num', text: 'Score' }), el('th', { text: 'Level' }))),
      el('tbody', null, hotspots.map((h) => el('tr', null,
        el('td', { class: 'break' },
          el('a', { class: 'cell-title', href: '/projects/' + h.projectId + '/impact?analysisId=' + h.analysisId + '&node=' + encodeURIComponent(h.entityId), text: h.name }),
          el('div', { class: 'cell-sub' }, kindLabel(h.kind), ' · ', h.projectName)),
        el('td', { class: 'num', text: fmt.number(h.transitiveDependents), title: h.directDependents + ' direct' }),
        el('td', { class: 'num', text: fmt.decimal(h.impactScore) }),
        el('td', null, levelBadge(h.level)))))));
  }

  function projectList(projects) {
    return el('ul', { class: 'list' }, projects.slice(0, 8).map((p) => {
      const latest = p.latestAnalysis;
      return el('li', { class: 'list-item' },
        icon(p.sourceType === 'LOCAL_DIRECTORY' ? 'folder' : 'box'),
        el('div', { class: 'truncate' },
          el('a', { class: 'cell-title', href: projectUrl(p.id), text: p.name }),
          el('div', { class: 'cell-sub', text: (p.versionCount ? p.versionCount + ' version(s) · ' : '') + 'updated ' + fmt.relative(p.updatedAt) })),
        el('span', { class: 'spacer' }),
        latest ? statusBadge(latest.status) : el('span', { class: 'faint small', text: 'Not analyzed' }));
    }));
  }

  function attention(data) {
    const items = [];
    for (const c of data.cycles) {
      items.push(el('li', { class: 'list-item' }, icon('repeat'),
        el('div', { class: 'truncate' },
          el('a', { class: 'cell-title', href: projectUrl(c.projectId, 'cycles', c.analysisId), text: c.projectName }),
          el('div', { class: 'cell-sub', text: c.cyclicComponents + ' cyclic component(s), ' + c.nodesInCycles + ' types involved' })),
        el('span', { class: 'spacer' }), el('span', { class: 'badge badge-danger', text: 'Cycles' })));
    }
    for (const w of data.warnings) {
      const parts = [];
      if (w.failedFiles) parts.push(w.failedFiles + ' file(s) not parsed');
      if (w.errors) parts.push(w.errors + ' error(s)');
      if (w.warnings) parts.push(w.warnings + ' warning(s)');
      if (w.unresolvedReferences) parts.push(w.unresolvedReferences + ' unresolved reference(s)');
      items.push(el('li', { class: 'list-item' }, icon('triangle-alert'),
        el('div', { class: 'truncate' },
          el('a', { class: 'cell-title', href: projectUrl(w.projectId, null, w.analysisId), text: w.projectName }),
          el('div', { class: 'cell-sub', text: parts.join(' · ') })),
        el('span', { class: 'spacer' }), el('span', { class: 'badge ' + (w.partial ? 'badge-warning' : 'badge-outline'), text: w.partial ? 'Partial' : 'Warnings' })));
    }
    if (!items.length) {
      return RM.empty('shield-check', 'Nothing needs attention', 'No circular dependencies, parse failures or warnings in the latest snapshots.');
    }
    return el('ul', { class: 'list' }, items);
  }

  function render(data) {
    const t = data.totals;
    if (!data.projects.length) {
      replace(root, el('section', { class: 'card' }, RM.empty('network', 'Welcome to RippleMap',
        'Import a Java project as a ZIP archive. RippleMap parses its sources, builds a dependency graph and lets you simulate the impact of changes.',
        el('a', { class: 'btn btn-primary', href: '/import' }, icon('upload'), 'Import your first project'))));
      return;
    }
    replace(root,
      el('div', { class: 'stats', role: 'group', 'aria-label': 'Totals' },
        stat('Projects', t.projects, t.analyzedProjects + ' with results', null, 'folder-kanban'),
        stat('Java files analyzed', t.analyzedFiles, t.failedFiles ? t.failedFiles + ' could not be parsed' : 'all parsed', t.failedFiles ? 'warning' : null, 'file-code'),
        stat('Types', t.types, fmt.number(t.methods) + ' methods & constructors', null, 'boxes'),
        stat('Type dependencies', t.dependencies, 'distinct source → target pairs', 'accent', 'network'),
        stat('Unresolved references', t.unresolvedReferences, 'may hide dependencies', t.unresolvedReferences ? 'warning' : null, 'circle-help'),
        stat('Cyclic components', t.cyclicComponents, 'in latest snapshots', t.cyclicComponents ? 'danger' : null, 'repeat')),
      el('p', { class: 'xsmall faint', text: 'Totals cover the latest completed snapshot of each project.' }),
      el('div', { class: 'grid grid-2' },
        card('Recent analyses', 'history', recentTable(data.recent)),
        card('Highest-impact components', 'zap', hotspotTable(data.hotspots), 'Structural impact score of the latest snapshots; not a probability of failure.')),
      el('div', { class: 'grid grid-2' },
        card('Projects', 'folder-kanban', projectList(data.projects), el('a', { href: '/projects', text: 'All projects (' + data.projects.length + ')' })),
        card('Needs attention', 'triangle-alert', attention(data))));
    const active = data.recent.some((r) => r.analysis.status === 'PENDING' || r.analysis.status === 'RUNNING');
    clearTimeout(pollTimer);
    if (active) pollTimer = setTimeout(load, 3000);
  }

  async function load() {
    try {
      render(await api('/api/dashboard'));
    } catch (e) {
      replace(root, RM.errorState(e, load));
    }
  }

  // add spacing between the stacked sections without inline styles
  root.classList.add('stack');
  load();
})();
