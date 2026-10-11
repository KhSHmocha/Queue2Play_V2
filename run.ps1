# Open this folder in VS Code and enter .\run.ps1 in the PowerShell terminal.
# This script compiles and runs QUEUE2PLAY. Java JDK must be installed.

$ErrorActionPreference = 'Stop'
# QUEUE2PLAY draws its screens with box-drawing characters, so use a UTF-8 console.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$buildFolder = Join-Path $PSScriptRoot '.build'
New-Item -ItemType Directory -Path $buildFolder -Force | Out-Null
$sources = Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -d $buildFolder $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
$terminalColumns = [Console]::WindowWidth
if ($terminalColumns -lt 40) { $terminalColumns = 120 }
$terminalRows = [Console]::WindowHeight
if ($terminalRows -lt 20) { $terminalRows = 50 }
& java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dq2p.width=$terminalColumns" "-Dq2p.height=$terminalRows" ("-Dq2p.dataDir=" + (Join-Path $PSScriptRoot 'data')) -cp $buildFolder Main
if ($LASTEXITCODE -ne 0) { throw 'QUEUE2PLAY stopped because of a data or startup error. Read the message above.' }
