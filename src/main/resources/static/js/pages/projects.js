(function () {
  'use strict';
  const { el, icon, api, fmt, replace, statusBadge } = RM;
  const root = document.getElementById('projects-root');
  const filter = document.getElementById('project-filter');
  const count = document.getElementById('project-count');
  let projects = [];
  let pollTimer = null;

  function metric(label, value) {
    return el('span', null, el('b', { text: fmt.number(value) }), ' ', label);
  }

  function projectCard(p) {
    const a = p.latestAnalysis;
    const c = a && a.hasResults ? a.counts : null;
    const href = '/projects/' + p.id;
    return el('article', { class: 'card project-card' },
      el('div', { class: 'row-between' },
        el('span', { class: 'badge badge-outline' }, icon(p.sourceType === 'LOCAL_DIRECTORY' ? 'folder' : 'box', 'icon-sm'),
          p.sourceType === 'LOCAL_DIRECTORY' ? 'Local directory' : 'ZIP · ' + p.versionCount + ' version(s)'),
        a ? statusBadge(a.status) : el('span', { class: 'faint small', text: 'Not analyzed' })),
      el('h3', null, el('a', { href, text: p.name })),
      p.description ? el('p', { class: 'muted small break', text: p.description.length > 180 ? p.description.slice(0, 177) + '…' : p.description }) : null,
      c ? el('div', { class: 'metrics' }, metric('files', c.filesParsed), metric('types', c.typeCount),
        metric('dependencies', c.typeDependencyCount), metric('cycles', c.typeCyclicComponents)) : null,
      a && a.status === 'FAILED' ? el('div', { class: 'cell-sub', text: a.errorMessage || 'The last analysis failed.' }) : null,
      a && (a.status === 'RUNNING' || a.status === 'PENDING') ? el('div', { class: 'cell-sub', text: a.stage || 'Waiting to start…' }) : null,
      el('div', { class: 'row' },
        el('a', { class: 'btn btn-sm', href }, icon('box', 'icon-sm'), 'Overview'),
        p.defaultAnalysisId ? el('a', { class: 'btn btn-sm', href: href + '/explorer' }, icon('network', 'icon-sm'), 'Explore') : null,
        p.defaultAnalysisId ? el('a', { class: 'btn btn-sm', href: href + '/impact' }, icon('zap', 'icon-sm'), 'Impact') : null,
        el('span', { class: 'spacer' }),
        el('span', { class: 'xsmall faint', text: 'Updated ' + fmt.relative(p.updatedAt), title: fmt.date(p.updatedAt) })));
  }

  function render() {
    const needle = filter.value.trim().toLowerCase();
    const visible = projects.filter((p) => !needle || p.name.toLowerCase().includes(needle)
      || (p.description || '').toLowerCase().includes(needle));
    count.textContent = projects.length ? visible.length + ' of ' + projects.length + ' project(s)' : '';
    if (!projects.length) {
      replace(root, el('section', { class: 'card' }, RM.empty('folder-kanban', 'No projects yet',
        'Import a Java project to start analyzing its dependencies.',
        el('a', { class: 'btn btn-primary', href: '/import' }, icon('upload'), 'Import project'))));
      return;
    }
    if (!visible.length) {
      replace(root, el('section', { class: 'card' }, RM.empty('search', 'No matching projects', 'No project name or description contains “' + filter.value.trim() + '”.')));
      return;
    }
    replace(root, el('div', { class: 'project-cards' }, visible.map(projectCard)));
  }

  async function load() {
    try {
      projects = await api('/api/projects');
      render();
      clearTimeout(pollTimer);
      if (projects.some((p) => p.latestAnalysis && (p.latestAnalysis.status === 'RUNNING' || p.latestAnalysis.status === 'PENDING'))) {
        pollTimer = setTimeout(load, 3000);
      }
    } catch (e) {
      replace(root, RM.errorState(e, load));
    }
  }

  filter.addEventListener('input', render);
  document.getElementById('projects-toolbar').classList.add('snapshot-banner');
  load();
})();
