/**
 * "Sita Sings the Blues" has two DIFFERENT media editions:
 * Commons' current 2023 WebM intentionally removed the copyrighted songs.
 * Nina Paley's own watch page links this FULL H264/AAC movie via Archive.org.
 * Never describe the soundtrack as universally CC0.
 *
 * Links verified using HEAD + ffprobe + actual AAC decode, Oct 17, 2026.
 * No user-controlled host or title can be substituted for these constants.
 */
export const SITA_FILM_ID='sita-sings-the-blues';
export const FILMMAKER_WATCH='https://www.sitasingstheblues.com/watch.html';
export const FILMMAKER_LICENSE='https://www.sitasingstheblues.com/license.html';
export const SITA_AUDIO_720=
  'https://archive.org/download/Sita_Sings_the_Blues/Sita_Sings_the_Blues_720p.mp4';
export const SITA_AUDIO_480=
  'https://archive.org/download/Sita_Sings_the_Blues/Sita_Sings_the_Blues_480p_2150kbps.mp4';
const EXACT_COMMONS_FILE=
  'https://commons.wikimedia.org/wiki/File:Sita_Sings_the_Blues.webm';
export function filmmakerSoundSources(film){
  if(film?.id!==SITA_FILM_ID||film?.title!=='Sita Sings the Blues'||
     film?.year!==2008||film?.available!==true||
     !Array.isArray(film.sources))return [];
  const silent=film.sources.find(s=>
    s?.sourcePage===EXACT_COMMONS_FILE&&
    /^https:\/\/upload\.wikimedia\.org\//.test(s.url||'')&&
    /^https:\/\/creativecommons\.org\/publicdomain\/zero\/1\.0\//.test(s.licenseUrl||'')&&
    Number(s.quality)>=720);
  if(!silent)return [];
  return [{
    url:SITA_AUDIO_720,quality:720,
    name:'نسخة المخرجة الرسمية • بالصوت AAC • 720p',
    officialSoundtrack:true,preferred:true,codecOptimized:true,
    sourcePage:FILMMAKER_WATCH,licenseUrl:FILMMAKER_LICENSE,
    attribution:'Nina Paley — موسيقى مرخّصة بشكل منفصل',
    audioCodec:'aac',videoCodec:'h264',subtitles:[],
    rightsNote:'Author permits film sharing; music in soundtrack has separate license terms.'
  },{
    ...silent,name:'Wikimedia Commons • نسخة صامتة (بدون صوت)',
    silent:true,preferred:false,codecOptimized:false
  }];
}
