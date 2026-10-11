Run from the project folder in PowerShell with a Java JDK on PATH:

```powershell
$sources = Get-ChildItem -LiteralPath . -Filter '*.java' | Select-Object -ExpandProperty FullName
$testBuild = Join-Path (Get-Location) ('test-run-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testBuild | Out-Null
javac -encoding UTF-8 -d $testBuild $sources tests/BehaviorRegressionTest.java
java "-Djava.io.tmpdir=$testBuild" -cp $testBuild BehaviorRegressionTest
```

The tests use scripted terminal choices and fresh data folders in the disposable `test-run-...` directory. They cover catalog search, all sort modes and stability, pagination, selection, quantities, duration, order cancellation, queue fairness, approval, cancellation, persistence, and ANSI platform colors. Checkout tests also cover boxed and numbered tape slips, separate status/message/input boxes, 81-column layout, retry output, session totals, batch preview and processing order, ready/wait labels, redraws, and navigation. They never load or change the production `data` folder, `.build`, or `.git`. The test-run directory can be deleted after the run.
