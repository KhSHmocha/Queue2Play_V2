---
name: java-dsa-beginner-project
version: 1.0.0
description: >
  A beginner-friendly Java and Data Structures & Algorithms coding guide for an AI
  agent working on a school-level, console-based Java project. Includes basic
  Java, core object-oriented programming, sorting, searching, linked lists,
  stacks, queues, trees, hashing, and practical project engineering rules.
---

# Skill: Beginner Java + DSA Project Agent

## 1. Purpose and scope

Use this skill when **explaining, planning, generating, reviewing, refactoring, or testing** a beginner-level Java console project involving data structures and algorithms (DSA).

**Primary goals, in order:**
1. Preserve the existing program's required behavior and data before changing implementation.
2. Make Java code understandable to a student who knows basic syntax and OOP.
3. Apply genuine DSA operations where the project needs them; do not replace them with hard-coded demonstrations.
4. Prefer readable, ordinary Java code over unnecessary abstraction.
5. Compile and test changes; never claim a test was run when it was not.

### Source materials used

The uploaded course materials cover the following topics:

| User-provided reference | Covered topics |
|---|---|
| `Java_DSA_Code_Compilation.docx` | Week 2: bubble, selection, insertion sorting; Week 3: linear, binary, jump search; Week 5: linked lists; Week 6: stacks; Weeks 8–9: queues |
| `week 11 Trees part 2.pdf` | Tree terminology; insertion, search, deletion, update, traversal; binary-tree `Node` example; inorder, preorder, postorder |
| `WEEK 12 HASHING AND HASH TABLE (1).pdf` | Keys, hash functions, hash tables, collisions, chaining, linear probing, insert/search/display, complexity |

**Source boundary:** Beginner Java syntax, OOP fundamentals, project architecture, input validation, persistence, priority-queue design, testing, and secure passwords below are **supplemental agent guidance**; they are not asserted to appear in those three course files. If a course file disagrees with supplemental advice, describe the difference rather than silently rewriting the lesson.

## 2. How an AI agent should use this skill

Before writing or modifying code:

1. Inspect the actual `.java` files and any provided rubric/guidelines.
2. Identify the application entry point, classes, files read/written, and feature flow.
3. Determine which data structures are actually used, and where.
4. Write down invariants (conditions that must always remain true), especially queue order, stock, transactions, and saved records.
5. Identify risks before editing; ask a focused question only if a missing requirement changes behavior.
6. Make small changes that are easy to review and undo.
7. Compile and test normal cases, invalid inputs, and boundary cases.
8. Report changed files, tests performed, observed results, and any unverified behavior.

**Never assume the example code here is a replacement for the actual project's requirements.**

## 3. Java fundamentals

### 3.1 Java program structure

A standard source file has a class; the JVM can start a program at `main`.

```java
public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, Java!");
    }
}
```

- `public class Main`: declares a class named `Main`.
- `main`: program entry point.
- `static`: callable without creating a `Main` object.
- `void`: no return value.
- `System.out.println`: prints a line; `System.out.print` does not append a line break.
- In an ordinary Java source setup, a `public` top-level class `Main` belongs in `Main.java`.

Compile and run from the correct directory:

```bash
javac Main.java
java Main
```

When a project uses packages or a build tool, follow its actual build instructions instead.

### 3.2 Variables, primitive types, and strings

```java
int quantity = 3;
double price = 149.50;
boolean available = true;
char grade = 'A';
String gameTitle = "Sample Game";
final int MAX_ITEMS = 10;
```

`int`, `double`, `boolean`, and `char` are primitive types. `String` is a class (reference type). `final` prevents reassigning a variable after initialization.

### 3.3 Operators

```java
int sum = 5 + 2;       // 7
int difference = 5 - 2; // 3
int product = 5 * 2;   // 10
int quotient = 5 / 2;  // 2: integer division
int remainder = 5 % 2; // 1
boolean valid = quantity > 0 && quantity <= MAX_ITEMS;
```

Important operators: `==`, `!=`, `<`, `>`, `<=`, `>=`; `&&` (AND), `||` (OR), `!` (NOT). For comparing String *contents*, use `.equals()` or `.equalsIgnoreCase()`, not `==`.

### 3.4 Conditions and loops

```java
if (quantity <= 0) {
    System.out.println("Invalid quantity.");
} else {
    System.out.println("Quantity accepted.");
}

for (int i = 0; i < 3; i++) {
    System.out.println(i);
}

int remaining = 3;
while (remaining > 0) {
    remaining--;
}
```

Use `switch` if it makes a menu easier to understand; use `break` to exit a loop or a traditional `switch` case; `continue` skips to the next loop iteration.

### 3.5 Arrays and ArrayList

```java
int[] stocks = {5, 2, 8};
System.out.println(stocks[0]);

import java.util.ArrayList; // Place imports above the class.
// Inside a class method:
ArrayList<String> titles = new ArrayList<>();
titles.add("Game A");
titles.add("Game B");
titles.remove("Game A");
```

An array has a fixed length; an `ArrayList` can grow. Array indices start at 0. The ArrayList fragment is illustrative and must be placed inside a valid Java class/method, with the import at the top of the file.

### 3.6 Input and validation

Prefer line-based `Scanner` input when a menu mixes numbers and text:

```java
import java.util.Scanner;

public class InputExample {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Quantity: ");
        String input = scanner.nextLine().trim();

        try {
            int quantity = Integer.parseInt(input);
            if (quantity > 0) {
                System.out.println("Accepted: " + quantity);
            } else {
                System.out.println("Enter a positive number.");
            }
        } catch (NumberFormatException e) {
            System.out.println("Please enter a whole number.");
        }
    }
}
```

Avoid accidental `nextInt()` + `nextLine()` newline bugs. Do not terminate the whole program for one invalid menu choice; show a helpful message and allow retry.

### 3.7 Methods, parameters, and return values

```java
public class Calculator {
    static int add(int first, int second) {
        return first + second;
    }

    public static void main(String[] args) {
        System.out.println(add(4, 6));
    }
}
```

A **method** has a name, optional parameters, and a return type. `void` means it does not return a result. Prefer descriptive names such as `findGameById`, `addToQueue`, `returnRental`, or `isValidQuantity`.

## 4. Basic object-oriented programming (OOP)

### 4.1 Classes and objects

A **class** is a blueprint; an **object** is an instance created from it. A **class attribute** is also called a *field*. A **class method** here means a method declared in a class; distinguish **instance methods** (called on objects) from **static methods** (called on the class).

```java
public class Game {
    // Fields / attributes
    private String title;
    private int availableStock;

    // Constructor
    public Game(String title, int availableStock) {
        this.title = title;
        this.availableStock = availableStock;
    }

    // Instance methods
    public String getTitle() {
        return title;
    }

    public int getAvailableStock() {
        return availableStock;
    }

    public boolean rentOne() {
        if (availableStock <= 0) {
            return false;
        }
        availableStock--;
        return true;
    }
}
```

Example usage from a different class:

```java
Game game = new Game("Sample Game", 2); // Create object
System.out.println(game.getTitle());    // Access instance method
boolean rented = game.rentOne();          // Change object state
```

`this.title` refers to the current object's field, distinguishing it from the constructor parameter `title`.

### 4.2 Constructors

A constructor initializes an object and has the same name as its class, with no return type. For example, `new Game("Sample Game", 2)` calls `Game(String, int)`.

### 4.3 Encapsulation and access modifiers

- `private`: accessible only within the declaring class (subject to Java's nested-class rules).
- package-private (no modifier): accessible in the same package.
- `protected`: package access plus permitted subclass access.
- `public`: publicly accessible.

Keep important state private. Expose methods that enforce rules rather than allowing external classes to set stock to negative values. Not every field needs a setter.

### 4.4 Static versus instance members

```java
public class Counter {
    private static int totalCreated = 0; // Shared across Counter instances
    private int value;                     // Separate for each object

    public Counter(int value) {
        this.value = value;
        totalCreated++;
    }

    public int getValue() {
        return value;
    }

    public static int getTotalCreated() {
        return totalCreated;
    }
}
```

Use static members for appropriate class-wide data or stateless helpers. Do not make every method static merely to avoid creating objects.

### 4.5 Four common OOP concepts

| Concept | Simple meaning | Beginner example |
|---|---|---|
| Encapsulation | Protect fields and expose controlled operations | `Game.rentOne()` checks stock |
| Abstraction | Show what a component does, hide irrelevant details | `CheckoutQueue.enqueue(request)` |
| Inheritance | Child class extends a parent class | `VipCustomer extends Customer` if there is a real shared relationship |
| Polymorphism | One parent/interface reference can invoke different implementations | Different payment implementations sharing an interface |

Do not force inheritance or interfaces when a few simple classes are enough. Prefer **composition** (a class *has a* queue/catalog) when it more clearly models the project.

### 4.6 References, null, and object equality

Variables holding objects contain references. A `null` reference has no object; calling a method on it causes `NullPointerException`. Use `==` for reference identity and `.equals()` for logical value equality when the class defines that meaning. Validate nulls only where they can legitimately occur.

## 5. DSA foundations

A **data structure** organizes data; an **algorithm** specifies steps to operate on it.

| Structure/algorithm | Main purpose | Typical operation |
|---|---|---|
| Array / ArrayList | Sequence of items | Access, add, remove |
| Linked list | Connected nodes | Insert, delete, traverse |
| Stack | Last in, first out (LIFO) | `push`, `pop`, `peek` |
| Queue | First in, first out (FIFO) | `offer`, `poll`, `peek` |
| Priority queue | Serve according to an explicit priority/order policy | Enqueue, select next |
| Tree | Hierarchical nodes | Insert, find, traverse |
| Hash table | Key-based access | Hash, insert, search |
| Sorting | Arrange items by a rule | Bubble/selection/insertion |
| Searching | Locate an item | Linear/binary/jump |

### 5.1 Time complexity (high-level)

- `O(1)`: roughly constant work.
- `O(log n)`: repeatedly narrows the search space.
- `O(n)`: work grows approximately with the number of items.
- `O(n log n)`: common efficient sorting bound (not the basic sorts below).
- `O(n²)`: often seen in simple nested-loop sorts.

State important assumptions; e.g., binary search **requires sorted data**; hashing is **average** `O(1)`, but collisions can cause `O(n)` worst-case work.

## 6. Sorting (source: Code Compilation, Week 2)

| Algorithm | Simple procedure | Typical time complexity | Notes |
|---|---|---|---|
| Bubble sort | Compare adjacent items and swap if out of order | `O(n²)` | Easy to trace |
| Selection sort | Select the smallest remaining item and place it next | `O(n²)` | Simple nested loops |
| Insertion sort | Insert each item into the already-sorted prefix | Worst `O(n²)` | Often good for small/nearly sorted lists |

Example selection sort of an integer array:

```java
for (int i = 0; i < numbers.length - 1; i++) {
    int smallest = i;
    for (int j = i + 1; j < numbers.length; j++) {
        if (numbers[j] < numbers[smallest]) {
            smallest = j;
        }
    }
    int temp = numbers[i];
    numbers[i] = numbers[smallest];
    numbers[smallest] = temp;
}
```

For catalog browsing, sorting should normally act on a **copy or displayed view**, so changing the sort order does not unexpectedly rearrange unrelated inventory records. Preserve stable identity with IDs rather than relying on displayed positions.

## 7. Searching (source: Code Compilation, Week 3)

- **Linear search:** inspect each item until matched; `O(n)` time; works on unsorted collections.
- **Binary search:** compare with middle and discard half each time; `O(log n)` time **only if data is ordered** by the searched key.
- **Jump search:** skip forward by blocks in a sorted array, then linearly search within a block; typical `O(√n)` comparisons with suitable block size.

Simple linear search:

```java
int foundIndex = -1;
for (int i = 0; i < numbers.length; i++) {
    if (numbers[i] == target) {
        foundIndex = i;
        break;
    }
}
```

For `String`, compare meaningful text with `.equalsIgnoreCase()` if case-insensitive search is intended. Define whether search matches exact names, prefixes, or substrings.

## 8. Linked lists (source: Code Compilation, Week 5)

A linked-list node holds data and references to other nodes.

```java
class Node {
    int data;
    Node next;

    Node(int data) {
        this.data = data;
        this.next = null;
    }
}
```

- **Singly linked:** each node has `next`.
- **Doubly linked:** nodes have `previous` and `next`.
- **Circular linked:** final node links back to the first node (with rules to prevent endless traversal).
- Operations: insert, search, delete, display/traverse.

Handle an empty head, deleting the first node, deleting the last node, and one-node lists. If building a queue on a linked list, track head and tail appropriately rather than traversing needlessly on every enqueue.

## 9. Stacks (source: Code Compilation, Week 6)

Stack = **LIFO**: the newest item is removed first.

```java
import java.util.Stack;

Stack<String> history = new Stack<>();
history.push("Start");
history.push("Browse");
String latest = history.peek(); // Browse, not removed
String removed = history.pop(); // Browse, removed
```

The code compilation uses Java's `Stack` class. For modern Java designs, `Deque`/`ArrayDeque` can often serve as a stack, but **do not replace the course example without explaining the difference**. Never `pop()` an empty stack; check first. Use an undo stack only if undo behavior is actually required.

## 10. Queues (source: Code Compilation, Weeks 8–9)

Queue = **FIFO**: the earliest waiting item is processed first.

```java
import java.util.LinkedList;
import java.util.Queue;

Queue<String> customers = new LinkedList<>();
customers.offer("Customer A");
customers.offer("Customer B");

String next = customers.peek(); // A, remains in queue
String served = customers.poll(); // A, removed
```

- `offer`: append to the queue.
- `peek`: inspect the head without removing; returns `null` if empty.
- `poll`: remove and return the head; returns `null` if empty.
- Display order is not a substitute for actual processing order.

### 10.1 Priority queue: supplemental project pattern

**Not specified by the uploaded queue lecture.** If the project includes VIP/regular queues, define and test its actual priority policy. A Java `PriorityQueue` uses a comparator/natural order and is **not automatically a FIFO queue within equal priority**; add a sequence number or use separate FIFO queues if arrival order must be retained.

A simple two-queue pattern:

```java
Queue<Request> vipQueue = new LinkedList<>();
Queue<Request> regularQueue = new LinkedList<>();
```

`Request` is a project-defined class. The rule for selecting the next request must be written explicitly (e.g., strict VIP-first **or** a documented fairness rule). Do not invent or change VIP/regular service ratios. If the system has a rule such as allowing one regular turn after three VIP turns, count **actual completed selections**, handle empty queues, and reset/carry the counter according to the project's existing semantics. Queue upgrades must specify whether the old request is moved, replaced, or retained.

## 11. Trees (source: Trees – Part 2, Week 11)

A tree arranges nodes in a hierarchy. Important terms are **root**, **parent**, **child**, and **leaf**. Course operations: **insertion**, **searching**, **deletion**, **updating**, **traversing**. Specific insertion/deletion rules depend on the type of tree.

Example binary tree structure:

```java
class TreeNode {
    String data;
    TreeNode left;
    TreeNode right;

    TreeNode(String data) {
        this.data = data;
    }
}
```

For this binary tree:

```text
        1
      /   \
     2     3
    / \   / \
   4   5 6   7
```

| Traversal | Order | Result |
|---|---|---|
| Inorder | Left → Root → Right | `4 2 5 1 6 3 7` |
| Preorder | Root → Left → Right | `1 2 4 5 3 6 7` |
| Postorder | Left → Right → Root | `4 5 2 6 7 3 1` |

Tree traversal methods above specifically describe a **binary tree** (left/right children). A general tree may have more than two children; do not assume every tree is a binary search tree. Do not insert trees into a project merely to increase the number of DSA topics.

## 12. Hashing and hash tables (source: Week 12)

- **Key:** identifier used to find data.
- **Hash function:** converts a key to an index.
- **Hash table:** stores keys/values in buckets or indexed positions.
- **Collision:** two keys map to the same original position.
- **Separate chaining:** store multiple entries in a bucket's chain/list.
- **Linear probing:** try successive slots after a collision.

Course example for nonnegative integer keys:

```java
int index = key % tableSize;
```

For `key = 35`, `tableSize = 10`, index is `5`. If slot 5 is occupied, linear probing checks slot 6, 7, and so on, wrapping around.

Safer helper for arbitrary integer keys:

```java
int hashFunction(int key, int tableSize) {
    if (tableSize <= 0) {
        throw new IllegalArgumentException("Table size must be positive.");
    }
    return Math.floorMod(key, tableSize);
}
```

Course examples use `-1` for an empty position; this means they **cannot store `-1` as a valid key** without redesign. Avoid an infinite loop by detecting a full table. For deletion with linear probing, naive clearing may break subsequent searches; use tombstones or a correct rehash strategy. These are important implementation safeguards beyond the simplified teaching examples.

**Average complexity (well-designed table):** insert/search/delete approximately `O(1)`; **worst case:** `O(n)` because of collisions. Java's built-in `HashMap` is available, but a manually implemented table may be better if the assignment requires demonstrating the algorithm.

## 13. Practical OOP design for a beginner console project

Suggested responsibilities (adapt to the real project; do not create files only to match this example):

| Component | Responsibility |
|---|---|
| `Main` | Start the application |
| `Game` / `Request` / `User` | Store and validate individual domain records |
| `GameCatalog` | Browse, search, sort, and query games |
| `CheckoutQueue` | Preserve queue ordering and process the next request |
| `RentalService` | Coordinate stock, rentals, and returns |
| `DataStore` | Load/save records safely |
| `ConsoleUI` | Print menus/tables and receive user input |

Keep each class focused, but **combine tiny classes where separation adds no clarity**. Keep UI printing separate from core queue/stock logic where reasonably possible. A method can coordinate several objects without turning into a giant all-purpose method.

### 13.1 Console UI guidelines

- This is a **text terminal** application, not a graphical interface.
- Use `System.out.print` / `System.out.println` and straightforward format strings.
- Prefer clear, manually maintained separators, tidy headings, and consistent column widths.
- Keep displayed choices easy to scan; paginate long lists where needed.
- Align content horizontally as practical for the expected terminal width; do not introduce complicated centering formulas unless necessary.
- Render new screens from the top after clearing when the actual terminal supports it; if ANSI screen-clearing is used, document terminal compatibility.
- Keep back/main-menu/cancel actions consistent and avoid a long unrelated navigation menu.
- Preserve existing accepted visual conventions when modifying an established program.

### 13.2 Input and data integrity

- Validate required fields, number ranges, IDs, quantities, and stock availability.
- Do not decrement inventory merely because an item was browsed or added to an unconfirmed cart.
- Define exactly **when stock is reserved**, deducted, restored, or permanently adjusted.
- Prevent a rental/request from being processed twice.
- Validate a returned item against an active rental and correct quantity.
- If transactions involve multiple updates, handle partial failure to avoid mismatched queue/inventory records.
- Do not silently discard or reset saved user data.

### 13.3 Passwords and account data

- Terminal password masking (displaying `*`) is an input/UI concern, **not encryption or hashing**.
- Do not store plaintext passwords in `.txt`, `.bak`, or other backups.
- For real accounts, store salted, slow password hashes using a well-reviewed password-hashing scheme; do not implement your own cryptography.
- Avoid echoing passwords in logs, debugging output, or error messages.
- Explain limitations of IDE consoles and platform-specific input masking rather than claiming `Scanner` natively displays asterisks.

### 13.4 Persistence and backups

- Validate loaded data and handle missing or malformed files gracefully.
- Write using a safe process (e.g., temporary file then replace, with appropriate failure handling) rather than risking half-written live data.
- Keep any `.bak` backup consistent with the application's current data format and security rules.
- Test saving and reloading after add, process, cancel, and return operations.

## 14. Code style and refactoring rules for the agent

**Prefer:**

- Meaningful class, variable, and method names.
- Regular `for`, enhanced `for`, `while`, and `if` statements where clearest.
- Short methods with one main purpose.
- Simple constructors, private fields, and straightforward getters.
- Comments that explain *why a rule exists*, not every obvious Java line.
- Built-in collections when permitted by assignment requirements.
- Consistent 4-space indentation and ordinary Java naming: `PascalCase` classes, `camelCase` methods and fields, `UPPER_SNAKE_CASE` constants.

**Avoid unless justified:**

- Streams, lambdas, reflection, frameworks, complex generics, advanced patterns, or extra dependencies merely to shorten code.
- One-line code so compressed that it becomes unreadable.
- Repeating the same business rule in multiple menus.
- Hard-coded demo queue outputs in place of actual queue operations.
- Unnecessary mathematical layout helpers for a small terminal interface.
- Removing validations or changing queue semantics just to reduce line count.

## 15. Verification checklist for any agent-generated change

Test at least the relevant cases below:

| Area | Required checks |
|---|---|
| Java build | Compile all affected `.java` files; fix compile errors |
| Startup/navigation | App launches, menus work, back/cancel paths are consistent |
| Login | Valid/invalid credentials, masked input limitations, no password leaks |
| Search/sort | Found/not found, duplicates, empty catalog, repeated sorting |
| Inventory | Zero stock, exact available quantity, excessive quantity, repeated checkout |
| Queue | Empty queue, one item, several items, expected FIFO order |
| Priority queue | Mixed priorities, tie order, fairness boundary, cancellation, upgrades |
| Processing | Only next eligible request processed; duplicate processing rejected |
| Returns | Valid return, wrong request, double return, correct stock restoration |
| Persistence | Save/restart/reload; invalid/missing files; backup behavior |
| UI | Narrow/long values, wrapping/truncation, tables, terminal origin |
| Regression | Previously supported features remain available and unchanged |

For every failed test: identify the inputs, expected outcome, actual outcome, likely cause, and fix. Clearly distinguish **manually reasoned** test scenarios from **actually executed** tests.

## 16. Suggested work and response format

When the user asks an agent to change their project, use this concise sequence:

1. **Current behavior:** identify the relevant methods, data structures, and stored state.
2. **Change plan:** state the minimal modification and its effect on functionality.
3. **Implementation:** change only affected code; keep coding style beginner-friendly.
4. **Verification:** show compile command and exact tests actually run.
5. **Result:** list modified files, confirmed behavior, unresolved issues, and any assumptions.

When explaining code to a beginner, describe **what it does**, **why it is needed**, **how it connects to other classes**, and **a short example flow**. Explain Java terms the first time they appear.

## 17. Agent guardrails

- Respect the exact project rubric and existing program over any example in this document.
- Do not claim a specific DSA is used unless the actual code uses it.
- Do not claim a feature works because a method name suggests it works.
- Do not claim execution, compilation, or file edits without doing them.
- Do not introduce a feature (e.g., trees or hashing) simply because the lessons mention it.
- If simplifying, preserve data format and observable behavior unless the user approves an intentional change.
- Explain when a teaching example has limitations or assumes particular inputs.
- If source materials lack a project-specific requirement, mark the recommendation as **supplemental**.

## 18. Quick reference: common Java words

| Term | Plain-language meaning |
|---|---|
| Class | Blueprint for creating objects |
| Object | An instance created from a class |
| Field / attribute | Data stored in an object or class |
| Method | Function declared inside a class |
| Constructor | Special initializer invoked by `new` |
| Parameter | Input variable declared by a method/constructor |
| Argument | Actual value passed to a method/constructor |
| `this` | Current object reference |
| `new` | Creates an object or array |
| `private` | Restricts access to the declaring class |
| `public` | Makes a member accessible where the containing type is visible |
| `static` | Belongs to the class, not a particular object |
| `void` | Method does not return a value |
| `return` | Returns a value or exits a method |
| `null` | No object reference |
| FIFO | First in, first out |
| LIFO | Last in, first out |
| Collision | More than one key maps to the same hash bucket/index |
| Traversal | Visiting items/nodes in a defined order |

---

**End of skill.** This is a reusable knowledge-and-behavior reference for an AI coding agent, not an instruction to change the project automatically.
