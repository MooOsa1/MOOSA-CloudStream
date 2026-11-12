#requires -Version 5.1
<#
MOOSA Stremio Browser Lab — run site on local Windows PC.
No admin rights, debrid plan or Android emulator.
Needs Node.js >=20 and Stremio Service on 127.0.0.1:11470.
MOOSA Web is served on loopback to avoid browser security restrictions
from a public HTTPS origin attempting to reach your local P2P engine.
#>
[CmdletBinding()]
param([switch]$NoOpen,[switch]$NoUpdate)
$ErrorActionPreference='Stop'
$RepoZip='https://github.com/MooOsa1/MOOSA-CloudStream/archive/refs/heads/experiment/moosa-hub-aiostreams-20261018.zip'
$Data=Join-Path $env:LOCALAPPDATA 'MOOSA-Hub'
$Web=Join-Path $Data 'Web'
$Entry=Join-Path $Web 'MOOSAWeb\server.mjs'
$Site='http://127.0.0.1:3000/stremio-lab.html'
Write-Host 'MOOSA Browser Lab (Windows)' -ForegroundColor Cyan
$node=Get-Command node -ErrorAction SilentlyContinue
if(-not $node){
 Write-Warning 'Node.js 20+ is required: https://nodejs.org/en/download'
 throw 'Node.js not installed. Nothing changed.'
}
$major=[int]((& $node.Source --version).TrimStart('v').Split('.')[0])
if($major -lt 20){throw 'Node.js 20 or newer is required.'}
if(-not (Test-Path $Data)){New-Item -ItemType Directory -Path $Data -Force|Out-Null}
if(-not $NoUpdate -or -not(Test-Path $Entry)){
 $Zip=Join-Path $Data 'MOOSAWeb.zip'
 $Unpack=Join-Path $Data 'Web-download'
 try{
  if(Test-Path $Unpack){Remove-Item -Recurse -Force $Unpack}
  Invoke-WebRequest -Uri $RepoZip -OutFile $Zip -TimeoutSec 100
  Expand-Archive -LiteralPath $Zip -DestinationPath $Unpack -Force
  $candidate=Get-ChildItem -LiteralPath $Unpack -Directory |
    Where-Object {Test-Path (Join-Path $_.FullName 'MOOSAWeb\server.mjs')} |
    Select-Object -First 1
  if(-not $candidate){throw 'GitHub package has no MOOSAWeb/server.mjs'}
  $siteDir=Join-Path $candidate.FullName 'MOOSAWeb'
  $expected=@('server.mjs','public\stremio-lab.html',
    'public\stremio-lab.js','local-stremio-bridge.mjs')
  foreach($file in $expected){if(-not(Test-Path (Join-Path $siteDir $file))){
    throw "Required website component missing: $file"
  }}
  if(Test-Path $Web){Remove-Item -Recurse -Force $Web}
  New-Item -ItemType Directory -Path (Join-Path $Web 'MOOSAWeb') -Force|Out-Null
  Copy-Item -Path (Join-Path $siteDir '*') -Destination (Join-Path $Web 'MOOSAWeb') -Recurse -Force
  Write-Host 'Updated MOOSA Web experiment from GitHub.' -ForegroundColor Green
 }finally{
  if(Test-Path $Zip){Remove-Item $Zip -Force}
  if(Test-Path $Unpack){Remove-Item -Recurse -Force $Unpack}
 }
}
$serviceReachable=$false
try{
 $r=Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:11470/settings' -TimeoutSec 3
 $serviceReachable=$r.StatusCode -eq 200
}catch{}
if(-not $serviceReachable){
 Write-Warning 'Start Stremio Service. It is not responding on 127.0.0.1:11470.'
}
$siteReachable=$false
try{
 $r=Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:3000/healthz' -TimeoutSec 2
 $siteReachable=$r.StatusCode -eq 200
}catch{}
if(-not $siteReachable){
 $env:PORT='3000'
 $env:MOOSA_HOST='127.0.0.1'
 $p=Start-Process -FilePath $node.Source -ArgumentList @('server.mjs') -WorkingDirectory (Join-Path $Web 'MOOSAWeb') -PassThru -WindowStyle Hidden
 $ready=$false
 for($i=0;$i -lt 40;$i++){
  Start-Sleep -Milliseconds 300
  if($p.HasExited){throw 'MOOSA Web Node server terminated before starting'}
  try{
   $r=Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:3000/healthz' -TimeoutSec 1
   if($r.StatusCode -eq 200){$ready=$true;break}
  }catch{}
 }
 if(-not $ready){throw 'MOOSA Web did not start on port 3000'}
}
Write-Host "MOOSA Web lab URL: $Site" -ForegroundColor Green
Write-Host 'Keep your installed add-on URL private.'
Write-Warning 'Experimental: browser HLS playback requires available peers, supported codecs, and local Stremio service.'
if(-not $NoOpen){Start-Process $Site}
