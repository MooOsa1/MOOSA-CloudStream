"use strict";
const $ = selector => document.querySelector(selector);
const state = {
  view: "home", configured: false, home: [], results: [], totalPages: 1, page: 1,
  filters: { type: "movie", genre: "", sort: "popular", country: "" },
  search: "", current: null, currentDetails: null, sources: [], player: null,
  hls: null, trackBlob: null, rawSubtitles: "", subtitleOffset: 0,
  lastFocused: null, lastViewScroll: 0, episode: null, detailGeneration: 0, browseGeneration: 0
};
const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g,
  c => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;" }[c]));
const keyOf = item => item.type + ":" + item.id;
const nameOf = item => item?.title || "عنوان غير متوفر";
const store = {
  get(k, fallback) { try { return JSON.parse(localStorage.getItem("moosa:" + k)) ?? fallback; } catch { return fallback; } },
  set(k,v) { try { localStorage.setItem("moosa:" + k, JSON.stringify(v)); } catch {} }
};
const saved = () => store.get("saved", []);
const toast = message => {
  const node = $("#toast"); node.textContent = message; node.classList.add("show");
  clearTimeout(toast.timer); toast.timer = setTimeout(() => node.classList.remove("show"), 2900);
};
async function api(path) {
  const response = await fetch(path, { headers: { Accept: "application/json" } });
  let data;
  try { data = await response.json(); } catch { throw new Error("تعذر قراءة الرد من الخادم"); }
  if (!response.ok) throw new Error(data.error || "الخدمة غير متاحة حاليًا");
  return data;
}
const posterMarkup = (item, backdrop = false) => {
  const url = backdrop ? item.backdrop : item.poster;
  return url ? '<img src="' + escapeHtml(url) + '" loading="lazy" decoding="async" alt="' + escapeHtml(nameOf(item)) + '">'
    : '<span class="fallback-art">' + escapeHtml(nameOf(item)) + '</span>';
};
const card = item => {
  const rating = item.rating && item.rating > 0 ? '<span class="score">★ ' + item.rating.toFixed(1) + '</span>' : "";
  return '<button class="movie-card" data-detail="' + escapeHtml(keyOf(item)) + '" aria-label="تفاصيل ' + escapeHtml(nameOf(item)) + '">' +
    '<span class="poster ' + (item.poster ? "" : "fallback") + '">' + rating + posterMarkup(item) + '</span>' +
    '<span class="movie-name" dir="auto">' + escapeHtml(nameOf(item)) + '</span>' +
    '<span class="movie-year">' + escapeHtml(item.year || (item.type === "tv" ? "مسلسل" : "فيلم")) + '</span></button>';
};
function setNav(name) {
  document.querySelectorAll("[data-nav]").forEach(button => button.classList.toggle("is-current", button.dataset.nav === name));
  $("#sidebar-state").textContent = state.configured ? "الكتالوج متصل" : "وضع التجربة";
  $(".sidebar").classList.remove("show");
}
function setPage(content, title) {
  $("#content").innerHTML = content;
  $("#page-label").textContent = title;
  window.scrollTo({ top: 0, behavior: "instant" });
}
function hero(item) {
  if (!item) return "";
  const backdrop = item.backdrop
    ? 'background-image:url("' + escapeHtml(item.backdrop) + '")'
    : "background-image:radial-gradient(ellipse at 65% 15%,#53647d 0%,#2e3543 31%,#1a1e27 70%)";
  return '<section class="hero"><div class="hero-art" style="' + backdrop + '"></div><div class="hero-body">' +
    '<div class="eyebrow">✦ MOOSA ORIGINAL EXPERIENCE</div><h1 dir="auto">' + escapeHtml(nameOf(item)) + '</h1>' +
    '<div class="hero-meta"><span>' + escapeHtml(item.year || "اكتشف") + '</span><span class="meta-dot"></span><span>' + (item.type === "tv" ? "مسلسل" : "فيلم") +
    '</span>' + (item.rating ? '<span class="meta-dot"></span><span>★ ' + item.rating.toFixed(1) + '</span>' : "") + '</div>' +
    '<p>' + escapeHtml(item.overview || "شاهد التفاصيل واكتشف المزيد عن هذا العنوان.") + '</p>' +
    '<div class="hero-actions"><button class="button primary" data-detail="' + escapeHtml(keyOf(item)) + '">▶ عرض التفاصيل</button>' +
    '<button class="button subtle" data-toggle-saved="' + escapeHtml(keyOf(item)) + '">♡ أضف إلى قائمتي</button></div></div></section>';
}
function row(section, idx) {
  if (!section.items?.length) return "";
  const target = section.items?.find(x => x.type === "tv" || x.type === "movie")?.type || (idx % 2 ? "tv" : "movie");
  return '<section class="section"><div class="section-head"><div><h2>' + escapeHtml(section.title) + '</h2>' +
    '<p>' + escapeHtml(section.subtitle || "") + '</p></div><button class="section-link" data-nav="' + target + '">عرض الكل ←</button></div>' +
    '<div class="rail">' + section.items.map(card).join("") + '</div></section>';
}
async function renderHome() {
  state.view = "home"; state.browseGeneration += 1; setNav("home");
  if (!state.home.length) {
    setPage('<div class="loading-page"><div class="skeleton hero-skel"></div><div class="skeleton-line"></div><div class="skeleton-cards"></div></div>', "الرئيسية");
    const data = await api("/api/home");
    state.home = data.sections || [];
  }
  const all = state.home.flatMap(x => x.items || []);
  setPage(hero(all[0]) + state.home.map(row).join("") + (!state.configured
    ? '<div class="empty-state"><h2>تجربة جاهزة للمشغل</h2><p>كتالوج TMDB الكامل يظهر عند إضافة مفتاح API إلى إعدادات الخادم. الفيلم التجريبي متاح لاختبار المشغل الآن.</p></div>' : ""), "الرئيسية");
}
const activeTags = () => {
  const f = state.filters;
  const genre = $("#filter-genre")?.selectedOptions[0]?.textContent || "";
  return [f.type === "tv" ? "مسلسلات" : "أفلام",
    f.genre && genre ? genre : "",
    f.country ? ({ IN: "الهند", US: "أمريكا", GB: "بريطانيا", KR: "كوريا", JP: "اليابان", TR: "تركيا", FR: "فرنسا", ES: "إسبانيا" }[f.country] || "") : "",
    ({ popular: "الأكثر شعبية", rated: "الأعلى تقييمًا", votes: "الأكثر تصويتًا", newest: "الأحدث" })[f.sort]]
    .filter(Boolean);
};
async function discover(reset = true) {
  if (reset) { state.results = []; state.page = 1; }
  state.view = "discover"; setNav(state.filters.type);
  if (reset) setPage('<div class="skeleton hero-skel" style="height:210px"></div>', "استكشاف");
  const params = new URLSearchParams({ ...state.filters, page: state.page });
  const generation = ++state.browseGeneration;
  const data = await api("/api/discover?" + params);
  if (generation !== state.browseGeneration || state.view !== "discover") return;
  state.totalPages = data.totalPages || 1;
  state.results.push(...(data.results || []));
  // Pagination must not recreate the whole view, scroll to top or move focus.
  if (!reset) {
    const grid = $("#content .grid");
    if (grid) {
      grid.insertAdjacentHTML("beforeend", (data.results || []).map(card).join(""));
      const load = $("#content [data-load-more]");
      if (load && state.page >= state.totalPages) load.remove();
      else if (load) load.disabled = false;
      return;
    }
  }
  const tags = activeTags();
  setPage('<div class="view-title"><div><span class="eyebrow">DISCOVER</span><h1>' + escapeHtml(tags.join(" • ")) + '</h1><p>النتائج تتغير من المصدر مباشرة عند اختيار الفلاتر</p></div>' +
    '<button class="button small" data-open-filters>☷ الفلاتر</button></div><div class="chip-list">' +
    tags.map(s => '<span class="chip">' + escapeHtml(s) + '</span>').join("") +
    '</div><div class="toolbar"><span class="eyebrow">' + (state.configured ? "TMDB LIVE RESULTS" : "DEMO CONTENT") + '</span></div>' +
    (state.results.length ? '<div class="grid">' + state.results.map(card).join("") + '</div>' : empty("ما لقينا نتائج بهذي الاختيارات")) +
    (state.page < state.totalPages ? '<button class="button load-more" data-load-more>المزيد من النتائج ↓</button>' : ""), "التصفح والتصنيفات");
}
function empty(message) { return '<div class="empty-state"><div class="empty-icon">⌕</div><h2>' + escapeHtml(message) + '</h2><p>تقدر تغيّر البحث أو ترجع للتصنيفات.</p></div>'; }
async function search(q) {
  q = q.trim();
  if (!q) return renderHome();
  state.view = "search"; state.search = q; state.page = 1; state.browseGeneration += 1; setNav("");
  setPage('<div class="skeleton hero-skel" style="height:210px"></div>', "البحث");
  const data = await api("/api/search?" + new URLSearchParams({ q }));
  state.results = data.results;
  setPage('<div class="view-title"><div><span class="eyebrow">SEARCH</span><h1>نتائج البحث: ' + escapeHtml(q) + '</h1><p>' +
    state.results.length + ' نتيجة</p></div></div>' +
    (state.results.length ? '<div class="grid">' + state.results.map(card).join("") + '</div>' : empty("ما لقينا عنوانًا مطابقًا")), "البحث");
}
function showSaved() {
  state.view = "saved"; state.browseGeneration += 1; setNav("saved");
  const list = saved();
  setPage('<div class="view-title"><div><span class="eyebrow">WATCHLIST</span><h1>قائمتي ♡</h1><p>محفوظة على هذا المتصفح فقط</p></div></div>' +
    (list.length ? '<div class="grid">' + list.map(card).join("") + '</div>' : empty("قائمتك فاضية حاليًا")), "قائمتي");
}
function lookup(key) {
  const items = [...state.home.flatMap(x => x.items || []), ...state.results, ...saved()];
  return items.find(item => keyOf(item) === key) || null;
}
async function openDetails(key) {
  const [type, id] = key.split(":");
  if ((type !== "tv" && type !== "movie") || !id || !/^[\w-]+$/.test(id)) return;
  state.lastFocused = document.activeElement;
  const generation = ++state.detailGeneration;
  state.episode = null;
  $("#detail-backdrop").classList.remove("hidden");
  $("#detail-dialog").classList.remove("hidden");
  $("#detail-body").innerHTML = '<div class="skeleton hero-skel"></div>';
  $("#detail-dialog").scrollTop = 0;
  document.querySelector("[data-close-details]").focus();
  try {
    const d = await api("/api/details?" + new URLSearchParams({ type, id }));
    if (generation !== state.detailGeneration ||
        $("#detail-dialog").classList.contains("hidden")) return;
    state.current = d; state.currentDetails = d;
    const genres = (d.genres || []).map(x => '<span>' + escapeHtml(x.name) + '</span>').join("");
    const imageStyle = d.backdrop ? 'background-image:url("' + escapeHtml(d.backdrop) + '")' :
      "background-image:radial-gradient(circle at 40% 40%,#505e76,#1c202a)";
    const isSaved = saved().some(x => keyOf(x) === key);
    $("#detail-body").innerHTML = '<div class="detail-banner" style="' + imageStyle + '"></div><div class="detail-content">' +
      '<div class="eyebrow">' + (type === "tv" ? "TV SERIES" : "MOVIE") + '</div><h2 dir="auto">' + escapeHtml(nameOf(d)) + '</h2>' +
      '<div class="detail-badges"><span>' + escapeHtml(d.year || "—") + '</span>' +
      (d.rating ? '<span>★ ' + d.rating.toFixed(1) + '</span>' : "") + (d.runtime ? '<span>' + d.runtime + ' دقيقة</span>' : "") + genres + '</div>' +
      '<p>' + escapeHtml(d.overview || "لا يتوفر وصف عربي لهذا العنوان حاليًا.") + '</p>' +
      '<div class="detail-actions"><button class="button primary" data-play>' + (type === "tv" ? "▤ اختر حلقة" : "▶ تشغيل") + '</button>' +
      '<button class="button" data-toggle-saved="' + escapeHtml(key) + '">' + (isSaved ? "♥ إزالة من قائمتي" : "♡ حفظ في قائمتي") + '</button></div>' +
      (type === "tv" && d.seasons?.length ?
        '<section class="episode-section"><h3>الحلقات والمواسم</h3><label for="season-select">اختر الموسم</label><select id="season-select">' +
        d.seasons.filter(x=>Number.isInteger(x.season_number)).map(x => '<option value="' + Number(x.season_number) + '">' +
        escapeHtml(x.name || ("الموسم " + x.season_number)) + '</option>').join("") +
        '</select><div id="episode-list" class="episode-list"><p>جاري تحميل الحلقات...</p></div></section>' : "") +
      (d.cast?.length ? '<div class="detail-credit">بطولة: ' + escapeHtml(d.cast.join(" • ")) + '</div>' : "") +
      '<div class="detail-credit">توفر العنوان في TMDB لا يعني توفر رابط مشاهدة. المشغل يدعم المصادر التي تملك إذن استخدامها.</div></div>';
    if (type === "tv" && d.seasons?.length) {
      const season = d.seasons.find(x => x.season_number > 0) || d.seasons[0];
      $("#season-select").value = String(season.season_number);
      loadEpisodes(id, season.season_number, generation);
    }
  } catch (error) { $("#detail-body").innerHTML = empty(error.message); }
}
async function loadEpisodes(id, season, generation = state.detailGeneration) {
  const list = $("#episode-list");
  if (!list) return;
  list.innerHTML = '<p>جاري تحميل الحلقات...</p>';
  try {
    const data = await api("/api/episodes?" + new URLSearchParams({ id, season }));
    if (generation !== state.detailGeneration || !$("#episode-list") ||
        $("#season-select")?.value !== String(season)) return;
    $("#episode-list").innerHTML = (data.episodes || []).length ?
      data.episodes.map(episode =>
        '<button class="episode-card" data-episode="' + Number(episode.number) + '" data-season="' + Number(season) + '">' +
        '<span class="episode-still">' + (episode.still ?
          '<img src="' + escapeHtml(episode.still) + '" alt="" loading="lazy">' : '▶') + '</span>' +
        '<span class="episode-info"><strong>' + Number(episode.number) + '. ' + escapeHtml(episode.name) +
        '</strong><small>' + escapeHtml(episode.airDate || "") +
        '</small><span>' + escapeHtml((episode.overview || "").slice(0, 180)) + '</span></span><span class="episode-arrow">▶</span></button>'
      ).join("") : '<p>ما لقينا حلقات لهذا الموسم.</p>';
  } catch { if (generation === state.detailGeneration && $("#episode-list"))
    $("#episode-list").innerHTML = '<p>تعذر تحميل الحلقات.</p>'; }
}
function closeDetails() {
  state.detailGeneration += 1;
  $("#detail-dialog").classList.add("hidden"); $("#detail-backdrop").classList.add("hidden");
  state.lastFocused?.focus?.(); state.lastFocused = null;
}
function toggleSaved(key) {
  const item = lookup(key) || (state.current && keyOf(state.current) === key ? state.current : null);
  if (!item) return;
  let items = saved(); const found = items.some(x => keyOf(x) === key);
  items = found ? items.filter(x => keyOf(x) !== key) : [item, ...items].slice(0, 80);
  store.set("saved", items); toast(found ? "انحذف من قائمتك" : "انضاف إلى قائمتك");
  if (state.view === "saved") showSaved();
}
async function loadGenres(type) {
  const select = $("#filter-genre"), old = select.value;
  select.innerHTML = '<option value="">كل التصنيفات</option>';
  try {
    const data = await api("/api/genres?type=" + type);
    for (const g of data.genres || []) {
      const option = document.createElement("option"); option.value = String(g.id);
      option.textContent = g.name; select.append(option);
    }
    if ([...select.options].some(o => o.value === old)) select.value = old;
  } catch { toast("تعذر تحميل التصنيفات"); }
}
async function openFilters() {
  $("#drawer-backdrop").classList.remove("hidden"); $("#filter-panel").hidden = false;
  $("#filter-type").value = state.filters.type;
  $("#filter-sort").value = state.filters.sort;
  $("#filter-country").value = state.filters.country;
  await loadGenres(state.filters.type);
  $("#filter-genre").value = state.filters.genre;
  $("#filter-type").focus();
}
function closeFilters() { $("#filter-panel").hidden = true; $("#drawer-backdrop").classList.add("hidden"); }
function stopVideo() {
  const video = $("#video");
  video.pause();
  if (state.hls) { state.hls.destroy(); state.hls = null; }
  video.removeAttribute("src"); video.load();
}
function message(text) { $("#player-message").textContent = text; }
function playUrl(url, type = "") {
  const video = $("#video"); stopVideo();
  $("#player-placeholder").classList.add("hidden");
  const valid = (() => { try { const u = new URL(url); return u.protocol === "https:" || (u.protocol === "http:" && ["localhost","127.0.0.1"].includes(u.hostname)); } catch { return false; } })();
  if (!valid) { $("#player-placeholder").classList.remove("hidden"); message("استخدم رابط HTTPS صالحًا، أو HTTP محليًا للتطوير."); return; }
  const hls = type === "hls" || /\.m3u8(?:[?#]|$)/i.test(url);
  state.player.sourceUrl = url;
  if (hls && video.canPlayType("application/vnd.apple.mpegurl")) video.src = url;
  else if (hls && window.Hls?.isSupported()) {
    state.hls = new window.Hls({ maxBufferLength: 25, enableWorker: true });
    state.hls.loadSource(url); state.hls.attachMedia(video);
    state.hls.on(window.Hls.Events.MANIFEST_PARSED, () => {
      video.play().catch(() => message("اضغط تشغيل داخل الفيديو للبدء."));
    });
    state.hls.on(window.Hls.Events.ERROR, (_, data) => {
      if (data.fatal) message("تعذر تشغيل HLS. تحقق من صلاحية الرابط ودعم CORS.");
    });
  } else if (hls) { message("هذا المتصفح لا يدعم HLS حاليًا. جرّب MP4 أو متصفحًا يدعم HLS."); return; }
  else video.src = url;
  message("إذا تعذر التشغيل، تأكد من صلاحية الرابط ودعم CORS وصيغة الفيديو.");
  if (!hls || video.canPlayType("application/vnd.apple.mpegurl"))
    video.play().catch(() => { message("اضغط تشغيل داخل الفيديو للبدء."); });
}
async function openPlayer() {
  if (!state.currentDetails) return;
  closeDetails();
  const baseKey = keyOf(state.currentDetails);
  const key = state.episode ?
    baseKey + ":" + state.episode.season + ":" + state.episode.number : baseKey;
  state.player = { key, sourceUrl: "" };
  $("#player-modal").classList.remove("hidden");
  $("#player-title").textContent = nameOf(state.currentDetails) +
    (state.episode ? " • S" + state.episode.season + "E" + state.episode.number : "");
  $("#player-placeholder").classList.remove("hidden");
  $("#source-select").innerHTML = '<option value="">اختر سيرفرًا</option>';
  $("#custom-url").value = "";
  clearSubtitles();
  message("اختر مصدرًا متاحًا أو أضف رابط فيديو مُصرّحًا لك بتشغيله.");
  try {
    const data = await api("/api/sources?key=" + encodeURIComponent(key));
    if (state.player?.key !== key) return;
    state.sources = data.sources || [];
    for (const source of state.sources) {
      const option = document.createElement("option"); option.value = source.id; option.textContent = source.name + " • " + (source.quality || "Auto");
      $("#source-select").append(option);
    }
    if (state.sources.length) {
      $("#source-select").value = state.sources[0].id;
      playUrl(state.sources[0].url, state.sources[0].type);
    }
  } catch { message("تعذر تحميل قائمة المصادر. يمكنك استخدام رابط فيديو خاص بك."); }
  $("#player-close").focus();
}
function closePlayer() {
  if (state.player) saveProgress();
  stopVideo(); clearSubtitles();
  $("#player-modal").classList.add("hidden");
  state.player = null;
}
function saveProgress() {
  const video = $("#video");
  if (!state.player || !Number.isFinite(video.currentTime) || video.currentTime < 5) return;
  const progress = store.get("progress", {});
  progress[state.player.key] = { time: video.currentTime, at: Date.now(), source: state.player.sourceUrl };
  const keys = Object.keys(progress).sort((a,b) => progress[b].at - progress[a].at);
  for (const key of keys.slice(30)) delete progress[key];
  store.set("progress", progress);
}
function secondsToVtt(seconds) {
  const ms = Math.max(0, Math.round(seconds * 1000));
  const hh = String(Math.floor(ms / 3600000)).padStart(2, "0"),
    mm = String(Math.floor(ms / 60000) % 60).padStart(2, "0"),
    ss = String(Math.floor(ms / 1000) % 60).padStart(2, "0"),
    mmm = String(ms % 1000).padStart(3, "0");
  return hh + ":" + mm + ":" + ss + "." + mmm;
}
function vttToSeconds(raw) {
  const parts = raw.replace(",", ".").split(":").map(Number);
  if (parts.some(x => !Number.isFinite(x))) return NaN;
  return parts.length === 3 ?
    parts[0] * 3600 + parts[1] * 60 + parts[2] :
    parts[0] * 60 + parts[1];
}
function clearSubtitles() {
  $("#video").querySelectorAll("track").forEach(t => t.remove());
  if (state.trackBlob) URL.revokeObjectURL(state.trackBlob);
  state.trackBlob = null; state.rawSubtitles = ""; state.subtitleOffset = 0;
  $("#subtitle-file").value = ""; $("#subtitle-offset").textContent = "0.0 ثانية";
}
function applySubtitle() {
  const video = $("#video");
  video.querySelectorAll("track").forEach(t => t.remove());
  if (state.trackBlob) URL.revokeObjectURL(state.trackBlob);
  if (!state.rawSubtitles) return;
  let input = state.rawSubtitles.replace(/^\uFEFF/, "").replace(/\r/g, "")
    .replace(/^WEBVTT[^\n]*\n/i, "").trim();
  const arrow = /^((?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*((?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3})(.*)$/gm;
  let cues = 0;
  input = input.replace(arrow, (_, a,b,extra) => {
    cues += 1;
    return secondsToVtt(vttToSeconds(a) + state.subtitleOffset) + " --> " +
      secondsToVtt(vttToSeconds(b) + state.subtitleOffset) + extra;
  });
  if (!cues) { message("صيغة الترجمة غير مدعومة؛ يلزم ملف SRT أو VTT بتوقيتات صحيحة."); return; }
  state.trackBlob = URL.createObjectURL(new Blob(["WEBVTT\n\n" + input + "\n"], { type: "text/vtt" }));
  const track = document.createElement("track");
  track.kind = "subtitles"; track.label = "الترجمة المختارة";
  track.srclang = /[\u0600-\u06FF]/.test(state.rawSubtitles) ? "ar" : "en";
  track.src = state.trackBlob; track.default = true;
  track.addEventListener("load", () => { if (track.track) track.track.mode = "showing"; });
  video.append(track);
  $("#subtitle-offset").textContent = state.subtitleOffset.toFixed(1) + " ثانية";
  message("تم تحميل " + cues + " توقيت للترجمة.");
}
const route = async nav => {
  try {
    if (nav === "home") await renderHome();
    else if (nav === "saved") showSaved();
    else if (nav === "tv" || nav === "movie") {
      state.filters.type = nav;
      state.filters.genre = ""; await discover(true);
    }
  } catch (error) { setPage(empty(error.message), "تعذر التحميل"); }
};
document.addEventListener("click", async event => {
  const button = event.target.closest("button,[data-nav]");
  if (!button) return;
  if (button.matches("[data-close-filters]")) return closeFilters();
  if (button.matches("[data-close-details]")) return closeDetails();
  if (button.dataset.nav) return route(button.dataset.nav);
  if (button.dataset.detail) return openDetails(button.dataset.detail);
  if (button.dataset.toggleSaved) return toggleSaved(button.dataset.toggleSaved);
  if (button.hasAttribute("data-open-filters")) return openFilters();
  if (button.hasAttribute("data-play")) {
    if (state.currentDetails?.type === "tv" && $("#episode-list")) {
      $("#episode-list").scrollIntoView({ block:"nearest", behavior:"smooth" });
      $("#episode-list").querySelector("button")?.focus(); return;
    }
    return openPlayer();
  }
  if (button.hasAttribute("data-episode")) {
    state.episode = { season: Number(button.dataset.season), number: Number(button.dataset.episode) };
    return openPlayer();
  }
  if (button.hasAttribute("data-load-more")) {
    state.page += 1; button.disabled = true;
    try { await discover(false); } catch { state.page -= 1; toast("فشل تحميل الصفحة التالية"); button.disabled = false; }
  }
});
$("#detail-dialog").addEventListener("change", event => {
  if (event.target.id === "season-select") {
    loadEpisodes(state.currentDetails?.id, Number(event.target.value));
  }
});
$("#watchlist-top").addEventListener("click", () => route("saved"));
$("#mobile-menu").addEventListener("click", () => $(".sidebar").classList.toggle("show"));
$("#search-form").addEventListener("submit", event => {
  event.preventDefault(); search($("#search-input").value).catch(e => toast(e.message));
});
$("#drawer-backdrop").addEventListener("click", closeFilters);
$("#detail-backdrop").addEventListener("click", closeDetails);
$("#filter-type").addEventListener("change", async event => {
  $("#filter-genre").value = ""; await loadGenres(event.target.value);
});
$("#filter-reset").addEventListener("click", () => {
  $("#filter-genre").value = ""; $("#filter-type").value = "movie";
  $("#filter-sort").value = "popular"; $("#filter-country").value = ""; loadGenres("movie");
});
$("#filter-apply").addEventListener("click", async () => {
  state.filters = {
    type: $("#filter-type").value, genre: $("#filter-genre").value,
    sort: $("#filter-sort").value, country: $("#filter-country").value
  };
  closeFilters();
  try { await discover(); } catch(e) { setPage(empty(e.message), "تعذر تحميل النتائج"); }
});
$("#source-select").addEventListener("change", event => {
  const source = state.sources.find(x => x.id === event.target.value);
  if (source) playUrl(source.url, source.type);
});
$("#play-custom").addEventListener("click", () => {
  const url = $("#custom-url").value.trim();
  if (!url) return message("أدخل رابط فيديو صالحًا.");
  playUrl(url);
});
$("#subtitle-file").addEventListener("change", async event => {
  const file = event.target.files?.[0];
  if (!file) return;
  if (file.size > 3_000_000) return message("ملف الترجمة كبير جدًا (الحد 3MB).");
  state.subtitleOffset = 0;
  state.rawSubtitles = await file.text();
  applySubtitle();
});
$("#subtitle-minus").addEventListener("click", () => { state.subtitleOffset -= 0.5; applySubtitle(); });
$("#subtitle-plus").addEventListener("click", () => { state.subtitleOffset += 0.5; applySubtitle(); });
$("#subtitle-clear").addEventListener("click", clearSubtitles);
$("#player-close").addEventListener("click", closePlayer);
$("#video").addEventListener("timeupdate", () => {
  if (!state.player) return;
  if (!state.player.lastSave || Date.now() - state.player.lastSave > 7000) {
    saveProgress(); state.player.lastSave = Date.now();
  }
});
$("#video").addEventListener("loadedmetadata", () => {
  const progress = store.get("progress", {})[state.player?.key];
  if (progress && progress.source === state.player?.sourceUrl && progress.time > 5) {
    try { $("#video").currentTime = progress.time; } catch {}
  }
});
$("#video").addEventListener("error", () => {
  if (state.player) message("تعذر تشغيل الملف. تحقق من الرابط، الترميز، CORS أو صلاحية المصدر.");
});
document.addEventListener("keydown", event => {
  if (event.key === "Escape" || event.key === "Backspace" && !/INPUT|TEXTAREA/.test(document.activeElement?.tagName || "")) {
    if (!$("#player-modal").classList.contains("hidden")) closePlayer();
    else if (!$("#detail-dialog").classList.contains("hidden")) closeDetails();
    else if (!$("#filter-panel").hidden) closeFilters();
    else return;
    event.preventDefault(); return;
  }
  if (event.key === "/" && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName || "")) {
    event.preventDefault(); $("#search-input").focus(); return;
  }
  // Keyboard/remote directional navigation, without rerendering UI on focus.
  if (!["ArrowUp","ArrowDown","ArrowLeft","ArrowRight"].includes(event.key) ||
    /INPUT|SELECT|TEXTAREA|VIDEO/.test(document.activeElement?.tagName || "")) return;
  const modal = !$("#player-modal").classList.contains("hidden") ? $("#player-modal") :
    !$("#detail-dialog").classList.contains("hidden") ? $("#detail-dialog") :
    !$("#filter-panel").hidden ? $("#filter-panel") : document;
  const focusables = [...modal.querySelectorAll("button:not([disabled]),input,select,a[href]")].filter(el => {
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== "hidden";
  });
  const current = document.activeElement;
  if (!focusables.includes(current)) return;
  const a = current.getBoundingClientRect();
  const ax = a.x + a.width / 2, ay = a.y + a.height / 2;
  const options = focusables.filter(x => x !== current).map(el => {
    const b = el.getBoundingClientRect(), dx = b.x+b.width/2-ax, dy = b.y+b.height/2-ay;
    const primary = event.key === "ArrowUp" ? -dy : event.key === "ArrowDown" ? dy :
      event.key === "ArrowLeft" ? -dx : dx;
    const cross = event.key.startsWith("ArrowU") || event.key.startsWith("ArrowD") ? Math.abs(dx) : Math.abs(dy);
    return { el, score: primary + cross*2, primary, cross };
  }).filter(x => x.primary > 8).sort((a,b) => a.score-b.score);
  if (options.length) {
    options[0].el.focus({ preventScroll:true });
    options[0].el.scrollIntoView({ block:"nearest",inline:"nearest",behavior:"instant" });
    event.preventDefault();
  } else if (modal !== document) { event.preventDefault(); }
});
(async function bootstrap() {
  try {
    const status = await api("/api/status");
    state.configured = status.configured;
    await renderHome();
  } catch(error) {
    setPage(empty("تعذر تشغيل الموقع: " + error.message), "خطأ");
  }
})();
