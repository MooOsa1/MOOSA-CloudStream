import test from 'node:test';
import assert from 'node:assert/strict';
import {gunzipSync} from 'node:zlib';
import {getH264ResolutionFromMpegTs,readH264SpsDimensions}
  from '../h264-mpegts-resolution.mjs';
// Independent FFmpeg/libx264 generated MPEG-TS samples (1 frame each).
// Compressed fixture data contains no protected, third-party media.
const CASES=[
  [852,480,'H4sIAAAAAAACA9VWS08TURQ+5SnGByAvH4uLxMQYKDNtaQCZBKhQNiQYE8ICHW9nbtsJ8+qdKW3VmC5YEDUuXWlU/APoxsSEBHfKwq1gXODGxI0SN25M6rnFhMREghoi3DZnzuM753797p2k8cGGehjeOAOBlwClQAkC34tNY42BmtFRy2WpukuMzxoak+TcjdhKaW+t+CDUAyweLnOHwAace1r1rLQ/VnwCuVcsNpa5f4ANOClM89L45D7gPgRS7QTA9UM3he7rAMVidTvUrlIR1m0ImxpebnlXefXbw+ULAJVQJczBgakXRVFMvzn6Ch811aXS1PuRT0sf18ae3+tcJWsdn7/kQ9EI6SKawxmR0eVhWeolYZnJfck+LIwFEdA9PjES74qQockYInWmYSHmuAWTJX0SkqRwV0gKhTGZ9n23v7s7l8sFZw2dOWY8AA3UDjo81S12CqZ9y0Sc4/qGY3v9RKMJqikS4SypyERnCdPRZhSpHz+E2tQseExExGKKblDiZRPoScT1CghHq3JdkYMSQtAQy8gzXRWzRIfKqZ1iihwlWpo7FlWxVSY+Z6ZpeIjozffqmo+OlrHQ6ozq1xybKSG5U5ZJkiL3Rs9XXW/GcLHx55CMqzrJpMdEo5/m2OQpPcR0nBmaxkDdynkmvslbCXkzISo2L++nGRb1BSfD9hk3KaIxnzCznBZUzbFcWmaHQvmcGjbOQiCnApPk1GLiR+SYkUr7LnozrIBlJdQjXOR+TISqZdhiZ43ZTMuKceURQiPOvLRQXlM0niRWAoUR0mKghMJBiWQEA0UKRtF1xZjyk+aVaB86ns9cJUIMF1XGs8RDiOCJZRQJ8MoF2Pxcf8MtqJAzyxBb6YUD1a93vpB701b09nJ2H32Re/N2iCtCHFj4F3l2TRrk3vIfxduU5uLp9iNziSLcmXah/utdqAjwnSzk3vprbr9cGuTetgNc+b2CwsJf6bNb2iD343tAw01tzs7Pxx63PYKO1lNQNfCkCMXp+9ss5H7id7UHe//OnL8Ne+c/yh+QX/8BCIgZggQLAAA='],
  [1280,720,'H4sIAAAAAAACA9VXTWgTQRR+W9taQW1atfXfqSKK2HQ3qaUtLrQNNr0UCkIRaV0mu5Nk6f5ldmMSFQnSg4h49KSg9OJRvXgq6MHfg1ereKgeREE0eBbim1WoF/G32H27vH0z72e+/eZl2KSH2mIwUtsL0j2AulQH6XN141i71Dw6ansst+Yo4ydNnclK6UzqSX1lSXoIYgA314XYQarBgVuNt+vRkPQEYm+42R5ifwU12CbUpvnxyQhgHwZ59QTA6bVnBe+LANVqUxesXqBiuKYmdG7k7q4X0lDH+8YhgFUQqrkHa28IX/5p6yN8NDfV68deHnk3/+b52J3LBxfI8z0fPpYTfb2km+guZ0RBkycVuZ8kFaYMZAfQMRbHgJ7xiSPp7l4yPJnCSIPp6Ei5XsVi2YAkZDnZnZATSZzMB4E32NNTKpXiJ02DuRZNS9DmxF2e6xErxfOBbWGc6wWm6/iDRKcZqqsy4SyrKsRgGcvVZ1R5EC9CHWpVfCZGxGaqYVLiFzNoycTzKxiOWuOGqsRlDEFFbLPMDE3UEhkap06OqUof0fPctamGqQoJOLMs08eI/nK/oQdo6AUbtcGoccp1mJpQDioKyVIfsbcHmufPmB4mfitS8DQ3m/WZSAzyHJN89RCxXHeG5nGgLc35Fv6QlyaUrxPC4/BwPd20aSAwmU7AuEUxGuczVpHTiqa7tkdDdEhUwKnpYC0M5FTEZDm1mXiJEjNz+cBDa4ZV0K0mDoUmYt+AQ802HbGyzhymF0W5sITgiDM/L5jXVZ1niZ1BYgS1OFATybhMCgKBKsf70PREmfBJy2rfABp+wDy1l5gesox7iZvQiztWUGWoYqux87ODbRegQSnchdSTfmhpevwbgtg3/kLYs+niyrsR+6biikT28xuxd6xAWIuioeD47q71s5kqXJzyIPbpEjRI/HtB7J38r+W/8b45oi3zDLFviR7ucthSiH1rd8v51PXN12BP53ZoPDxXherUlX8qV5eN920RbRlxRm6P8Bm544fuE2FbxV7vjr0d3Q8t9x+C1Lp/EUjnvn8rf877zgjzvivCZ2Tsj5PDzynoOPefekp8v0eY9/YIn5HJaVjOf5XLhr36BXsrMxvkEAAA']
];
test('Real libx264 TS decoding returns original encoded 852x480 and 1280x720 resolutions',()=>{
  for(const [width,height,gz] of CASES){
    const ts=gunzipSync(Buffer.from(gz,'base64'));
    assert.equal(ts.length%188,0);
    assert.deepEqual(getH264ResolutionFromMpegTs(ts),{width,height,codec:'avc'});
  }
});
test('MPEG-TS packets alone are not enough evidence to claim HD',()=>{
  const invalid=new Uint8Array(188*2);invalid[0]=0x47;invalid[188]=0x47;
  assert.equal(getH264ResolutionFromMpegTs(invalid),null);
  assert.equal(getH264ResolutionFromMpegTs(new Uint8Array([0x47,0x00])),null);
  assert.equal(readH264SpsDimensions(new Uint8Array([0x67,0x00,0x00])),null);
});
test('Truncated or misaligned first segment fails closed',()=>{
  const ts=gunzipSync(Buffer.from(CASES[1][2],'base64'));
  assert.equal(getH264ResolutionFromMpegTs(ts.subarray(1)),null);
  assert.equal(getH264ResolutionFromMpegTs(ts.subarray(0,188)),null);
});
