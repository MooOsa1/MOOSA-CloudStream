import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { join, resolve, extname, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL("./public/", import.meta.url)));
const KEY = process.env.TMDB_API_KEY || "";
const TOKEN = process.env.TMDB_READ_ACCESS_TOKEN || "";
const tmdbEnabled = Boolean(KEY || TOKEN);
const cache = new Map();
const DEMO = {
  id: "demo-bunny", type: "movie", title: "Big Buck Bunny",
  originalTitle: "Big Buck Bunny", overview: "فيلم رسوم متحركة مفتوح من Blender Foundation. نسخة تجريبية مجانية لاختبار المشغل.",
  year: "2008", rating: null, backdrop: "", poster: "", genreIds: [16, 35],
  demo: true
};
const DEMO_SOURCE = {
  id: "bunny-mp4", name: "Big Buck Bunny • Demo MP4", quality: "720p sample",
  type: "mp4", url: "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
  notice: "Officially distributed sample film. For player testing only."
};
const json = (res, status, value, cacheAge = 0) => {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": cacheAge ? "public, max-age=" + cacheAge : "no-store" });
  res.end(JSON.stringify(value));
};
const clampPage = value => Math.min(100, Math.max(1, Number.parseInt(value, 10) || 1));
const country = value => /^[A-Z]{2}$/.test(value || "") ? value : "";
const typeSafe = value => value === "tv" ? "tv" : "movie";
const safeId = value => /^\d{1,12}$/.test(String(value)) ? String(value) : null;
const normalized = (item, type) => ({
  id: item.id, type: type || item.media_type || (item.first_air_date ? "tv" : "movie"),
  title: item.title || item.name || item.original_title || item.original_name || "",
  originalTitle: item.original_title || item.original_name || "",
  overview: item.overview || "", year: (item.release_date || item.first_air_date || "").slice(0, 4),
  rating: typeof item.vote_average === "number" ? item.vote_average : null,
  poster: item.poster_path ? "https://image.tmdb.org/t/p/w500" + item.poster_path : "",
  backdrop: item.backdrop_path ? "https://image.tmdb.org/t/p/w1280" + item.backdrop_path : "",
  genreIds: item.genre_ids || item.genres?.map(g => g.id) || [],
  votes: item.vote_count || 0, demo: false
});
const api = async (path, params = {}) => {
  if (!tmdbEnabled) throw Object.assign(new Error("TMDB_NOT_CONFIGURED"), { code: 503 });
  const url = new URL("https://api.themoviedb.org/3/" + path);
  for (const [k,v] of Object.entries({ language: "ar-SA", include_adult: "false", ...params })) {
    if (v !== "" && v != null) url.searchParams.set(k, String(v));
  }
  if (KEY) url.searchParams.set("api_key", KEY);
  const lookup = url.pathname + url.search;
  const stored = cache.get(lookup);
  if (stored?.expires > Date.now()) return stored.body;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 8000);
  try {
    const response = await fetch(url, { signal: ctrl.signal,
      headers: TOKEN ? { Authorization: "Bearer " + TOKEN, Accept: "application/json" } : { Accept: "application/json" } });
    if (!response.ok) throw Object.assign(new Error("Metadata provider unavailable"), { code: 502 });
    const data = await response.json();
    cache.set(lookup, { expires: Date.now() + 5 * 60_000, body: data });
    if (cache.size > 300) cache.delete(cache.keys().next().value);
    return data;
  } finally { clearTimeout(timer); }
};
const sections = async () => {
  if (!tmdbEnabled) return { demo: true, sections: [{ title: "تجربة المشغل", subtitle: "محتوى تجريبي مفتوح", items: [DEMO] }] };
  const requests = [
    ["أفلام رائجة", "trending/movie/week", "movie", {}],
    ["مسلسلات رائجة", "trending/tv/week", "tv", {}],
    ["أفضل الأفلام الهندية", "discover/movie", "movie", { with_origin_country: "IN", sort_by: "popularity.desc" }],
    ["مسلسلات هندية", "discover/tv", "tv", { with_origin_country: "IN", sort_by: "popularity.desc" }]
  ];
  const output = await Promise.all(requests.map(async ([title,path,type,params]) => {
    try {
      const data = await api(path, params);
      return { title, subtitle: "نتائج حقيقية من TMDB", items: (data.results || []).filter(i => i.poster_path || i.backdrop_path).slice(0, 16).map(i => normalized(i, type)) };
    } catch { return { title, subtitle: "تعذر تحميل هذا القسم", items: [] }; }
  }));
  return { demo: false, sections: output };
};
const sorts = { popular: "popularity.desc", rated: "vote_average.desc", votes: "vote_count.desc", newest: null };
export const metadataRoute = async (pathname, query) => {
  if (pathname === "/api/status") return { configured: tmdbEnabled, player: ["mp4", "hls", "webvtt", "srt"] };
  if (pathname === "/api/home") return await sections();
  if (pathname === "/api/sources") {
    const key = query.get("key") || "";
    return { sources: key === "movie:demo-bunny" ? [DEMO_SOURCE] : [],
      notice: "Metadata does not include streaming rights or playback links. Add authorized sources only." };
  }
  if (pathname === "/api/genres") {
    const type = typeSafe(query.get("type"));
    if (!tmdbEnabled) return { genres: [{ id: 16, name: "رسوم متحركة" }, { id: 35, name: "كوميديا" }] };
    const data = await api("genre/" + type + "/list");
    return { genres: data.genres || [] };
  }
  if (pathname === "/api/search") {
    const q = (query.get("q") || "").trim().slice(0, 100);
    if (!q) return { results: [], totalPages: 0 };
    if (!tmdbEnabled) return { demo: true, results: DEMO.title.toLowerCase().includes(q.toLowerCase()) ? [DEMO] : [], totalPages: 1 };
    const d = await api("search/multi", { query: q, page: clampPage(query.get("page")) });
    return { results: (d.results || []).filter(x => (x.media_type === "movie" || x.media_type === "tv") && !x.adult).map(x => normalized(x)), totalPages: d.total_pages || 1 };
  }
  if (pathname === "/api/discover") {
    if (!tmdbEnabled) return { demo: true, results: [DEMO], totalPages: 1 };
    const type = typeSafe(query.get("type"));
    const sort = Object.hasOwn(sorts, query.get("sort")) ? query.get("sort") : "popular";
    const date = new Date().toISOString().slice(0, 10);
    const params = { sort_by: sorts[sort] || (type === "tv" ? "first_air_date.desc" : "primary_release_date.desc"), page: clampPage(query.get("page")), with_origin_country: country(query.get("country")) };
    const genre = Number(query.get("genre"));
    if (Number.isSafeInteger(genre) && genre > 0 && genre < 1e6) params.with_genres = genre;
    if (sort === "rated") params["vote_count.gte"] = type === "tv" ? 50 : 100;
    if (sort === "newest") params[type === "tv" ? "first_air_date.lte" : "primary_release_date.lte"] = date;
    const d = await api("discover/" + type, params);
    return { results: (d.results || []).map(x => normalized(x, type)), totalPages: d.total_pages || 1 };
  }
  if (pathname === "/api/details") {
    const type = typeSafe(query.get("type")), id = query.get("id");
    if (id === "demo-bunny") return { ...DEMO, runtime: 10,
      genres: [{ id: 16, name: "رسوم متحركة" }], cast: [], recommendations: [], sources: [DEMO_SOURCE] };
    if (!safeId(id)) throw Object.assign(new Error("Invalid title ID"), { code: 400 });
    const [data, credits, recommendations] = await Promise.all([
      api(type + "/" + id), api(type + "/" + id + "/credits").catch(() => ({})),
      api(type + "/" + id + "/recommendations").catch(() => ({}))
    ]);
    return { ...normalized(data, type), runtime: data.runtime || data.episode_run_time?.[0] || null,
      genres: data.genres || [], seasons: data.seasons || [],
      cast: (credits.cast || []).slice(0, 8).map(x => x.name),
      recommendations: (recommendations.results || []).slice(0, 8).map(x => normalized(x, type)) };
  }
  return null;
};
const mime = { ".html": "text/html", ".css": "text/css", ".js": "text/javascript",
  ".svg": "image/svg+xml", ".webmanifest": "application/manifest+json", ".ico": "image/x-icon" };
export const handler = async (req, res) => {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; style-src 'self'; img-src 'self' https://image.tmdb.org data:; connect-src 'self' https:; media-src 'self' https: blob:; font-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
  const url = new URL(req.url || "/", "http://localhost");
  if (req.method !== "GET" && req.method !== "HEAD") return json(res, 405, { error: "Method not allowed" });
  if (url.pathname.startsWith("/api/")) {
    try {
      const payload = await metadataRoute(url.pathname, url.searchParams);
      if (!payload) return json(res, 404, { error: "Unknown endpoint" });
      return json(res, 200, payload);
    } catch (err) {
      return json(res, err.code || 503, { error: err.code === 400 ? "Invalid request" : err.message === "TMDB_NOT_CONFIGURED" ? "Configure TMDB_API_KEY on the server" : "Metadata service unavailable" });
    }
  }
  let path = url.pathname === "/" ? "/index.html" : url.pathname;
  if (!/^\/[\w/.-]+$/.test(path) || path.includes("..")) return json(res, 404, { error: "Not found" });
  const file = resolve(root, "." + path);
  if (file !== root && !file.startsWith(root + sep)) return json(res, 404, { error: "Not found" });
  try {
    const info = await stat(file);
    if (!info.isFile()) return json(res, 404, { error: "Not found" });
    const bytes = await readFile(file);
    res.writeHead(200, { "Content-Type": (mime[extname(file)] || "application/octet-stream") + "; charset=utf-8",
      "Cache-Control": path === "/index.html" ? "no-cache" : "public, max-age=3600" });
    res.end(req.method === "HEAD" ? undefined : bytes);
  } catch { json(res, 404, { error: "Not found" }); }
};
export const startServer = (port = Number(process.env.PORT || 3000)) =>
  createServer(handler).listen(port, "0.0.0.0");
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = startServer();
  server.on("listening", () => console.log("MOOSA Web running on http://localhost:" + server.address().port));
}
