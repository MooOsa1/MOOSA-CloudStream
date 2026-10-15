import { appendPlayerSources } from './player.js';

const $ = selector => document.querySelector(selector);
const STORAGE_KEY = 'moosa-cloudstream-bridge-v1';
let token = '';
let searchGeneration = 0;
let lastDevicePoll = 0;

try { token = localStorage.getItem(STORAGE_KEY) || ''; } catch {}

async function call(path, options = {}, authorized = true) {
  const res = await fetch(path, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(authorized && token ? { Authorization: 'Bearer ' + token } : {}),
      ...(options.body ? { 'Content-Type': 'application/json' } : {})
    },
    cache: 'no-store'
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || 'تعذر الاتصال بالجسر');
  return data;
}

function statusText(text) {
  $('#bridge-device-status').textContent = text;
}
export function bridgeStatus(text) {
  $('#bridge-play-status').textContent = text;
}

async function refresh() {
  if (!token) { statusText('غير مرتبط. أنشئ رمز إقران جديدًا.'); return; }
  try {
    const response = await call('/api/bridge/status');
    const count = response.providers.length;
    const label = response.online ? 'متصل' : response.paired ? 'مقترن لكنه غير متصل الآن' : 'بانتظار جهاز CloudStream';
    statusText(label + (count ? ' • ' + count + ' إضافة متاحة' : ''));
    $('#bridge-providers').textContent = response.providers.length
      ? response.providers.join(' • ')
      : 'شغّل CloudStream على جهاز أندرويد ثم افتح MOOSA Hub → Settings → Web Bridge.';
    $('#bridge-indicator').textContent = response.online ? '● CloudStream' : '○ CloudStream';
    $('#bridge-indicator').classList.toggle('online', response.online);
    lastDevicePoll = Date.now();
    return response;
  } catch {
    statusText('الجهاز غير مرتبط أو انتهت صلاحية الإقران. أنشئ رمزًا جديدًا.');
    $('#bridge-indicator').classList.remove('online');
    return null;
  }
}

async function makePair() {
  $('#bridge-code').textContent = '••••••••••••••';
  statusText('جاري إنشاء رمز...');
  try {
    const data = await call('/api/bridge/pair', { method: 'POST' }, false);
    token = data.browserToken;
    try { localStorage.setItem(STORAGE_KEY, token); } catch {}
    $('#bridge-code').textContent = data.code;
    statusText('الرمز صالح لعشر دقائق. أدخله في إعدادات MOOSA Hub على جهازك.');
    $('#bridge-providers').textContent = 'الربط لا ينقل الإضافات إلى الإنترنت؛ يتم البحث من جهاز CloudStream عند الضغط على Play فقط.';
    $('#bridge-indicator').textContent = '○ CloudStream';
  } catch (e) {
    statusText('تعذر إنشاء الرمز: ' + e.message);
  }
}

export function closeBridgeDialog() {
  $('#bridge-panel').classList.add('hidden');
  if ($('#detail-panel').classList.contains('hidden') &&
      $('#player-panel').classList.contains('hidden')) $('#overlay').classList.add('hidden');
}
export function openBridgeDialog() {
  $('#overlay').classList.remove('hidden');
  $('#bridge-panel').classList.remove('hidden');
  $('#close-bridge').focus();
  if (token) void refresh();
  else void makePair();
}

export async function startBridgeSearch(request) {
  const generation = ++searchGeneration;
  if (!token) {
    bridgeStatus('اربط جهاز CloudStream من زر «ربط CloudStream» للبحث في إضافاتك.');
    return;
  }
  bridgeStatus('جاري الاتصال بإضافات CloudStream...');
  try {
    const check = await refresh();
    if (generation !== searchGeneration) return;
    if (!check?.online) {
      bridgeStatus('جهاز CloudStream غير متصل. افتح CloudStream وتأكد من تفعيل Web Bridge.');
      return;
    }
    const { requestId } = await call('/api/bridge/find', {
      method: 'POST', body: JSON.stringify(request)
    });
    for (let index = 0; index < 40 && generation === searchGeneration; index++) {
      await new Promise(resolve => setTimeout(resolve, 1300));
      if (generation !== searchGeneration) return;
      const data = await call('/api/bridge/result?id=' + encodeURIComponent(requestId));
      if (data.sources?.length) {
        appendPlayerSources(data.sources);
        bridgeStatus('وصلت ' + data.sources.length + ' مصادر HD أو أعلى • بعض الروابط قد تتطلب صلاحيات متصفح غير متاحة.');
      }
      if (data.done) {
        if (!data.sources?.length) bridgeStatus('ما حصلنا سيرفرات مؤكدة التوافق لهذا العنوان. تقدر تستخدم رابط مصرح به.');
        return;
      }
    }
    if (generation === searchGeneration) bridgeStatus('انتهت مهلة البحث. تقدر تعيد المحاولة.');
  } catch (e) {
    if (generation === searchGeneration) bridgeStatus('تعذر البحث: ' + e.message);
  }
}
export function stopBridgeSearch() { searchGeneration++; }

export function initBridge() {
  $('#open-bridge').addEventListener('click', openBridgeDialog);
  $('#close-bridge').addEventListener('click', closeBridgeDialog);
  $('#bridge-new-code').addEventListener('click', () => void makePair());
  $('#bridge-refresh').addEventListener('click', () => void refresh());
  $('#bridge-copy').addEventListener('click', async () => {
    const code = $('#bridge-code').textContent;
    if (/^[A-F0-9]{14}$/.test(code)) {
      try { await navigator.clipboard.writeText(code); statusText('تم نسخ الرمز. الصقه في إعدادات MOOSA Hub.'); }
      catch { statusText('انسخ الرمز الظاهر يدويًا.'); }
    }
  });
  document.addEventListener('moosa-player-closed', stopBridgeSearch);
  window.setInterval(() => {
    if (token && document.visibilityState === 'visible' && Date.now() - lastDevicePoll > 14_000) void refresh();
  }, 15_000);
  if (token) void refresh();
}
