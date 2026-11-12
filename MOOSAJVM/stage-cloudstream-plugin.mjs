#!/usr/bin/env node
/**
 * CLI: stage an explicitly trusted CloudStream .cs3 file into a temporary
 * 0700 directory after verifying its repository manifest and SHA256 checksum.
 *
 * Usage:
 *   node MOOSAJVM/stage-cloudstream-plugin.mjs \
 *      'https://raw.githubusercontent.com/OWNER/REPO/builds/repo.json' \
 *      'OWNER/REPO' 'PluginInternalName'
 *
 * Staging is NOT installation, permission to run arbitrary Kotlin code, or
 * proof of availability of any video. Never execute an untrusted .cs3 here.
 */
import {mkdtemp,writeFile,chmod} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {inspectCloudStreamRepo,downloadCheckedCs3} from './cloudstream-repository.mjs';

async function main(){
  const [index,repo,name]=process.argv.slice(2);
  if(!index||!repo||!name||process.argv.length!==5){
    throw new Error('usage: stage-cloudstream-plugin.mjs <repo.json|plugins.json> <owner/repo> <internalName>');
  }
  if(!/^[a-zA-Z0-9_.-]{1,120}$/.test(name)||
     !/^[a-zA-Z0-9-]{1,39}\/[a-zA-Z0-9_.-]{1,100}$/.test(repo)){
    throw new Error('invalid_selection');
  }
  const catalog=await inspectCloudStreamRepo(index,{allowedRepos:[repo],timeoutMs:16000});
  const matched=catalog.accepted.filter(p=>p.internalName===name);
  if(matched.length!==1)throw new Error('not_exactly_one_approved_plugin');
  const p=matched[0];
  const result=await downloadCheckedCs3(p,{allowedRepos:[repo],timeoutMs:25000});
  const dir=await mkdtemp(join(tmpdir(),'moosa-cs3-'));
  await chmod(dir,0o700);
  const file=join(dir,p.internalName+'.cs3');
  await writeFile(file,result.bytes,{flag:'wx',mode:0o600});
  process.stdout.write('MOOSA_CS3_STAGED '+JSON.stringify({
    repository:catalog.repository,
    name:p.internalName,version:p.version,apiVersion:p.apiVersion,
    bytes:result.bytes.byteLength,sha256:result.sha256,
    checksumVerified:true,stagedFile:file
  })+'\n');
}
main().catch(e=>{
  process.stderr.write('MOOSA_CS3_STAGE_FAILED '+JSON.stringify({
    reason:String(e?.message||e).slice(0,120)
  })+'\n');
  process.exitCode=1;
});
