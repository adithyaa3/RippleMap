/*
 * RippleMap client core. All text coming from the server is inserted with textContent (never innerHTML),
 * because names, paths and messages originate from untrusted source code.
 */
(function () {
  'use strict';

  const ICONS = '/assets/icons.svg';
  const csrfMeta = document.querySelector('meta[name="ripplemap-csrf"]');
  const CSRF = csrfMeta ? csrfMeta.getAttribute('content') : '';

  // ------------------------------------------------------------------ DOM helpers

  function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    if (attrs) {
      for (const [key, value] of Object.entries(attrs)) {
        if (value === null || value === undefined || value === false) continue;
        if (key === 'class') node.className = value;
        else if (key === 'text') node.textContent = value;
        else if (key === 'dataset') Object.assign(node.dataset, value);
        else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2), value);
        else if (value === true) node.setAttribute(key, '');
        else node.setAttribute(key, String(value));
      }
    }
    append(node, children);
    return node;
  }

  function append(node, children) {
    for (const child of children.flat(Infinity)) {
      if (child === null || child === undefined || child === false) continue;
      node.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
    return node;
  }

  function replace(node, ...children) {
    clear(node);
    return append(node, children);
  }

  function icon(name, cls) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'icon' + (cls ? ' ' + cls : ''));
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', ICONS + '#i-' + name);
    svg.appendChild(use);
    return svg;
  }

  function $(selector, root) {
    return (root || document).querySelector(selector);
  }

  // ------------------------------------------------------------------ API client

  class ApiError extends Error {
    constructor(status, body) {
      super((body && body.message) || ('Request failed (' + status + ')'));
      this.status = status;
      this.code = body && body.code;
      this.fieldErrors = (body && body.fieldErrors) || [];
    }
  }

  async function api(path, options) {
    const opts = options || {};
    const method = (opts.method || 'GET').toUpperCase();
    const headers = { Accept: 'application/json' };
    let body = opts.body;
    if (opts.json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.json);
    }
    if (method !== 'GET' && method !== 'HEAD') headers['X-RippleMap-CSRF'] = CSRF;
    let response;
    try {
      response = await fetch(path, { method, headers, body, signal: opts.signal, credentials: 'same-origin' });
    } catch (e) {
      if (e.name === 'AbortError') throw e;
      throw new ApiError(0, { code: 'NETWORK_ERROR', message: 'The RippleMap server could not be reached. Is it still running?' });
    }
    if (response.status === 204) return null;
    const text = await response.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch (e) { data = null; }
    }
    if (!response.ok) {
      if (response.status === 403 && data && data.code === 'CSRF_TOKEN_INVALID') {
        data.message = 'Your session token has expired (the server was restarted). Reload the page and try again.';
      }
      throw new ApiError(response.status, data || { message: 'Request failed (' + response.status + ')' });
    }
    return data;
  }

  /** Multipart upload with progress (fetch cannot report upload progress). */
  function upload(path, formData, onProgress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', path);
      xhr.setRequestHeader('X-RippleMap-CSRF', CSRF);
      xhr.setRequestHeader('Accept', 'application/json');
      xhr.upload.addEventListener('progress', (e) => {
        if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total);
      });
      xhr.addEventListener('load', () => {
        let data = null;
        try { data = xhr.responseText ? JSON.parse(xhr.responseText) : null; } catch (e) { data = null; }
        if (xhr.status >= 200 && xhr.status < 300) resolve(data);
        else reject(new ApiError(xhr.status, data || { message: 'Upload failed (' + xhr.status + ')' }));
      });
      xhr.addEventListener('error', () => reject(new ApiError(0, { code: 'NETWORK_ERROR', message: 'The upload was interrupted.' })));
      xhr.send(formData);
    });
  }

  function qs(params) {
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params || {})) {
      if (value === null || value === undefined || value === '') continue;
      if (Array.isArray(value)) {
        if (value.length) search.set(key, value.join(','));
      } else {
        search.set(key, String(value));
      }
    }
    const text = search.toString();
    return text ? '?' + text : '';
  }

  // ------------------------------------------------------------------ formatting

  const numberFormat = new Intl.NumberFormat(undefined);
  const fmt = {
    number(value) {
      return value === null || value === undefined ? '–' : numberFormat.format(value);
    },
    decimal(value, digits) {
      if (value === null || value === undefined) return '–';
      return Number(value).toFixed(digits === undefined ? 1 : digits);
    },
    date(value) {
      if (!value) return '–';
      const date = new Date(value);
      return date.toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    },
    relative(value) {
      if (!value) return '–';
      const seconds = Math.round((Date.now() - new Date(value).getTime()) / 1000);
      if (seconds < 45) return 'just now';
      const minutes = Math.round(seconds / 60);
      if (minutes < 60) return minutes + ' min ago';
      const hours = Math.round(minutes / 60);
      if (hours < 24) return hours + ' h ago';
      const days = Math.round(hours / 24);
      if (days < 30) return days + ' d ago';
      return fmt.date(value);
    },
    duration(ms) {
      if (ms === null || ms === undefined) return '–';
      if (ms < 1000) return ms + ' ms';
      const s = ms / 1000;
      if (s < 60) return s.toFixed(1) + ' s';
      return Math.floor(s / 60) + ' min ' + Math.round(s % 60) + ' s';
    },
    bytes(value) {
      if (value === null || value === undefined) return '–';
      if (value < 1024) return value + ' B';
      if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KB';
      return (value / (1024 * 1024)).toFixed(1) + ' MB';
    },
    kind(kind) {
      return kind ? kind.charAt(0) + kind.slice(1).toLowerCase() : '';
    },
    relationship(kind) {
      return kind ? kind.toLowerCase().replace(/_/g, ' ') : '';
    }
  };

  function statusBadge(status) {
    const labels = { COMPLETED: 'Completed', PARTIAL: 'Partial', FAILED: 'Failed', RUNNING: 'Running', PENDING: 'Pending' };
    return el('span', { class: 'status status-' + status, text: labels[status] || status });
  }

  function levelBadge(level) {
    return el('span', { class: 'badge badge-impact-' + level, text: level });
  }

  function kindLabel(kind) {
    return el('span', { class: 'kind kind-' + kind, text: fmt.kind(kind) });
  }

  // ------------------------------------------------------------------ feedback

  function toast(type, title, message, timeout) {
    const region = document.getElementById('toast-region');
    if (!region) return;
    const icons = { success: 'circle-check', error: 'circle-x', warning: 'triangle-alert', info: 'info' };
    const node = el('div', { class: 'toast toast-' + type, role: type === 'error' ? 'alert' : null },
      icon(icons[type] || 'info'),
      el('div', { class: 'toast-body' }, el('div', { class: 'toast-title', text: title }), message ? el('div', { class: 'muted', text: message }) : null),
      el('button', { type: 'button', class: 'btn btn-ghost btn-sm btn-icon', 'aria-label': 'Dismiss notification', onclick: () => node.remove() }, icon('x', 'icon-sm')));
    region.appendChild(node);
    const ms = timeout === undefined ? (type === 'error' ? 9000 : 5000) : timeout;
    if (ms > 0) setTimeout(() => node.remove(), ms);
  }

  function errorToast(error, fallbackTitle) {
    toast('error', fallbackTitle || 'Something went wrong', error && error.message ? error.message : String(error));
  }

  /** Accessible confirmation dialog; resolves true when confirmed. */
  function confirmDialog(options) {
    const dialog = document.getElementById('confirm-dialog');
    if (!dialog || typeof dialog.showModal !== 'function') {
      return Promise.resolve(window.confirm(options.message));
    }
    const title = document.getElementById('confirm-title');
    const message = document.getElementById('confirm-message');
    const ok = document.getElementById('confirm-ok');
    const cancel = document.getElementById('confirm-cancel');
    const textField = document.getElementById('confirm-text-field');
    const textInput = document.getElementById('confirm-text-input');
    const textLabel = document.getElementById('confirm-text-label');
    title.textContent = options.title || 'Confirm';
    message.textContent = options.message || '';
    ok.textContent = options.confirmLabel || 'Confirm';
    ok.className = 'btn ' + (options.danger ? 'btn-danger-solid' : 'btn-primary');
    const previous = document.activeElement;
    if (options.requireText) {
      textField.classList.remove('hidden');
      textLabel.textContent = 'Type "' + options.requireText + '" to confirm';
      textInput.value = '';
      ok.disabled = true;
    } else {
      textField.classList.add('hidden');
      ok.disabled = false;
    }
    return new Promise((resolve) => {
      const onInput = () => { ok.disabled = textInput.value !== options.requireText; };
      const onCancel = () => dialog.close('cancel');
      const finish = () => {
        textInput.removeEventListener('input', onInput);
        cancel.removeEventListener('click', onCancel);
        dialog.removeEventListener('close', finish);
        if (previous && previous.focus) previous.focus();
        resolve(dialog.returnValue === 'ok');
      };
      textInput.addEventListener('input', onInput);
      cancel.addEventListener('click', onCancel);
      dialog.addEventListener('close', finish);
      dialog.returnValue = 'cancel';
      dialog.showModal();
      (options.requireText ? textInput : cancel).focus();
    });
  }

  let dialogCounter = 0;

  /**
   * Opens a modal form. onSubmit(form) may return a promise; the dialog closes when it resolves to anything
   * but false. Validation errors (fieldErrors) are shown next to fields marked data-field="...".
   */
  function formDialog(options) {
    const id = 'form-dialog-' + (++dialogCounter);
    const submit = el('button', { type: 'submit', class: 'btn ' + (options.danger ? 'btn-danger-solid' : 'btn-primary'), text: options.submitLabel || 'Save' });
    const cancel = el('button', { type: 'button', class: 'btn', text: 'Cancel' });
    const form = el('form', { novalidate: true },
      el('div', { class: 'modal-header' }, el('h2', { id: id + '-title', text: options.title })),
      el('div', { class: 'modal-body' }, options.intro ? el('p', { class: 'muted small', text: options.intro }) : null, options.content),
      el('div', { class: 'modal-footer' }, cancel, submit));
    const dialog = el('dialog', { class: 'modal', 'aria-labelledby': id + '-title' }, form);
    const previous = document.activeElement;
    cancel.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
      dialog.remove();
      if (previous && previous.focus) previous.focus();
    });
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      clearFieldErrors(form);
      submit.disabled = true;
      try {
        const result = await options.onSubmit(form);
        if (result !== false) dialog.close();
      } catch (err) {
        if (!showFieldErrors(form, err)) errorToast(err, options.errorTitle);
      } finally {
        submit.disabled = false;
      }
    });
    document.body.appendChild(dialog);
    dialog.showModal();
    const first = form.querySelector('input:not([type=hidden]), textarea, select');
    if (first) first.focus();
    return dialog;
  }

  function field(label, input, opts) {
    const o = opts || {};
    const inputId = input.id || ('f-' + Math.random().toString(36).slice(2));
    input.id = inputId;
    return el('div', { class: 'field', dataset: o.name ? { field: o.name } : null },
      el('label', { for: inputId }, label, o.optional ? el('span', { class: 'optional', text: ' (optional)' }) : null),
      input, o.hint ? el('span', { class: 'hint', text: o.hint }) : null, el('span', { class: 'error', role: 'alert' }));
  }

  function loading(text) {
    return el('div', { class: 'loading', role: 'status' }, icon('loader-circle', 'spin'), el('span', { text: text || 'Loading…' }));
  }

  function empty(iconName, title, text, action) {
    return el('div', { class: 'empty' }, icon(iconName), el('h3', { text: title }), text ? el('p', { text }) : null, action || null);
  }

  function errorState(error, retry) {
    return el('div', { class: 'empty' }, icon('triangle-alert'), el('h3', { text: 'Could not load data' }),
      el('p', { text: error && error.message ? error.message : String(error) }),
      retry ? el('button', { type: 'button', class: 'btn', onclick: retry }, icon('refresh-cw'), 'Try again') : null);
  }

  /** Shows field errors from a VALIDATION_FAILED response on fields named data-field="..." in the form. */
  function showFieldErrors(form, error) {
    clearFieldErrors(form);
    let shown = false;
    for (const fe of (error.fieldErrors || [])) {
      const field = form.querySelector('[data-field="' + CSS.escape(fe.field) + '"]');
      if (field) {
        field.classList.add('invalid');
        const msg = field.querySelector('.error');
        if (msg) msg.textContent = fe.message;
        const input = field.querySelector('input, textarea, select');
        if (input) input.setAttribute('aria-invalid', 'true');
        shown = true;
      }
    }
    return shown;
  }

  function clearFieldErrors(form) {
    form.querySelectorAll('.field.invalid').forEach((f) => {
      f.classList.remove('invalid');
      const input = f.querySelector('[aria-invalid]');
      if (input) input.removeAttribute('aria-invalid');
    });
  }

  // ------------------------------------------------------------------ URL state

  function param(name) {
    return new URLSearchParams(window.location.search).get(name);
  }

  function setParams(values) {
    const url = new URL(window.location.href);
    for (const [key, value] of Object.entries(values)) {
      if (value === null || value === undefined || value === '') url.searchParams.delete(key);
      else url.searchParams.set(key, String(value));
    }
    window.history.replaceState(null, '', url.toString());
    updateProjectLinks();
  }

  function updateProjectLinks() {
    const analysisId = param('analysisId');
    document.querySelectorAll('a.project-link').forEach((link) => {
      const url = new URL(link.getAttribute('href'), window.location.origin);
      if (analysisId) url.searchParams.set('analysisId', analysisId);
      else url.searchParams.delete('analysisId');
      link.setAttribute('href', url.pathname + url.search);
    });
  }

  // ------------------------------------------------------------------ theme and navigation

  function applyTheme(preference) {
    let theme = preference;
    if (preference === 'system') {
      theme = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
    }
    document.documentElement.setAttribute('data-theme', theme === 'light' ? 'light' : 'dark');
    document.documentElement.setAttribute('data-theme-preference', preference);
    try { window.localStorage.setItem('ripplemap.theme', preference); } catch (e) { /* storage unavailable */ }
    renderThemeToggle();
    document.dispatchEvent(new CustomEvent('rm:theme'));
  }

  function themePreference() {
    return document.documentElement.getAttribute('data-theme-preference') || 'dark';
  }

  function renderThemeToggle() {
    const button = document.getElementById('theme-toggle');
    if (!button) return;
    const pref = themePreference();
    const names = { dark: 'dark', light: 'light', system: 'system' };
    replace(button, icon(pref === 'light' ? 'sun' : pref === 'system' ? 'monitor' : 'moon'), el('span', { text: 'Theme: ' + names[pref] }));
    button.setAttribute('aria-label', 'Theme: ' + names[pref] + '. Activate to switch theme.');
  }

  function initNavigation() {
    const sidebar = document.getElementById('sidebar');
    const menu = document.getElementById('menu-button');
    let backdrop = null;
    function close() {
      sidebar.classList.remove('open');
      if (menu) menu.setAttribute('aria-expanded', 'false');
      if (backdrop) { backdrop.remove(); backdrop = null; }
    }
    if (menu && sidebar) {
      menu.addEventListener('click', () => {
        const open = !sidebar.classList.contains('open');
        if (open) {
          sidebar.classList.add('open');
          menu.setAttribute('aria-expanded', 'true');
          backdrop = el('div', { class: 'sidebar-backdrop', onclick: close });
          document.body.appendChild(backdrop);
          const first = sidebar.querySelector('a, button');
          if (first) first.focus();
        } else {
          close();
        }
      });
      document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && sidebar.classList.contains('open')) { close(); menu.focus(); } });
    }
    const toggle = document.getElementById('theme-toggle');
    if (toggle) {
      toggle.addEventListener('click', () => {
        const order = ['dark', 'light', 'system'];
        applyTheme(order[(order.indexOf(themePreference()) + 1) % order.length]);
      });
      renderThemeToggle();
    }
    if (window.matchMedia) {
      window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => {
        if (themePreference() === 'system') applyTheme('system');
      });
    }
    updateProjectLinks();
  }

  // ------------------------------------------------------------------ project snapshot selection

  const project = {
    id: document.body.dataset.projectId ? Number(document.body.dataset.projectId) : null,
    analyses: [],
    selected: null,
    listeners: [],

    /** Loads snapshots and resolves the selection: ?analysisId if given, else the latest snapshot with results. */
    async init() {
      const select = document.getElementById('snapshot-select');
      try {
        this.analyses = await api('/api/projects/' + this.id + '/analyses');
      } catch (e) {
        if (select) replace(select, el('option', { value: '', text: 'Unavailable' }));
        throw e;
      }
      const requested = param('analysisId');
      const withResults = this.analyses.filter((a) => a.hasResults);
      let selected = null;
      if (requested) {
        selected = this.analyses.find((a) => String(a.id) === requested) || null;
        if (!selected) {
          toast('warning', 'Snapshot not found', 'The requested snapshot does not exist in this project. Showing the latest snapshot instead.');
        } else if (!selected.hasResults) {
          toast('warning', 'Snapshot has no results', 'Snapshot #' + selected.sequenceNumber + ' is ' + selected.status + '.');
          selected = null;
        }
        if (!selected) setParams({ analysisId: null });
      }
      if (!selected) selected = withResults[0] || null;
      this.selected = selected;
      this.renderPicker();
      return selected;
    },

    renderPicker() {
      const select = document.getElementById('snapshot-select');
      if (!select) return;
      const withResults = this.analyses.filter((a) => a.hasResults);
      clear(select);
      if (!withResults.length) {
        select.appendChild(el('option', { value: '', text: 'No completed snapshot' }));
        select.disabled = true;
        return;
      }
      select.disabled = false;
      withResults.forEach((a, index) => {
        const label = '#' + a.sequenceNumber + (a.versionNumber ? ' · v' + a.versionNumber : '') + ' · ' + fmt.date(a.finishedAt)
          + (a.status === 'PARTIAL' ? ' · partial' : '') + (index === 0 ? ' (latest)' : '');
        select.appendChild(el('option', { value: a.id, text: label, selected: this.selected && a.id === this.selected.id }));
      });
      if (!select.dataset.bound) {
        select.dataset.bound = '1';
        select.addEventListener('change', () => {
          const chosen = this.analyses.find((a) => String(a.id) === select.value) || null;
          this.select(chosen);
        });
      }
    },

    select(analysis) {
      this.selected = analysis;
      setParams({ analysisId: analysis ? analysis.id : null });
      this.renderPicker();
      this.listeners.forEach((fn) => fn(analysis));
    },

    onChange(fn) {
      this.listeners.push(fn);
    },

    isLatest() {
      const latest = this.analyses.find((a) => a.hasResults);
      return !this.selected || !latest || latest.id === this.selected.id;
    },

    /** Banner describing the selected snapshot when it is historical or partial. */
    banner() {
      const a = this.selected;
      if (!a) return null;
      const notes = [];
      if (!this.isLatest()) {
        notes.push(el('div', { class: 'alert alert-info snapshot-banner' }, icon('history'),
          el('div', null, el('div', { class: 'alert-title', text: 'Viewing historical snapshot #' + a.sequenceNumber }),
            el('div', { text: 'All data on this page comes from the analysis finished ' + fmt.date(a.finishedAt) + '. Choose another snapshot in the selector above.' }))));
      }
      if (a.status === 'PARTIAL') {
        notes.push(el('div', { class: 'alert alert-warning snapshot-banner' }, icon('triangle-alert'),
          el('div', null, el('div', { class: 'alert-title', text: 'Partial snapshot' }),
            el('div', { text: (a.partialReasons || []).join('; ') + '. Declarations in those files are missing, so results may be incomplete.' }))));
      }
      return notes.length ? el('div', null, notes) : null;
    },

    path(suffix) {
      return '/api/projects/' + this.id + suffix;
    }
  };

  /** Polls a function until it returns true or the timeout passes. */
  function poll(fn, intervalMs, timeoutMs) {
    const deadline = Date.now() + (timeoutMs || 30 * 60 * 1000);
    return new Promise((resolve, reject) => {
      const tick = async () => {
        try {
          if (await fn()) { resolve(true); return; }
        } catch (e) {
          reject(e);
          return;
        }
        if (Date.now() > deadline) { resolve(false); return; }
        setTimeout(tick, intervalMs);
      };
      tick();
    });
  }

  function debounce(fn, ms) {
    let timer = null;
    return function (...args) {
      clearTimeout(timer);
      timer = setTimeout(() => fn.apply(this, args), ms);
    };
  }

  function downloadLink(href, label, iconName) {
    return el('a', { class: 'btn btn-sm', href, download: '' }, icon(iconName || 'download', 'icon-sm'), label);
  }

  window.RM = {
    el, append, clear, replace, icon, $, api, upload, qs, ApiError, fmt, statusBadge, levelBadge, kindLabel,
    toast, errorToast, confirm: confirmDialog, formDialog, field, loading, empty, errorState, showFieldErrors, clearFieldErrors,
    param, setParams, project, poll, debounce, downloadLink, applyTheme, themePreference
  };

  document.addEventListener('DOMContentLoaded', initNavigation);
})();
