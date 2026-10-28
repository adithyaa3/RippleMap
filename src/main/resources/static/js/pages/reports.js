(function () {
  'use strict';
  const { el, icon, api, fmt, replace } = RM;
  const list = document.getElementById('report-list');
  const notes = document.getElementById('snapshot-notes');
  const scope = document.getElementById('report-scope');
  const ICONS = { HTML: 'file-text', JSON: 'file-code', CSV: 'list-tree' };

  async function load(analysis) {
    replace(notes, RM.project.banner());
    if (!analysis) {
      scope.textContent = '';
      replace(list, RM.empty('file-text', 'No completed analysis', 'Reports become available when a snapshot has results.'));
      return;
    }
    scope.textContent = 'Snapshot #' + analysis.sequenceNumber + ' · ' + fmt.date(analysis.finishedAt) + ' · ' + analysis.status.toLowerCase();
    replace(list, RM.loading('Loading reports…'));
    try {
      const catalog = await api(RM.project.path('/reports' + RM.qs({ analysisId: analysis.id })));
      replace(list, el('ul', { class: 'list' }, catalog.reports.map((r) => el('li', { class: 'list-item' },
        icon(ICONS[r.format] || 'file-text'),
        el('div', { class: 'truncate' }, el('div', { class: 'cell-title', text: r.title }), el('div', { class: 'cell-sub', text: r.description })),
        el('span', { class: 'spacer' }),
        el('span', { class: 'badge badge-outline', text: r.format }),
        el('a', { class: 'btn btn-sm btn-primary', href: r.href, download: '', 'aria-label': 'Download ' + r.title + ' (' + r.format + ')' }, icon('download', 'icon-sm'), 'Download')))));
    } catch (e) {
      replace(list, RM.errorState(e, () => load(analysis)));
    }
  }

  RM.project.onChange(load);
  RM.project.init().then(load).catch((e) => replace(list, RM.errorState(e)));
})();
