# Code Comprehension Journal — Task Manager

---

## Part 1: Task Creation & Updates

### Main Components Involved

| Component | Role |
|---|---|
| `TaskManagerCli` | Presentation layer. Parses raw CLI args, validates argument counts, and calls the service layer. |
| `TaskManager` | Application service. Converts primitive inputs (strings, ints) into domain objects and orchestrates storage. |
| `Task` | Domain model. Owns all task fields plus business methods (`update`, `markAsDone`, `isOverdue`, `addTag`, `removeTag`). |
| `TaskPriority` / `TaskStatus` | Typed enums that prevent invalid values at compile time and provide `fromValue()` factories for deserialisation. |
| `TaskStorage` | Persistence layer. Maintains a `HashMap<String, Task>` as the in-memory store and serialises it to/from `tasks.json` via Gson. |

---

### Execution Flow — Task Creation

```
User types: create "Buy milk" "grocery run" 2 2025-06-01 shopping
                    │
         TaskManagerCli.main()
                    │  parses args[0..4]
         handleCreateCommand(args)
                    │  calls:
         TaskManager.createTask(title, desc, priorityValue, dueDateStr, tags)
                    │
                    ├─ TaskPriority.fromValue(2)  →  MEDIUM
                    ├─ LocalDate.parse("2025-06-01")  →  LocalDateTime (end-of-day)
                    │  (returns null + prints error if format is wrong)
                    │
                    ├─ new Task(title, desc, MEDIUM, dueDate, tags)
                    │      UUID assigned, status = TODO, createdAt = now()
                    │
                    └─ TaskStorage.addTask(task)
                               │
                               ├─ tasks.put(task.getId(), task)   [in-memory]
                               └─ save()  →  Gson.toJson → tasks.json
                    │
         Returns task UUID to CLI → printed to stdout
```

---

### Execution Flow — Task Update (e.g., priority change)

```
User types: priority <task_id> 3
                    │
         handlePriorityCommand(args)
                    │
         TaskManager.updateTaskPriority(taskId, 3)
                    │
                    ├─ TaskPriority.fromValue(3)  →  HIGH
                    │  (returns false + stderr if value is out of range 1-4)
                    │
                    ├─ new Task("tempTitle")          ← patch/diff object pattern
                    │      .setPriority(HIGH)
                    │
                    └─ TaskStorage.updateTask(taskId, patchTask)
                               │
                               ├─ getTask(taskId)  →  null check (returns false)
                               ├─ task.update(patchTask)   ← null-safe merge
                               │      only non-null fields are copied
                               │      updatedAt = now()
                               └─ save()  →  tasks.json rewritten in full
```

---

### How Data Is Stored and Retrieved

**Storage medium:** A single flat JSON file (`tasks.json`) in the working directory.

**In-memory representation:** `HashMap<String, Task>` keyed by UUID string. Lookups are O(1).

**Write strategy:** Every mutating operation (add, update, delete, tag) calls `save()`, which rewrites the *entire* file from the current HashMap contents. There is no append-only or diff-based write.

**Serialisation:** Gson with two hand-written inner-class adapters:
- `LocalDateTimeSerializer` → formats `LocalDateTime` as ISO-8601 string.
- `LocalDateTimeDeserializer` → parses it back. Required because Gson has no built-in support for `java.time` types.

**Read strategy:** `load()` is called once in the `TaskStorage` constructor. It deserialises the JSON array into `Task[]` and populates the HashMap. Subsequent reads operate entirely in memory.

---

### Interesting Design Patterns

1. **Patch Object / Partial Update Pattern** — `updateTaskPriority` and `updateTaskDueDate` create a temporary `Task("tempTitle")`, set only the changed field, then pass it to `task.update(patchTask)`. The `update()` method applies only non-null fields. This avoids separate setter paths for every updatable field.

2. **Value-Object Enums** — Both `TaskPriority` and `TaskStatus` carry a serialisable value (`int` / `String`) alongside a static `fromValue()` factory. This decouples the wire format from the enum constant name, making renaming safe.

3. **Layered Architecture** — CLI → Service → Storage with no layer bypassing another. The CLI never touches storage directly; storage never formats output.

4. **Mock-Friendly Inheritance in Tests** — `TaskManagerTest` overrides `getStorage()` using anonymous subclasses to inject mock storage without a DI framework. This is a lightweight substitute for constructor injection.

---

## Part 2: Marking a Task as Complete

### Entry Points and Components

```
CLI entry point:    TaskManagerCli.handleStatusCommand(args)
Service method:     TaskManager.updateTaskStatus(taskId, "done")
Domain mutation:    Task.markAsDone()
Persistence:        TaskStorage.save()
```

### Code That Handles State Changes

**`TaskManager.updateTaskStatus` (lines 65-77):**
```java
TaskStatus newStatus = TaskStatus.fromValue(newStatusValue);  // validate string
Task task = getStorage().getTask(taskId);                      // null check
if (task != null) {
    task.setStatus(newStatus);          // always set status
    if (newStatus == TaskStatus.DONE) {
        task.markAsDone();              // special path for completion
    }
    getStorage().save();
    return true;
}
return false;
```

**`Task.markAsDone` (lines 146-150):**
```java
public void markAsDone() {
    this.status = TaskStatus.DONE;
    this.completedAt = LocalDateTime.now();
    this.updatedAt = this.completedAt;   // updatedAt and completedAt share the same instant
}
```

> Note: `setStatus(DONE)` is called *before* `markAsDone()`, meaning `status` is technically set twice. `markAsDone` sets it again — harmless but redundant.

---

### Data Flow Diagram — Task Completion

```
┌─────────────────────────────────────────────────────────────────────┐
│  USER INPUT                                                         │
│  $ status <uuid> done                                               │
└────────────────────────┬────────────────────────────────────────────┘
                         │ args[]
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  TaskManagerCli.handleStatusCommand(args)                           │
│  • Validates args.length >= 2                                       │
│  • Extracts taskId = args[0], newStatus = args[1]                   │
│  • Delegates to TaskManager                                         │
└────────────────────────┬────────────────────────────────────────────┘
                         │ (taskId, "done")
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  TaskManager.updateTaskStatus(taskId, "done")                       │
│  • TaskStatus.fromValue("done") → DONE enum        [can throw]      │
│  • storage.getTask(taskId)      → Task or null     [null = false]   │
│  • task.setStatus(DONE)                                             │
│  • task.markAsDone()            → sets completedAt + updatedAt      │
│  • storage.save()                                                   │
└────────────────────────┬────────────────────────────────────────────┘
                         │ mutates in-memory Task object
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Task (in-memory, inside HashMap)                                   │
│  BEFORE:  status=IN_PROGRESS, completedAt=null                      │
│  AFTER:   status=DONE, completedAt=now(), updatedAt=now()           │
└────────────────────────┬────────────────────────────────────────────┘
                         │ storage.save()
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  TaskStorage.save()                                                 │
│  • Gson.toJson(tasks.values()) → JSON string                        │
│  • FileWriter overwrites tasks.json in full                         │
└─────────────────────────────────────────────────────────────────────┘
                         │
                         ▼
                   tasks.json  ✓
```

---

### State Changes During Task Completion

| Field | Before | After |
|---|---|---|
| `status` | Any (`TODO` / `IN_PROGRESS` / `REVIEW`) | `DONE` |
| `completedAt` | `null` | `LocalDateTime.now()` |
| `updatedAt` | Previous timestamp | Same instant as `completedAt` |
| All other fields | Unchanged | Unchanged |

The `isOverdue()` method automatically returns `false` once `status == DONE`, so overdue logic self-corrects without needing an extra field change.

---

### Potential Points of Failure

| # | Point of Failure | Consequence | Mitigation in code |
|---|---|---|---|
| 1 | `TaskStatus.fromValue("done")` — invalid string passed | `IllegalArgumentException` thrown; bubbles up uncaught through CLI | CLI prints no friendly error; stack trace visible to user |
| 2 | `storage.getTask(taskId)` returns `null` (wrong/partial UUID) | `updateTaskStatus` returns `false`; CLI prints generic "Task not found" | Handled — the null check is explicit |
| 3 | `setStatus(DONE)` called before `markAsDone()` — double-set | Status is set twice (redundant, not dangerous) | No failure, but it is a code smell |
| 4 | `FileWriter` IOException in `save()` | Error printed to stderr; in-memory state is updated but disk is stale | Only `System.err.println`; no retry, no transaction rollback |
| 5 | Concurrent process writes `tasks.json` between load and save | Last writer wins; changes from the other process are silently overwritten | No locking mechanism exists |
| 6 | Disk full during `save()` | Partial or empty `tasks.json`; all tasks lost on next `load()` | No atomic write (e.g., write-to-temp-then-rename) is used |

---

### How the Application Persists Changes

1. Every mutation ends with a call to `TaskStorage.save()`.
2. `save()` opens a `FileWriter` on `tasks.json` (creating or truncating it).
3. `Gson.toJson(tasks.values(), writer)` serialises the entire `Collection<Task>` as a JSON array.
4. `LocalDateTime` fields are converted by the custom `LocalDateTimeSerializer` to ISO-8601 strings.
5. The file is closed (via try-with-resources), flushing all bytes to disk.
6. On the next startup, `TaskStorage` constructor calls `load()`, which reads the array back and repopulates the HashMap.

**Key characteristic:** Persistence is synchronous, full-rewrite, and call-site-triggered — there is no background flush, no write-ahead log, and no dirty-flag optimisation.

