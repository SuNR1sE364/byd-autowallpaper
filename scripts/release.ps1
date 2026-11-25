[CmdletBinding()]
param(
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

try {
    [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
} catch {
    # 在非交互式宿主中忽略控制台编码设置失败
}

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & git @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    $text = ($output | ForEach-Object { "$_" }) -join [Environment]::NewLine
    $text = $text.Trim()
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "git $($Arguments -join ' ') 执行失败 (exit $exitCode): $text"
    }
    return [PSCustomObject]@{
        ExitCode = $exitCode
        Output   = $text
    }
}

# 定位仓库根目录
$repoCheck = Invoke-Git -Arguments @('rev-parse', '--show-toplevel') -AllowFailure
if ($repoCheck.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($repoCheck.Output)) {
    Write-Error '错误: 当前目录不在 Git 仓库中。'
    exit 1
}
$repoRoot = $repoCheck.Output
Set-Location -Path $repoRoot

$gradleFile = Join-Path $repoRoot 'app/build.gradle'
if (-not (Test-Path -LiteralPath $gradleFile)) {
    Write-Error "错误: 未找到 $gradleFile"
    exit 1
}

# 检查当前分支（不允许 detached HEAD）
$branchCheck = Invoke-Git -Arguments @('symbolic-ref', '--quiet', '--short', 'HEAD') -AllowFailure
if ($branchCheck.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($branchCheck.Output)) {
    Write-Error '错误: 当前处于游离 HEAD 状态，请先切换到发布分支（例如 master）。'
    exit 1
}
$currentBranch = $branchCheck.Output

# 检查工作区是否干净
$statusCheck = Invoke-Git -Arguments @('status', '--porcelain')
if (-not [string]::IsNullOrWhiteSpace($statusCheck.Output)) {
    Write-Error "错误: 当前 Git 工作区存在未提交的修改，请先提交或暂存后再运行发布脚本。`n$($statusCheck.Output)"
    exit 1
}

# 读取 app/build.gradle 中的 versionName 与 versionCode
$gradleContent = [System.IO.File]::ReadAllText($gradleFile, [System.Text.UTF8Encoding]::new($false))
$versionNameMatch = [regex]::Match($gradleContent, '(?m)^([ \t]*versionName[ \t]+)["'']([^"'']+)["'']')
$versionCodeMatch = [regex]::Match($gradleContent, '(?m)^([ \t]*versionCode[ \t]+)([0-9]+)')

if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) {
    Write-Error '错误: 无法从 app/build.gradle 解析 versionName 或 versionCode。'
    exit 1
}

$currentVersion = $versionNameMatch.Groups[2].Value
$currentCode = [int]$versionCodeMatch.Groups[2].Value

# 解析语义化版本号: MAJOR.MINOR.PATCH[-PRERELEASE]
$semverRegex = '^([0-9]+)\.([0-9]+)\.([0-9]+)(?:-([0-9A-Za-z.-]+))?$'
$semverMatch = [regex]::Match($currentVersion, $semverRegex)
if (-not $semverMatch.Success) {
    Write-Error "错误: 当前版本号 '$currentVersion' 不符合 SemVer 格式 (X.Y.Z 或 X.Y.Z-prerelease)。"
    exit 1
}

$major = [int]$semverMatch.Groups[1].Value
$minor = [int]$semverMatch.Groups[2].Value
$patch = [int]$semverMatch.Groups[3].Value
$prerelease = $semverMatch.Groups[4].Value

# 计算候选版本号
$candidateKeep = $currentVersion

if (-not [string]::IsNullOrEmpty($prerelease)) {
    $patchLabel = 'release (转正式版)'
    $candidatePatch = "$major.$minor.$patch"
} else {
    $patchLabel = 'patch             '
    $candidatePatch = "$major.$minor.$($patch + 1)"
}

$candidateMinor = "$major.$($minor + 1).0"
$candidateMajor = "$($major + 1).0.0"

# 计算 alpha 候选版本
if ([string]::IsNullOrEmpty($prerelease)) {
    $candidateAlpha = "$major.$minor.$($patch + 1)-alpha.1"
} elseif ($prerelease -match '^alpha\.([0-9]+)$') {
    $candidateAlpha = "$major.$minor.$patch-alpha.$([int]$Matches[1] + 1)"
} elseif ($prerelease -eq 'alpha') {
    $candidateAlpha = "$major.$minor.$patch-alpha.1"
} else {
    $candidateAlpha = "$major.$minor.$($patch + 1)-alpha.1"
}

# 计算 beta 候选版本
if ([string]::IsNullOrEmpty($prerelease)) {
    $candidateBeta = "$major.$minor.$($patch + 1)-beta.1"
} elseif ($prerelease -match '^alpha(?:\.[0-9]+)?$') {
    $candidateBeta = "$major.$minor.$patch-beta.1"
} elseif ($prerelease -match '^beta\.([0-9]+)$') {
    $candidateBeta = "$major.$minor.$patch-beta.$([int]$Matches[1] + 1)"
} elseif ($prerelease -eq 'beta') {
    $candidateBeta = "$major.$minor.$patch-beta.1"
} else {
    $candidateBeta = "$major.$minor.$($patch + 1)-beta.1"
}

Write-Host '=========================================='
Write-Host '  BYD Auto Dark Wallpaper 版本发布助手'
Write-Host '=========================================='
if ($DryRun) {
    Write-Host '  [演练模式 -DryRun：不会修改文件或推送]' -ForegroundColor Yellow
}
Write-Host "当前分支:    $currentBranch"
Write-Host "当前版本:    $currentVersion (versionCode: $currentCode)"
Write-Host ''
Write-Host '请选择目标版本号:'
Write-Host "  [1] 不变               -> $candidateKeep"
Write-Host "  [2] $patchLabel -> $candidatePatch"
Write-Host "  [3] minor              -> $candidateMinor"
Write-Host "  [4] major              -> $candidateMajor"
Write-Host "  [5] alpha              -> $candidateAlpha"
Write-Host "  [6] beta               -> $candidateBeta"
Write-Host '  [7] 自行输入'
Write-Host '  [0] 取消'
Write-Host ''

$targetVersion = ''
while ([string]::IsNullOrEmpty($targetVersion)) {
    $rawChoice = Read-Host '请输入选项编号 [0-7]'
    if ($null -eq $rawChoice) {
        Write-Host '已取消发布。'
        exit 0
    }
    $choice = $rawChoice.Trim()
    switch ($choice) {
        '1' { $targetVersion = $candidateKeep }
        '2' { $targetVersion = $candidatePatch }
        '3' { $targetVersion = $candidateMinor }
        '4' { $targetVersion = $candidateMajor }
        '5' { $targetVersion = $candidateAlpha }
        '6' { $targetVersion = $candidateBeta }
        '7' {
            while ($true) {
                $customInput = Read-Host '请输入自定义版本号 (例如 2.2.0 或 2.2.0-rc.1，输入 q 返回)'
                if ($null -eq $customInput -or $customInput -eq 'q' -or $customInput -eq 'Q') {
                    break
                }
                $customClean = $customInput.Trim() -replace '^[vV]', ''
                if ($customClean -match $semverRegex) {
                    $targetVersion = $customClean
                    break
                } else {
                    Write-Host '格式无效：请输入类似 2.2.0 或 2.2.0-alpha.1 的 SemVer 版本号。' -ForegroundColor Red
                }
            }
        }
        '0' {
            Write-Host '已取消发布。'
            exit 0
        }
        default {
            Write-Host "无效选项 '$choice'，请输入 0 到 7 之间的数字。" -ForegroundColor Red
        }
    }
}

$tagName = "v$targetVersion"
$versionChanged = ($targetVersion -ne $currentVersion)
$targetCode = if ($versionChanged) { $currentCode + 1 } else { $currentCode }

# 检查本地与远程 Tag 是否已存在
$localTagCheck = Invoke-Git -Arguments @('rev-parse', '-q', '--verify', "refs/tags/$tagName") -AllowFailure
if ($localTagCheck.ExitCode -eq 0) {
    Write-Error "错误: 本地已存在标签 '$tagName'，请先确认版本号或清理旧标签。"
    exit 1
}

$remoteName = 'origin'
$remoteUrlCheck = Invoke-Git -Arguments @('remote', 'get-url', $remoteName) -AllowFailure
if ($remoteUrlCheck.ExitCode -ne 0) {
    Write-Error "错误: 未配置名为 '$remoteName' 的远程仓库。"
    exit 1
}

$remoteTagCheck = Invoke-Git -Arguments @('ls-remote', '--tags', $remoteName, "refs/tags/$tagName") -AllowFailure
if ($remoteTagCheck.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($remoteTagCheck.Output)) {
    Write-Error "错误: 远程仓库 '$remoteName' 已存在标签 '$tagName'。"
    exit 1
}

$commitMsg = "release: release $tagName"

Write-Host ''
Write-Host '发布确认:'
Write-Host "  分支:        $currentBranch"
if ($versionChanged) {
    Write-Host "  versionName: $currentVersion -> $targetVersion"
    Write-Host "  versionCode: $currentCode -> $targetCode"
    Write-Host "  提交信息:    $commitMsg"
} else {
    Write-Host "  versionName: $currentVersion (不变)"
    Write-Host "  versionCode: $currentCode (不变)"
    Write-Host '  提交信息:    (无版本改动，跳过 commit)'
}
Write-Host "  标签 (Tag):  $tagName"
Write-Host "  推送目标:    $remoteName ($currentBranch + $tagName)"
Write-Host ''

$rawConfirm = Read-Host '确认执行以上操作? [y/N]'
$confirm = if ($null -eq $rawConfirm) { '' } else { $rawConfirm.Trim() }
if ($confirm -notmatch '^[yY](?:es)?$') {
    Write-Host '已取消发布。'
    exit 0
}

if ($DryRun) {
    Write-Host ''
    Write-Host '[DryRun] 演练完成，未执行实际修改或推送。' -ForegroundColor Yellow
    exit 0
}

# 1. 更新 app/build.gradle 并提交（仅当版本号变化时）
if ($versionChanged) {
    $codeRegex = [regex]::new('(?m)^([ \t]*versionCode[ \t]+)[0-9]+')
    $nameRegex = [regex]::new('(?m)^([ \t]*versionName[ \t]+)["''][^"'']+["'']')
    $updatedContent = $codeRegex.Replace($gradleContent, ('${1}' + $targetCode), 1)
    $updatedContent = $nameRegex.Replace($updatedContent, ('${1}"' + $targetVersion + '"'), 1)
    [System.IO.File]::WriteAllText($gradleFile, $updatedContent, [System.Text.UTF8Encoding]::new($false))

    Invoke-Git -Arguments @('add', 'app/build.gradle') | Out-Null
    Invoke-Git -Arguments @('commit', '-m', $commitMsg) | Out-Null
    Write-Host "已更新 app/build.gradle 并提交: $commitMsg" -ForegroundColor Green
} else {
    Write-Host '版本号保持不变，跳过修改与提交。'
}

# 2. 创建附注标签
Invoke-Git -Arguments @('tag', '-a', $tagName, '-m', "Release $tagName") | Out-Null
Write-Host "已创建标签: $tagName" -ForegroundColor Green

# 3. 推送分支与标签
Write-Host "正在推送分支 $currentBranch 到 $remoteName..."
Invoke-Git -Arguments @('push', $remoteName, $currentBranch) | Out-Null

Write-Host "正在推送标签 $tagName 到 $remoteName..."
Invoke-Git -Arguments @('push', $remoteName, $tagName) | Out-Null

Write-Host ''
Write-Host "发布推送完成！Tag '$tagName' 已推送至 $remoteName，GitHub Actions Release 工作流将自动触发。" -ForegroundColor Green
