(function () {
  'use strict';
  const { el, icon, api, fmt, replace, statusBadge, toast } = RM;
  const projectId = RM.project.id;
  const table = document.getElementById('history-table');
  const versions = document.getElementById('versions-table');
  const actions = document.getElementById('history-actions');
  let detail = null;
  let analyses = [];
  let pollTimer = null;

  const TRIGGERS = { IMPORT: 'Initial import', NEW_VERSION: 'New version', REANALYZE: 'Re-analysis', RESCAN: 'Directory rescan' };

  function link(section, analysis, label, iconName) {
    return el('a', { class: 'btn btn-sm', href: '/projects/' + projectId + (section ? '/' + section : '') + '?analysisId=' + analysis.id }, icon(iconName, 'icon-sm'), label);
  }

  function previousWithResults(analysis) {
    return analyses.find((a) => a.hasResults && a.sequenceNumber < analysis.sequenceNumber) || null;
  }

  function row(a) {
    const c = a.counts;
    const previous = previousWithResults(a);
    return el('tr', null,
      el('td', { class: 'nowrap' }, el('div', { class: 'cell-title', text: '#' + a.sequenceNumber }),
        el('div', { class: 'cell-sub', text: TRIGGERS[a.trigger] || a.trigger })),
      el('td', null, statusBadge(a.status),
        a.status === 'FAILED' && a.errorMessage ? el('div', { class: 'cell-sub', text: a.errorMessage }) : null,
        a.status === 'PARTIAL' ? el('div', { class: 'cell-sub', text: a.partialReasons.join('; ') }) : null,
        a.status === 'RUNNING' || a.status === 'PENDING' ? el('div', { class: 'cell-sub', text: a.stage || 'Waiting for a worker…' }) : null),
      el('td', { class: 'hide-sm' }, a.versionNumber ? 'v' + a.versionNumber : el('span', { class: 'faint', text: 'directory' }),
        el('div', { class: 'cell-sub', text: a.includeTests ? 'with tests' : 'production only' })),
      el('td', { class: 'nowrap hide-sm' }, el('div', { text: fmt.date(a.finishedAt || a.createdAt) }),
        el('div', { class: 'cell-sub', text: a.durationMs !== null ? fmt.duration(a.durationMs) : '' })),
      el('td', { class: 'num', text: a.hasResults ? fmt.number(c.filesParsed) : '–' }),
      el('td', { class: 'num', text: a.hasResults ? fmt.number(c.typeCount) : '–' }),
      el('td', { class: 'num hide-sm', text: a.hasResults ? fmt.number(c.typeDependencyCount) : '–' }),
      el('td', { class: 'num hide-sm', text: a.hasResults ? fmt.number(c.referencesUnresolved) : '–' }),
      el('td', { class: 'num hide-sm', text: a.hasResults ? fmt.number(c.typeCyclicComponents) : '–' }),
      el('td', { class: 'actions' }, el('div', { class: 'row' },
        a.hasResults ? link(null, a, 'Open', 'box') : null,
        a.hasResults ? link('explorer', a, 'Explore', 'network') : null,
        a.hasResults && previous ? el('a', { class: 'btn btn-sm', href: '/projects/' + projectId + '/compare?base=' + previous.id + '&target=' + a.id },
          icon('git-compare', 'icon-sm'), 'Compare with #' + previous.sequenceNumber) : null,
        a.status === 'RUNNING' || a.status === 'PENDING'
          ? el('button', { type: 'button', class: 'btn btn-sm btn-danger', onclick: () => RM.projectActions.cancelAnalysis(projectId, a).then(load) }, icon('ban', 'icon-sm'), 'Cancel')
          : el('button', { type: 'button', class: 'btn btn-sm btn-ghost', 'aria-label': 'Delete snapshot #' + a.sequenceNumber, title: 'Delete snapshot', onclick: () => remove(a) }, icon('trash-2', 'icon-sm')))));
  }

  async function remove(a) {
    const ok = await RM.confirm({ title: 'Delete snapshot #' + a.sequenceNumber + '?', danger: true, confirmLabel: 'Delete snapshot',
      message: 'The snapshot and all its results are removed permanently. Stored source versions are kept.' });
    if (!ok) return;
    try {
      await api('/api/projects/' + projectId + '/analyses/' + a.id, { method: 'DELETE' });
      toast('success', 'Snapshot #' + a.sequenceNumber + ' deleted');
      if (RM.param('analysisId') === String(a.id)) RM.setParams({ analysisId: null });
      await load();
      await RM.project.init();
    } catch (e) {
      RM.errorToast(e, 'Could not delete the snapshot');
    }
  }

  function renderActions() {
    const isZip = detail.project.sourceType === 'ZIP_UPLOAD';
    const started = async () => { await load(); };
    replace(actions,
      isZip ? el('button', { type: 'button', class: 'btn btn-sm btn-primary', disabled: detail.analysisActive, onclick: () => RM.projectActions.uploadVersion(detail, started) },
        icon('upload', 'icon-sm'), 'Upload new version') : null,
      el('button', { type: 'button', class: 'btn btn-sm', disabled: detail.analysisActive, onclick: () => RM.projectActions.reanalyze(detail, started) },
        icon('refresh-cw', 'icon-sm'), isZip ? 'Re-analyze' : 'Rescan directory'));
  }

  function renderVersions() {
    const card = document.getElementById('versions-card');
    card.classList.toggle('hidden', detail.project.sourceType !== 'ZIP_UPLOAD');
    if (!detail.versions.length) {
      replace(versions, RM.empty('box', 'No stored versions', ''));
      return;
    }
    replace(versions, el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('thead', null, el('tr', null, el('th', { text: 'Version' }), el('th', { text: 'File' }), el('th', { class: 'num', text: 'Java files' }),
        el('th', { class: 'num hide-sm', text: 'Archive' }), el('th', { class: 'hide-sm', text: 'Imported' }))),
      el('tbody', null, detail.versions.map((v) => el('tr', null,
        el('td', null, el('span', { class: 'cell-title', text: 'v' + v.versionNumber }), v.label ? el('div', { class: 'cell-sub', text: v.label }) : null),
        el('td', { class: 'mono small break', text: v.originalFilename || '–' }),
        el('td', { class: 'num', text: fmt.number(v.javaFileCount) }),
        el('td', { class: 'num hide-sm', text: fmt.bytes(v.archiveBytes) }),
        el('td', { class: 'hide-sm nowrap', text: fmt.date(v.importedAt) })))))));
  }

  async function load() {
    try {
      [detail, analyses] = await Promise.all([api('/api/projects/' + projectId), api('/api/projects/' + projectId + '/analyses')]);
      renderActions();
      renderVersions();
      if (!analyses.length) {
        replace(table, RM.empty('history', 'No snapshots', 'Start an analysis to create the first snapshot.'));
      } else {
        replace(table, el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
          el('thead', null, el('tr', null, el('th', { text: 'Snapshot' }), el('th', { text: 'Status' }), el('th', { class: 'hide-sm', text: 'Source' }),
            el('th', { class: 'hide-sm', text: 'Finished' }), el('th', { class: 'num', text: 'Files' }), el('th', { class: 'num', text: 'Types' }),
            el('th', { class: 'num hide-sm', text: 'Deps' }), el('th', { class: 'num hide-sm', text: 'Unresolved' }), el('th', { class: 'num hide-sm', text: 'Cycles' }),
            el('th', { class: 'actions', text: 'Actions' }))),
          el('tbody', null, analyses.map(row)))));
      }
      clearTimeout(pollTimer);
      if (analyses.some((a) => a.status === 'PENDING' || a.status === 'RUNNING')) pollTimer = setTimeout(load, 2000);
    } catch (e) {
      replace(table, RM.errorState(e, load));
    }
  }

  RM.project.init().catch(() => null);
  RM.project.onChange(() => null);
  load();
})();
