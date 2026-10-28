(function () {
  'use strict';
  const { el, icon, api, fmt, replace, clear, statusBadge, levelBadge, kindLabel, toast } = RM;
  const projectId = RM.project.id;
  const projectCard = document.getElementById('project-card');
  const activeRoot = document.getElementById('active-analysis');
  const notesRoot = document.getElementById('snapshot-notes');
  const snapshotRoot = document.getElementById('snapshot-root');
  let detail = null;
  let following = null;

  // ---------------------------------------------------------------- project card

  function sourceDescription(p, versions) {
    if (p.sourceType === 'LOCAL_DIRECTORY') return el('span', { class: 'mono break', text: p.localPath });
    const latest = versions[0];
    if (!latest) return 'No stored version';
    return el('span', null, versions.length + ' version(s); latest v' + latest.versionNumber,
      latest.originalFilename ? ' (' + latest.originalFilename + ')' : '', ', ' + fmt.number(latest.javaFileCount) + ' Java files, imported ' + fmt.date(latest.importedAt));
  }

  function renderProjectCard() {
    const p = detail.project;
    const isZip = p.sourceType === 'ZIP_UPLOAD';
    const actions = el('div', { class: 'row' },
      el('button', { type: 'button', class: 'btn btn-sm', onclick: editProject }, icon('pencil', 'icon-sm'), 'Edit details'),
      isZip ? el('button', { type: 'button', class: 'btn btn-sm btn-primary', onclick: uploadVersion, disabled: detail.analysisActive }, icon('upload', 'icon-sm'), 'Upload new version') : null,
      el('button', { type: 'button', class: 'btn btn-sm', onclick: reanalyze, disabled: detail.analysisActive },
        icon('refresh-cw', 'icon-sm'), isZip ? 'Re-analyze version' : 'Rescan directory'),
      el('button', { type: 'button', class: 'btn btn-sm btn-danger', onclick: deleteProject, disabled: detail.analysisActive }, icon('trash-2', 'icon-sm'), 'Delete'));
    replace(projectCard,
      el('div', { class: 'card-body' },
        el('div', { class: 'row-between' },
          el('div', { class: 'stack' },
            el('h2', { id: 'project-heading', class: 'break', text: p.name }),
            p.description ? el('p', { class: 'muted break', text: p.description }) : el('p', { class: 'faint small', text: 'No description' })),
          actions),
        el('dl', { class: 'kv' },
          el('dt', { text: 'Source' }), el('dd', null, sourceDescription(p, detail.versions)),
          el('dt', { text: 'Default scan scope' }), el('dd', { text: p.includeTests ? 'Production and test sources' : 'Production sources only' }),
          el('dt', { text: 'Snapshots' }), el('dd', { text: detail.analysisCount + ' of at most ' + detail.maxSnapshots }),
          el('dt', { text: 'Created' }), el('dd', { text: fmt.date(p.createdAt) }),
          el('dt', { text: 'Last activity' }), el('dd', { text: fmt.date(p.updatedAt) }))));
  }

  // ---------------------------------------------------------------- actions

  function editProject() {
    const p = detail.project;
    const name = el('input', { class: 'input', maxlength: 100, value: p.name, autocomplete: 'off' });
    const description = el('textarea', { class: 'textarea', maxlength: 2000 });
    description.value = p.description || '';
    const tests = el('input', { type: 'checkbox', checked: p.includeTests });
    RM.formDialog({
      title: 'Edit project', submitLabel: 'Save changes',
      content: [RM.field('Name', name, { name: 'name' }), RM.field('Description', description, { name: 'description', optional: true }),
        el('label', { class: 'check' }, tests, 'Include test sources by default')],
      onSubmit: async () => {
        await api('/api/projects/' + projectId, { method: 'PATCH', json: { name: name.value, description: description.value, includeTests: tests.checked } });
        toast('success', 'Project updated');
        await loadProject();
      }
    });
  }

  function uploadVersion() {
    RM.projectActions.uploadVersion(detail, async (analysis) => {
      await loadProject();
      follow(analysis);
    });
  }

  function reanalyze() {
    RM.projectActions.reanalyze(detail, async (analysis) => {
      await loadProject();
      follow(analysis);
    });
  }

  async function deleteProject() {
    const p = detail.project;
    const ok = await RM.confirm({
      title: 'Delete project?', danger: true, confirmLabel: 'Delete project', requireText: p.name,
      message: 'This permanently deletes “' + p.name + '”, all ' + detail.versions.length + ' stored version(s) and '
        + detail.analysisCount + ' snapshot(s). This cannot be undone.'
    });
    if (!ok) return;
    try {
      await api('/api/projects/' + projectId, { method: 'DELETE' });
      toast('success', 'Project deleted');
      window.location.href = '/projects';
    } catch (e) {
      RM.errorToast(e, 'Could not delete the project');
    }
  }

  // ---------------------------------------------------------------- active analysis

  function follow(analysis) {
    if (following === analysis.id) return;
    following = analysis.id;
    const render = (a) => replace(activeRoot, el('section', { class: 'card' }, el('div', { class: 'card-body stack' },
      el('div', { class: 'row-between' },
        el('div', { class: 'row' }, statusBadge(a.status), el('strong', { text: 'Snapshot #' + a.sequenceNumber + (a.versionNumber ? ' of version v' + a.versionNumber : '') })),
        el('button', { type: 'button', class: 'btn btn-sm btn-danger', onclick: () => cancel(a) }, icon('ban', 'icon-sm'), 'Cancel')),
      el('div', { class: 'progress progress-indeterminate' }, el('div', { class: 'progress-bar' })),
      el('div', { class: 'small muted', text: a.stage || 'Waiting for a worker…' }))));
    render(analysis);
    RM.poll(async () => {
      const a = await api('/api/projects/' + projectId + '/analyses/' + analysis.id);
      if (a.status === 'PENDING' || a.status === 'RUNNING') { render(a); return false; }
      following = null;
      clear(activeRoot);
      if (a.hasResults) {
        toast('success', 'Snapshot #' + a.sequenceNumber + ' ' + (a.status === 'PARTIAL' ? 'finished with partial results' : 'completed'));
        RM.setParams({ analysisId: a.id });
      } else {
        toast('error', 'Analysis failed', a.errorMessage || '');
      }
      await loadProject();
      await initSnapshots();
      return true;
    }, 1500).catch((e) => RM.errorToast(e, 'Lost track of the analysis'));
  }

  function cancel(a) {
    return RM.projectActions.cancelAnalysis(projectId, a);
  }

  // ---------------------------------------------------------------- snapshot content

  function stat(label, value, hint, variant) {
    return el('div', { class: 'stat' + (variant ? ' stat-' + variant : '') },
      el('div', { class: 'stat-label', text: label }), el('div', { class: 'stat-value', text: fmt.number(value) }),
      hint ? el('div', { class: 'stat-hint', text: hint }) : null);
  }

  function referenceBreakdown(c) {
    const rows = [
      ['Resolved by symbol solver', c.referencesResolved, 'badge-success'],
      ['Inferred by documented rules', c.referencesInferred, 'badge-accent'],
      ['Unresolved – may hide dependencies', c.referencesUnresolved, 'badge-warning'],
      ['Unresolved – likely library', c.referencesLikelyExternal, 'badge-outline'],
      ['External (JDK, libraries, generated code)', c.referencesExternal, 'badge-outline'],
      ['Internal (self / local types)', c.referencesInternal, 'badge-outline']];
    const total = rows.reduce((sum, r) => sum + (r[1] || 0), 0) || 1;
    return el('div', { class: 'table-wrap' }, el('table', { class: 'table table-compact' },
      el('tbody', null, rows.map(([label, value, badge]) => el('tr', null,
        el('td', null, el('span', { class: 'badge ' + badge, text: label })),
        el('td', { class: 'num', text: fmt.number(value) }),
        el('td', { class: 'num faint', text: fmt.decimal(100 * (value || 0) / total) + ' %' }))))));
  }

  async function hotspots(analysis) {
    const target = el('div', null, RM.loading('Ranking types…'));
    try {
      const result = await api(RM.project.path('/nodes' + RM.qs({ analysisId: analysis.id, layer: 'TYPE', limit: 10 })));
      const items = result.items.filter((i) => i.impactScore !== null && i.impactScore > 0);
      if (!items.length) {
        replace(target, RM.empty('zap', 'No type has dependents', 'Every type is independent of the others in this snapshot.'));
      } else {
        replace(target, el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
          el('thead', null, el('tr', null, el('th', { text: 'Type' }), el('th', { class: 'num', text: 'Direct' }),
            el('th', { class: 'num', text: 'Transitive' }), el('th', { class: 'num', text: 'Score' }))),
          el('tbody', null, items.map((i) => el('tr', null,
            el('td', { class: 'break' }, el('a', { class: 'cell-title', href: '/projects/' + projectId + '/impact?analysisId=' + analysis.id + '&node=' + encodeURIComponent(i.entity.id), text: i.entity.name }),
              el('div', { class: 'cell-sub' }, kindLabel(i.entity.kind), el('span', { class: 'hide-sm', text: ' · ' + (i.entity.filePath || '') }))),
            el('td', { class: 'num', text: fmt.number(i.directDependents) }),
            el('td', { class: 'num', text: fmt.number(i.transitiveDependents) }),
            el('td', { class: 'num', text: fmt.decimal(i.impactScore) })))))));
      }
    } catch (e) {
      replace(target, RM.errorState(e));
    }
    return target;
  }

  function diagnosticsCard(analysis) {
    const body = el('div', null, RM.loading('Loading diagnostics…'));
    const severity = el('select', { class: 'select select-sm', 'aria-label': 'Filter diagnostics by severity' },
      el('option', { value: '', text: 'All severities' }), el('option', { value: 'ERROR', text: 'Errors' }),
      el('option', { value: 'WARNING', text: 'Warnings' }), el('option', { value: 'INFO', text: 'Information' }));
    let page = 0;
    const size = 25;
    async function load() {
      try {
        const data = await api(RM.project.path('/analyses/' + analysis.id + '/diagnostics' + RM.qs({ severity: severity.value, page, size })));
        const pages = Math.max(1, Math.ceil(data.total / size));
        replace(body,
          data.categories.length ? el('div', { class: 'row' }, data.categories.map((c) =>
            el('span', { class: 'badge ' + (c.severity === 'ERROR' ? 'badge-danger' : c.severity === 'WARNING' ? 'badge-warning' : 'badge-info'),
              title: c.description, text: c.category.replace(/_/g, ' ').toLowerCase() + ': ' + fmt.number(c.count) }))) : null,
          data.storageTruncated ? el('p', { class: 'xsmall faint', text: 'Only the first stored diagnostics are listed; counts are exact.' }) : null,
          data.items.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table table-compact' },
            el('thead', null, el('tr', null, el('th', { text: 'Severity' }), el('th', { class: 'hide-sm', text: 'Category' }), el('th', { class: 'hide-sm', text: 'Location' }), el('th', { text: 'Message' }))),
            el('tbody', null, data.items.map((d) => el('tr', null,
              el('td', null, el('span', { class: 'badge ' + (d.severity === 'ERROR' ? 'badge-danger' : d.severity === 'WARNING' ? 'badge-warning' : 'badge-info'), text: d.severity })),
              el('td', { class: 'small nowrap hide-sm', text: d.category.replace(/_/g, ' ').toLowerCase() }),
              el('td', { class: 'small mono break hide-sm', text: d.filePath ? d.filePath + (d.line ? ':' + d.line : '') : '–' }),
              el('td', { class: 'small break' }, d.message, d.symbol ? el('div', { class: 'cell-sub mono', text: d.symbol }) : null))))))
            : RM.empty('circle-check', 'No diagnostics', severity.value ? 'No diagnostics with this severity.' : 'The analysis reported no problems.'),
          data.total > size ? el('div', { class: 'row-between card-footer' },
            el('span', { text: 'Page ' + (page + 1) + ' of ' + pages + ' · ' + fmt.number(data.total) + ' diagnostics' }),
            el('div', { class: 'row' },
              el('button', { type: 'button', class: 'btn btn-sm', disabled: page === 0, onclick: () => { page--; load(); } }, icon('chevron-left', 'icon-sm'), 'Previous'),
              el('button', { type: 'button', class: 'btn btn-sm', disabled: page + 1 >= pages, onclick: () => { page++; load(); } }, 'Next', icon('chevron-right', 'icon-sm')))) : null);
      } catch (e) {
        replace(body, RM.errorState(e, load));
      }
    }
    severity.addEventListener('change', () => { page = 0; load(); });
    load();
    return el('section', { class: 'card' },
      el('div', { class: 'card-header' }, el('h2', null, icon('triangle-alert'), 'Diagnostics'), severity),
      el('div', { class: 'card-body' }, body));
  }

  function filesCard(analysis) {
    const body = el('div', null, RM.loading('Loading files…'));
    api(RM.project.path('/analyses/' + analysis.id + '/files')).then((data) => {
      const failed = data.files.filter((f) => f.status === 'FAILED');
      const shown = (failed.length ? failed : data.files).slice(0, 100);
      replace(body,
        el('p', { class: 'small muted', text: fmt.number(data.total) + ' file(s) in scope, ' + failed.length + ' could not be analyzed.'
          + (failed.length ? ' Files that failed are listed first.' : '') }),
        el('div', { class: 'table-wrap table-scroll', tabindex: '0', role: 'region', 'aria-label': 'Source files' }, el('table', { class: 'table table-compact' },
          el('thead', null, el('tr', null, el('th', { text: 'File' }), el('th', { text: 'Status' }), el('th', { class: 'num hide-sm', text: 'Lines' }), el('th', { class: 'num hide-sm', text: 'Types' }))),
          el('tbody', null, shown.map((f) => el('tr', null,
            el('td', { class: 'mono small break' }, f.path, f.testSource ? el('span', { class: 'badge badge-outline', text: 'test' }) : null),
            el('td', null, el('span', { class: 'badge ' + (f.status === 'FAILED' ? 'badge-danger' : 'badge-success'), text: f.status === 'FAILED' ? 'Failed' : 'Parsed' })),
            el('td', { class: 'num hide-sm', text: fmt.number(f.lineCount) }),
            el('td', { class: 'num hide-sm', text: fmt.number(f.typeCount) })))))),
        data.total > shown.length ? el('p', { class: 'xsmall faint', text: 'Showing ' + shown.length + ' of ' + data.total + '. The full list is in the dependency and entity CSV reports.' }) : null);
    }).catch((e) => replace(body, RM.errorState(e)));
    return el('section', { class: 'card' }, el('div', { class: 'card-header' }, el('h2', null, icon('file-code'), 'Source files')),
      el('div', { class: 'card-body' }, body));
  }

  async function renderSnapshot(analysis) {
    replace(notesRoot, RM.project.banner());
    if (!analysis) {
      const latest = RM.project.analyses[0];
      replace(snapshotRoot, el('section', { class: 'card' }, RM.empty('history', 'No completed analysis yet',
        latest && latest.status === 'FAILED' ? 'The latest analysis failed: ' + (latest.errorMessage || 'unknown error') : 'Results appear here when the first analysis finishes.')));
      return;
    }
    const c = analysis.counts;
    const header = el('div', { class: 'row-between' },
      el('h2', { class: 'row' }, 'Snapshot #' + analysis.sequenceNumber, statusBadge(analysis.status)),
      el('div', { class: 'row small muted' }, analysis.versionNumber ? 'Version v' + analysis.versionNumber + ' · ' : '',
        'finished ' + fmt.date(analysis.finishedAt) + ' · ' + fmt.duration(analysis.durationMs) + ' · ' + (analysis.includeTests ? 'with tests' : 'production sources'),
        el('a', { class: 'btn btn-sm', href: '/projects/' + projectId + '/explorer?analysisId=' + analysis.id }, icon('network', 'icon-sm'), 'Explore'),
        el('a', { class: 'btn btn-sm', href: '/projects/' + projectId + '/impact?analysisId=' + analysis.id }, icon('zap', 'icon-sm'), 'Analyze impact')));
    const stats = el('div', { class: 'stats' },
      stat('Java files parsed', c.filesParsed, c.filesFailed ? c.filesFailed + ' failed · ' + c.filesDiscovered + ' discovered' : c.filesDiscovered + ' discovered', c.filesFailed ? 'warning' : null),
      stat('Types', c.typeCount, c.isolatedTypes + ' isolated'),
      stat('Methods & constructors', (c.methodCount || 0) + (c.constructorCount || 0), fmt.number(c.fieldCount) + ' fields'),
      stat('Type dependencies', c.typeDependencyCount, fmt.number(c.typeRelationshipCount) + ' relationships by kind', 'accent'),
      stat('Method dependencies', c.methodDependencyCount, 'resolved call graph edges'),
      stat('Cyclic components', c.typeCyclicComponents, c.typeNodesInCycles + ' types involved', c.typeCyclicComponents ? 'danger' : null),
      stat('Unresolved references', c.referencesUnresolved, 'may hide dependencies', c.referencesUnresolved ? 'warning' : null));
    const excluded = [];
    if (c.filesExcludedTests) excluded.push(c.filesExcludedTests + ' test file(s) excluded by the scan scope');
    if (c.filesExcludedGenerated) excluded.push(c.filesExcludedGenerated + ' generated file(s) skipped');
    replace(snapshotRoot, el('div', { class: 'stack' }, header, stats,
      excluded.length ? el('p', { class: 'xsmall faint', text: excluded.join(' · ') }) : null,
      el('div', { class: 'grid grid-2' },
        el('section', { class: 'card' }, el('div', { class: 'card-header' }, el('h2', null, icon('zap'), 'Highest structural impact'),
          el('a', { class: 'btn btn-sm btn-ghost', href: '/projects/' + projectId + '/impact?analysisId=' + analysis.id, text: 'Impact analysis' })),
        el('div', { class: 'card-body flush' }, await hotspots(analysis))),
        el('section', { class: 'card' }, el('div', { class: 'card-header' }, el('h2', null, icon('link'), 'Reference resolution')),
          el('div', { class: 'card-body flush' }, referenceBreakdown(c)),
          el('div', { class: 'card-footer', text: 'Resolved and inferred references become graph edges; unresolved ones are reported as diagnostics only.' }))),
      diagnosticsCard(analysis), filesCard(analysis)));
  }

  // ---------------------------------------------------------------- load

  async function loadProject() {
    try {
      detail = await api('/api/projects/' + projectId);
      renderProjectCard();
      const latest = detail.project.latestAnalysis;
      if (detail.analysisActive && latest && (latest.status === 'PENDING' || latest.status === 'RUNNING')) follow(latest);
      else if (!following) clear(activeRoot);
      if (!detail.analysisActive && latest && latest.status === 'FAILED' && !following) {
        replace(activeRoot, el('div', { class: 'alert alert-danger' }, icon('circle-x'),
          el('div', null, el('div', { class: 'alert-title', text: 'The latest analysis (snapshot #' + latest.sequenceNumber + ') failed' }),
            el('div', { text: latest.errorMessage || 'No details were recorded.' }),
            el('div', { class: 'xsmall', text: 'Earlier completed snapshots are unaffected and remain available.' }))));
      }
    } catch (e) {
      replace(projectCard, el('div', { class: 'card-body' }, RM.errorState(e, loadProject)));
    }
  }

  async function initSnapshots() {
    try {
      const selected = await RM.project.init();
      await renderSnapshot(selected);
    } catch (e) {
      replace(snapshotRoot, RM.errorState(e, initSnapshots));
    }
  }

  RM.project.onChange((analysis) => renderSnapshot(analysis));
  loadProject().then(initSnapshots);
})();
