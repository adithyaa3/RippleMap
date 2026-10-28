// Applies the stored theme before first paint. Dark is the default; storage may be unavailable.
(function () {
  var preference = 'dark';
  try {
    preference = window.localStorage.getItem('ripplemap.theme') || 'dark';
  } catch (e) {
    preference = 'dark';
  }
  var theme = preference;
  if (preference === 'system') {
    theme = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  }
  document.documentElement.setAttribute('data-theme', theme === 'light' ? 'light' : 'dark');
  document.documentElement.setAttribute('data-theme-preference', preference);
})();
