# msi 안에 WSL 코어 묶음이 실렸는지 본다(core-ui-split-design.md 6.16 의 4a 정정). 릴리스 잡과 Desktop CI 의 윈도우 잡이 함께 쓴다.
#
#   verify-msi-carries-wsl-core.ps1 -AppImage <createDistributable 의 앱 디렉토리> -Msi <msi> -SuppliedBundle <놓은 묶음>
#
# 두 자리를 본다.
#   1. app-image — Compose 가 리소스를 어디에 놓는지의 근거. 화면이 런타임에 compose.application.resources.dir 로 받는 자리가 이
#      디렉토리의 app\resources 다(jpackage 의 $APPDIR\resources).
#   2. msi — 관리 설치(msiexec /a)로 풀어 같은 상대 경로에 같은 크기의 파일이 있는지. checkBundledWslCore 는 "놓은 자리에 있는가" 만
#      본다. 자리 이름(windows-x64)이 Compose 가 고르는 이름과 어긋나면 그 검사는 통과한 채 묶음 없는 msi 가 나간다.
param(
    [Parameter(Mandatory = $true)][string]$AppImage,
    [Parameter(Mandatory = $true)][string]$Msi,
    [Parameter(Mandatory = $true)][string]$SuppliedBundle
)
$ErrorActionPreference = 'Stop'

$bundleRelativePath = 'app\resources\wsl-core\kyuchestration-wsl-core.tar.gz'
$suppliedSize = (Get-Item -LiteralPath $SuppliedBundle).Length

function Assert-OneBundleAt([string]$root, [string]$where) {
    $found = @(Get-ChildItem -LiteralPath $root -Recurse -File -Filter 'kyuchestration-wsl-core.tar.gz')
    if ($found.Count -ne 1) {
        Write-Output "::error::$where 안의 WSL 코어 묶음이 하나여야 하는데 $($found.Count) 개다"
        $found | ForEach-Object { Write-Output $_.FullName }
        exit 1
    }
    $bundle = $found[0]
    Write-Output "$where : $($bundle.FullName) ($($bundle.Length) 바이트)"
    if (-not $bundle.FullName.EndsWith($bundleRelativePath, [System.StringComparison]::OrdinalIgnoreCase)) {
        Write-Output "::error::$where 의 묶음이 화면이 찾는 자리(…\$bundleRelativePath)에 있지 않다"
        exit 1
    }
    if ($bundle.Length -ne $suppliedSize) {
        Write-Output "::error::$where 의 묶음 크기 $($bundle.Length) 가 놓은 묶음 $suppliedSize 와 다르다"
        exit 1
    }
}

Assert-OneBundleAt $AppImage 'app-image'

$extracted = Join-Path $env:RUNNER_TEMP 'msi-administrative-image'
if (Test-Path -LiteralPath $extracted) { Remove-Item -LiteralPath $extracted -Recurse -Force }
# 관리 설치는 설치하지 않고 msi 의 파일을 TARGETDIR 아래에 풀기만 한다 — 러너의 사용자 프로필을 건드리지 않는다.
$msiexec = Start-Process -FilePath 'msiexec.exe' `
    -ArgumentList @('/a', "`"$((Resolve-Path -LiteralPath $Msi).Path)`"", '/qn', "TARGETDIR=`"$extracted`"") `
    -Wait -PassThru
if ($msiexec.ExitCode -ne 0) {
    Write-Output "::error::msiexec /a 가 $($msiexec.ExitCode) 로 끝났다"
    exit 1
}
Assert-OneBundleAt $extracted 'msi'
Write-Output 'msi 에 WSL 코어 묶음이 실렸다'
