# Task Manager

A command-line task management application written in Java. Tasks are stored
locally as JSON and surfaced through a rich CLI with filtering, tagging,
priority scoring, and conflict-aware list merging.

---

## Table of Contents

1. [Features](#features)
2. [Prerequisites](#prerequisites)
3. [Installation](#installation)
4. [Usage](#usage)
   - [Command reference](#command-reference)
   - [Priority levels](#priority-levels)
   - [Status values](#status-values)
   - [Natural-language text parser](#natural-language-text-parser)
5. [Configuration](#configuration)
6. [Project structure](#project-structure)
7. [Running tests](#running-tests)
8. [Troubleshooting](#troubleshooting)
9. [Contributing](#contributing)
10. [License](#license)

---

## Features

- **Full CRUD** — create, read, update, and delete tasks from the terminal.
- **Flexible filtering** — list tasks by status, priority, or overdue state.
- **Rich metadata** — every task carries a title, description, priority level,
  due date, and an arbitrary set of tags.
- **Multi-factor priority scoring** — `TaskPriorityManager` ranks tasks using a
  weighted algorithm across five dimensions: base priority, deadline urgency,
  completion status, boost tags (`blocker`, `critical`, `urgent`), and recency.
- **Conflict-aware merging** — `TaskMergeService` implements a three-way merge
  strategy (last-write-wins for metadata, DONE-always-wins for status, union
  for tags) for synchronising local and remote task lists.
- **Natural-language input** — `TaskTextParser` interprets inline tokens
  (`!priority`, `@tag`, `#date`) so tasks can be created from free-form text.
- **Persistent JSON storage** — tasks survive between runs via a human-readable
  `tasks.json` file written to the working directory.
- **Statistics dashboard** — `stats` command shows totals by status, by
  priority, overdue count, and tasks completed in the last seven days.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java (JDK) | 11 or later |
| Gradle | bundled via `./gradlew` (no separate install needed) |

> **macOS / Linux** — ensure `./gradlew` is executable:
> ```bash
> chmod +x gradlew
> ```

---

## Installation

```bash
# 1. Clone the repository
git clone <repository-url>
cd use-cases/code-algorithms/java/TaskManager

# 2. Build the project (compiles sources and runs all tests)
./gradlew build
```

A successful build prints `BUILD SUCCESSFUL`. The compiled application is
ready to run immediately via Gradle's `run` task — no separate install step
is required.

---

## Usage

All commands are run through Gradle:

```bash
./gradlew run --args="<command> [arguments]"
```

Run without arguments (or with `--help`) to print the full command list:

```bash
./gradlew run --args="--help"
```

### Command reference

| Command | Syntax | Description |
|---|---|---|
| `create` | `create <title> [description] [priority] [due_date] [tags]` | Create a new task |
| `list` | `list [-s <status>] [-p <priority>] [-o]` | List tasks, with optional filters |
| `status` | `status <task_id> <new_status>` | Update a task's status |
| `priority` | `priority <task_id> <new_priority>` | Update a task's priority |
| `due` | `due <task_id> <YYYY-MM-DD>` | Update a task's due date |
| `tag` | `tag <task_id> <tag>` | Add a tag to a task |
| `untag` | `untag <task_id> <tag>` | Remove a tag from a task |
| `show` | `show <task_id>` | Show full details for one task |
| `delete` | `delete <task_id>` | Permanently delete a task |
| `stats` | `stats` | Print a statistics summary |

**Create a task** (only the title is required; the rest are optional):
```bash
./gradlew run --args="create 'Fix login bug' 'Users cannot log in on mobile' 3 2025-06-01 bugs,mobile"
#                            title             description                   ^   ^           ^
#                                                                        priority due date  tags (comma-separated)
```

**List all tasks:**
```bash
./gradlew run --args="list"
```

**Filter tasks by status:**
```bash
./gradlew run --args="list -s in_progress"
```

**Filter tasks by priority:**
```bash
./gradlew run --args="list -p 3"
```

**Show only overdue tasks:**
```bash
./gradlew run --args="list -o"
```

**Update a task's status** (use the first 8 characters of the task ID shown in `list`):
```bash
./gradlew run --args="status a1b2c3d4 done"
```

**Add and remove tags:**
```bash
./gradlew run --args="tag    a1b2c3d4 blocker"
./gradlew run --args="untag  a1b2c3d4 blocker"
```

**Show the statistics dashboard:**
```bash
./gradlew run --args="stats"
```

---

### Priority levels

Priorities are specified as an integer or as the word value:

| Integer | Name | Description |
|---|---|---|
| `1` | `low` | Background / nice-to-have |
| `2` | `medium` | Default priority |
| `3` | `high` | Should be done soon |
| `4` | `urgent` | Needs immediate attention |

---

### Status values

| Value | Display | Meaning |
|---|---|---|
| `todo` | `[ ]` | Not started (default) |
| `in_progress` | `[>]` | Actively being worked on |
| `review` | `[?]` | Work complete, awaiting review |
| `done` | `[✓]` | Finished; `completedAt` is recorded |

---

### Natural-language text parser

`TaskTextParser` lets you embed metadata directly in a task title using inline
tokens. Tokens are stripped from the stored title automatically.

| Token | Example | Effect |
|---|---|---|
| `!N` or `!name` | `!3` or `!high` | Sets priority |
| `@tag` | `@backend` | Adds a tag |
| `#date` | `#tomorrow`, `#friday`, `#2025-06-01` | Sets due date |

**Accepted `#date` values:**

- `today` / `now` — due today
- `tomorrow` — due tomorrow
- `next_week` / `nextweek` — due in 7 days
- `monday` / `mon`, `tuesday` / `tue`, … `sunday` / `sun` — next occurrence
  of that weekday
- `YYYY-MM-DD` — explicit ISO date

**Examples:**
```java
// Within application code
Task t = TaskTextParser.parseTaskFromText(
    "Fix prod outage @backend @ops !urgent #tomorrow"
);
// t.getTitle()    => "Fix prod outage"
// t.getPriority() => URGENT
// t.getTags()     => ["backend", "ops"]
// t.getDueDate()  => tomorrow at midnight
```

---

## Configuration

| Setting | How to change | Default |
|---|---|---|
| Storage file path | Edit `TaskManagerCli.java` line 15: `new TaskManager("tasks.json")` | `tasks.json` in the working directory |
| Boost tags (priority scoring) | Edit `BOOST_TAGS` constant in `TaskPriorityManager.java` | `blocker`, `critical`, `urgent` |
| Priority weights | Edit `PRIORITY_WEIGHTS` constant in `TaskPriorityManager.java` | LOW=1, MEDIUM=2, HIGH=3, URGENT=4 (×10) |

There is no external configuration file; all tuneable values are `static final`
constants in the relevant utility classes.

---

## Project structure

```
src/
├── main/java/za/co/wethinkcode/taskmanager/
│   ├── cli/
│   │   └── TaskManagerCli.java       # Entry point; parses CLI arguments
│   ├── app/
│   │   └── TaskManager.java          # Application service; orchestrates operations
│   ├── model/
│   │   ├── Task.java                 # Domain model with business methods
│   │   ├── TaskPriority.java         # Enum: LOW(1) … URGENT(4)
│   │   └── TaskStatus.java           # Enum: TODO → IN_PROGRESS → REVIEW → DONE
│   ├── storage/
│   │   └── TaskStorage.java          # JSON persistence via Gson + custom LocalDateTime adapters
│   └── util/
│       ├── TaskPriorityManager.java  # Multi-factor weighted triage scoring algorithm
│       ├── TaskMergeService.java     # Three-way conflict resolution for list sync
│       └── TaskTextParser.java       # Natural-language text → Task (!, @, # DSL)
│
└── test/java/za/co/wethinkcode/taskmanager/
    ├── app/
    │   └── TaskManagerTest.java
    └── util/
        ├── TaskPriorityManagerTest.java
        ├── TaskMergeServiceTest.java
        └── TaskTextParserTest.java
```

### Key design decisions

- **Layered architecture** — `CLI → Service → Storage`; no layer skips.
- **Domain logic in the model** — `Task.markAsDone()`, `Task.isOverdue()`, and
  `Task.update()` encapsulate business rules instead of spreading them across
  the service layer.
- **Immutable enum wire format** — `TaskStatus` and `TaskPriority` carry a
  serialisable string/integer value and a `fromValue()` factory, decoupling the
  JSON representation from the enum constant name.
- **Gson custom adapters** — `LocalDateTime` (Java 8+) is not supported by
  Gson natively; `TaskStorage` registers serialiser/deserialiser adapters at
  construction time.

---

## Running tests

```bash
# Run the full test suite
./gradlew test

# View the HTML test report after a run
open build/reports/tests/test/index.html
```

Test output (passed / skipped / failed) is printed to the console during the
run. The suite covers:

| Test class | What it tests |
|---|---|
| `TaskManagerTest` | Service-layer CRUD operations |
| `TaskPriorityManagerTest` | Score calculation, sorting, top-N selection |
| `TaskMergeServiceTest` | Conflict resolution rules (LWW, DONE-wins, tag union) |
| `TaskTextParserTest` | Token extraction and natural-language date parsing |

---

## Troubleshooting

**`BUILD FAILED` with "invalid source release"**
> Your installed JDK version is older than the one required. Install JDK 11 or
> later and ensure `JAVA_HOME` points to it:
> ```bash
> java -version
> export JAVA_HOME=$(/usr/libexec/java_home -v 11)   # macOS
> ```

**`Error loading tasks: …` on startup**
> The `tasks.json` file is corrupt or was written by an incompatible version.
> Delete it and restart:
> ```bash
> rm tasks.json
> ```

**`Invalid status value: …`**
> Status strings are case-sensitive and must exactly match one of:
> `todo`, `in_progress`, `review`, `done`.

**`Invalid priority value: …`**
> Priority must be an integer `1`–`4`. The string names (`low`, `high`, etc.)
> are only accepted by `TaskTextParser`, not by the `priority` CLI command.

**`Task not found`**
> Task IDs are full UUIDs. The `list` and `show` commands display only the
> first 8 characters for readability, but any unambiguous prefix of the full
> UUID works as an argument.

**Due dates are not being saved**
> Dates must be in `YYYY-MM-DD` format for the `create` and `due` commands.
> Example: `2025-06-15`, not `15/06/2025` or `June 15`.

---

## Contributing

1. Fork the repository and create a feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   ```
2. Make your changes, keeping to the existing layered architecture — business
   rules belong in `model/` or `util/`, not in `cli/`.
3. Add or update tests for any behaviour you change. Run the full suite before
   opening a pull request:
   ```bash
   ./gradlew test
   ```
4. Open a pull request against `main` with a clear description of what was
   changed and why.

### Code style guidelines

- Follow standard Java naming conventions (camelCase methods, PascalCase classes).
- All public methods must have Javadoc comments (description, `@param`,
  `@return`, `@throws`).
- No magic numbers — extract constants with descriptive names.
- Do not break the layer contract: `TaskManagerCli` must not access
  `TaskStorage` directly.

---

## License

This project is provided for educational purposes as part of the
WeThinkCode_ AI code-exercise curriculum. No additional open-source licence
is currently applied. Contact the repository owner for reuse permissions.