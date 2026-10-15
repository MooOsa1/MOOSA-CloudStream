import { randomBytes, createHmac, timingSafeEqual } from 'node:crypto';

// HTTPS-only relay of metadata between the user's browser and their own
// CloudStream device. Neither this backend nor its browser client executes
// arbitrary extension code or proxies video bytes.
export function createBridge({ secret = process.env.BRIDGE_SECRET || randomBytes(32).toString('hex'), now = () => Date.now() } = {}) {
  const sessions = new Map();
  const codes = new Map();
  const attempts = new Map();
  const MAX_AGE = 14 * 24 * 60 * 60_000;
  const PAIR_WINDOW = 10 * 60_000;
  const REQUEST_WINDOW = 50_000;
  const MAX_ACTIVE_SESSIONS = 100;

  function ticket(role, id) {
    return id + '.' + createHmac('sha256', secret).update('moosa-bridge-v1:' + role + ':' + id).digest('base64url');
  }
  function identify(role, header) {
    const token = String(header || '').replace(/^Bearer\s+/i, '');
    const [id, supplied, extra] = token.split('.');
    if (!id || !supplied || extra || !/^[a-z0-9-]{20,90}$/.test(id)) return null;
    const stamp = parseInt(id.split('-')[0], 36);
    if (!Number.isSafeInteger(stamp) || stamp > now() + 60_000 || now() - stamp > MAX_AGE) return null;
    const expected = ticket(role, id).split('.')[1];
    const a = Buffer.from(supplied), b = Buffer.from(expected);
    if (a.length !== b.length || !timingSafeEqual(a, b)) return null;
    let session = sessions.get(id);
    // A signed ticket survives a Render restart if BRIDGE_SECRET is persistent.
    if (!session) {
      session = { id, code: null, expires: stamp + MAX_AGE, paired: role === 'agent',
        lastSeen: 0, providers: [], pending: new Map(), queued: [], waiting: null, count: 0, countAt: now() };
      sessions.set(id, session);
    }
    if (role === 'agent') session.paired = true;
    return session;
  }
  function respond(res, status, value) {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer' });
    res.end(JSON.stringify(value));
  }
  async function body(req, max = 65_536) {
    const chunks = [];
    let length = 0;
    for await (const part of req) {
      length += part.length;
      if (length > max) throw Object.assign(new Error('Request too large'), { status: 413 });
      chunks.push(part);
    }
    try { return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); }
    catch { throw Object.assign(new Error('Malformed JSON'), { status: 400 }); }
  }
  function limit(req) {
    const key = req.socket.remoteAddress || 'unknown';
    const old = attempts.get(key);
    const item = old && now() - old.at < 60_000 ? old : { at: now(), count: 0 };
    item.count++;
    attempts.set(key, item);
    if (attempts.size > 1000) for (const [ip, data] of attempts) {
      if (now() - data.at > 60_000) attempts.delete(ip);
    }
    return item.count <= 30;
  }
  function sweep() {
    for (const [code, item] of codes) if (now() > item.expires) codes.delete(code);
    for (const [id, s] of sessions) {
      if (now() > s.expires) { sessions.delete(id); continue; }
      for (const [key, t] of s.pending) if (now() - t.created > REQUEST_WINDOW * 2) s.pending.delete(key);
      s.queued = s.queued.filter(task => s.pending.has(task.id));
    }
  }
  function allowedUrl(value) {
    if (typeof value !== 'string' || value.length > 3000) return false;
    try {
      const parsed = new URL(value);
      return parsed.protocol === 'https:' && !!parsed.hostname &&
        !parsed.username && !parsed.password &&
        !['localhost', '127.0.0.1', '::1'].includes(parsed.hostname) &&
        !/^10\.|^192\.168\.|^169\.254\.|^172\.(1[6-9]|2\d|3[01])\./.test(parsed.hostname);
    } catch { return false; }
  }
  function sanitizeSources(items) {
    if (!Array.isArray(items)) return [];
    const known = new Set();
    return items.slice(0, 80).map(item => {
      const url = item?.url;
      const quality = Number(item?.quality);
      if (!allowedUrl(url) || !Number.isInteger(quality) || quality < 720 || quality > 8640) return null;
      const label = String(item?.name || item?.provider || 'CloudStream').slice(0, 95);
      if (/download|تحميل|داونلود|تنزيل/i.test(label) || known.has(url)) return null;
      known.add(url);
      const subtitles = Array.isArray(item.subtitles) ? item.subtitles.slice(0, 20).filter(t =>
        t && allowedUrl(t.url) && ['ar', 'en'].includes(t.language)
      ).map(t => ({
        url: t.url, language: t.language,
        label: String(t.label || t.language).slice(0, 95),
        source: String(t.source || 'CloudStream').slice(0, 60)
      })) : [];
      return { url, name: label, quality, subtitles };
    }).filter(Boolean).sort((a,b) => b.quality - a.quality).slice(0, 30);
  }
  function validateTask(v) {
    const media = v?.media;
    const id = Number(v?.id);
    const season = Number(v?.season);
    const episode = Number(v?.episode);
    if (!['movie','tv'].includes(media) || !Number.isSafeInteger(id) || id < 1 || id > 2_147_483_647) return null;
    if (media === 'tv' && (!Number.isInteger(season) || season < 0 || season > 100 ||
      !Number.isInteger(episode) || episode < 1 || episode > 1000)) return null;
    const title = String(v?.title || '').trim().slice(0, 180);
    if (title.length < 2) return null;
    return { media, id, title, originalTitle: String(v?.originalTitle || '').slice(0, 180),
      imdbId: /^tt\d{5,12}$/.test(v?.imdbId || '') ? v.imdbId : null,
      year: Number.isInteger(Number(v?.year)) && Number(v.year) >= 1880 && Number(v.year) <= 2200 ? Number(v.year) : null,
      language: /^[a-z]{2}$/.test(v?.language || '') ? v.language : 'en',
      season: media === 'tv' ? season : null, episode: media === 'tv' ? episode : null };
  }
  function notifyAgent(session) {
    if (session.waiting) { const wake = session.waiting; session.waiting = null; wake(); }
  }
  async function handle(req, res, url) {
    sweep();
    const p = url.pathname;
    const method = req.method || 'GET';
    try {
      if (p === '/api/bridge/pair' && method === 'POST') {
        if (!limit(req)) return respond(res, 429, { error: 'Slow down' });
        if (sessions.size >= MAX_ACTIVE_SESSIONS) return respond(res, 503, { error: 'Pairing is busy' });
        const id = now().toString(36) + '-' + randomBytes(16).toString('hex');
        const code = randomBytes(7).toString('hex').toUpperCase();
        const session = { id, code, expires: now() + MAX_AGE, paired: false,
          lastSeen: 0, providers: [], pending: new Map(), queued: [], waiting: null, count: 0, countAt: now() };
        sessions.set(id, session);
        codes.set(code, { id, expires: now() + PAIR_WINDOW });
        return respond(res, 200, { code, browserToken: ticket('browser', id), expiresIn: PAIR_WINDOW / 1000 });
      }
      if (p === '/api/bridge/claim' && method === 'POST') {
        if (!limit(req)) return respond(res, 429, { error: 'Slow down' });
        const data = await body(req, 2048);
        const code = String(data.code || '').trim().toUpperCase();
        const c = codes.get(code), s = c && sessions.get(c.id);
        if (!s || now() > c.expires || s.paired) return respond(res, 404, { error: 'Pair code expired or invalid' });
        codes.delete(code);
        s.paired = true;
        s.lastSeen = now();
        return respond(res, 200, { agentToken: ticket('agent', s.id) });
      }
      const agent = ['/api/bridge/next','/api/bridge/report','/api/bridge/providers'].includes(p);
      const s = identify(agent ? 'agent' : 'browser', req.headers.authorization);
      if (!s) return respond(res, 401, { error: 'Bridge authentication required' });
      if (p === '/api/bridge/status' && method === 'GET') return respond(res, 200, {
        paired: s.paired, online: s.paired && now() - s.lastSeen < 42_000,
        providers: s.providers, pending: [...s.pending.values()].filter(t => !t.done).length
      });
      if (p === '/api/bridge/providers' && method === 'POST') {
        const data = await body(req, 12_000);
        s.providers = Array.isArray(data.providers) ? [...new Set(data.providers.filter(x => typeof x === 'string')
          .map(x => x.trim().slice(0, 80)).filter(Boolean))].slice(0, 130) : [];
        s.lastSeen = now();
        return respond(res, 200, { ok: true });
      }
      if (p === '/api/bridge/find' && method === 'POST') {
        if (!s.paired || now() - s.lastSeen >= 42_000) return respond(res, 503, { error: 'CloudStream device is offline' });
        const task = validateTask(await body(req, 4096));
        if (!task) return respond(res, 400, { error: 'Invalid movie or episode request' });
        if (now() - s.countAt > 60_000) { s.count = 0; s.countAt = now(); }
        if (++s.count > 12) return respond(res, 429, { error: 'Too many requests' });
        if ([...s.pending.values()].filter(t => !t.done).length >= 3) return respond(res, 429, { error: 'Bridge is busy' });
        const id = randomBytes(12).toString('hex');
        const record = { id, created: now(), done: false, sources: [], status: 'searching' };
        s.pending.set(id, record);
        s.queued.push({ id, ...task });
        notifyAgent(s);
        return respond(res, 202, { requestId: id });
      }
      if (p === '/api/bridge/result' && method === 'GET') {
        const id = String(url.searchParams.get('id') || '');
        const item = s.pending.get(id);
        if (!item) return respond(res, 404, { error: 'Request expired' });
        return respond(res, 200, { done: item.done, status: item.status, sources: item.sources });
      }
      if (p === '/api/bridge/next' && method === 'GET') {
        s.lastSeen = now();
        if (!s.queued.length) {
          // Render has one service instance on the free plan. A poll is owned by
          // the user agent and stays pending for up to 20 seconds.
          await new Promise(resolve => {
            const timeout = setTimeout(finish, 20_000);
            function finish() { clearTimeout(timeout); res.off('close', finish); if (s.waiting === finish) s.waiting = null; resolve(); }
            s.waiting = finish;
            res.on('close', finish);
          });
        }
        s.lastSeen = now();
        return respond(res, 200, { task: s.queued.shift() || null });
      }
      if (p === '/api/bridge/report' && method === 'POST') {
        const data = await body(req);
        const id = String(data.requestId || '');
        const item = s.pending.get(id);
        if (!item || item.done || now() - item.created > REQUEST_WINDOW)
          return respond(res, 404, { error: 'Request expired' });
        item.sources = sanitizeSources([...item.sources, ...(Array.isArray(data.sources) ? data.sources : [])]);
        item.done = data.final !== false;
        item.status = item.sources.length ? (item.done ? 'ready' : 'searching') :
          (item.done ? 'no-browser-compatible-sources' : 'searching');
        s.lastSeen = now();
        return respond(res, 200, { ok: true, accepted: item.sources.length });
      }
      return respond(res, 404, { error: 'Unknown bridge route' });
    } catch (e) {
      return respond(res, e.status || 500, { error: e.status ? e.message : 'Bridge request failed' });
    }
  }
  return { handle };
}
