/**
 * Test-only licensed example implementing Nuvio's getStreams() interface.
 * The URLs are synthetic and NOT playable. No actual media site is accessed.
 *
 * This file must never be advertised as a genuine CineStream/StreamPlay host.
 */
module.exports.getStreams=async function getStreams(tmdbId,mediaType,season,episode){
  if(mediaType!=='movie'||!Number.isInteger(tmdbId) ||
    season!==null||episode!==null)return [];
  if(tmdbId===999) await new Promise(resolve=>setTimeout(resolve,1200));
  return [
    {
      name:'LicensedFixture',
      title:'Big Buck Bunny 720p',
      quality:'720p',
      url:'https://upload.wikimedia.org/mock-open-film-720.webm',
      headers:{},
      subtitles:[{language:'en',url:'https://example.org/en.vtt'}]
    },
    {
      name:'LicensedFixture',
      title:'Big Buck Bunny 1080p (protected)',
      quality:'1080p',
      url:'https://upload.wikimedia.org/mock-protected-1080.mp4',
      headers:{Referer:'https://protected.example/watch'}
    },
    {
      name:'LicensedFixture',
      title:'Big Buck Bunny 4K (unknown host)',
      quality:'4K',
      url:'https://some-unverified-host.example/4k.mp4',
      headers:{}
    }
  ];
};
