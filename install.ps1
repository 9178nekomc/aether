<#
.SYNOPSIS
    GitHub AI Agent Pro —— Windows 安装脚本 (v2.1.0)

.DESCRIPTION
    安装后即可在任意目录（PowerShell / CMD / Windows Terminal）用一条命令启动：

        github-agent            # 在当前目录启动（首次会询问是否允许访问该工作区）
        github-agent D:\proj    # 在指定目录启动
        gha                     # 短别名

    目录布局：
        %USERPROFILE%\.github-agent\lib\github-agent.jar   程序本体
        %USERPROFILE%\.github-agent\config.properties      API Key / Token / 主题
        %USERPROFILE%\.github-agent\trusted-dirs.json      已授权的工作区目录
        %USERPROFILE%\.github-agent\bin\github-agent.cmd   启动器（已加入用户 PATH）
        %USERPROFILE%\.github-agent\bin\gha.cmd            短别名

.PARAMETER JarPath
    使用本地已构建好的 fat jar 安装（默认自动查找 target\github-agent.jar，其次 Maven 构建，最后从 Release 下载）

.PARAMETER Root
    安装根目录（默认 $env:USERPROFILE\.github-agent）

.PARAMETER NoPath
    不修改用户 PATH，只在标准输出打印需要手动添加的路径

.PARAMETER Uninstall
    卸载（默认保留配置与信任记录）

.PARAMETER Purge
    与 -Uninstall 搭配，连同配置与信任记录一起删除

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\install.ps1

.EXAMPLE
    irm https://raw.githubusercontent.com/9178nekomc/github-Agent/main/install.ps1 | iex
#>
[CmdletBinding()]
param(
    [string]$JarPath,
    [string]$Root,
    [string]$BinDir,
    [switch]$NoPath,
    [switch]$Uninstall,
    [switch]$Purge
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$ScriptVersion = '2.1.0'
$Repo = '9178nekomc/github-Agent'
$ReleaseJarUrl = "https://github.com/$Repo/releases/latest/download/github-agent.jar"

if (-not $Root -or $Root.Trim() -eq '') {
    if ($env:GITHUB_AGENT_HOME -and $env:GITHUB_AGENT_HOME.Trim() -ne '') { $Root = $env:GITHUB_AGENT_HOME }
    else { $Root = Join-Path $env:USERPROFILE '.github-agent' }
}
if (-not $BinDir -or $BinDir.Trim() -eq '') {
    $BinDir = Join-Path $Root 'bin'
}

function Write-Ok($msg)   { Write-Host "  " -NoNewline; Write-Host "OK " -ForegroundColor Green -NoNewline; Write-Host $msg }
function Write-Info($msg) { Write-Host "  i  " -ForegroundColor Cyan -NoNewline; Write-Host $msg }
function Write-Warn2($msg){ Write-Host "  !  " -ForegroundColor Yellow -NoNewline; Write-Host $msg }
function Fail($msg) { Write-Host "  X  " -ForegroundColor Red -NoNewline; Write-Host $msg; exit 1 }

# ------------------------------------------------------------------ 卸载
if ($Uninstall) {
    Write-Host ""
    Write-Host "卸载 GitHub AI Agent Pro" -ForegroundColor White
    foreach ($name in @('github-agent.cmd', 'gha.cmd', 'github-agent.ps1', 'gha.ps1')) {
        $p = Join-Path $BinDir $name
        if (Test-Path $p) { Remove-Item $p -Force; Write-Ok "已移除 $p" }
    }
    $jar = Join-Path $Root 'lib\github-agent.jar'
    if (Test-Path $jar) { Remove-Item $jar -Force; Write-Ok "已移除 $jar" }
    if ($Purge) {
        Remove-Item $Root -Recurse -Force -ErrorAction SilentlyContinue
        Write-Ok "已删除配置目录 $Root"
    } else {
        Write-Warn2 "已保留配置与信任记录: $Root（加 -Purge 可一并删除）"
    }
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath) {
        $parts = $userPath -split ';' | Where-Object { $_ -and $_.TrimEnd('\') -ne $BinDir.TrimEnd('\') }
        [Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User')
        Write-Ok "已从用户 PATH 移除 $BinDir"
    }
    Write-Host ""
    Write-Host "卸载完成。" -ForegroundColor Green
    Write-Host ""
    exit 0
}

# ------------------------------------------------------------------ 环境检查
Write-Host ""
Write-Host "================================================================" -ForegroundColor DarkCyan
Write-Host "   GitHub AI Agent Pro  ·  Windows 安装程序  v$ScriptVersion" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor DarkCyan

$javaExe = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path $candidate) { $javaExe = $candidate }
}
if (-not $javaExe) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $javaExe = $cmd.Source }
}
if (-not $javaExe) {
    Fail "未找到 Java。请先安装 JDK/JRE 21+ (https://adoptium.net/)，安装后重开终端再执行本脚本。"
}

$javaVersionText = ''
try {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $javaExe
    $psi.Arguments = '-version'
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $javaVersionText = $proc.StandardError.ReadToEnd() + $proc.StandardOutput.ReadToEnd()
    $proc.WaitForExit()
} catch {
    $javaVersionText = ''
}

$javaMajor = 0
$m = [regex]::Match($javaVersionText, 'version "(\d+)(?:\.(\d+))?')
if ($m.Success) {
    $javaMajor = [int]$m.Groups[1].Value
    if ($javaMajor -eq 1 -and $m.Groups[2].Success) { $javaMajor = [int]$m.Groups[2].Value }
}
if ($javaMajor -gt 0 -and $javaMajor -lt 21) {
    Fail "检测到 Java $javaMajor，本程序需要 Java 21 或更高版本：https://adoptium.net/"
}
Write-Ok "Java 环境正常（$javaExe）"

# ------------------------------------------------------------------ 获取 jar
$libDir = Join-Path $Root 'lib'
New-Item -ItemType Directory -Force -Path $libDir | Out-Null
$targetJar = Join-Path $libDir 'github-agent.jar'
$tempJar = Join-Path ([System.IO.Path]::GetTempPath()) ("github-agent-" + [guid]::NewGuid().ToString('N') + ".jar")

$resolved = $false
if ($JarPath -and $JarPath.Trim() -ne '') {
    if (-not (Test-Path $JarPath)) { Fail "指定的 jar 不存在: $JarPath" }
    Copy-Item $JarPath $tempJar -Force
    Write-Info "使用本地 jar: $JarPath"
    $resolved = $true
}
if (-not $resolved -and $PSScriptRoot) {
    $built = Join-Path $PSScriptRoot 'target\github-agent.jar'
    if (Test-Path $built) {
        Copy-Item $built $tempJar -Force
        Write-Info "使用已构建产物: $built"
        $resolved = $true
    }
    $pom = Join-Path $PSScriptRoot 'pom.xml'
    if (-not $resolved -and (Test-Path $pom)) {
        $mvn = Get-Command mvn -ErrorAction SilentlyContinue
        if ($mvn) {
            Write-Info "检测到源码工程，正在用 Maven 构建（首次会下载依赖，请稍候）..."
            Push-Location $PSScriptRoot
            try {
                & $mvn.Source -q -B clean package -DskipTests
                if ($LASTEXITCODE -ne 0) { Fail "Maven 构建失败（exit $LASTEXITCODE）" }
            } finally {
                Pop-Location
            }
            Copy-Item $built $tempJar -Force
            Write-Ok "构建完成"
            $resolved = $true
        } else {
            Write-Warn2 "检测到源码但未安装 Maven，改为下载发行版。"
        }
    }
}
if (-not $resolved) {
    Write-Info "正在下载发行版: $ReleaseJarUrl"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $ReleaseJarUrl -OutFile $tempJar -UseBasicParsing
    } catch {
        Fail "下载失败：$($_.Exception.Message)`n 请检查网络，或克隆仓库后在本目录内运行本脚本。"
    }
    if (-not (Test-Path $tempJar) -or (Get-Item $tempJar).Length -lt 100000) {
        Fail "下载的文件不完整，请稍后重试。"
    }
    $resolved = $true
}

Move-Item -Force $tempJar $targetJar
$sizeMb = [math]::Round((Get-Item $targetJar).Length / 1MB, 1)
Write-Ok "程序本体已安装: $targetJar ($sizeMb MB)"

# ------------------------------------------------------------------ 生成启动器
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null

$cmdBody = @"
@echo off
rem github-agent launcher (generated by installer v$ScriptVersion)
setlocal
if not defined GITHUB_AGENT_HOME set "GITHUB_AGENT_HOME=$Root"
set "JAR=$targetJar"
set "JAVA_BIN=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not exist "%JAR%" (
  echo [x] 找不到 github-agent.jar: %JAR%
  echo     请重新运行安装脚本 install.ps1
  exit /b 127
)
"%JAVA_BIN%" -jar "%JAR%" %*
"@

# cmd 文件必须用系统 ANSI 编码写入，否则含中文路径时会被破坏
$ansi = [System.Text.Encoding]::Default
[System.IO.File]::WriteAllText((Join-Path $BinDir 'github-agent.cmd'), ($cmdBody -replace "`r?`n", "`r`n"), $ansi)
[System.IO.File]::WriteAllText((Join-Path $BinDir 'gha.cmd'), ($cmdBody -replace "`r?`n", "`r`n"), $ansi)
Write-Ok "启动器已创建: $(Join-Path $BinDir 'github-agent.cmd')（别名: gha.cmd）"

# ------------------------------------------------------------------ PATH
if ($NoPath) {
    Write-Warn2 "已跳过 PATH 配置（-NoPath）。手动添加：$BinDir"
} else {
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not $userPath) { $userPath = '' }
    $exists = $false
    foreach ($p in ($userPath -split ';')) {
        if ($p -and $p.TrimEnd('\') -ieq $BinDir.TrimEnd('\')) { $exists = $true; break }
    }
    if ($exists) {
        Write-Ok "用户 PATH 中已包含 $BinDir"
    } else {
        $newPath = $userPath
        if ($newPath -and -not $newPath.EndsWith(';')) { $newPath = $newPath + ';' }
        [Environment]::SetEnvironmentVariable('Path', ($newPath + $BinDir), 'User')
        Write-Ok "已把 $BinDir 加入用户 PATH（新开的终端生效）"
    }
    if (($env:Path -split ';') -notcontains $BinDir) {
        $env:Path = $env:Path + ';' + $BinDir
    }
}

# ------------------------------------------------------------------ 自检
Write-Host ""
$versionCheck = & (Join-Path $BinDir 'github-agent.cmd') --version
if ($LASTEXITCODE -eq 0 -and $versionCheck) {
    Write-Ok "自检通过：$($versionCheck | Select-Object -First 1)"
} else {
    Write-Warn2 "自检未能运行，请手动执行: & '$BinDir\github-agent.cmd' --version"
}

Write-Host ""
Write-Host "安装完成！" -ForegroundColor Green
Write-Host ""
Write-Host "  下一步：" -ForegroundColor White
Write-Host "    cd 你的项目目录"
Write-Host "    github-agent        " -NoNewline -ForegroundColor Cyan
Write-Host "# 启动（首次会询问是否允许访问该工作区，并选择权限模式）"
Write-Host ""
Write-Host "  常用参数：" -ForegroundColor White
Write-Host "    github-agent D:\code\proj          # 指定工作区"
Write-Host "    github-agent -p `"重构这个模块`"     # 一次性任务"
Write-Host "    github-agent --doctor             # 环境自检"
Write-Host "    github-agent --help               # 全部参数"
Write-Host "    github-agent --forget             # 撤销当前目录授权"
Write-Host ""
Write-Host "  配置文件: $(Join-Path $Root 'config.properties')" -ForegroundColor DarkGray
Write-Host "  信任记录: $(Join-Path $Root 'trusted-dirs.json')" -ForegroundColor DarkGray
Write-Host "  卸载: powershell -ExecutionPolicy Bypass -File install.ps1 -Uninstall [-Purge]" -ForegroundColor DarkGray
Write-Host ""
