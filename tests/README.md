Run from the project folder in PowerShell with a Java JDK on PATH:

```powershell
$sources = Get-ChildItem -LiteralPath . -Filter '*.java' | Select-Object -ExpandProperty FullName
javac -encoding UTF-8 -d .build $sources tests/BehaviorRegressionTest.java
java -cp .build BehaviorRegressionTest
```

The tests use scripted terminal choices and fresh data folders under `.build`. They cover catalog search, all sort modes and stability, pagination, selection, quantities, duration, order cancellation, queue fairness, approval, cancellation, persistence, and ANSI platform colors. They never load or change the production `data` folder.
