/* This bootstrap stays in the APK; hot UI bundles keep the same bridge contract. */
(() => {
  'use strict';
  const runtime = window.HotRuntime || { revision: -1, version: '内置', theme: {} };
  let timer, extraCss;
  const tokens = new Set(['--bg', '--panel', '--text', '--muted', '--accent', '--dock', '--line']);
  const local = path => /^theme\/[a-zA-Z0-9_./-]+$/.test(path) && !path.split('/').includes('..')
    ? new URL(path, 'https://appassets.androidplatform.net/assets/').href : '';
  const style = document.createElement('style');
  style.id = 'hot-theme-style';
  document.head.append(style);
  const theme = runtime.theme || {};
  function tokenRules(values) {
    return Object.entries(values || {}).filter(([key, value]) => tokens.has(key)
      && typeof value === 'string' && CSS.supports('color', value))
      .map(([key, value]) => key + ':' + value).join(';');
  }
  function applyTheme() {
    clearTimeout(timer);
    const now = Date.now();
    const start = theme.startsAt ? Date.parse(theme.startsAt) : -Infinity;
    const end = theme.endsAt ? Date.parse(theme.endsAt) : Infinity;
    const enabled = theme.enabled === true && now >= start && now < end;
    style.textContent = '';
    document.getElementById('hot-theme-background')?.remove();
    extraCss?.remove(); extraCss = null;
    document.querySelectorAll('#dock .hot-theme-icon').forEach(image => image.remove());
    document.querySelectorAll('#dock .nav-icon svg').forEach(svg => svg.style.removeProperty('display'));
    if (enabled) {
      let css = '#app{' + tokenRules(theme.light) + '}html[data-theme=dark] #app{' + tokenRules(theme.dark) + '}';
      const dock = theme.dock || {};
      const dimension = (value, low, high) => Number.isFinite(value) ? Math.max(low, Math.min(high, value)) : null;
      const radius = dimension(dock.radiusDp, 0, 48), buttonRadius = dimension(dock.buttonRadiusDp, 0, 32);
      const height = dimension(dock.heightDp, 56, 96);
      if (radius !== null) css += '#dock{border-radius:' + radius + 'px}';
      if (buttonRadius !== null) css += '#dock button{border-radius:' + buttonRadius + 'px}';
      if (height !== null) css += '#dock{height:' + height + 'px}';
      style.textContent = css;
      if (theme.css && local(theme.css)) {
        extraCss = document.createElement('link'); extraCss.rel = 'stylesheet'; extraCss.href = local(theme.css);
        document.head.append(extraCss);
      }
      for (const [name, path] of Object.entries(dock.icons || {})) {
        if (!['home', 'adult', 'ai', 'library'].includes(name) || !local(path)) continue;
        const host = document.querySelector('#dock [data-nav="' + name + '"] .nav-icon');
        if (!host) continue;
        const image = document.createElement('img'); image.className = 'hot-theme-icon'; image.alt = '';
        image.src = local(path); image.style.cssText = 'width:19px;height:19px;object-fit:contain';
        host.querySelector('svg')?.style.setProperty('display', 'none'); host.append(image);
      }
      const app = document.getElementById('app'), background = theme.background || {};
      if (app && (app.dataset.route || 'home') === 'home' && background.asset && local(background.asset)) {
        const layer = document.createElement('div'); layer.id = 'hot-theme-background';
        layer.style.cssText = 'position:absolute;inset:0;pointer-events:none;z-index:0;background-size:cover;background-position:center';
        layer.style.backgroundImage = 'url("' + local(background.asset) + '")';
        layer.style.opacity = Math.max(0, Math.min(1, Number(background.opacity ?? .16)));
        layer.setAttribute('aria-hidden', 'true'); app.prepend(layer);
        style.textContent += '#scroll{position:relative;z-index:1}';
      }
    }
    const next = Math.min(start > now ? start : Infinity, end > now ? end : Infinity);
    if (Number.isFinite(next)) timer = setTimeout(applyTheme, Math.min(next - now + 10, 24 * 60 * 60 * 1000));
  }
  window.hgThemeRefresh = applyTheme;
  window.hgBootReady = () => {
    applyTheme();
    if (typeof api === 'function') api('hot-ready', { revision: runtime.revision, bootId: runtime.bootId || '' }).catch(() => {});
  };
  document.addEventListener('visibilitychange', () => { if (!document.hidden) applyTheme(); });
})();
