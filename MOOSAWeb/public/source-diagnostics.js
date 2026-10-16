/**
 * Explain source lookup outcomes without claiming Android CloudStream plugins
 * were searched merely because their .cs3 manifests were indexed.
 *
 * The configuration bit only says an operator provided a remote runtime URL
 * and secret. It does NOT certify the remote executor or stream health.
 */
export function describeSourceResolution(result) {
  const count=Array.isArray(result?.sources)?result.sources.length:0;
  if(count) return 'تم العثور على '+count+' مصدر مشاهدة. التشغيل يعتمد على صلاحية الرابط وتوافق المتصفح.';
  if(result?.cloudstreamEngineConfigured===false) {
    return 'ما ظهرت سيرفرات مشاهدة. محرك CineStream وStreamPlay غير مشغّل على خادم الموقع حاليًا؛ وجود الإضافتين في الفهرس لا يعني تشغيلهما. تقدر تجرب فيديو الاختبار أو رابطًا تملك حق استخدامه.';
  }
  if(result?.cloudstreamEngineConfigured===true) {
    return 'لم يصل رابط مشاهدة صالح لهذا العنوان. إعداد المحرك موجود لكن الاتصال به أو عمل الإضافات أو صلاحية الروابط غير مؤكدة.';
  }
  return 'ما حصلنا رابط مشاهدة متوافقًا لهذا العنوان. تقدر تستخدم فيديو الاختبار أو رابطًا مصرحًا به.';
}
