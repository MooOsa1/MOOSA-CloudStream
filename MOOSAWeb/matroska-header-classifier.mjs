/**
 * Read-only Matroska CodecID hints from a bounded EBML prefix.
 * No media URLs, scene metadata, personal data, or arbitrary EBML fields are
 * returned. A container signature alone never establishes codec compatibility.
 */
const KNOWN=new Set([
  'V_MPEG4/ISO/AVC','V_MPEGH/ISO/HEVC','V_AV1','V_VP9','V_VP8',
  'A_AAC','A_AC3','A_EAC3','A_OPUS','A_VORBIS','A_DTS','A_FLAC',
  'S_TEXT/UTF8','S_TEXT/ASS','S_HDMV/PGS'
]);
export function inspectMatroskaCodecs(bytes){
  const buf=Buffer.isBuffer(bytes)?bytes:Buffer.from(bytes||[]);
  if(buf.length<4 || !buf.subarray(0,4).equals(
    Buffer.from([0x1a,0x45,0xdf,0xa3])))
    return {container:'not_ebml',codecIds:[],
      codecMetadataPresent:false,mp4RemuxCandidate:false};
  const ids=new Set();
  // Matroska CodecID is EBML element 0x86. Decode the following VINT
  // length, then check only a strict known-codec vocabulary.
  for(let i=4;i<buf.length-5;i++){
    if(buf[i]!==0x86)continue;
    const sizeByte=buf[i+1];
    if(sizeByte===0 || sizeByte===0xff)continue;
    let width=0,mask=0x80;
    while(width<8 && !(sizeByte&mask)){width++;mask>>=1;}
    width++;
    if(width>4||i+1+width>=buf.length)continue;
    let length=sizeByte&(mask-1);
    for(let j=1;j<width;j++)length=length*256+buf[i+1+j];
    if(length<5||length>64||i+1+width+length>buf.length)continue;
    const text=buf.subarray(i+1+width,i+1+width+length).toString('ascii');
    if(KNOWN.has(text))ids.add(text);
  }
  const codecIds=[...ids].sort();
  const video=codecIds.filter(x=>x.startsWith('V_'));
  const audio=codecIds.filter(x=>x.startsWith('A_'));
  return {container:'matroska',codecIds,
    codecMetadataPresent:codecIds.length>0,
    // Informational only; real stream track selection, copyright clearance,
    // MP4 muxing and Safari decoder compatibility remain unverified.
    mp4RemuxCandidate:video.includes('V_MPEG4/ISO/AVC') &&
      audio.includes('A_AAC')};
}
