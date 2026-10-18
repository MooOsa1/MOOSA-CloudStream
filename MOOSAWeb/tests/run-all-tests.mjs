/**
 * Project tests must NOT accidentally inherit a deployed private lab's
 * video-provider opt-in flags or authentication settings. This runner only
 * cleans the CHILD test process environment, never the running web service.
 */
import {spawnSync} from 'node:child_process';
import {readdirSync} from 'node:fs';
const files=readdirSync(new URL('.',import.meta.url))
  .filter(name=>name.endsWith('.test.mjs')).sort()
  .map(name=>'tests/'+name);
const env={...process.env};
for(const key of ['MOOSA_TWO_FILM_PRIVATE_LAB','MOOSA_LAB_PASSWORD',
  'CINESTREAM_MULTIFILM_PILOT','CINESTREAM_MULTIFILM_RIGHTS_APPROVED',
  'CINESTREAM_MEDIA_HOSTS'])delete env[key];
const run=spawnSync(process.execPath,['--test',...files],{
  cwd:new URL('..',import.meta.url).pathname,
  stdio:'inherit',env
});
if(run.error)throw run.error;
process.exit(run.status??1);
