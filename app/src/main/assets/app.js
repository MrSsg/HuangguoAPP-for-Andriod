'use strict';

const callbacks = new Map();
let requestNumber = 0;
window.NativeCallbacks = {
  resolve(id, value) { callbacks.get(id)?.resolve(value); callbacks.delete(id); },
  reject(id, message) { callbacks.get(id)?.reject(new Error(message)); callbacks.delete(id); }
};
function api(action, args = {}) {
  return new Promise((resolve, reject) => {
    const id = String(++requestNumber);
    callbacks.set(id, { resolve, reject });
    try { AndroidHost.request(id, action, JSON.stringify(args)); }
    catch (error) { callbacks.delete(id); reject(error); }
  });
}

const scroll = document.getElementById('scroll');
const content = document.getElementById('content');
const avatarButton = document.getElementById('account-avatar');
const avatarImage = document.getElementById('account-avatar-image');
const authOverlay = document.getElementById('auth-overlay');
const compact = document.getElementById('compact-category');
const toastHost = document.getElementById('toast-host');
const updateOverlay = document.getElementById('update-overlay');
const updateDownloadButton = document.getElementById('update-download');
const updateLaterButton = document.getElementById('update-later');
const searchInput = document.getElementById('search-input');
const headerSettings = document.getElementById('header-settings');
const confirmOverlay = document.getElementById('confirm-overlay');
const categoryIds = { adult: ['ai-duanju', 'ai-manju'], ai: ['ai-huanlian', 'ai-mogai'] };
const popularTags = [
  ['dushi', '都市'], ['xiaoyuan', '校园'], ['gufeng', '古风'], ['haomen', '豪门'],
  ['chuanyue', '穿越'], ['chongsheng', '重生'], ['qihuan', '奇幻'], ['zhichang', '职场']
];
const tagGroups = [
  ['popular', '热门类型'], ['story', '故事题材'], ['setting', '场景与身份'],
  ['relationship', '人物与关系'], ['style', '外形风格'], ['plot', '情节元素'], ['other', '更多标签']
];
const state = {
  route: 'home', tab: 'home', sub: { adult: 0, ai: 0 }, home: null,
  category: null, categoryItems: [], detail: null, sourceItem: null,
  search: null, query: '', filter: 'all', banner: 0,
  listing: null, tags: null, tagsReturn: null, listingReturn: null, settingsReturn: null,
  libraryTab: 'history', libraryQuery: '', accountReturn: null, account: { loggedIn: false },
  sourceGroup: '', sourceQueue: [], previous: null, compactHeld: false
};
let routeToken = 0;
let toastTimer;
let observer;
let listingObserver;
let categoryObserver;
const screenSnapshots = {};
let predictivePreview;
let predictiveTimer;
const coverRequests = new Map();
let bannerChanging = false;
let bannerTimer;
let lastDockTap = { tab: '', at: 0, navigated: false };
let updateChecking = false;
let updateDownloading = false;

const esc = value => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
const asArray = value => Array.isArray(value) ? value : [];
const icon = name => {
  const paths = {
    play: '<path d="m8 5 11 7-11 7z"/>',
    arrow: '<path d="m9 5 7 7-7 7"/>',
    back: '<path d="m15 5-7 7 7 7"/>'
  };
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name]}</svg>`;
};

function toast(message, info = false) {
  clearTimeout(toastTimer);
  toastHost.innerHTML = `<div class="toast ${info ? 'info' : ''}"><i></i><span>${esc(message)}</span></div>`;
  toastHost.classList.add('visible');
  toastTimer = setTimeout(() => toastHost.classList.remove('visible'), 3000);
}

let finishConfirmation;
function closeConfirmation(accepted) {
  if (confirmOverlay.hidden) return;
  confirmOverlay.hidden = true;
  finishConfirmation?.(accepted);
  finishConfirmation = null;
}
function askConfirmation(title, message, action = '清空') {
  document.getElementById('confirm-title').textContent = title;
  document.getElementById('confirm-message').textContent = message;
  document.getElementById('confirm-accept').textContent = action;
  confirmOverlay.hidden = false;
  document.getElementById('confirm-cancel').focus();
  return new Promise(resolve => { finishConfirmation = resolve; });
}
document.getElementById('confirm-cancel').addEventListener('click', () => closeConfirmation(false));
document.getElementById('confirm-accept').addEventListener('click', () => closeConfirmation(true));
confirmOverlay.addEventListener('click', event => { if (event.target === confirmOverlay) closeConfirmation(false); });

function setAccount(profile) {
  state.account = profile || { loggedIn: false };
  avatarImage.hidden = !profile?.avatar;
  avatarButton.querySelector('.account-avatar-default').hidden = Boolean(profile?.avatar);
  if (profile?.avatar) avatarImage.src = profile.avatar;
  else avatarImage.removeAttribute('src');
  avatarButton.classList.toggle('is-signed-in', Boolean(profile?.loggedIn));
  avatarButton.setAttribute('aria-label', profile?.loggedIn ? `打开${profile.username}的账号管理` : '登录账号');
  if (state.route === 'library') renderLibrary();
  if (state.route === 'settings') renderSettings();
  if (state.route === 'account-management') renderAccountManagement();
}
api('account').then(setAccount).catch(() => {});

let authMode = 'login';
let authPending = false;
function setAuthPending(pending, mode = authMode) {
  const submit = document.getElementById('auth-submit');
  submit.disabled = pending;
  submit.classList.toggle('is-loading', pending);
  submit.setAttribute('aria-busy', String(pending));
  submit.textContent = pending ? (mode === 'login' ? '正在登录…' : '正在注册…') : (mode === 'login' ? '登录' : '注册并登录');
  document.querySelectorAll('[data-auth-mode]').forEach(button => { button.disabled = pending; });
}
function showAuth(mode = 'login') {
  if (authPending) mode = authMode;
  authMode = mode;
  authOverlay.hidden = false;
  document.getElementById('auth-title').textContent = mode === 'login' ? '登录黄果账号' : '注册黄果账号';
  setAuthPending(authPending, mode);
  document.getElementById('auth-password').autocomplete = mode === 'login' ? 'current-password' : 'new-password';
  document.querySelectorAll('[data-auth-mode]').forEach(button => button.classList.toggle('selected', button.dataset.authMode === mode));
  document.getElementById('auth-error').hidden = true;
}
function hideAuth() {
  authOverlay.hidden = true;
  document.getElementById('auth-form').reset();
}
avatarButton.addEventListener('click', () => {
  if (state.account.loggedIn) goAccountManagement();
  else showAuth();
});
headerSettings.addEventListener('click', goSettings);
document.getElementById('auth-close').addEventListener('click', hideAuth);
document.querySelectorAll('[data-auth-mode]').forEach(button => button.addEventListener('click', () => showAuth(button.dataset.authMode)));
document.getElementById('auth-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (authPending) return;
  const mode = authMode;
  const username = document.getElementById('auth-username').value.trim();
  const password = document.getElementById('auth-password').value;
  const error = document.getElementById('auth-error');
  authPending = true;
  setAuthPending(true, mode);
  error.hidden = true;
  try {
    const profile = await api(mode, { username, password });
    hideAuth();
    setAccount(profile);
    if (state.route === 'library') goLibrary(state.libraryTab);
    toast(mode === 'login' ? '登录成功，片单正在同步' : '注册成功，片单正在同步');
  } catch (failure) {
    if (authOverlay.hidden) toast(failure.message || '请稍后重试', true);
    else { error.textContent = failure.message || '请稍后重试'; error.hidden = false; }
  } finally { authPending = false; setAuthPending(false, mode); }
});
window.hgAccountRefreshed = profile => {
  if (profile?.userId && state.account.userId === profile.userId) setAccount(profile);
};
window.hgAvatarUpdated = profile => { setAccount(profile); toast('头像已更新'); };
window.hgAvatarError = message => toast(message || '头像保存失败', true);
window.hgCloudSynced = () => { if (state.route === 'library') api('library').then(data => { state.library = data; renderLibrary(); }).catch(() => {}); };

function showUpdate(result) {
  document.getElementById('update-version').textContent = `当前 ${result.currentVersion} · 新版 ${result.version}`;
  const notes = document.getElementById('update-notes');
  notes.textContent = String(result.notes || '').trim().slice(0, 1200);
  notes.hidden = !notes.textContent;
  document.getElementById('update-progress').hidden = true;
  updateDownloadButton.textContent = '下载并安装';
  updateDownloadButton.disabled = false;
  updateOverlay.hidden = false;
}

async function checkUpdate(manual) {
  if (updateDownloading) { if (manual) toast('更新包正在下载', true); return; }
  if (updateChecking) { if (manual) toast('正在检查更新', true); return; }
  updateChecking = true;
  try {
    const result = await api('check-update', { manual });
    if (result.status === 'available') showUpdate(result);
    else if (manual && result.status === 'current') toast(`已是最新版本 ${result.version}`);
    else if (manual && result.status === 'unpublished') toast('暂未发布更新包', true);
    else if (manual && result.status === 'throttled') toast('一小时内最多手动检查 5 次，请稍后再试', true);
  } catch (error) {
    if (manual) toast(error.message || '检查更新失败，请稍后重试', true);
  } finally { updateChecking = false; }
}

window.hgUpdateProgress = percent => {
  document.getElementById('update-progress').hidden = false;
  document.getElementById('update-percent').textContent = `${percent}%`;
  document.getElementById('update-fill').style.transform = `scaleX(${percent / 100})`;
};
window.hgUpdateInstallPermissionDenied = () => toast('请允许此应用安装更新包后重试', true);
window.hgUpdateInstallError = () => toast('无法打开系统安装程序，请重试', true);

updateDownloadButton.addEventListener('click', async () => {
  if (updateDownloading) return;
  updateDownloading = true;
  updateDownloadButton.disabled = true;
  updateLaterButton.disabled = true;
  updateDownloadButton.textContent = '正在下载…';
  window.hgUpdateProgress(0);
  try {
    await api('download-update');
    updateDownloadButton.textContent = '重新打开安装程序';
  } catch (error) {
    updateDownloadButton.textContent = '重试下载';
    toast(error.message || '下载失败，请重试', true);
  } finally {
    updateDownloading = false;
    updateDownloadButton.disabled = false;
    updateLaterButton.disabled = false;
  }
});
updateLaterButton.addEventListener('click', () => { if (!updateDownloading) updateOverlay.hidden = true; });

window.hgTheme = dark => { document.documentElement.dataset.theme = dark ? 'dark' : 'light'; };
api('theme').then(window.hgTheme).catch(() => {});

function itemCard(item) {
  const id = esc(item.id);
  const title = esc(item.title || '影片');
  const info = esc(item.episodeLabel || asArray(item.tags).slice(0, 2).join(' · ') || '打开详情');
  return `<button class="film-card" type="button" data-open-id="${id}" aria-label="查看${title}">
    <span class="poster loading"><span class="poster-letter">${title.slice(0, 1)}</span><img data-cover="${esc(item.cover || '')}" alt=""><span class="poster-score" ${item.score ? '' : 'hidden'}>${esc(item.score || '')}</span></span>
    <span class="film-title">${title}</span><span class="film-info">${info}</span></button>`;
}

function section(title, items, kind = '') {
  if (!items.length) return '';
  return `<section class="film-section"><div class="section-heading"><h2>${esc(title)}</h2>${kind ? `<button class="more" data-more="${kind}" type="button">更多 ${icon('arrow')}</button>` : ''}</div><div class="film-row">${items.map(itemCard).join('')}</div></section>`;
}
function homeTagBar() {
  return `<nav class="home-tags" aria-label="按类型浏览"><button type="button" class="all-tags-link" data-all-tags="1">全部类型 ${icon('arrow')}</button>
    <div class="home-tag-scroll">${popularTags.map(([slug, name]) => `<button type="button" class="home-tag" data-tag-slug="${slug}" data-tag-name="${name}">${name}</button>`).join('')}</div></nav>`;
}

function homeSignature(data) {
  return JSON.stringify([asArray(data.featured), asArray(data.homepageRecommend), asArray(data.homepageNewest), asArray(data.recommend?.items), asArray(data.newest?.items)]
    .map(list => list.map(item => [item.id, item.title, item.episodeLabel, item.score])));
}
function listSignature(data) {
  return JSON.stringify(asArray(data.items).map(item => [item.id, item.title, item.episodeLabel, item.score]));
}

function paletteFromImage(image) {
  const canvas = document.createElement('canvas'); canvas.width = canvas.height = 48;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  context.drawImage(image, 0, 0, 48, 48);
  const pixels = context.getImageData(0, 0, 48, 48).data;
  let red = 0, green = 0, blue = 0, weightSum = 0;
  for (let index = 0; index < pixels.length; index += 16) {
    const r = pixels[index], g = pixels[index + 1], b = pixels[index + 2];
    const brightness = (r + g + b) / 3;
    if (brightness < 28 || brightness > 225) continue;
    const weight = 0.6 + (Math.max(r, g, b) - Math.min(r, g, b)) / 255;
    red += r * weight; green += g * weight; blue += b * weight; weightSum += weight;
  }
  if (!weightSum) throw new Error('未找到可用颜色');
  const r = red / weightSum / 255, g = green / weightSum / 255, b = blue / weightSum / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), difference = max - min;
  let hue = 28;
  if (difference) {
    hue = max === r ? ((g - b) / difference) % 6 : max === g ? (b - r) / difference + 2 : (r - g) / difference + 4;
    hue = (hue * 60 + 360) % 360;
  }
  const saturation = Math.round(Math.max(17, Math.min(32, difference / Math.max(max, .01) * 75)));
  return { light: `hsl(${Math.round(hue)} ${saturation}% 62%)`, dark: `hsl(${Math.round(hue)} ${Math.min(34, saturation + 2)}% 25%)` };
}
function applyBannerPalette(image) {
  const slide = image.closest('.hero-slide') || image.closest('.hero');
  if (!slide) return;
  const key = (image.dataset.cover || '').split('?')[0];
  let cache = {};
  try { cache = JSON.parse(localStorage.getItem('banner-palettes') || '{}'); } catch { }
  let colors = cache[key];
  if (!colors) {
    try { colors = paletteFromImage(image); cache[key] = colors; localStorage.setItem('banner-palettes', JSON.stringify(cache)); }
    catch { colors = { light: '#B5A08B', dark: '#49332F' }; }
  }
  slide.style.setProperty('--hero-tint-light', colors.light);
  slide.style.setProperty('--hero-tint-dark', colors.dark);
}

function coverKey(url) {
  try { return new URL(url).pathname; } catch { return String(url).split('?')[0]; }
}
function getCover(url) {
  const key = coverKey(url);
  if (coverRequests.has(key)) return coverRequests.get(key);
  const request = api('cover', { url }).catch(error => { coverRequests.delete(key); throw error; });
  coverRequests.set(key, request);
  if (coverRequests.size > 80) coverRequests.delete(coverRequests.keys().next().value);
  return request;
}
async function loadCoverInto(image) {
  const url = image.dataset.cover;
  if (image.dataset.loading === '1') return;
  if (!url) {
    image.closest('.poster')?.classList.remove('loading');
    image.closest('.poster')?.classList.add('fallback');
    return;
  }
  image.dataset.loading = '1';
  const poster = image.closest('.poster');
  try {
    let data;
    try { data = await getCover(url); }
    catch { await new Promise(resolve => setTimeout(resolve, 450)); data = await getCover(url); }
    if (!image.isConnected || image.dataset.cover !== url) return;
    await new Promise((resolve, reject) => {
      image.onload = () => { image.onload = image.onerror = null; resolve(); };
      image.onerror = () => { image.onload = image.onerror = null; reject(new Error('图片无法解码')); };
      image.src = data;
      if (image.complete && image.naturalWidth > 0) { image.onload = image.onerror = null; resolve(); }
    });
    if (!image.isConnected || image.dataset.cover !== url) return;
    poster?.classList.remove('loading', 'fallback');
    poster?.classList.add('has-image');
    delete image.dataset.retryAfter;
    if (image.classList.contains('hero-image')) applyBannerPalette(image);
  } catch (error) {
    coverRequests.delete(coverKey(url));
    if (image.isConnected) {
      image.removeAttribute('src');
      poster?.classList.remove('loading');
      poster?.classList.add('fallback');
      image.dataset.retryAfter = String(Date.now() + (error.message === '图片无法解码' ? 300000 : 30000));
    }
    const id = image.closest('[data-open-id]')?.dataset.openId || image.closest('.hero-slide,.category-spotlight')?.querySelector('[data-open-id]')?.dataset.openId || 'unknown';
    console.warn('封面加载失败', id, (() => { try { return new URL(url).host; } catch { return 'invalid'; } })(), error.message);
  } finally { delete image.dataset.loading; }
}

function retryFailedImages(force = false) {
  const viewport = scroll.getBoundingClientRect();
  content.querySelectorAll('img[data-cover]').forEach(image => {
    if (image.naturalWidth > 0 || image.dataset.loading === '1') return;
    if (!force && Date.now() < Number(image.dataset.retryAfter || 0)) return;
    const card = image.closest('.poster');
    if (card && !card.classList.contains('fallback')) return;
    const box = image.getBoundingClientRect();
    if (box.bottom < viewport.top - 320 || box.top > viewport.bottom + 320) return;
    const row = image.closest('.film-row');
    if (row) {
      const visible = row.getBoundingClientRect();
      if (box.right < visible.left - 250 || box.left > visible.right + 250) return;
    }
    card?.classList.remove('fallback');
    card?.classList.add('loading');
    loadCoverInto(image);
  });
}
let coverScrollTimer;
scroll.addEventListener('scroll', () => {
  clearTimeout(coverScrollTimer);
  coverScrollTimer = setTimeout(() => retryFailedImages(), 160);
}, { passive: true });
window.addEventListener('online', () => retryFailedImages(true));
function hydrateImages(root = content) {
  if (observer) observer.disconnect();
  observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (!entry.isIntersecting) return;
      const image = entry.target;
      observer.unobserve(image);
      loadCoverInto(image);
    });
  }, { root: scroll, rootMargin: '320px' });
  root.querySelectorAll('img[data-cover]').forEach((image, index) => {
    if (image.naturalWidth > 0) return;
    if (index < 14 || image.classList.contains('hero-image')) loadCoverInto(image);
    else observer.observe(image);
  });
}

function homeSlides() {
  const featured = asArray(state.home?.featured);
  return featured.length ? featured : asArray(state.home?.recommend?.items).slice(0, 5);
}
function stopBannerAuto() {
  clearTimeout(bannerTimer);
  bannerTimer = null;
}
function scheduleBannerAuto() {
  stopBannerAuto();
  if (state.route !== 'home' || document.hidden || homeSlides().length < 2 || !content.querySelector('.hero')) return;
  bannerTimer = setTimeout(() => {
    bannerTimer = null;
    if (state.route !== 'home' || document.hidden) return;
    const count = homeSlides().length;
    if (count > 1) changeBanner((state.banner + 1) % count, 1, true);
  }, 3000);
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) stopBannerAuto();
  else scheduleBannerAuto();
});
function heroSlide(item, index, count) {
  return `<div class="hero-slide" data-index="${index}"><img class="hero-image" data-cover="${esc(item.cover || '')}" alt=""><div class="hero-shade"></div>
    <div class="hero-copy"><span class="hero-label">本周焦点 <i></i> 独家精选</span><h2>${esc(item.title || '为你推荐')}</h2><p>${esc(item.description || item.episodeLabel || '')}</p><button class="hero-action" data-open-id="${esc(item.id || '')}" type="button">${icon('play')} 立即观看</button></div>
    <div class="hero-index"><b>${String(index + 1).padStart(2, '0')}</b> / ${String(count).padStart(2, '0')}</div></div>`;
}
async function changeBanner(targetIndex, direction, automatic = false) {
  const slides = homeSlides();
  const hero = content.querySelector('.hero');
  if (!hero || !slides.length || bannerChanging || targetIndex === state.banner) return;
  stopBannerAuto();
  bannerChanging = true;
  let incoming = null;
  try {
    const item = slides[targetIndex];
    const data = await getCover(item.cover);
    const template = document.createElement('template');
    template.innerHTML = heroSlide(item, targetIndex, slides.length);
    incoming = template.content.firstElementChild;
    const image = incoming.querySelector('img');
    image.src = data;
    if (!hero.isConnected || state.route !== 'home' || document.hidden) return;
    incoming.style.transform = `translateX(${direction > 0 ? 100 : -100}%)`;
    hero.appendChild(incoming);
    await image.decode();
    if (!hero.isConnected || state.route !== 'home' || document.hidden) { incoming.remove(); return; }
    applyBannerPalette(image);
    const current = hero.querySelector('.hero-slide:not(:last-child)');
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    incoming.classList.add('animating');
    current.classList.add('animating');
    incoming.style.transform = 'translateX(0)';
    current.style.transform = `translateX(${direction > 0 ? -100 : 100}%)`;
    await new Promise(resolve => setTimeout(resolve, 330));
    current.remove();
    incoming.classList.remove('animating');
    incoming.style.transform = '';
    state.banner = targetIndex;
    content.querySelectorAll('.hero-dots [data-slide]').forEach(dot => dot.classList.toggle('active', Number(dot.dataset.slide) === targetIndex));
  } catch (error) {
    incoming?.remove();
    hero.querySelectorAll('.hero-slide').forEach(slide => { slide.classList.remove('animating'); slide.style.transform = ''; });
    if (!automatic) toast('封面加载失败，请重试', true);
  }
  finally { bannerChanging = false; scheduleBannerAuto(); }
}
function renderHome() {
  const data = state.home;
  if (!data) { renderSkeleton(); return; }
  const slides = homeSlides();
  const index = Math.min(state.banner, Math.max(0, slides.length - 1));
  const selected = slides[index] || {};
  content.innerHTML = `<div class="home-page"><div class="hero" data-hero="1">${heroSlide(selected, index, slides.length)}</div>
      <div class="hero-dots">${slides.map((_, i) => `<i class="${i === index ? 'active' : ''}" data-slide="${i}"></i>`).join('')}</div>
      ${homeTagBar()}
      ${section('精选推荐', asArray(data.homepageRecommend).length ? asArray(data.homepageRecommend) : asArray(data.recommend?.items).slice(0, 12), 'recommend')}
      ${section('最近上新', asArray(data.homepageNewest).length ? asArray(data.homepageNewest) : asArray(data.newest?.items).slice(0, 12), 'newest')}</div>`;
  hydrateImages();
  slides.forEach(item => { if (item.cover) getCover(item.cover).catch(() => {}); });
  scheduleBannerAuto();
}

function renderTags() {
  const tags = asArray(state.tags?.items);
  const groups = tagGroups.map(([id, title]) => {
    const members = tags.filter(tag => tag.group === id);
    if (!members.length) return '';
    return `<section class="tags-group"><h2>${title}</h2><div class="tags-grid">${members.map(tag => {
      const name = String(tag.name || tag.slug);
      const size = name.length > 8 ? 'wide' : name.length > 4 ? 'medium' : '';
      return `<button type="button" class="tag-choice ${size}" data-tag-slug="${esc(tag.slug)}" data-tag-name="${esc(name)}">${esc(name)}</button>`;
    }).join('')}</div></section>`;
  }).join('');
  content.innerHTML = `<div class="tags-page"><div class="tags-heading"><button type="button" data-back="1" aria-label="返回首页">${icon('back')}</button><h1>全部类型</h1><span>${tags.length} 个标签</span></div>
    <p class="tags-intro">点选标签，发现相关作品</p>${groups}</div>`;
}

function refreshMissingImages(items) {
  const byId = new Map(items.filter(item => item?.id && item?.cover).map(item => [String(item.id), item.cover]));
  content.querySelectorAll('[data-open-id]').forEach(button => {
    const image = button.querySelector('img[data-cover]') || button.closest('.hero-slide,.category-spotlight')?.querySelector('img[data-cover]');
    const cover = byId.get(button.dataset.openId);
    if (!image || !cover || image.naturalWidth > 0) return;
    image.dataset.cover = cover;
    delete image.dataset.loading;
    loadCoverInto(image);
  });
}

function categoryId() { return categoryIds[state.tab][state.sub[state.tab]]; }
function compactMarkup() {
  const adult = state.tab === 'adult';
  const tabs = adult ? ['成人短剧', '成人漫剧'] : ['AI换脸', 'AI魔改'];
  return `<strong>${adult ? '成人剧场' : 'AI专区'}</strong><div class="compact-tabs" role="tablist">${tabs.map((name, index) => `<button type="button" role="tab" aria-selected="${index === state.sub[state.tab]}" data-sub="${index}" class="${index === state.sub[state.tab] ? 'selected' : ''}">${name}</button>`).join('')}</div>`;
}
function updateCompact() {
  const eligible = state.route === 'category';
  if (!eligible || scroll.scrollTop <= 0) state.compactHeld = false;
  else if (scroll.scrollTop > 92) state.compactHeld = true;
  const visible = eligible && state.compactHeld;
  compact.classList.toggle('visible', visible);
  compact.setAttribute('aria-hidden', String(!visible));
  compact.inert = !visible;
  content.querySelector('.category-page')?.classList.toggle('is-collapsed', visible);
}
scroll.addEventListener('scroll', updateCompact, { passive: true });

function renderCategory(keepCompact = false) {
  categoryObserver?.disconnect();
  const adult = state.tab === 'adult';
  const tabs = adult ? ['成人短剧', '成人漫剧'] : ['AI换脸', 'AI魔改'];
  const items = asArray(state.category?.items);
  const lead = items[0] || null;
  content.innerHTML = `<div class="category-page"><div class="category-head"><h1>${adult ? '成人剧场' : 'AI专区'}</h1><p>${adult ? '短剧与漫剧，走进不同的人生。' : '换脸与魔改，探索影像新可能。'}</p></div>
    <div class="segmented" role="tablist">${tabs.map((name, index) => `<button type="button" role="tab" data-sub="${index}" aria-selected="${index === state.sub[state.tab]}" class="${index === state.sub[state.tab] ? 'selected' : ''}">${name}</button>`).join('')}</div>
    ${lead ? `<div class="category-spotlight"><img data-cover="${esc(lead.cover)}" alt=""><div class="category-spotlight-copy"><h2>${esc(lead.title)}</h2><button type="button" data-open-id="${esc(lead.id)}">查看作品 ${icon('arrow')}</button></div></div>` : ''}
    <section class="category-section"><div class="section-heading"><h2>${tabs[state.sub[state.tab]]}作品</h2><span class="category-count">已显示 ${items.length} 部</span></div>
    <div class="search-grid category-grid">${items.map(itemCard).join('')}</div><div class="category-footer">${categoryFooter()}</div></section></div>`;
  compact.innerHTML = compactMarkup();
  scroll.scrollTop = keepCompact ? 150 : 0;
  state.compactHeld = keepCompact;
  updateCompact();
  hydrateImages();
  observeCategoryEnd();
}

function categoryFooter() {
  const category = state.category;
  if (category?.error) return '<button type="button" data-retry-category="1">加载失败，点击重试</button>';
  if (category?.loading) return '<span role="status">正在加载更多…</span>';
  if (category?.refreshing) return '<span role="status">正在更新列表…</span>';
  if (category?.nextPage) return '<span class="category-sentinel" aria-hidden="true"></span>';
  return '<span>已经到底了</span>';
}

function observeCategoryEnd() {
  categoryObserver?.disconnect();
  const category = state.category;
  if (state.route !== 'category' || !category?.nextPage || category.loading || category.refreshing || category.error) return;
  const sentinel = content.querySelector('.category-sentinel');
  if (!sentinel) return;
  if (!categoryObserver) {
    categoryObserver = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) loadMoreCategory();
    }, { root: scroll, rootMargin: '0px 0px 320px 0px' });
  }
  categoryObserver.observe(sentinel);
}

function updateCategoryFooter() {
  const footer = content.querySelector('.category-footer');
  if (!footer) return;
  footer.innerHTML = categoryFooter();
  observeCategoryEnd();
}

function renderLibrary() {
  const data = state.library || { progress: [], bookmarks: [] };
  const query = state.libraryQuery.trim().toLocaleLowerCase();
  const matches = item => String(item.title || '').toLocaleLowerCase().includes(query);
  const progress = asArray(data.progress).filter(matches);
  const bookmarks = asArray(data.bookmarks).filter(matches);
  const historyActive = state.libraryTab === 'history';
  const historyCards = progress.map(item => {
    const completed = Boolean(item.completed);
    const episode = Number(item.episode) || 1;
    const percentage = completed ? 100 : Math.max(0, Math.min(100, item.duration ? item.position / item.duration * 100 : 0));
    return `<button type="button" class="history-card" data-history-id="${esc(item.id)}" data-history-episode="${episode}" data-history-completed="${completed}" aria-label="${esc(item.title)}，第 ${episode} 集，${completed ? '已看完，打开详情' : '继续观看'}">
      <span class="history-poster poster loading"><span class="poster-letter">${esc(String(item.title || '影片').slice(0, 1))}</span><img data-cover="${esc(item.cover || '')}" alt=""></span><span class="history-copy"><span class="history-status">${completed ? '已看完' : '继续观看'} · 第 ${episode} 集</span>
      <strong>${esc(item.title || '影片')}</strong><span class="progress"><i style="width:${percentage}%"></i></span><span class="history-hint">${completed ? '打开详情选择剧集' : `已看 ${Math.floor(item.position / 60000)} 分钟`}</span></span></button>`;
  }).join('');
  content.innerHTML = `<div class="library-page"><div class="my-heading"><h1>我的</h1></div>
    ${state.account.loggedIn ? '' : `<button type="button" class="my-account" data-account-action="1">点击头像登录，同步收藏和观看历史 ${icon('arrow')}</button>`}
    ${query ? `<div class="library-results"><p class="library-count">在观看历史中找到 ${progress.length} 部，在我的收藏中找到 ${bookmarks.length} 部</p>
      <h2>观看历史</h2>${progress.length ? `<div class="history-list">${historyCards}</div>` : '<p class="library-no-match">没有匹配的观看记录</p>'}
      <h2>我的收藏</h2>${bookmarks.length ? `<div class="search-grid library-bookmark-grid">${bookmarks.map(itemCard).join('')}</div>` : '<p class="library-no-match">没有匹配的收藏</p>'}</div>` : `
    <div class="segmented library-tabs" role="tablist" aria-label="我的片单分类"><button type="button" role="tab" data-library-tab="history" aria-selected="${historyActive}" class="${historyActive ? 'selected' : ''}">观看历史</button>
    <button type="button" role="tab" data-library-tab="bookmarks" aria-selected="${!historyActive}" class="${!historyActive ? 'selected' : ''}">我的收藏</button></div>
    <p class="library-count">${historyActive ? `最近观看的 ${progress.length} 部作品` : `已收藏 ${bookmarks.length} 部作品`}</p>
    ${historyActive ? (progress.length ? `<div class="history-list">${historyCards}</div>` : '<div class="empty-state">还没有观看记录，播放作品后会出现在这里</div>') :
      (bookmarks.length ? `<div class="search-grid library-bookmark-grid">${bookmarks.map(itemCard).join('')}</div>` : '<div class="empty-state">还没有收藏作品</div>')}`}</div>`;
  hydrateImages();
}

function renderSettings() {
  content.innerHTML = `<div class="settings-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回我的</button><h1>设置</h1>
    <section><button type="button" class="settings-link" data-account-management="1"><span><strong>账号管理</strong><small>${state.account.loggedIn ? esc(state.account.username) : '登录或注册账号'}</small></span>${icon('arrow')}</button></section>
    <section class="settings-library"><h2>片单管理</h2><p>${state.account.loggedIn ? '清空后会同步到当前账号的其他设备。' : '清空当前设备保存的片单。'}</p>
      <button type="button" data-clear="history" ${asArray(state.library?.progress).length ? '' : 'disabled'}>清空观看历史</button>
      <button type="button" data-clear="bookmarks" ${asArray(state.library?.bookmarks).length ? '' : 'disabled'}>清空我的收藏</button></section>
    <section class="update-settings"><div><h2>应用更新</h2><p>启动后自动检查，也可以在这里手动检查。</p></div><button type="button" data-check-update>检查更新</button></section>
    <section class="settings-storage"><h2>本地存储</h2><button type="button" data-clear="cache">清理内容缓存</button></section></div>`;
}

function renderAccountManagement() {
  const account = state.account;
  content.innerHTML = `<div class="settings-page account-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button><h1>账号管理</h1>
    ${account.loggedIn ? `<section class="account-identity"><img src="${esc(account.avatar || 'user.svg')}" alt=""><div><strong>${esc(account.username)}</strong><span>已登录黄果账号</span></div></section>
      <section class="settings-account"><h2>个人资料</h2><button type="button" data-choose-avatar="1">从相册更换头像</button></section>
      <section class="settings-account"><h2>修改密码</h2><p>先验证旧密码，再设置至少 8 位的新密码。</p>
        <form id="password-form" class="password-form">
          <label for="old-password">旧密码</label><input id="old-password" type="password" autocomplete="current-password" required>
          <label for="new-password">新密码</label><input id="new-password" type="password" autocomplete="new-password" minlength="8" required>
          <label for="confirm-password">再次输入新密码</label><input id="confirm-password" type="password" autocomplete="new-password" minlength="8" required>
          <p id="password-error" class="auth-error" role="alert" hidden></p><button type="submit">确认修改</button>
        </form></section>
      <section class="settings-account"><h2>同步与登录</h2><button type="button" data-sync-now="1">立即同步片单</button><button type="button" data-logout="1" aria-live="polite">退出登录</button></section>` : `<section class="settings-account"><p>登录后可同步收藏和观看历史，并管理头像与密码。</p><button type="button" data-account-action="1">登录或注册</button></section>`}</div>`;
}

function renderSearch() {
  const filters = [['all', '全部'], ['ai-duanju', '成人短剧'], ['ai-manju', '成人漫剧'], ['ai-huanlian', 'AI换脸'], ['ai-mogai', 'AI魔改']];
  const all = asArray(state.search?.items);
  const items = state.filter === 'all' ? all : all.filter(item => {
    if (item.category) return item.category === state.filter;
    const title = String(item.title || '');
    if (state.filter === 'ai-huanlian') return /AI\s*换脸/.test(title);
    if (state.filter === 'ai-mogai') return /AI\s*魔改/.test(title);
    if (state.filter === 'ai-manju') return /漫剧/.test(title);
    return !/AI\s*(换脸|魔改)|漫剧/.test(title);
  });
  content.innerHTML = `<div class="search-page"><h1>搜索结果</h1><p>${esc(state.query)} · ${items.length} 部作品</p><div class="filter-row">${filters.map(([id, label]) => `<button type="button" data-filter="${id}" class="${state.filter === id ? 'active' : ''}">${label}</button>`).join('')}</div>
    ${items.length ? `<div class="search-grid">${items.map(itemCard).join('')}</div>` : '<div class="empty-state">没有找到匹配的影片</div>'}</div>`;
  hydrateImages();
}

function listingFooter() {
  const listing = state.listing;
  if (listing.error) return '<button type="button" data-retry-listing="1">加载失败，点击重试</button>';
  if (listing.loading) return '<span role="status">正在加载更多…</span>';
  if (listing.refreshing) return '<span role="status">正在更新列表…</span>';
  if (listing.nextPage) return listing.kind === 'tag-list' ? '<span class="listing-sentinel" aria-hidden="true"></span>' : '<span class="listing-sentinel">继续下滑加载更多</span>';
  return '<span>已经到底了</span>';
}

function observeListingEnd() {
  listingObserver?.disconnect();
  if (state.route !== 'listing' || !state.listing?.nextPage || state.listing.loading ||
      state.listing.refreshing || state.listing.error) return;
  const sentinel = content.querySelector('.listing-sentinel');
  if (!sentinel) return;
  if (!listingObserver) {
    listingObserver = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) loadMoreListing();
    }, { root: scroll, rootMargin: '0px 0px 320px 0px' });
  }
  listingObserver.observe(sentinel);
}

function updateListingFooter() {
  const footer = content.querySelector('.listing-footer');
  if (!footer) return;
  footer.innerHTML = listingFooter();
  observeListingEnd();
}

function renderListing() {
  const listing = state.listing;
  if (!listing?.items.length && listing?.refreshing) { renderSkeleton(); return; }
  const title = listing.kind === 'tag-list' ? `#${String(listing.label || listing.slug).replace(/^#+/, '')}` : listing.kind === 'recommend' ? '精选推荐' : '最近上新';
  const backLabel = listing.kind === 'tag-list' && state.listingReturn?.route === 'tags' ? '返回全部类型' : '返回首页';
  content.innerHTML = `<div class="listing-page"><button class="listing-back" type="button" data-back="1">${icon('back')} ${backLabel}</button>
    <h1>${esc(title)}</h1><p>已显示 ${listing.items.length} 部作品</p>
    ${listing.items.length ? `<div class="search-grid listing-grid">${listing.items.map(itemCard).join('')}</div>` : '<div class="empty-state">这个标签下暂无作品</div>'}
    <div class="listing-footer">${listingFooter()}</div></div>`;
  hydrateImages();
  observeListingEnd();
}

function renderDetail() {
  const data = state.detail;
  if (!data) { renderSkeleton(); return; }
  const episodes = asArray(data.episodes);
  content.innerHTML = `<div class="detail-page"><button class="detail-back" type="button" data-back="1">${icon('back')} 返回</button>
    <div class="detail-art"><img data-cover="${esc(data.cover)}" alt=""></div><div class="detail-copy"><h1>${esc(data.title)}</h1><p>${esc(data.score)} ${esc(data.episodeLabel)}</p><p class="detail-description">${esc(data.description || '')}</p>
    <div class="detail-buttons"><button class="detail-play" type="button" data-play="1">立即播放</button><button class="bookmark-button" type="button" data-bookmark="1" aria-label="${state.bookmarked ? '取消收藏' : '收藏'}">${state.bookmarked ? '★' : '☆'}</button></div>
    ${episodes.length ? `<h2>选集</h2><div class="episode-grid">${episodes.map((item, index) => `<button type="button" data-episode="${item.number}" class="${index === 0 ? 'active' : ''}">${item.number}</button>`).join('')}</div>` : ''}</div></div>`;
  hydrateImages();
}

function renderSkeleton() {
  content.innerHTML = '<div class="skeleton-home" role="status" aria-label="正在加载内容"><div class="skeleton skeleton-hero"></div><div class="skeleton skeleton-title"></div><div class="skeleton-row"><div class="skeleton skeleton-poster"></div><div class="skeleton skeleton-poster"></div><div class="skeleton skeleton-poster"></div></div><div class="skeleton skeleton-line"></div></div>';
}
function renderError(error, retry) {
  if (/站点连接失败|站点请求失败|failed to connect|unable to resolve host|unknownhost|connectexception|sockettimeoutexception|timed? out|network is unreachable|connection reset|connection refused|网络|连接失败|无法连接/i.test(error?.message || '')) {
    content.innerHTML = `<div class="error-state error-state--network" role="alert">
      <div class="error-main">
        <svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><path d="m12 24 6-5 6 5 6-5 6 5"/></svg>
        <h1>网络走丢了</h1>
      </div>
      <div class="error-footer"><p>请尝试使用代理网络，或联系开发者。</p><button type="button" data-retry="${esc(retry)}">重新尝试</button></div>
    </div>`;
    return;
  }
  content.innerHTML = `<div class="error-state">${esc(error.message || '加载失败，请稍后重试')}<button type="button" data-retry="${esc(retry)}">重试</button></div>`;
}

function rememberScreen() {
  if (state.route === 'detail') return;
  screenSnapshots[state.route] = {
    content: content.cloneNode(true), scrollTop: scroll.scrollTop,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft)
  };
}
function captureScreen() {
  rememberScreen();
  return {
    route: state.route, tab: state.tab, pageNode: content.firstElementChild,
    scrollTop: scroll.scrollTop, searchValue: searchInput.value,
    listing: state.route === 'listing' ? state.listing : null,
    listingReturn: state.listingReturn, tagsReturn: state.tagsReturn,
    compactHeld: state.compactHeld,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft)
  };
}
function restoreScreen(snapshot) {
  if (!snapshot?.pageNode) { goHome(); return; }
  ++routeToken;
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  setRoute(snapshot.route);
  state.tab = snapshot.tab;
  state.compactHeld = snapshot.compactHeld;
  if (snapshot.listing) state.listing = snapshot.listing;
  state.listingReturn = snapshot.listingReturn;
  state.tagsReturn = snapshot.tagsReturn;
  content.replaceChildren(snapshot.pageNode);
  if (snapshot.route === 'library') renderLibrary();
  if (snapshot.route === 'settings') renderSettings();
  searchInput.value = snapshot.searchValue || '';
  document.querySelectorAll('#dock [data-nav]').forEach(button => button.classList.toggle('active', button.dataset.nav === state.tab));
  scroll.scrollTop = snapshot.scrollTop || 0;
  content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = snapshot.horizontalScroll?.[index] || 0;
  });
  hydrateImages();
  if (snapshot.route === 'home') scheduleBannerAuto();
  if (snapshot.route === 'category') { compact.innerHTML = compactMarkup(); updateCompact(); observeCategoryEnd(); }
  if (snapshot.route === 'listing') observeListingEnd();
}

function setRoute(route) {
  if (route !== 'home') stopBannerAuto();
  const libraryArea = ['library', 'settings', 'account-management'].includes(route);
  if (state.route === 'library' && route !== 'library') searchInput.value = '';
  state.route = route;
  headerSettings.hidden = route !== 'library';
  searchInput.placeholder = libraryArea ? '搜索观看历史和收藏' : '搜索剧名或 #标签';
  searchInput.setAttribute('aria-label', libraryArea ? '搜索观看历史和收藏' : '搜索剧名或标签');
  if (route === 'library') searchInput.value = state.libraryQuery;
  window.AndroidHost?.setBackEnabled(route !== 'home');
}

function backDestination() {
  if (state.route === 'detail') return state.previous?.route || 'home';
  if (state.route === 'account-management') return state.accountReturn?.route || 'settings';
  if (state.route === 'settings') return state.settingsReturn?.route || 'library';
  if (state.route === 'tags') return state.tagsReturn?.route || 'home';
  if (state.route === 'listing' && state.listing?.kind === 'tag-list') return state.listingReturn?.route || 'home';
  return state.route === 'home' ? null : 'home';
}

function clearPredictivePreview() {
  clearTimeout(predictiveTimer);
  predictivePreview?.remove();
  predictivePreview = null;
  document.getElementById('app').classList.remove('predictive-active');
  scroll.style.removeProperty('transition');
  scroll.style.removeProperty('transform');
  scroll.style.removeProperty('border-radius');
  scroll.style.removeProperty('box-shadow');
}

window.hgPredictiveBackStart = fromRight => {
  clearPredictivePreview();
  const snapshot = screenSnapshots[backDestination()];
  if (!snapshot) return false;
  const preview = document.createElement('div');
  preview.className = 'back-preview';
  preview.setAttribute('aria-hidden', 'true');
  const previous = snapshot.content.cloneNode(true);
  previous.removeAttribute('id');
  previous.style.transform = `translateY(${-snapshot.scrollTop}px)`;
  preview.appendChild(previous);
  document.getElementById('app').appendChild(preview);
  preview.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = snapshot.horizontalScroll?.[index] || 0;
  });
  document.getElementById('app').classList.add('predictive-active');
  predictivePreview = preview;
  predictivePreview.fromRight = fromRight;
  return true;
};

window.hgPredictiveBackProgress = progress => {
  if (!predictivePreview) return;
  const direction = predictivePreview.fromRight ? -1 : 1;
  const amount = Math.max(0, Math.min(1, progress));
  scroll.style.transition = 'none';
  scroll.style.transform = `translateX(${direction * amount * 42}%)`;
  scroll.style.borderRadius = `${Math.round(amount * 18)}px`;
  scroll.style.boxShadow = `${-direction * 8}px 0 24px #0003`;
};

window.hgPredictiveBackCancel = () => {
  if (!predictivePreview) return;
  scroll.style.transition = 'transform 180ms ease-out';
  scroll.style.transform = 'translateX(0)';
  predictiveTimer = setTimeout(clearPredictivePreview, 180);
};

window.hgPredictiveBackCommit = () => {
  if (!predictivePreview) return window.hgBack();
  const direction = predictivePreview.fromRight ? -1 : 1;
  scroll.style.transition = 'transform 150ms ease-out';
  scroll.style.transform = `translateX(${direction * 100}%)`;
  predictiveTimer = setTimeout(() => {
    const oldPage = content.firstElementChild;
    if (!window.hgBack()) { clearPredictivePreview(); return; }
    if (content.firstElementChild !== oldPage) { clearPredictivePreview(); return; }
    const changed = new MutationObserver(() => {
      if (content.firstElementChild === oldPage) return;
      changed.disconnect();
      clearPredictivePreview();
    });
    changed.observe(content, { childList: true });
    predictiveTimer = setTimeout(() => { changed.disconnect(); clearPredictivePreview(); }, 1500);
  }, 150);
  return true;
};

function setNav(tab) {
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  document.querySelectorAll('#dock [data-nav]').forEach(button => button.classList.toggle('active', button.dataset.nav === tab));
  compact.classList.remove('visible');
  compact.inert = true;
  state.compactHeld = false;
  scroll.scrollTop = 0;
}

async function goHome() {
  const token = ++routeToken;
  stopBannerAuto();
  setRoute('home'); state.tab = 'home'; setNav('home');
  const refreshing = api('home');
  const cached = await api('cached', { key: 'home' }).catch(() => null);
  if (token !== routeToken) return;
  state.home = cached?.data || null;
  if (state.home) renderHome(); else renderSkeleton();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = state.home && homeSignature(state.home) !== homeSignature(fresh);
    const firstLoad = !state.home;
    state.home = fresh;
    if (firstLoad || changed) renderHome();
    else refreshMissingImages([...asArray(fresh.featured), ...asArray(fresh.recommend?.items), ...asArray(fresh.newest?.items)]);
    if (changed) toast('更新成功');
  } catch (error) { if (!state.home && token === routeToken) renderError(error, 'home'); }
}

async function goTags() {
  const token = ++routeToken;
  if (state.route !== 'tags') state.tagsReturn = captureScreen();
  setRoute('tags'); state.tab = 'home'; setNav('home');
  const refreshing = api('tags');
  const cached = await api('cached', { key: 'tags' }).catch(() => null);
  if (token !== routeToken) return;
  state.tags = cached?.data || state.tags;
  if (state.tags) renderTags(); else renderSkeleton();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = JSON.stringify(state.tags?.items) !== JSON.stringify(fresh.items);
    const previousScroll = scroll.scrollTop;
    state.tags = fresh;
    if (changed) { renderTags(); scroll.scrollTop = previousScroll; }
  } catch (error) { if (token === routeToken && !state.tags) renderError(error, 'tags'); }
}

async function goTag(slug, label) {
  const token = ++routeToken;
  if (state.route !== 'listing' || state.listing?.kind !== 'tag-list' || state.listing?.slug !== slug) state.listingReturn = captureScreen();
  setRoute('listing'); state.tab = 'home'; setNav('home');
  searchInput.value = `#${String(label).replace(/^#+/, '')}`;
  const refreshing = api('tag-list', { slug, page: 1 });
  const cached = await api('cached', { key: `tag:${slug}:1` }).catch(() => null);
  if (token !== routeToken) return;
  const firstLoad = !cached?.data;
  state.listing = cached?.data ? { ...cached.data, kind: 'tag-list', slug, label, refreshing: true, loading: false, error: false } :
    { items: [], nextPage: null, kind: 'tag-list', slug, label, refreshing: true, loading: false, error: false };
  renderListing();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = listSignature(state.listing) !== listSignature(fresh) || state.listing.nextPage !== fresh.nextPage;
    const previousScroll = scroll.scrollTop;
    state.listing = { ...fresh, kind: 'tag-list', slug, label, refreshing: false, loading: false, error: false };
    if (firstLoad || changed) { renderListing(); scroll.scrollTop = previousScroll; }
    else updateListingFooter();
  } catch (error) {
    if (token !== routeToken) return;
    if (!state.listing.items.length) renderError(error, 'tag-list');
    else { state.listing.refreshing = false; updateListingFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

async function goTagQuery(query) {
  const name = query.replace(/^[#＃]\s*/, '').trim();
  if (!name) { goTags(); return; }
  let tags = asArray(state.tags?.items);
  if (!tags.length) {
    const cached = await api('cached', { key: 'tags' }).catch(() => null);
    tags = asArray(cached?.data?.items);
  }
  if (!tags.length) {
    try { const fresh = await api('tags'); state.tags = fresh; tags = asArray(fresh.items); }
    catch { toast('标签暂时无法加载，请重试', true); return; }
  }
  const normalize = value => String(value || '').replace(/^#+/, '').trim().toLocaleLowerCase();
  let tag = tags.find(item => normalize(item.name) === normalize(name));
  if (!tag) {
    try { const fresh = await api('tags'); state.tags = fresh; tag = asArray(fresh.items).find(item => normalize(item.name) === normalize(name)); }
    catch { /* 已有清单仍可用于搜索 */ }
  }
  if (tag) goTag(tag.slug, tag.name);
  else toast('没有找到这个标签', true);
}

async function openListing(kind) {
  if (kind !== 'recommend' && kind !== 'newest') return;
  const token = ++routeToken;
  rememberScreen();
  listingObserver?.disconnect();
  setRoute('listing'); state.tab = 'home';
  setNav('home');
  const initial = state.home?.[kind];
  state.listing = {
    kind, items: [...asArray(initial?.items)], nextPage: initial?.nextPage || null,
    refreshing: true, loading: false, error: false
  };
  renderListing();
  try {
    const fresh = await api(kind, { page: 1 });
    if (token !== routeToken) return;
    state.listing.items = asArray(fresh.items);
    state.listing.nextPage = fresh.nextPage;
    state.listing.refreshing = false;
    const previousScroll = scroll.scrollTop;
    renderListing();
    scroll.scrollTop = previousScroll;
  } catch (error) {
    if (token !== routeToken) return;
    state.listing.refreshing = false;
    if (!state.listing.items.length) renderError(error, 'listing');
    else { updateListingFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

async function loadMoreListing() {
  const listing = state.listing;
  if (state.route !== 'listing' || !listing?.nextPage || listing.refreshing || listing.loading || listing.error) return;
  const token = routeToken;
  const pageNumber = listing.nextPage;
  listing.loading = true;
  updateListingFooter();
  try {
    const page = await api(listing.kind, { page: pageNumber, slug: listing.slug });
    if (token !== routeToken || state.listing !== listing || state.route !== 'listing') return;
    const known = new Set(listing.items.map(item => String(item.id)));
    const added = asArray(page.items).filter(item => {
      const id = String(item.id);
      if (known.has(id)) return false;
      known.add(id);
      return true;
    });
    listing.items.push(...added);
    listing.nextPage = page.items?.length && page.nextPage > pageNumber ? page.nextPage : null;
    const grid = content.querySelector('.listing-grid');
    if (grid && added.length) {
      grid.insertAdjacentHTML('beforeend', added.map(itemCard).join(''));
      const images = [...grid.querySelectorAll('img[data-cover]')].slice(-added.length);
      images.forEach((image, index) => index < 8 ? loadCoverInto(image) : observer.observe(image));
    }
    const count = content.querySelector('.listing-page > p');
    if (count) count.textContent = `已显示 ${listing.items.length} 部作品`;
  } catch (error) {
    if (token === routeToken && state.listing === listing && state.route === 'listing') listing.error = true;
  } finally {
    listing.loading = false;
    if (state.listing === listing && state.route === 'listing') updateListingFooter();
  }
}

async function goCategory(tab, sub = state.sub[tab], keepCompact = false) {
  const token = ++routeToken;
  categoryObserver?.disconnect();
  if (state.route === 'home') rememberScreen();
  setRoute('category'); state.tab = tab; state.sub[tab] = sub; state.category = null;
  if (!keepCompact) setNav(tab);
  const id = categoryId();
  const refreshing = api('category', { id, page: 1 });
  const cached = await api('cached', { key: `category:${id}:1` }).catch(() => null);
  if (token !== routeToken) return;
  state.category = cached?.data ? { ...cached.data, refreshing: true, loading: false, error: false } : null;
  if (state.category) renderCategory(keepCompact); else renderSkeleton();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = state.category && listSignature(state.category) !== listSignature(fresh);
    const firstLoad = !state.category;
    const previousScroll = scroll.scrollTop;
    state.category = { ...fresh, refreshing: false, loading: false, error: false };
    state.categoryItems = asArray(fresh.items);
    if (firstLoad || changed) {
      renderCategory(keepCompact || state.compactHeld);
      if (!firstLoad) scroll.scrollTop = previousScroll;
    } else {
      refreshMissingImages(asArray(fresh.items));
      updateCategoryFooter();
    }
    if (changed) toast('更新成功');
  } catch (error) {
    if (token !== routeToken) return;
    if (!state.category) renderError(error, 'category');
    else { state.category.refreshing = false; updateCategoryFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

async function goLibrary(tab = 'history') {
  const token = ++routeToken;
  if (state.route === 'home') rememberScreen();
  state.libraryTab = tab;
  setRoute('library'); state.tab = 'library'; setNav('library'); renderSkeleton();
  try { state.library = await api('library'); if (token === routeToken) renderLibrary(); }
  catch (error) { if (token === routeToken) renderError(error, 'library'); }
}

function goSettings() {
  state.settingsReturn = captureScreen();
  setRoute('settings'); state.tab = 'library'; setNav('library');
  renderSettings();
}

function goAccountManagement() {
  if (state.route === 'account-management') return;
  if (state.route !== 'settings') goSettings();
  state.accountReturn = captureScreen();
  setRoute('account-management');
  renderAccountManagement();
}

async function goSearch(query) {
  const token = ++routeToken;
  if (state.route === 'home') rememberScreen();
  state.previous = { route: state.route, tab: state.tab };
  setRoute('search'); state.query = query.trim(); state.filter = 'all'; state.search = null;
  const refreshing = api('search', { query: state.query });
  const cached = await api('cached', { key: `search:${state.query}` }).catch(() => null);
  if (token !== routeToken) return;
  state.search = cached?.data || null;
  if (state.search) renderSearch(); else renderSkeleton();
  try { const fresh = await refreshing; if (token === routeToken) { state.search = fresh; renderSearch(); } }
  catch (error) { if (!state.search && token === routeToken) renderError(error, 'search'); }
}

function sourceItem(id) {
  const candidates = [state.listing?.items, state.home?.featured, state.home?.recommend?.items, state.home?.newest?.items, state.category?.items, state.search?.items, state.library?.bookmarks, state.library?.progress].flatMap(asArray);
  return candidates.find(item => item.id === id) || { id, title: `剧集 ${id}` };
}
async function goDetail(id) {
  const token = ++routeToken;
  rememberScreen();
  state.previous = {
    route: state.route, tab: state.tab, sub: state.sub[state.tab], query: state.query,
    filter: state.filter, scrollTop: scroll.scrollTop, compactHeld: state.compactHeld,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row')].map(row => row.scrollLeft),
    pageNode: content.firstElementChild
  };
  if (state.route === 'listing' && state.listing) state.listing.refreshing = false;
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  state.sourceItem = sourceItem(id);
  state.sourceGroup = state.route === 'category' ? categoryId() : state.sourceItem.category || '';
  const aiGroup = state.sourceGroup === 'ai-huanlian' || state.sourceGroup === 'ai-mogai';
  const candidates = state.route === 'category' ? asArray(state.category?.items) : state.route === 'search' ? asArray(state.search?.items) : state.route === 'listing' ? asArray(state.listing?.items) :
    [...asArray(state.home?.recommend?.items), ...asArray(state.home?.newest?.items)];
  state.sourceQueue = aiGroup ? candidates.filter(item => item.category === state.sourceGroup) : [];
  setRoute('detail'); state.detail = null; state.bookmarked = false;
  updateCompact();
  const refreshing = api('detail', { id });
  const cached = await api('cached', { key: `detail:${id}` }).catch(() => null);
  if (token !== routeToken) return;
  state.detail = cached?.data || null;
  if (state.detail) renderDetail(); else renderSkeleton();
  api('bookmark-state', { id }).then(saved => { if (token === routeToken) { state.bookmarked = saved; if (state.detail) renderDetail(); } }).catch(() => {});
  try { const fresh = await refreshing; if (token === routeToken) { state.detail = fresh; renderDetail(); } }
  catch (error) { if (!state.detail && token === routeToken) renderError(error, 'detail'); }
}

function returnFromDetail() {
  const previous = state.previous || { route: 'home', tab: 'home' };
  if (!previous.pageNode) { goHome(); return; }
  ++routeToken;
  setRoute(previous.route);
  state.tab = previous.tab;
  if (previous.route === 'category') state.sub[previous.tab] = previous.sub;
  state.query = previous.query || '';
  state.filter = previous.filter || 'all';
  state.compactHeld = previous.compactHeld;
  content.replaceChildren(previous.pageNode);
  state.previous = null;
  content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = previous.horizontalScroll?.[index] || 0;
  });
  document.querySelectorAll('#dock [data-nav]').forEach(button =>
    button.classList.toggle('active', button.dataset.nav === state.tab));
  scroll.scrollTop = previous.scrollTop || 0;
  if (previous.route === 'category') compact.innerHTML = compactMarkup();
  updateCompact();
  hydrateImages();
  if (previous.route === 'category') observeCategoryEnd();
  if (previous.route === 'listing') observeListingEnd();
  if (previous.route === 'home') scheduleBannerAuto();
}

window.hgBack = () => {
  if (!authOverlay.hidden) { hideAuth(); return true; }
  if (!confirmOverlay.hidden) { closeConfirmation(false); return true; }
  if (!updateOverlay.hidden) { if (!updateDownloading) updateOverlay.hidden = true; return true; }
  if (state.route === 'account-management') { restoreScreen(state.accountReturn); return true; }
  if (state.route === 'settings') { restoreScreen(state.settingsReturn); return true; }
  if (state.route === 'detail') { returnFromDetail(); return true; }
  if (state.route === 'listing' && state.listing?.kind === 'tag-list') { restoreScreen(state.listingReturn); return true; }
  if (state.route === 'tags') { restoreScreen(state.tagsReturn); return true; }
  if (state.route === 'listing') { goHome(); return true; }
  if (state.route === 'search') { searchInput.value = ''; goHome(); return true; }
  if (state.route !== 'home') { goHome(); return true; }
  return false;
};
window.hgResume = () => {
  retryFailedImages(true);
  if (state.route === 'library') goLibrary(state.libraryTab);
  if (state.route === 'detail' && state.detail) api('bookmark-state', { id: state.detail.id }).then(value => { state.bookmarked = value; renderDetail(); });
};

async function openPlayer(episode = 1) {
  const detail = state.detail;
  if (!detail) return;
  const item = { ...state.sourceItem, ...detail };
  const queue = state.sourceQueue.length ? [...state.sourceQueue] : [item];
  if (!queue.some(candidate => candidate.id === item.id)) queue.unshift(item);
  const index = queue.findIndex(candidate => candidate.id === item.id);
  await api('open-player', { item, detail, episodes: asArray(detail.episodes), queue, index, group: state.sourceGroup, episode });
}

async function loadMoreCategory() {
  const category = state.category;
  if (state.route !== 'category' || !category?.nextPage || category.loading || category.refreshing || category.error) return;
  const token = routeToken;
  const next = category.nextPage;
  const id = categoryId();
  category.loading = true;
  updateCategoryFooter();
  try {
    const page = await api('category', { id, page: next });
    if (token !== routeToken || state.category !== category || state.route !== 'category' || categoryId() !== id) return;
    const existing = new Set(asArray(category.items).map(item => String(item.id)));
    const added = asArray(page.items).filter(item => {
      const itemId = String(item.id);
      if (existing.has(itemId)) return false;
      existing.add(itemId);
      return true;
    });
    category.items.push(...added);
    category.nextPage = page.items?.length && page.nextPage > next ? page.nextPage : null;
    const grid = content.querySelector('.category-grid');
    if (grid && added.length) {
      grid.insertAdjacentHTML('beforeend', added.map(itemCard).join(''));
      [...grid.querySelectorAll('img[data-cover]')].slice(-added.length)
        .forEach((image, index) => index < 8 ? loadCoverInto(image) : observer.observe(image));
    }
    const count = content.querySelector('.category-count');
    if (count) count.textContent = `已显示 ${category.items.length} 部`;
  } catch (error) {
    if (token === routeToken && state.category === category && state.route === 'category') category.error = true;
  } finally {
    category.loading = false;
    if (state.category === category && state.route === 'category') updateCategoryFooter();
  }
}

document.getElementById('search-form').addEventListener('submit', event => {
  event.preventDefault();
  const query = searchInput.value.trim();
  searchInput.blur();
  if (['library', 'settings', 'account-management'].includes(state.route)) {
    state.libraryQuery = query;
    if (state.route === 'library') renderLibrary();
    else goLibrary(state.libraryTab);
    scroll.scrollTop = 0;
    return;
  }
  if (/^[#＃]/.test(query)) goTagQuery(query);
  else if (query) goSearch(query);
});
searchInput.addEventListener('input', () => {
  if (state.route !== 'library') return;
  state.libraryQuery = searchInput.value;
  renderLibrary();
  scroll.scrollTop = 0;
});

document.addEventListener('submit', async event => {
  if (event.target.id !== 'password-form') return;
  event.preventDefault();
  const form = event.target;
  const oldPassword = form.querySelector('#old-password').value;
  const newPassword = form.querySelector('#new-password').value;
  const repeated = form.querySelector('#confirm-password').value;
  const error = form.querySelector('#password-error');
  const button = form.querySelector('[type="submit"]');
  error.hidden = true;
  if (newPassword !== repeated) { error.textContent = '两次输入的新密码不一致'; error.hidden = false; return; }
  if (newPassword === oldPassword) { error.textContent = '新密码不能与旧密码相同'; error.hidden = false; return; }
  button.disabled = true;
  try { setAccount(await api('change-password', { oldPassword, newPassword })); toast('密码已修改'); }
  catch (failure) { error.textContent = failure.message || '修改失败，请稍后重试'; error.hidden = false; button.disabled = false; }
});

function navigateDock(tab) {
  if (state.route === 'detail') state.previous = null;
  if (tab === 'home') goHome();
  else if (tab === 'library') goLibrary();
  else goCategory(tab);
}

function tapDock(button) {
  const tab = button.dataset.nav;
  const now = performance.now();
  if (lastDockTap.tab === tab && now - lastDockTap.at < 320) {
    const alreadyNavigated = lastDockTap.navigated;
    lastDockTap = { tab: '', at: 0, navigated: false };
    const icon = button.querySelector('.nav-icon');
    icon?.classList.remove('dock-bounce');
    void icon?.offsetWidth;
    icon?.classList.add('dock-bounce');
    icon?.addEventListener('animationend', () => icon.classList.remove('dock-bounce'), { once: true });
    scroll.scrollTop = 0;
    if (!alreadyNavigated) navigateDock(tab);
    return;
  }
  const route = tab === 'home' ? 'home' : tab === 'library' ? 'library' : 'category';
  const navigated = state.route !== route || state.tab !== tab;
  lastDockTap = { tab, at: now, navigated };
  if (navigated) navigateDock(tab);
}

document.addEventListener('click', async event => {
  if (event.target.closest('[data-check-update]')) { checkUpdate(true); return; }
  if (event.target.closest('[data-account-management]')) { goAccountManagement(); return; }
  if (event.target.closest('[data-choose-avatar]')) { api('choose-avatar').catch(error => toast(error.message, true)); return; }
  if (event.target.closest('[data-account-action]')) {
    if (state.account.loggedIn) goAccountManagement();
    else showAuth();
    return;
  }
  if (event.target.closest('[data-sync-now]')) {
    try { await api('sync'); state.library = await api('library'); toast('片单已同步'); }
    catch (error) { toast(`同步失败：${error.message}`, true); }
    return;
  }
  if (event.target.closest('[data-logout]')) {
    const button = event.target.closest('[data-logout]');
    if (button.disabled) return;
    button.disabled = true;
    button.classList.add('is-loading');
    button.setAttribute('aria-busy', 'true');
    button.textContent = '正在退出…';
    try {
      setAccount(await api('logout'));
      state.library = await api('library');
      setRoute('settings'); renderSettings();
      toast('已退出登录');
    }
    catch (error) { toast(error.message, true); }
    finally {
      if (button.isConnected) {
        button.disabled = false;
        button.classList.remove('is-loading');
        button.removeAttribute('aria-busy');
        button.textContent = '退出登录';
      }
    }
    return;
  }
  const nav = event.target.closest('[data-nav]');
  if (nav) { tapDock(nav); return; }
  const libraryTab = event.target.closest('[data-library-tab]');
  if (libraryTab) { state.libraryTab = libraryTab.dataset.libraryTab; renderLibrary(); scroll.scrollTop = 0; return; }
  const historyCard = event.target.closest('[data-history-id]');
  if (historyCard) {
    const id = historyCard.dataset.historyId;
    if (historyCard.dataset.historyCompleted === 'true') goDetail(id);
    else goDetail(id).then(() => openPlayer(Number(historyCard.dataset.historyEpisode || 1)));
    return;
  }
  if (event.target.closest('[data-all-tags]')) { goTags(); return; }
  const tagChoice = event.target.closest('[data-tag-slug]');
  if (tagChoice) { goTag(tagChoice.dataset.tagSlug, tagChoice.dataset.tagName); return; }
  const sub = event.target.closest('[data-sub]');
  if (sub) { goCategory(state.tab, Number(sub.dataset.sub), !!sub.closest('#compact-category')); return; }
  const card = event.target.closest('[data-open-id]');
  if (card?.dataset.openId) { goDetail(card.dataset.openId); return; }
  const filter = event.target.closest('[data-filter]');
  if (filter) { state.filter = filter.dataset.filter; renderSearch(); return; }
  const slide = event.target.closest('[data-slide]');
  if (slide) { const target = Number(slide.dataset.slide); changeBanner(target, target > state.banner ? 1 : -1); return; }
  if (event.target.closest('[data-back]')) { window.hgBack(); return; }
  const episode = event.target.closest('[data-episode]');
  if (episode) { openPlayer(Number(episode.dataset.episode)); return; }
  if (event.target.closest('[data-play]')) { openPlayer(1); return; }
  if (event.target.closest('[data-bookmark]')) {
    if (!state.detail) return;
    try { state.bookmarked = await api('bookmark', state.detail); renderDetail(); toast(state.bookmarked ? '已加入我的片单' : '已取消收藏'); }
    catch (error) { toast(error.message, true); }
    return;
  }
  if (event.target.closest('[data-retry-category]')) { state.category.error = false; loadMoreCategory(); return; }
  const more = event.target.closest('[data-more]');
  if (more) { openListing(more.dataset.more); return; }
  if (event.target.closest('[data-retry-listing]')) { state.listing.error = false; loadMoreListing(); return; }
  const retry = event.target.closest('[data-retry]');
  if (retry) {
    if (retry.dataset.retry === 'home') goHome();
    else if (retry.dataset.retry === 'category') goCategory(state.tab);
    else if (retry.dataset.retry === 'library') goLibrary();
    else if (retry.dataset.retry === 'search') goSearch(state.query);
    else if (retry.dataset.retry === 'listing') openListing(state.listing.kind);
    else if (retry.dataset.retry === 'tags') goTags();
    else if (retry.dataset.retry === 'tag-list') goTag(state.listing.slug, state.listing.label);
    else if (retry.dataset.retry === 'detail') goDetail(state.sourceItem.id);
    return;
  }
  const clear = event.target.closest('[data-clear]');
  if (clear) {
    if (clear.dataset.clear === 'cache') {
      if (!await askConfirmation('清理内容缓存', '将删除已缓存的目录和封面。收藏与观看进度会保留。', '清理')) return;
      await api('clear-content-cache'); toast('内容缓存已清空');
    } else if (clear.dataset.clear === 'history' || clear.dataset.clear === 'bookmarks') {
      const history = clear.dataset.clear === 'history';
      if (!await askConfirmation(history ? '清空观看历史' : '清空我的收藏', history ? '所有观看记录会被删除，我的收藏会保留。' : '所有收藏作品会被移除，观看历史会保留。')) return;
      await api(history ? 'clear-history' : 'clear-bookmarks');
      state.library = await api('library');
      renderSettings();
      toast(history ? '观看历史已清空' : '收藏已清空');
    }
  }
});

let heroStartX;
content.addEventListener('touchstart', event => { if (event.target.closest('.hero')) heroStartX = event.touches[0].clientX; }, { passive: true });
content.addEventListener('touchend', event => {
  if (heroStartX == null || !event.target.closest('.hero')) return;
  const move = event.changedTouches[0].clientX - heroStartX;
  heroStartX = null;
  if (Math.abs(move) < 55 || !state.home) return;
  const count = homeSlides().length;
  if (!count) return;
  const direction = move < 0 ? 1 : -1;
  changeBanner((state.banner + direction + count) % count, direction);
}, { passive: true });

function startHome() { goHome(); setTimeout(() => checkUpdate(false), 3000); }
document.getElementById('age-confirm').addEventListener('click', () => { localStorage.setItem('adult-confirmed', '1'); document.getElementById('age-gate').hidden = true; startHome(); });
document.getElementById('age-exit').addEventListener('click', () => api('quit'));
if (localStorage.getItem('adult-confirmed') === '1') startHome();
else document.getElementById('age-gate').hidden = false;
