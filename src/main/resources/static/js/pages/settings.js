(function () {
  'use strict';
  const { el, api, fmt, replace, toast } = RM;
  const form = document.getElementById('settings-form');
  const low = document.getElementById('low-threshold');
  const high = document.getElementById('high-threshold');
  const inferred = document.getElementById('include-inferred');
  const nodeLimit = document.getElementById('node-limit');
  const updated = document.getElementById('settings-updated');
  const DEFAULTS = { lowThreshold: 30, highThreshold: 60, includeInferred: true, explorerNodeLimit: 300 };

  function fill(s) {
    low.value = s.lowThreshold;
    high.value = s.highThreshold;
    inferred.checked = s.includeInferred;
    nodeLimit.value = s.explorerNodeLimit;
    if (s.updatedAt) updated.textContent = 'Last saved ' + fmt.date(s.updatedAt);
  }

  function invalid(field, message) {
    RM.showFieldErrors(form, { fieldErrors: [{ field, message }] });
    const input = form.querySelector('[data-field="' + field + '"] input');
    if (input) input.focus();
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    RM.clearFieldErrors(form);
    const lowValue = Number(low.value);
    const highValue = Number(high.value);
    const limit = Number(nodeLimit.value);
    if (!low.value || Number.isNaN(lowValue) || lowValue <= 0 || lowValue >= 100) return invalid('lowThreshold', 'Enter a number greater than 0 and below 100');
    if (!high.value || Number.isNaN(highValue) || highValue <= 0 || highValue > 100) return invalid('highThreshold', 'Enter a number greater than 0 and at most 100');
    if (highValue <= lowValue) return invalid('highThreshold', 'The medium/high threshold must be greater than the low/medium threshold');
    if (!Number.isInteger(limit) || limit < 25 || limit > 2000) return invalid('explorerNodeLimit', 'Enter a whole number between 25 and 2000');
    const button = document.getElementById('settings-save');
    button.disabled = true;
    try {
      const saved = await api('/api/settings', { method: 'PUT', json: {
        lowThreshold: low.value, highThreshold: high.value, includeInferred: inferred.checked, explorerNodeLimit: limit } });
      fill(saved);
      toast('success', 'Settings saved');
    } catch (err) {
      if (!RM.showFieldErrors(form, err)) RM.errorToast(err, 'Settings were not saved');
    } finally {
      button.disabled = false;
    }
  });

  document.getElementById('settings-defaults').addEventListener('click', () => {
    fill(Object.assign({}, DEFAULTS, { updatedAt: null }));
    toast('info', 'Defaults restored in the form', 'Select “Save settings” to apply them.');
  });

  // theme
  document.querySelectorAll('input[name="theme"]').forEach((radio) => {
    radio.checked = radio.value === RM.themePreference();
    radio.addEventListener('change', () => { if (radio.checked) RM.applyTheme(radio.value); });
  });
  document.addEventListener('rm:theme', () => {
    document.querySelectorAll('input[name="theme"]').forEach((radio) => { radio.checked = radio.value === RM.themePreference(); });
  });

  api('/api/settings').then(fill).catch((e) => RM.errorToast(e, 'Settings could not be loaded'));

  api('/api/system').then((info) => {
    const rows = [
      ['Application version', info.applicationVersion], ['Analyzer version', info.analyzerVersion],
      ['Java', info.javaVersion + ' (' + info.javaVendor + ')'], ['System', info.operatingSystem],
      ['Java language level', info.languageLevel], ['Data directory', info.dataDirectory],
      ['Local directory scanning', info.localScanEnabled ? 'enabled for: ' + info.localScanRoots.join(', ') : 'disabled']];
    const dl = document.getElementById('system-info');
    replace(dl);
    for (const [k, v] of rows) {
      dl.appendChild(el('dt', { text: k }));
      dl.appendChild(el('dd', { class: k === 'Data directory' ? 'mono' : null, text: v }));
    }
    replace(document.getElementById('limits-table'), el('div', { class: 'table-wrap' }, el('table', { class: 'table table-compact' },
      el('tbody', null, info.limits.map((l) => el('tr', null,
        el('td', null, el('div', { text: l.name }), el('div', { class: 'cell-sub mono', text: l.property })),
        el('td', { class: 'num', text: l.value })))))));
  }).catch((e) => replace(document.getElementById('system-info'), el('dt', { text: 'Unavailable' }), el('dd', { text: e.message })));
})();
