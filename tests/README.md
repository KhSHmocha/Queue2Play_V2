Run from the project folder in PowerShell with a Java JDK on PATH:

```powershell
$sources = Get-ChildItem -LiteralPath . -Filter '*.java' | Select-Object -ExpandProperty FullName
$testBuild = Join-Path (Get-Location) ('test-run-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testBuild | Out-Null
javac -encoding UTF-8 -d $testBuild $sources tests/BehaviorRegressionTest.java
java "-Djava.io.tmpdir=$testBuild" -cp $testBuild BehaviorRegressionTest
```

Responsive list and discard-confirmation checks:

```powershell
java "-Djava.io.tmpdir=$testBuild" -cp $testBuild BehaviorRegressionTest responsive
```

These checks cover height-based catalog and audit pages, complete wrapped records,
narrow columns, centered helpers, usable pagination keys, admin/customer queue
page navigation, and preserving game, stock, cart and registration drafts after No.
The full suite includes these checks. No production data is used.

`run.ps1` passes the console's window height and width to Java. Without the launcher,
Java uses `LINES`/`COLUMNS` when provided, otherwise 50 rows/120 columns.
Use `-Dq2p.height=36 -Dq2p.width=80` to specify a terminal size explicitly.
Pages reserve space for headings, status, table headings, choices, helpers and prompt.
Catalog pages contain at most nine games. Narrow tables wrap in smaller columns;
below 60 columns records use an indented continuation layout to remain readable.
An individual record taller than the space available stays together on its own page.

Add Game editing/category checks can also run on their own:

```powershell
java "-Djava.io.tmpdir=$testBuild" -cp $testBuild BehaviorRegressionTest editing
```

These checks edit each game field independently, preserve unchanged values and
values rejected by validation, return directly to the summary, keep the summary's
Back to Available Copies option, reject duplicate title/platform combinations,
save/reload edited values, and cancel or leave without saving a draft. Choice
layout checks keep actions, cancellation and navigation in separate categories,
including registration, inventory and checkout.

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

Queue display tests cover predicted processing positions, the current VIP streak,
waiting requests, shared-stock shortages, customer/admin agreement, Order Submitted,
and recalculation after approval, cancellation, restocking, returns and membership
changes. Preview calculations must leave real stock, queue order and streak unchanged.

Return tests cover nine-row pages, earliest-due ordering, single and multiple
current-page selections, duplicate/invalid selections, customer/game searches,
no matches, clear search, confirmation and cancellation, individual late fees,
full-quantity stock restoration, shared-game returns, reload, double-return
rejection, and rollback of the entire batch when its save fails. Return table
rendering is checked at the existing 95-column width.

Terminal positioning tests cover horizontal margins, prompts, top-of-terminal
clearing, and plain output at 120, 81, 60, and 40 columns. To run only layout
checks without saving transaction files, append `layout` to the Java command:

```powershell
java "-Djava.io.tmpdir=$testBuild" "-Dq2p.ascii=true" "-Dq2p.color=false" -cp $testBuild BehaviorRegressionTest layout
```

Navigation tests also cover empty screens staying visible until a named Back or
Dashboard action, retries without redrawing the screen, B for Back, L for Logout,
Q for Exit, T for sort, C for checkout cancellation, V for queue viewing, and E
for editing an order. Adding another game retains the selected platform; switching
platforms retains the cart, and a new order starts with platform selection.

Rental navigation also checks Finish Selection from the game list and platform
selection, returning to Edit Order, and Yes/No/invalid answers before discarding
a cart for Dashboard. Panel checks cover centered rows, up to three choices with
six-space gaps, one blank row between categories, right-aligned page/cart totals,
long choices at narrow widths, restored platform colors, violet Browse All Games,
and removal of generic tutorial text and redundant rental cancellation options.

Menus use vertical numbered lists with aligned labels. Options boxes have one
blank row at the top and bottom, and the pink input separator appears below
the box. Tests cover the landing page, both dashboards, inventory, platform
selection, active rentals, boxed login details/navigation, registration prompts,
and the grouped checkout keys. Existing checkout retry and batch tests still
check that changing the input rendering does not repeat results or status boxes.

Receipt navigation tests check that R leaves the receipt visible, invalid input
retries without redrawing the queue, B returns to Checkout, and M goes directly
to Dashboard. Both empty and populated receipts preserve rental/session state.

Large transaction display checks can also run separately:

```powershell
java "-Djava.io.tmpdir=$testBuild" -cp $testBuild BehaviorRegressionTest pagination
```

Receipts and processed return results use N/P pages. Large checkout batch previews
use F/P pages, preserving N/NO for declining and Y/YES for approving the whole
batch. These tests display forty records at 120, 81, 60 and 40 columns and at
50, 36, 30, 24 and 20 rows, including the navigation and prompt. They check
complete titles, every record, full-session totals, usable keys, repeated invalid
input, Back/Dashboard and unchanged queue, stock and VIP state. Short screens use
a compact heading; narrow transaction records wrap across the available width.
Invalid result navigation clears the retry area before showing the error and
choices, keeping repeated errors from accumulating above the prompt.

Receipt metadata (administrator and session time) is separate from transaction
rows. Empty receipts count zero records; cancellations have a STATUS column
instead of occupying DUE. Compact transaction records retain field labels.
Return confirmations use F/P pages, keeping Y/YES and N/NO for the entire
selection. Order Summary and Edit Order use N/P; cart line numbers stay tied
to the whole cart so removing a line retains the existing selection behavior.
The pagination checks include these screens, full wrapped titles, short/narrow
viewports, invalid-input retries, and unchanged drafts/rental state.

Completed-result tests run the real navigation loop for Order Submitted, the
last queue cancellation, VIP upgrade, checkout exit receipts, processed returns,
and Account Created. They check M Dashboard/Main Menu, invalid keys without
result redraws, and B Return List only when rentals remain. Older transaction
tests mock this new result-navigation step so their business-rule inputs remain
focused on the transaction; the dedicated checks provide its scripted input.

Back, Main Menu, Dashboard and pagination navigation options are white.
Destructive actions (Logout, Cancel, Exit, Delete, Remove and Discard) are red.
Positive confirmation actions are green. Regular options have bold mustard
(#D4A017) bracketed keys and white labels; colors follow the label rather than
the shortcut, so Receipt and Remove do not share a destructive style.
Browse All Games labels stay violet, platform labels keep blue/green/red, and
Yes/No labels retain green/red. Helpers, borders, separators, prompts and status
colors are unchanged.

Login tests check attempt numbers, remaining attempts, and all countdown values
from 30 to zero. Scripted countdown tests verify carriage-return updates and mock
the one-second wait. The separate `cooldown` command above verifies real elapsed
time. Choice colors and visible panel widths are checked with colors enabled or
disabled; the `layout` command includes these checks. Interactive password masking
and countdown appearance in an actual terminal still require a manual check.
