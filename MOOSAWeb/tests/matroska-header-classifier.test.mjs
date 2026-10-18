import test from 'node:test';
import assert from 'node:assert/strict';
import {inspectMatroskaCodecs} from '../matroska-header-classifier.mjs';
const tag=Buffer.from([0x1a,0x45,0xdf,0xa3]);
function codec(id){
  const bytes=Buffer.from(id,'ascii');
  return Buffer.concat([Buffer.from([0x86,0x80+bytes.length]),bytes]);
}
test('read-only Matroska header recognizes H264 and AAC tracks as remux candidates, not playable',()=>{
  const prefix=Buffer.concat([tag,Buffer.alloc(23),codec('V_MPEG4/ISO/AVC'),
    Buffer.alloc(25),codec('A_AAC')]);
  const result=inspectMatroskaCodecs(prefix);
  assert.deepEqual(result.codecIds,['A_AAC','V_MPEG4/ISO/AVC']);
  assert.equal(result.mp4RemuxCandidate,true);
  assert.equal(result.codecMetadataPresent,true);
  assert.equal(result.container,'matroska');
});
test('HEVC and DTS cannot pass the H264/AAC remux fast-path',()=>{
  const prefix=Buffer.concat([tag,codec('V_MPEGH/ISO/HEVC'),codec('A_DTS')]);
  const result=inspectMatroskaCodecs(prefix);
  assert.deepEqual(result.codecIds,['A_DTS','V_MPEGH/ISO/HEVC']);
  assert.equal(result.mp4RemuxCandidate,false);
});
test('missing tracks and spoofed CodecID text never imply browser compatibility',()=>{
  const malformed=Buffer.concat([tag,Buffer.from('V_MPEG4/ISO/AVC A_AAC','ascii')]);
  assert.equal(inspectMatroskaCodecs(malformed).mp4RemuxCandidate,false);
  assert.equal(inspectMatroskaCodecs(malformed).codecMetadataPresent,false);
  assert.deepEqual(inspectMatroskaCodecs(Buffer.from('<html>Oops</html>')).codecIds,[]);
  const audioOnly=Buffer.concat([tag,codec('A_AAC')]);
  assert.equal(inspectMatroskaCodecs(audioOnly).mp4RemuxCandidate,false);
});
