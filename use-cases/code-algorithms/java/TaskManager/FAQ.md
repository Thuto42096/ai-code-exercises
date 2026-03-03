# Task Manager — Frequently Asked Questions

**Audience:** developers and students working with this CLI-based Java task manager.

---

## Table of Contents

1. [Getting Started](#1-getting-started)
2. [Features and Functionality](#2-features-and-functionality)
3. [Troubleshooting Common Issues](#3-troubleshooting-common-issues)
4. [Priority Scoring and Advanced Behaviour](#4-priority-scoring-and-advanced-behaviour)

---

## 1. Getting Started

### Q: What do I need installed before I can run the application?

You need a **Java Development Kit (JDK) version 11 or later**. Gradle is
bundled with the project via the `./gradlew` wrapper — you do not need to
install Gradle separately.

Verify your Java version:
```bash
java -version
```

### Q: How do I build the project for the first time?

From the `TaskManager/` directory:
```bash
./gradlew build
```

This compiles all sources, runs the full test suite, and prints
`BUILD SUCCESSFUL` if everything is working. On macOS or Linux you may first
need to make the wrapper executable:
```bash
chmod +x gradlew
```

### Q: How do I create my first task?

Only the title is required. All other arguments are optional:
```bash
# Minimal — title only
./gradlew run --args="create 'Review pull request'"

# Full — title, description, priority (3=HIGH), due date, tags
./gradlew run --args="create 'Fix login bug' 'Fails on mobile Safari' 3 2025-06-15 bug,mobile"
```

The application prints the new task's full UUID when creation succeeds.

### Q: Where are my tasks stored?

Tasks are saved to a file called **`tasks.json`** in whichever directory you
run the application from. It is a human-readable, pretty-printed JSON array.
Deleting or moving this file will cause all tasks to be lost.

### Q: How do I see all my tasks?

```bash
./gradlew run --args="list"
```

Each task is displayed with a status symbol, the first 8 characters of its
UUID, a priority indicator (`!` through `!!!!`), title, description, due date,
and tags.

### Q: How do I get help from the command line?

```bash
./gradlew run --args="--help"
# or simply run with no arguments
./gradlew run
```

---

## 2. Features and Functionality

### Q: What priority levels are available?

| Integer | Keyword | Display | Meaning |
|---|---|---|---|
| `1` | `low` | `!` | Background / nice-to-have |
| `2` | `medium` | `!!` | Default when not specified |
| `3` | `high` | `!!!` | Should be done soon |
| `4` | `urgent` | `!!!!` | Needs immediate attention |

Use the **integer** when calling `create` or `priority` from the CLI.
The keyword form (`low`, `high`, etc.) is only accepted by the
natural-language text parser (see below).

### Q: What status values are there and what do they mean?

| String value | Display | Meaning |
|---|---|---|
| `todo` | `[ ]` | Not yet started (assigned automatically on creation) |
| `in_progress` | `[>]` | Actively being worked on |
| `review` | `[?]` | Work done, awaiting code review or sign-off |
| `done` | `[✓]` | Finished; `completedAt` timestamp is recorded |

Status strings are **case-sensitive** and must be typed exactly as shown
(e.g. `in_progress`, not `IN_PROGRESS` or `In Progress`).

### Q: How do I mark a task as complete?

Use the `status` command with the value `done`:
```bash
./gradlew run --args="status <task_id> done"
```

When a task is set to `done`, two timestamps are updated simultaneously:
`completedAt` and `updatedAt` are both set to the current time. This is
handled by `Task.markAsDone()` and cannot be triggered in any other way.

### Q: How do I filter the task list?

Three filter flags are available on the `list` command:

```bash
# Filter by status
./gradlew run --args="list -s in_progress"

# Filter by priority (integer)
./gradlew run --args="list -p 3"

# Show only overdue tasks (past due date and not DONE)
./gradlew run --args="list -o"
```

Filters are mutually exclusive: `--overdue` takes precedence over `--status`,
which takes precedence over `--priority`.

### Q: How do tags work, and which tags are special?

Any string can be used as a tag. Tags are added with `tag` and removed with
`untag`:
```bash
./gradlew run --args="tag    <task_id> blocker"
./gradlew run --args="untag  <task_id> blocker"
```

Three tags have special meaning in the priority scoring algorithm — they add
**+8 points** to a task's importance score:

- `blocker`
- `critical`
- `urgent`

Only the **first matching boost tag** counts; having all three does not
stack to +24.

### Q: What does the `stats` command show?

```bash
./gradlew run --args="stats"
```

Output includes:

- **Total tasks** — count of all stored tasks.
- **By status** — count for each of the four status values.
- **By priority** — count for each of the four priority levels.
- **Overdue tasks** — tasks whose due date has passed and that are not `done`.
- **Completed in last 7 days** — tasks with a `completedAt` timestamp after
  `now() - 7 days`.

### Q: Can I create a task using natural-language shorthand?

Yes. `TaskTextParser` reads free-form text with embedded tokens:

| Token | Example | Effect |
|---|---|---|
| `!N` or `!name` | `!3` / `!high` | Sets priority |
| `@word` | `@backend` | Adds a tag |
| `#word` | `#tomorrow` / `#friday` / `#2025-06-01` | Sets due date |

```bash
# "Buy milk" becomes the title; the rest is extracted and stripped
TaskTextParser.parseTaskFromText("Buy milk @shopping !1 #tomorrow");
```

**Accepted `#date` values:** `today`, `now`, `tomorrow`, `next_week`,
`nextweek`, any day name or three-letter abbreviation (`monday`/`mon` …
`sunday`/`sun`), or an ISO date (`YYYY-MM-DD`).

> **Note:** This parser is a utility class — it is not wired into the CLI
> `create` command. It is intended for programmatic use or future integration.

### Q: What date format must I use for the `create` and `due` commands?

Dates must be in **`YYYY-MM-DD`** (ISO 8601) format:
```bash
./gradlew run --args="due <task_id> 2025-06-15"
```

Formats like `15/06/2025`, `June 15`, or `06-15-2025` will be rejected with
the message `Invalid date format. Use YYYY-MM-DD`. Due dates are stored as
the last instant of that day (23:59:59.999…) so a task due on `2025-06-15`
becomes overdue only after that day ends.

---

## 3. Troubleshooting Common Issues

### Q: I get "Invalid status value: …" — what went wrong?

The status string must exactly match one of: `todo`, `in_progress`, `review`,
`done`. Common mistakes:

| What you typed | What to type instead |
|---|---|
| `IN_PROGRESS` | `in_progress` |
| `In Progress` | `in_progress` |
| `complete` | `done` |
| `closed` | `done` |

This error currently throws an uncaught `IllegalArgumentException` and prints
a stack trace. The task is **not** updated.

### Q: I get "Task not found" — how do task IDs work?

Every task is assigned a full UUID (e.g.
`a1b2c3d4-e5f6-7890-abcd-ef1234567890`) at creation time. The `list` and
`show` commands display only the **first 8 characters** (`a1b2c3d4`) for
readability. When passing an ID to `status`, `priority`, `due`, `tag`,
`untag`, `show`, or `delete`, you must supply the **full UUID** — not just the
short prefix shown in the output.

Copy the ID from the `create` output or use `show` to retrieve it.

### Q: My tasks disappeared after I restarted the application.

Tasks are loaded from `tasks.json` in the **current working directory** when
the application starts. If you ran the application from a different directory
than usual, a separate (empty) `tasks.json` will have been created there.

Check the directory you are running from:
```bash
pwd
ls tasks.json
```

### Q: The build fails with "invalid source release" or "unsupported class file major version".

Your installed JDK is older than required. Install **JDK 11 or later** and
point `JAVA_HOME` at it:
```bash
# macOS (Homebrew)
brew install openjdk@17
export JAVA_HOME=$(brew --prefix openjdk@17)

# Verify
java -version
```

### Q: I get "Invalid date format" even though my date looks correct.

Verify there are no extra characters, slashes, or spaces:

```bash
# Correct
./gradlew run --args="due <task_id> 2025-06-15"

# Wrong — slashes
./gradlew run --args="due <task_id> 2025/06/15"

# Wrong — extra space inside the quotes
./gradlew run --args="due <task_id> 2025-06- 15"
```

### Q: A completed task is still appearing near the top of my `list`.

The `list` command returns tasks in insertion order (HashMap-backed), not by
score. The priority scoring algorithm (used by `TaskPriorityManager`) is a
separate utility not currently wired into the CLI `list` output.

If a DONE task is also `URGENT` **and** overdue, its score can remain
positive (40 + 30 − 50 = 20) and will appear above some active tasks when
sorting is applied programmatically. This is by design — completed tasks
sink in the ranked list but are never hidden from history.

---

## 4. Priority Scoring and Advanced Behaviour

### Q: How exactly does the priority scoring algorithm rank tasks?

`TaskPriorityManager.calculateTaskScore()` assigns an integer score using five
additive components evaluated in order:

| Component | Condition | Points |
|---|---|---|
| Base priority | LOW / MEDIUM / HIGH / URGENT | +10 / +20 / +30 / +40 |
| Due-date urgency | Overdue | +30 |
| | Due today (0 whole days left) | +20 |
| | Due within 2 days | +15 |
| | Due within 7 days | +10 |
| | No due date or due in > 7 days | +0 |
| Status penalty | DONE | −50 |
| | REVIEW | −15 |
| | TODO / IN_PROGRESS | +0 |
| Boost-tag bonus | Has `blocker`, `critical`, or `urgent` tag | +8 |
| Recency bonus | Updated within the current calendar day | +5 |

Tasks are then sorted highest-score-first by `sortTasksByImportance()`.

### Q: Why does an overdue LOW-priority task sometimes rank above an URGENT task?

Because a missed deadline (+30) is weighted more heavily than any single
priority level. An overdue LOW task scores 10 + 30 = **40**, which ties with
an URGENT task that has no due date (40 + 0 = **40**). The algorithm treats
"you already missed this deadline" as a stronger signal than "you labelled
this urgent".

### Q: What are "boost tags" and do multiple boost tags stack?

Boost tags (`blocker`, `critical`, `urgent`) add **+8 points** to the score.
The check is `anyMatch` — if any one boost tag is present the bonus is
applied once. Having all three tags does not give +24; it still gives +8.
The tag name `urgent` acts as a boost tag independently of the `URGENT`
priority level — a MEDIUM-priority task tagged `urgent` scores 20 + 8 = 28.

### Q: Why do two tasks with the same score appear in a different order each run?

Tie-breaking is not implemented. The sort is applied over a Java `Stream`
that comes from a `HashMap<String, Task>`, which does not guarantee iteration
order. Tasks with identical scores will appear in a non-deterministic sequence
that can change between runs. If consistent ordering matters for your use
case, add a secondary sort on `createdAt` ascending in
`TaskPriorityManager.sortTasksByImportance()`.

### Q: The recency bonus says "within the last 24 hours" — is that exactly right?

Not quite. The check uses `ChronoUnit.DAYS.between(updatedAt, now)`, which
**truncates to whole days**, not a rolling 24-hour window. In practice:

- A task updated **23 h 59 m ago** still counts as 0 days old → **+5 bonus**.
- A task updated **24 h 01 m ago** counts as 1 day old → **no bonus**.

The boundary shifts with the time of day, so whether a task receives the
bonus is partly a function of when it was last touched relative to the current
clock time, not a fixed 24-hour rolling window.

### Q: What is `TaskMergeService` and when would I use it?

`TaskMergeService.mergeTaskLists()` is a utility for synchronising two
independent task lists (e.g. a local list and a remote/server list) using
three conflict-resolution rules:

| Field type | Rule |
|---|---|
| Metadata (title, description, priority, due date) | **Last-write-wins** — the version with the later `updatedAt` timestamp is kept |
| Completion status | **DONE always wins** — if either version is `done`, the merged result is `done` |
| Tags | **Union** — all tags from both versions are combined |

The method returns a `MergeResult` object that tells the caller which tasks
need to be created or updated on each side, so the caller can propagate
changes without re-sending the full list. This class is not currently wired
into the CLI; it is intended for integration with a remote storage backend.

