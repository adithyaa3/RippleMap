/* Project actions shared by the overview and history pages. */
(function () {
  'use strict';
  const { el, api, fmt, toast } = RM;

  /** Upload a new ZIP version; resolves with the started analysis via onStarted. */
  function uploadVersion(detail, onStarted) {
    const projectId = detail.project.id;
    const file = el('input', { type: 'file', accept: '.zip,application/zip', class: 'input' });
    const label = el('input', { class: 'input', maxlength: 100, placeholder: 'e.g. after refactoring', autocomplete: 'off' });
    const tests = el('input', { type: 'checkbox', checked: detail.project.includeTests });
    const status = el('div', { class: 'small muted', 'aria-live': 'polite' });
    RM.formDialog({
      title: 'Upload new version', submitLabel: 'Upload and analyze', errorTitle: 'Upload failed',
      intro: 'The new ZIP is stored as the next version and analyzed into a new snapshot. Earlier snapshots stay unchanged for comparison.',
      content: [RM.field('ZIP archive', file, { name: 'file' }), RM.field('Version label', label, { name: 'label', optional: true }),
        el('label', { class: 'check' }, tests, 'Include test sources'), status],
      onSubmit: async (form) => {
        if (!file.files[0]) {
          RM.showFieldErrors(form, { fieldErrors: [{ field: 'file', message: 'Choose a ZIP archive' }] });
          return false;
        }
        const data = new FormData();
        data.append('file', file.files[0], file.files[0].name);
        data.append('label', label.value);
        data.append('includeTests', tests.checked ? 'true' : 'false');
        let response;
        try {
          response = await RM.upload('/api/projects/' + projectId + '/versions', data, (f) => {
            status.textContent = f < 1 ? 'Uploading… ' + Math.round(f * 100) + '%' : 'Validating and extracting…';
          });
        } catch (err) {
          status.textContent = '';
          if (err.code && err.code.startsWith('ARCHIVE_REJECTED')) {
            RM.showFieldErrors(form, { fieldErrors: [{ field: 'file', message: err.message }] });
            return false;
          }
          throw err;
        }
        toast('info', 'Version v' + response.version.versionNumber + ' uploaded', 'Snapshot #' + response.analysis.sequenceNumber + ' is being analyzed.');
        onStarted(response.analysis);
      }
    });
  }

  /** Re-analyze a stored version (ZIP) or rescan the directory (local). */
  function reanalyze(detail, onStarted) {
    const projectId = detail.project.id;
    const isZip = detail.project.sourceType === 'ZIP_UPLOAD';
    const tests = el('input', { type: 'checkbox', checked: detail.project.includeTests });
    const content = [];
    let version = null;
    if (isZip) {
      version = el('select', { class: 'select' }, detail.versions.map((v) => el('option', { value: v.id,
        text: 'v' + v.versionNumber + (v.label ? ' – ' + v.label : '') + ' · ' + fmt.date(v.importedAt) })));
      content.push(RM.field('Stored version', version, { name: 'versionId', hint: 'Stored sources never change. Upload a new version to analyze changed code.' }));
    } else {
      content.push(el('p', { class: 'small muted', text: 'The current contents of the directory are read again and stored as a new snapshot.' }));
    }
    content.push(el('label', { class: 'check' }, tests, 'Include test sources'));
    RM.formDialog({
      title: isZip ? 'Re-analyze a stored version' : 'Rescan directory', submitLabel: 'Start analysis', content,
      errorTitle: 'Could not start the analysis',
      onSubmit: async () => {
        const analysis = await api('/api/projects/' + projectId + '/analyze', { method: 'POST',
          json: { versionId: version ? Number(version.value) : null, includeTests: tests.checked } });
        toast('info', 'Analysis started', 'Snapshot #' + analysis.sequenceNumber + ' is queued.');
        onStarted(analysis);
      }
    });
  }

  async function cancelAnalysis(projectId, analysis) {
    try {
      await api('/api/projects/' + projectId + '/analyses/' + analysis.id + '/cancel', { method: 'POST' });
      toast('info', 'Cancellation requested', 'Snapshot #' + analysis.sequenceNumber + ' will stop shortly.');
    } catch (e) {
      RM.errorToast(e, 'Could not cancel');
    }
  }

  RM.projectActions = { uploadVersion, reanalyze, cancelAnalysis };
})();
