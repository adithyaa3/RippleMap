(function () {
  'use strict';
  const { el, icon, api, fmt, replace, toast } = RM;
  const zipForm = document.getElementById('zip-form');
  const localForm = document.getElementById('local-form');
  const fileInput = document.getElementById('zip-file');
  const dropzone = document.getElementById('dropzone');
  const fileName = document.getElementById('zip-file-name');
  const progress = document.getElementById('import-progress');
  const progressTitle = document.getElementById('progress-title');
  const progressDetail = document.getElementById('progress-detail');
  const progressBar = document.getElementById('progress-bar');
  const progressTrack = document.getElementById('progress-track');
  const progressResult = document.getElementById('progress-result');
  const localEnabled = document.body.dataset.localScan === 'true';
  let maxUploadBytes = 50 * 1024 * 1024;
  let selectedFile = null;
  let busy = false;

  // ---------------------------------------------------------------- tabs

  const tabs = [document.getElementById('tab-zip'), document.getElementById('tab-local')];
  const panels = [document.getElementById('panel-zip'), document.getElementById('panel-local')];
  function selectTab(index) {
    tabs.forEach((t, i) => {
      t.setAttribute('aria-selected', String(i === index));
      t.tabIndex = i === index ? 0 : -1;
      panels[i].classList.toggle('hidden', i !== index);
    });
  }
  tabs.forEach((tab, i) => {
    tab.addEventListener('click', () => selectTab(i));
    tab.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
        const next = (i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length;
        selectTab(next);
        tabs[next].focus();
      }
    });
  });
  selectTab(0);
  document.getElementById('local-disabled').classList.toggle('hidden', localEnabled);
  localForm.classList.toggle('hidden', !localEnabled);

  // ---------------------------------------------------------------- limits

  api('/api/system').then((info) => {
    const dl = document.getElementById('limits');
    replace(dl);
    for (const limit of info.limits.slice(0, 7)) {
      dl.appendChild(el('dt', { text: limit.name }));
      dl.appendChild(el('dd', { text: limit.value }));
    }
    const upload = info.limits.find((l) => l.property === 'ripplemap.import.max-upload-size');
    if (upload) {
      const mb = parseFloat(upload.value);
      if (!Number.isNaN(mb)) maxUploadBytes = mb * 1024 * 1024;
      document.getElementById('zip-limit-hint').textContent = 'Up to ' + upload.value + '. Only .java files are extracted.';
    }
    if (info.localScanEnabled) {
      document.getElementById('local-roots-hint').textContent = 'Allowed roots: ' + info.localScanRoots.join(', ');
    }
  }).catch(() => {
    replace(document.getElementById('limits'), el('dt', { text: 'Limits could not be loaded' }), el('dd'));
  });

  // ---------------------------------------------------------------- file selection

  function setFile(file) {
    selectedFile = file || null;
    fileName.textContent = file ? file.name + ' · ' + fmt.bytes(file.size) : '';
    const field = zipForm.querySelector('[data-field="file"]');
    field.classList.remove('invalid');
    const nameInput = document.getElementById('zip-name');
    if (file && !nameInput.value.trim()) {
      nameInput.value = file.name.replace(/\.zip$/i, '').replace(/[^\p{L}\p{N} ._()\[\]-]+/gu, ' ').trim().slice(0, 100);
    }
  }
  dropzone.addEventListener('click', () => fileInput.click());
  dropzone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); }
  });
  fileInput.addEventListener('change', () => setFile(fileInput.files[0]));
  ['dragenter', 'dragover'].forEach((type) => dropzone.addEventListener(type, (e) => { e.preventDefault(); dropzone.classList.add('dragover'); }));
  ['dragleave', 'drop'].forEach((type) => dropzone.addEventListener(type, (e) => { e.preventDefault(); dropzone.classList.remove('dragover'); }));
  dropzone.addEventListener('drop', (e) => {
    const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (file) setFile(file);
  });
  zipForm.addEventListener('reset', () => {
    setFile(null);
    RM.clearFieldErrors(zipForm);
  });

  function fieldError(form, name, message) {
    const field = form.querySelector('[data-field="' + name + '"]');
    field.classList.add('invalid');
    field.querySelector('.error').textContent = message;
    const input = field.querySelector('input, textarea');
    if (input && input.type !== 'file') input.focus();
    else dropzone.focus();
  }

  // ---------------------------------------------------------------- progress

  function showProgress(title, detail, fraction) {
    progress.classList.remove('hidden');
    progressTitle.textContent = title;
    progressDetail.textContent = detail || '';
    progressTrack.classList.toggle('progress-indeterminate', fraction === null);
    progressTrack.classList.remove('hidden');
    progressBar.style.width = fraction === null ? '' : Math.round(fraction * 100) + '%';
  }

  function setBusy(value) {
    busy = value;
    document.getElementById('zip-submit').disabled = value;
    document.getElementById('local-submit').disabled = value;
  }

  async function followAnalysis(projectId, analysis) {
    replace(progressResult);
    showProgress('Analyzing sources…', analysis.stage || 'Waiting for a worker', null);
    let current = analysis;
    await RM.poll(async () => {
      current = await api('/api/projects/' + projectId + '/analyses/' + analysis.id);
      if (current.status === 'PENDING' || current.status === 'RUNNING') {
        showProgress('Analyzing sources…', current.stage || 'Waiting for a worker', null);
        return false;
      }
      return true;
    }, 1000);
    progressTrack.classList.add('hidden');
    const overview = '/projects/' + projectId;
    if (current.hasResults) {
      const c = current.counts;
      progressTitle.textContent = current.status === 'PARTIAL' ? 'Analysis finished with partial results' : 'Analysis completed';
      progressDetail.textContent = fmt.duration(current.durationMs);
      replace(progressResult, el('div', { class: 'alert ' + (current.status === 'PARTIAL' ? 'alert-warning' : 'alert-success') },
        icon(current.status === 'PARTIAL' ? 'triangle-alert' : 'circle-check'),
        el('div', null,
          el('div', { class: 'alert-title', text: fmt.number(c.filesParsed) + ' files · ' + fmt.number(c.typeCount) + ' types · ' + fmt.number(c.typeDependencyCount) + ' dependencies' }),
          current.status === 'PARTIAL' ? el('div', { text: current.partialReasons.join('; ') }) : null,
          el('div', { class: 'row', role: 'group' },
            el('a', { class: 'btn btn-primary btn-sm', href: overview }, 'Open project overview'),
            el('a', { class: 'btn btn-sm', href: overview + '/explorer' }, icon('network', 'icon-sm'), 'Explore dependencies')))));
      toast('success', 'Analysis completed', 'The project is ready to explore.');
    } else {
      progressTitle.textContent = 'Analysis failed';
      progressDetail.textContent = '';
      replace(progressResult, el('div', { class: 'alert alert-danger' }, icon('circle-x'),
        el('div', null, el('div', { class: 'alert-title', text: current.errorMessage || 'The analysis failed.' }),
          el('a', { href: overview + '/history', text: 'Open the project to retry or upload another version' }))));
      toast('error', 'Analysis failed', current.errorMessage || '');
    }
  }

  // ---------------------------------------------------------------- submit

  zipForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (busy) return;
    RM.clearFieldErrors(zipForm);
    const name = document.getElementById('zip-name').value.trim();
    if (!name) { fieldError(zipForm, 'name', 'Enter a project name'); return; }
    if (!selectedFile) { fieldError(zipForm, 'file', 'Choose a ZIP archive'); return; }
    if (selectedFile.size > maxUploadBytes) {
      fieldError(zipForm, 'file', 'The file is ' + fmt.bytes(selectedFile.size) + '; the limit is ' + fmt.bytes(maxUploadBytes));
      return;
    }
    const data = new FormData();
    data.append('file', selectedFile, selectedFile.name);
    data.append('name', name);
    data.append('description', document.getElementById('zip-description').value);
    data.append('includeTests', document.getElementById('zip-tests').checked ? 'true' : 'false');
    setBusy(true);
    replace(progressResult);
    showProgress('Uploading…', fmt.bytes(selectedFile.size), 0);
    try {
      const response = await RM.upload('/api/projects/import', data, (fraction) => {
        showProgress(fraction < 1 ? 'Uploading…' : 'Validating and extracting…', Math.round(fraction * 100) + '%', fraction < 1 ? fraction : null);
      });
      toast('info', 'Project created', response.version.javaFileCount + ' Java files extracted from ' + (response.version.originalFilename || 'the archive'));
      await followAnalysis(response.project.id, response.analysis);
    } catch (err) {
      progress.classList.add('hidden');
      if (!RM.showFieldErrors(zipForm, err)) {
        if (err.code === 'PROJECT_NAME_TAKEN') fieldError(zipForm, 'name', err.message);
        else if (err.code && err.code.startsWith('ARCHIVE_REJECTED') || err.status === 413) fieldError(zipForm, 'file', err.message);
        else RM.errorToast(err, 'Import failed');
      }
    } finally {
      setBusy(false);
    }
  });

  localForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (busy) return;
    RM.clearFieldErrors(localForm);
    const name = document.getElementById('local-name').value.trim();
    const path = document.getElementById('local-path').value.trim();
    if (!name) { fieldError(localForm, 'name', 'Enter a project name'); return; }
    if (!path) { fieldError(localForm, 'path', 'Enter an absolute directory path'); return; }
    setBusy(true);
    showProgress('Registering directory…', '', null);
    try {
      const response = await api('/api/projects/local', { method: 'POST', json: {
        name, path, description: document.getElementById('local-description').value,
        includeTests: document.getElementById('local-tests').checked } });
      await followAnalysis(response.project.id, response.analysis);
    } catch (err) {
      progress.classList.add('hidden');
      if (!RM.showFieldErrors(localForm, err)) {
        if (err.code === 'PROJECT_NAME_TAKEN') fieldError(localForm, 'name', err.message);
        else if (err.code === 'PATH_NOT_ALLOWED' || err.code === 'LOCAL_SCAN_DISABLED') fieldError(localForm, 'path', err.message);
        else RM.errorToast(err, 'Registration failed');
      }
    } finally {
      setBusy(false);
    }
  });
})();
