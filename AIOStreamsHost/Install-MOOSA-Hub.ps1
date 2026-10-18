#requires -Version 5.1
<#
MOOSA Hub first-time setup for Windows + Stremio.
- Uses the existing tested AIOStreams user API and MOOSA v1.3+ template.
- Creates a random, independent configuration, validates its Stremio manifest
  AND a movie source response before offering the installation link.
- Existing installation is reused: it will not create a second account.
- Stores API password encrypted by Windows DPAPI for the current Windows user.
- NEVER downloads movies, runs unsigned external scripts, or edits Stremio files.
- TestOnly creates and deletes a disposable profile; nothing persists.
#>
[CmdletBinding()]
param(
    [switch]$TestOnly,
    [switch]$NoOpen
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$HostBase = 'https://aiostreams-nightly.fortheweak.cloud'
$UserApi = "$HostBase/api/v1/user"
$TemplateUrl = 'https://raw.githubusercontent.com/MooOsa1/MOOSA-CloudStream/experiment/moosa-hub-aiostreams-20261018/AIOStreamsHost/moosa-hub-template.json'
$LocalDirectory = Join-Path $env:LOCALAPPDATA 'MOOSA-Hub'
$LocalProfile = Join-Path $LocalDirectory 'stremio-config.json'
$Owner = $null
$IsNew = $false
$NeedCleanup = $false

function New-MoosaPassword {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 36
    try {
        $rng.GetBytes($bytes)
        return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    }
    finally { $rng.Dispose() }
}
function ConvertTo-MoosaConfig([object]$template) {
    $cfg = $template.config | ConvertTo-Json -Depth 70 | ConvertFrom-Json
    $cfg | Add-Member -MemberType NoteProperty -Name services -Value @() -Force
    $cfg | Add-Member -MemberType NoteProperty -Name formatter -Value ([pscustomobject]@{ id='gdrive' }) -Force
    $cfg | Add-Member -MemberType NoteProperty -Name addonName -Value 'MOOSA Hub - iPhone & PC' -Force
    $cfg | Add-Member -MemberType NoteProperty -Name addonDescription -Value 'Automatic source aggregation, Arabic/English subtitle preference, free P2P/HTTP.' -Force
    return $cfg
}
function Get-MoosaAuth([string]$uuid, [string]$password) {
    $raw = [Text.Encoding]::UTF8.GetBytes(("{0}:{1}" -f $uuid, $password))
    return @{ Authorization = 'Basic ' + [Convert]::ToBase64String($raw) }
}
function Remove-MoosaConfig([object]$record) {
    $auth = Get-MoosaAuth $record.uuid $record.password
    Invoke-RestMethod -Uri $UserApi -Method Delete -Headers $auth -TimeoutSec 25 | Out-Null
}
function Check-MoosaStreams([string]$uuid, [string]$token) {
    $segment = [Uri]::EscapeDataString($token)
    $manifest = "$HostBase/stremio/$uuid/$segment/manifest.json"
    $m = Invoke-RestMethod -Uri $manifest -TimeoutSec 35
    $resources = @($m.resources | ForEach-Object { if ($_ -is [string]) { $_ } else { $_.name } })
    if ($resources -notcontains 'stream') { throw 'MOOSA manifest does not provide a stream resource.' }
    $probe = "$HostBase/stremio/$uuid/$segment/stream/movie/tt0063350.json"
    $movie = Invoke-RestMethod -Uri $probe -TimeoutSec 70
    $count = @($movie.streams).Count
    if ($count -lt 1) { throw 'No stream candidates found through the installed MOOSA addon.' }
    Write-Host ("MOOSA installed addon verified: {0} indexed stream candidates." -f $count) -ForegroundColor Green
    return $manifest
}

try {
    $tpl = Invoke-RestMethod -Uri $TemplateUrl -TimeoutSec 30
    if ($tpl.metadata.id -ne 'moosa.hub.iphone-pc') {
        throw 'Unexpected source template. Aborting setup.'
    }
    $Owner = $null
    if (-not $TestOnly -and (Test-Path $LocalProfile)) {
        try {
            $saved = Get-Content -LiteralPath $LocalProfile -Raw -Encoding UTF8 | ConvertFrom-Json
            $secure = ConvertTo-SecureString $saved.protectedPassword
            $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try { $pass = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
            $auth = Get-MoosaAuth $saved.uuid $pass
            $existing = Invoke-RestMethod -Uri $UserApi -Method Get -Headers $auth -TimeoutSec 30
            if ($existing.success -and $existing.data.encryptedPassword) {
                $Owner = [pscustomobject]@{
                    uuid=$saved.uuid; password=$pass
                    encryptedPassword=$existing.data.encryptedPassword
                }
                Write-Host 'Existing MOOSA Hub profile found. Reusing it.' -ForegroundColor Cyan
            }
        }
        catch {
            Write-Warning 'The previous local profile could not be verified. A new setup will be created.'
        }
    }

    if ($null -eq $Owner) {
        $password = New-MoosaPassword
        $config = ConvertTo-MoosaConfig $tpl
        $body = @{ config=$config; password=$password } | ConvertTo-Json -Depth 70 -Compress
        $created = Invoke-RestMethod -Uri $UserApi -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 90
        if (-not $created.success -or -not $created.data.uuid -or -not $created.data.encryptedPassword) {
            throw 'AIOStreams did not create the MOOSA account.'
        }
        $Owner = [pscustomobject]@{
            uuid=$created.data.uuid
            password=$password
            encryptedPassword=$created.data.encryptedPassword
        }
        $IsNew = $true
        $NeedCleanup = $true
    }
    $install = Check-MoosaStreams $Owner.uuid $Owner.encryptedPassword

    if ($TestOnly) {
        Remove-MoosaConfig $Owner
        $NeedCleanup = $false
        Write-Host 'TEST PASSED: temporary addon deleted; no saved profile.' -ForegroundColor Green
        exit 0
    }

    if ($IsNew) {
        if (-not (Test-Path $LocalDirectory)) { New-Item -Path $LocalDirectory -ItemType Directory -Force | Out-Null }
        $secret = ConvertTo-SecureString -String $Owner.password -AsPlainText -Force | ConvertFrom-SecureString
        $local = @{ uuid=$Owner.uuid; protectedPassword=$secret; templateVersion=$tpl.metadata.version } |
            ConvertTo-Json -Compress
        [IO.File]::WriteAllText($LocalProfile, $local, (New-Object Text.UTF8Encoding($false)))
        $NeedCleanup = $false
    }

    # Manifest URLs contain an account token. Do not paste them publicly.
    Set-Clipboard -Value $install
    Write-Host ''
    Write-Host 'MOOSA Hub is ready. The private install URL was copied to your clipboard.' -ForegroundColor Green
    Write-Host 'In Stremio Desktop: Addons -> Add addon -> paste (Ctrl+V) -> Install.'
    Write-Host 'Sign into the SAME Stremio account on your iPhone full IPA to sync the addon.'
    Write-Host "Local configuration saved to: $LocalProfile"
    Write-Warning 'Keep your install URL private. P2P availability varies; listing is not proof of playback.'
    if (-not $NoOpen) {
        Start-Process 'https://web.stremio.com/'
    }
}
catch {
    Write-Error ("MOOSA setup failed: " + $_.Exception.Message)
}
finally {
    if ($NeedCleanup -and $null -ne $Owner) {
        try { Remove-MoosaConfig $Owner; Write-Host 'Failed temporary profile cleaned up.' }
        catch { Write-Warning 'Failed to delete the temporary server profile; the random password was not shared.' }
    }
}
