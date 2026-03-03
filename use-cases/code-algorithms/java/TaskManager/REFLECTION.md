# Task Manager Codebase – Reflection Document

---

## 1. Initial vs. Final Understanding

### Initial Understanding
At first glance, the Task Manager appeared to be a straightforward CLI CRUD application: take a command, manipulate a task, persist it to a file. The README listed ten commands and pointed to a Gradle build, which suggested a well-structured Java project but gave no hint of the internal complexity.

My early mental model was flat:

```
CLI → TaskManager → TaskStorage → tasks.json
```

I expected a single service class doing most of the work, with simple data-bag model objects and little business logic beyond basic getters and setters.

### Final Understanding
The codebase is more layered and thoughtfully designed than the entry point implies. The actual architecture is:

```
TaskManagerCli  (presentation / argument parsing – Apache Commons CLI)
      │
TaskManager     (application service – orchestrates operations)
      │
TaskStorage     (persistence – Gson + custom LocalDateTime adapters)
      │
   tasks.json

Utilities (domain logic living outside the core CRUD path):
  ├── TaskPriorityManager  – composite scoring algorithm (priority, due date, tags, recency)
  ├── TaskMergeService     – three-way sync / conflict resolution for local↔remote task lists
  └── TaskTextParser       – natural-language text → Task (!, @, # token DSL)

Model:
  ├── Task          – rich domain object with business methods (isOverdue, markAsDone, update)
  ├── TaskStatus    – enum: TODO → IN_PROGRESS → REVIEW → DONE
  └── TaskPriority  – enum with numeric values (1 LOW … 4 URGENT)
```

The most surprising discovery was the three utility classes. `TaskMergeService` implements a full conflict-resolution strategy (last-write-wins for metadata, completed-always-wins for status, union for tags) that would be at home in a distributed system. `TaskPriorityManager` runs a multi-factor scoring algorithm rather than sorting naively by the priority field. `TaskTextParser` implements a mini-DSL (`!priority @tag #date`) using pre-compiled regex patterns, including fuzzy weekday resolution.

---

## 2. Most Valuable Insights from Each Area of Exploration

| Area explored | Key insight |
|---|---|
| `Task.java` | The model holds real business logic: `isOverdue()` checks both date *and* status; `markAsDone()` sets `completedAt` atomically; `update()` is a null-safe partial update. |
| `TaskStatus` / `TaskPriority` enums | Both enums carry a serialisable value (`String` / `int`) and a `fromValue()` factory, decoupling the wire format from the enum constant name. |
| `TaskStorage.java` | Persistence is JSON via Gson with hand-written `LocalDateTime` adapters (Java 8 types are not natively supported by Gson). The store is an in-memory `HashMap<String, Task>` that is written to disk on every mutation. |
| `TaskPriorityManager.java` | The scoring weights reveal business priorities: overdue tasks (+30) outrank any priority level; tag boosts (`blocker`, `critical`, `urgent`) are additive; DONE tasks are penalised (−50) so they sink to the bottom without being filtered out. |
| `TaskMergeService.java` | Conflict resolution uses three distinct rules depending on the field type: timestamp (last-write-wins), completion status (DONE always wins), tags (union). The result object encodes *what needs to propagate where*, not just the merged state. |
| `TaskTextParser.java` | Regex patterns are compiled once as `static final` constants. The `#` token accepts both natural language ("tomorrow", weekday names) and ISO dates, tried in order of specificity. |

---

## 3. Approach to Implementing a New Business Rule

When asked to add a rule such as *"a task cannot be moved back to TODO once it has reached IN_PROGRESS"*, the layered architecture made the right insertion point obvious:

**Step 1 – Locate the invariant owner.**
Status transitions are a domain concern, so the rule belongs in `Task` or `TaskStatus`, not in the CLI or storage layer. Adding it higher (in `TaskManagerCli`) would let the rule be bypassed by any future caller of `TaskManager`.

**Step 2 – Encode the rule close to the data.**
Add a `canTransitionTo(TaskStatus next)` method on `Task` (or a static `isValidTransition(current, next)` on `TaskStatus`) that encodes the allowed state machine. This keeps the rule in one place and makes it testable in isolation.

**Step 3 – Enforce it at the service layer.**
In `TaskManager.updateTaskStatus()`, call `canTransitionTo` before applying the change and return `false` (or throw) if the transition is invalid. The CLI already surfaces false returns as error messages, so no presentation-layer change is needed.

**Step 4 – Update `TaskMergeService` if necessary.**
Because the merge service can also set task status (e.g., propagating a DONE from remote), it must respect the same transition rules, or it could silently violate the invariant during sync.

**Step 5 – Write tests first.**
The existing test structure (`TaskManagerTest`, `TaskPriorityManagerTest`, etc.) shows unit tests for each layer. A `TaskStatusTransitionTest` class should be added alongside.

---

## 4. Strategies for Approaching Unfamiliar Code in the Future

1. **Read the directory tree before reading any file.** Package names in Java mirror the conceptual architecture. Seeing `app`, `cli`, `model`, `storage`, `util` immediately suggests a layered design and narrows where to look first.

2. **Follow the entry point.** `main()` in `TaskManagerCli` acts as a map of the whole application. Every command dispatched from `executeCommand()` is a feature; reading the switch cases takes seconds and surfaces the full surface area.

3. **Inspect the model objects for hidden business logic.** It is tempting to dismiss model classes as data bags. Methods like `isOverdue()`, `markAsDone()`, and `update()` in `Task` carry domain rules that would be easy to duplicate or contradict if missed.

4. **Treat utilities as the algorithmic core.** In this codebase, the richest logic lives in `util/`, not in the service layer. When exploring an unfamiliar project, look for packages named `util`, `service`, `domain`, or `engine` — they often contain the most complex and reusable logic.

5. **Check serialisation boundaries early.** Custom Gson adapters in `TaskStorage` mean that any new field added to `Task` must be handled explicitly; missing this would cause silent data loss on save/load. Identifying serialisation customisation early prevents hard-to-debug regressions.

6. **Use tests as living documentation.** The test files (`TaskMergeServiceTest`, `TaskPriorityManagerTest`, `TaskTextParserTest`) reveal the *intended* edge cases far more concisely than reading production code. When joining an unfamiliar codebase, reading the tests first can compress onboarding time significantly.

---

## 5. Deep Dive — `TaskPriorityManager` Algorithm

### How the AI's Explanation Changed My Understanding

Before close reading, the algorithm looked like a simple priority sort — rank tasks by their `TaskPriority` enum and be done with it. The AI's walkthrough revealed it is actually a **multi-factor weighted scoring system**, closer in design to a recommendation engine than a comparator.

The shift in understanding came from seeing the five independent dimensions laid out together:

| Dimension | Max contribution | Direction |
|---|---|---|
| Base priority (`LOW`→`URGENT`, weights 1–4 × 10) | +40 | Positive |
| Due-date urgency (overdue / today / 2 days / 7 days) | +30 | Positive |
| Status penalty (`REVIEW` / `DONE`) | −50 | Negative |
| Boost tags (`blocker`, `critical`, `urgent`) | +8 | Positive |
| Recency boost (updated within last 24 h) | +5 | Positive |

The most important realisation was that **overdue urgency (+30) outweighs any single priority level** (max base = +40), meaning an overdue LOW-priority task (score: 10 + 30 = 40) ties with a non-overdue URGENT task (score: 40). The algorithm is saying: _"a deadline you already missed matters more than how you labelled the task."_

The second realisation was that the DONE penalty (−50) is large enough to push tasks **below zero** (e.g., DONE + LOW = 10 − 50 = −20), so completed tasks always sink to the very bottom without being filtered out. This is a deliberate design choice — the list stays as a historical record, just deprioritised.

---

### What Was Still Difficult to Understand After the Explanation

**1. Score interaction edge cases — can a DONE task still surface?**

Working through the math manually resolved this: a DONE + URGENT + overdue task scores 40 + 30 − 50 = **20**, which still beats a LOW TODO with no due date (score: **10**). So yes, a completed task can outrank a pending one. This feels unintuitive and is not tested by the existing test suite.

**2. The `recency boost` truncation behaviour**

The line `ChronoUnit.DAYS.between(task.getUpdatedAt(), LocalDateTime.now())` truncates to whole days. A task updated 23 hours ago counts as 0 days (< 1 → +5 boost). A task updated 25 hours ago counts as 1 day (not < 1 → no boost). The cutoff is not "was this updated today?" — it is "was this updated in the last calendar-day boundary?" This makes the boost non-deterministic depending on the time of day the score is computed.

**3. Tie-breaking is undefined**

When two tasks produce identical scores, there is no secondary sort. The ordering depends on whichever order `tasks.stream()` produces, which for tasks loaded from a `HashMap` is unpredictable. Two equal-scoring tasks can swap positions between runs.

**4. Version inconsistency between the shown code and the file**

The code shown in the question re-creates the `priorityWeights` Map _inside_ `calculateTaskScore` on every invocation. The actual file in the repository correctly declares it as `private static final`. This matters: if `calculateTaskScore` is called in a tight loop (e.g., sorting 1 000 tasks), the shown version allocates a new `Map.of(...)` for every task, whereas the file version allocates it once at class load. This was easy to miss without comparing both versions side by side.

---

### How to Explain This to Another Junior Developer

> **"Think of it like a doctor's triage score sheet."**
>
> Imagine each task walks into an emergency room. The nurse fills out a score card:
>
> - **Start with the label:** `URGENT` tasks get 40 points, `HIGH` gets 30, `MEDIUM` gets 20, `LOW` gets 10. That's your starting number.
>
> - **Check the deadline:** If the deadline has already passed, add 30 points — that's a big red flag. If it's due today, add 20. Due in the next two days? Add 15. Due this week? Add 10. No deadline? Nothing changes.
>
> - **Check if it's already handled:** If the task is DONE, subtract 50 points — it should go to the back of the queue. If it's in REVIEW (nearly done), subtract 15.
>
> - **Check for emergency tags:** If the task is labelled `blocker`, `critical`, or `urgent`, add 8 bonus points — someone really needs this fixed.
>
> - **Check if it was touched recently:** If someone updated it in the last day, add 5 points — it's actively being worked on and probably relevant.
>
> Once every task has a score, sort them highest-to-lowest. The task at the top is what you should work on next.
>
> The key insight is that **missing a deadline matters more than how you prioritised the task upfront.** A low-priority task you forgot about for a week can outrank a high-priority task due next month.

---

### Testing Understanding Against the AI

The test suite (`TaskPriorityManagerTest`) was used to verify the mental model. Running through the `sortTasksByImportance_shouldSortTasksCorrectly` test manually confirmed the score order:

| Task | Calculation | Score |
|---|---|---|
| `urgentOverdueTask` (URGENT + overdue) | 4×10 + 30 = | **70** |
| `highPriorityTask` (HIGH, no due date) | 3×10 = | **30** |
| `lowPriorityTask` (LOW, no due date) | 1×10 = | **10** |
| `completedTask` (HIGH + DONE) | 3×10 − 50 = | **−20** |

This matches the test assertions exactly. The model held up.

One gap found: no test covers a DONE task with an overdue date. Manually: MEDIUM + DONE + overdue = 20 − 50 + 30 = **0**. It would place between `lowPriorityTask` (10) and `completedTask` (−20) — a subtle ordering the tests don't catch.

---

### How to Improve the Algorithm

**1. Extract magic numbers as named constants**
```java
// Before (buried in if-chain)
score += 30;

// After (self-documenting)
private static final int OVERDUE_BOOST        = 30;
private static final int DUE_TODAY_BOOST      = 20;
private static final int DUE_SOON_BOOST       = 15;
private static final int DUE_THIS_WEEK_BOOST  = 10;
private static final int DONE_PENALTY         = -50;
private static final int REVIEW_PENALTY       = -15;
private static final int BOOST_TAG_BONUS      = 8;
private static final int RECENCY_BONUS        = 5;
```

**2. Add a secondary sort to break ties deterministically**
```java
// Sort by score descending, then by creation date ascending (older first)
.sorted(Comparator.comparing(TaskPriorityManager::calculateTaskScore).reversed()
        .thenComparing(Task::getCreatedAt))
```

**3. Floor the score at zero** to prevent completed tasks from going negative (a cosmetic improvement for display purposes):
```java
return Math.max(0, score);
```

**4. Make the overdue boost scale with how overdue the task is** instead of a flat +30, so a 30-day overdue task ranks above a 1-day overdue task:
```java
if (daysUntilDue < 0) {
    // Cap at +50 to prevent runaway scores; minimum +30
    score += Math.min(30 + (int) Math.abs(daysUntilDue), 50);
}
```

**5. Replace the binary recency boost with a decay curve** so a task updated 1 hour ago scores higher than one updated 20 hours ago:
```java
long hoursSinceUpdate = ChronoUnit.HOURS.between(task.getUpdatedAt(), LocalDateTime.now());
if (hoursSinceUpdate < 24) {
    score += Math.max(0, RECENCY_BONUS - (int)(hoursSinceUpdate / 5)); // decays every 5 hours
}
```

**6. Make weights configurable** by accepting a `ScoringConfig` object rather than hardcoding values — this would allow different teams or projects to tune the algorithm without touching the source code.

