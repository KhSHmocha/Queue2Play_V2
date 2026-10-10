# Queue2Play Beginner Java Refactor

Date: October 10, 2026.

Reference: `JAVA_DSA_BEGINNER_AGENT_SKILL.md` in this directory.

The user requested simpler Java/DSA code while keeping the current UI/TUI design. During this task, the user explicitly approved retaining existing APIs needed for the skill's recommended features, including secure password hashing and safe storage, while simplifying the surrounding code.

## Changes

- Replaced all three anonymous comparator sorts with ordinary insertion-sort loops: queue arrival ordering, membership refresh, and loaded rental ordering.
- Retained the custom doubly linked checkout queue, VIP/regular FIFO groups, stock eligibility checks, and regular preference after three VIP checkouts.
- Removed unused queue `peek`/`dequeue` helpers rather than changing actual eligibility-based processing to front-only processing.
- Retained manual catalog insertion sort, linear search, stacks, hash-based lookup, and the active-rental priority queue.
- Replaced ternary expressions throughout application code with ordinary `if/else` statements.
- Replaced custom varargs declarations with explicit string arrays; formatting now receives an already formatted string.
- Replaced multi-catch clauses with ordinary separate catch blocks.
- Replaced mixed-type rollback arrays and identity-keyed snapshot maps with typed arrays aligned with saved object lists. Restoration still updates the original objects and shared collections.
- Removed the first, discarded history push during rental loading. History is now rebuilt with one user-clear loop and one rental-push loop, rather than scanning every rental for every user.
- Simplified cart traversal from nested `Map.Entry` types to game keys and quantities.
- Removed the optional catalog-less service constructor and made the catalog reference final.
- Removed redundant quantity validation within checkout validation and unreachable post-service stock checks, while retaining authoritative service and save/load safeguards.
- Removed unused imports, the unused stock calculation, unused amount-reading and rendering helpers, an unused browse-menu overload, and unused save/return wrappers.
- Expanded compact conditions, loops, and helpers into readable blocks. Corrected menu try-block indentation and stale stock comments.
- Preserved platform ID rules, rental pricing and rounding, fees, saved record layouts, and account behavior.

## UI/TUI preservation

The refactor preserves current text, artwork, colors, box borders, table widths, navigation choices, wrapping, and input behavior. The renderer still uses its existing supporting APIs because preserving the established design is an explicit user requirement.

The verification harness calls 70 screen methods twice per mode and compares original/refactored output byte-for-byte across eight modes:

- Unicode and ASCII borders.
- Color requested on and off; effective color also respects the existing `NO_COLOR` environment setting.
- Terminal widths 40 and 120.

These are output comparisons, not a claim of manually operating every screen in a native terminal. Windows key-reading/password masking was not exercised interactively.

## Supporting APIs retained

This is not a claim that every remaining Java API appears verbatim in the skill examples. Under the user's explicit clarification, the existing supporting APIs remain for:

- PBKDF2 password hashing, random salts, and verification.
- Dates, due ordering, elapsed time, and formatting.
- UTF-8 rendering, regex validation/styling, and Windows console input.
- File I/O, locks, recoverable journal commits, automatic resource closing, and synchronized commit protection.
- Built-in collection operations and duplicate detection.

The code scan confirms no ternary operators, custom varargs declarations, comparator classes, built-in `.sort()` calls, `IdentityHashMap`, mixed `Object[]` snapshots, `Map.Entry` traversal, multi-catch clauses, streams, lambdas, or `do/while` loops remain in application code.

## Verification

All 16 application source files compile using JDK 24.0.2. A separate compilation with `-Xlint:all` completed without diagnostics before the final indentation-only adjustment.

Both original and refactored builds pass the same 84 behavior checks, covering:

- Queue empty/one/multiple-node behavior; VIP and regular ordering; fairness; stock skipping; membership upgrade; cancellation.
- Catalog search, all four sorting choices, stable ties, preserved catalog order, and invalid titles/IDs.
- Regular/VIP pricing and unit-first rounding.
- Checkout stock/history/queue updates and active duplicate rejection.
- Exact due time, seconds/fractions overdue, return stock/fees, double-return rejection, and negative-stock rejection.
- Queue, active rental, completed return, history, and fee save/reload.
- Save failure after each of five file-replacement positions: account, stock, rental field, and file-byte rollback.
- External-file modification detection and correct/incorrect password verification.

Additional checks:

- Original/refactored UI output matches byte-for-byte in all eight modes.
- Startup, invalid menu input, canceled exit, and confirmed exit output match byte-for-byte using redirected input with JDK's base console provider.
- Process-halting recovery passes at each of the five replacement positions on both builds. Reload restores all original live file bytes and cleans the journal.
- Tests run against copied data directories, not the project's live data.
- SHA-256 comparison confirms all 11 original data/backup/lock files remain unchanged.

## Artifacts and recovery

Original Java sources, the combined review diff, test harnesses, output comparisons, and verification results are stored outside the project source directory:

`C:\Users\KohaiSoHai\.codex\visualizations\2026\10\10\01a12497-350d-79f0-904e-b70b0468850b\queue2play-refactor`

The PowerShell launcher was not changed. Historical analysis documents were not rewritten. No new Java library, framework, or application dependency was introduced.

## Practical limitations

Manual insertion sort is O(n²) in the worst case. Replacing the built-in ordering operations with explicit lesson-style loops prioritizes beginner readability and course alignment for this small console project.

Known existing policies such as restoring embedded demo credentials and preserving historical account backups were not redesigned in this behavior-preserving refactor. The earlier project analysis describes those limitations.
