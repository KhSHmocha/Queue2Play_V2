Run from the project folder in PowerShell with a Java JDK on PATH:

```powershell
$sources = Get-ChildItem -LiteralPath . -Filter '*.java' | Select-Object -ExpandProperty FullName
$testBuild = Join-Path (Get-Location) ('test-run-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testBuild | Out-Null
javac -encoding UTF-8 -d $testBuild $sources tests/BehaviorRegressionTest.java
java "-Djava.io.tmpdir=$testBuild" -cp $testBuild BehaviorRegressionTest
```

The tests use scripted terminal choices and fresh data folders in the disposable `test-run-...` directory. They cover catalog search, all sort modes and stability, pagination, selection, quantities, duration, order cancellation, queue fairness, approval, cancellation, persistence, and ANSI platform colors. Checkout tests also cover boxed and numbered tape slips, separate status/message/input boxes, 81-column layout, retry output, session totals, batch preview and processing order, ready/wait labels, redraws, and navigation. They never load or change the production `data` folder, `.build`, or `.git`. The test-run directory can be deleted after the run.

Account tests cover registration limits, duplicate identifiers, field editing,
password confirmation, cancellation, saving/reloading, older account formats,
login messages, navigation, and the three-attempt cooldown across identifiers
and menu returns. Empty fields and navigation do not count as authentication
attempts. Scripted tests count the thirty one-second waits without sleeping.
Run just these checks with:

```powershell
java "-Djava.io.tmpdir=$testBuild" -cp $testBuild BehaviorRegressionTest accounts
```

To check the actual thirty-second wait, including when cosmetic animations are
disabled, run this separate check (it takes at least thirty seconds):

```powershell
java "-Djava.io.tmpdir=$testBuild" -cp $testBuild BehaviorRegressionTest cooldown
```

Password masking still needs a manual check in an interactive terminal; scripted
tests do not exercise Windows key capture.

Terminal positioning tests cover horizontal margins, prompts, top-of-terminal
clearing, and plain output at 120, 81, 60, and 40 columns. To run only layout
checks without saving transaction files, append `layout` to the Java command:

```powershell
java "-Djava.io.tmpdir=$testBuild" -Dq2p.ascii=true -Dq2p.color=false -cp $testBuild BehaviorRegressionTest layout
```
