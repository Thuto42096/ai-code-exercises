package za.co.wethinkcode.taskmanager.util;

import za.co.wethinkcode.taskmanager.model.Task;
import za.co.wethinkcode.taskmanager.model.TaskPriority;
import za.co.wethinkcode.taskmanager.model.TaskStatus;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class that implements a multi-factor weighted triage scoring algorithm
 * for {@link Task} objects.
 *
 * <p>Each task is assigned an integer "importance score" derived from five independent
 * dimensions, applied in order:
 * <ol>
 *   <li><b>Base priority weight</b> — {@code TaskPriority} level multiplied by 10
 *       (LOW=10, MEDIUM=20, HIGH=30, URGENT=40).</li>
 *   <li><b>Due-date urgency boost</b> — flat bonus based on how soon the task is due
 *       (overdue +30, due today +20, ≤2 days +15, ≤7 days +10, no deadline +0).</li>
 *   <li><b>Status penalty</b> — completed or near-complete tasks are pushed down
 *       (DONE −50, REVIEW −15).</li>
 *   <li><b>Boost-tag bonus</b> — +8 if the task carries at least one of the tags
 *       {@code blocker}, {@code critical}, or {@code urgent}.</li>
 *   <li><b>Recency bonus</b> — +5 if the task was last updated within the current
 *       calendar day (as measured by whole-day truncation via {@link ChronoUnit#DAYS}).</li>
 * </ol>
 *
 * <p>Scores are <em>not</em> clamped: a DONE task can produce a negative value
 * (e.g. DONE + LOW = 10 − 50 = −20). Callers that need a non-negative display
 * value should apply {@code Math.max(0, score)} themselves.
 *
 * <p>All methods are {@code static}; this class is not meant to be instantiated.
 *
 * <p><b>Thread safety:</b> All methods are stateless and safe for concurrent use.
 * The two {@code static final} constant maps ({@code PRIORITY_WEIGHTS},
 * {@code BOOST_TAGS}) are immutable.
 *
 * <p><b>Example — rank a mixed list and pick the top two:</b>
 * <pre>{@code
 * Task urgent = new Task("Fix prod outage", "Server down", TaskPriority.URGENT,
 *         LocalDateTime.now().minusDays(1), List.of("blocker"));
 * Task low    = new Task("Update README");
 * low.setPriority(TaskPriority.LOW);
 *
 * List<Task> top2 = TaskPriorityManager.getTopPriorityTasks(
 *         List.of(low, urgent), 2);
 * // top2.get(0) == urgent  (score: 40 + 30 + 8 + 5 = 83)
 * // top2.get(1) == low     (score: 10 + 5 = 15, assuming updated today)
 * }</pre>
 *
 * @see Task
 * @see TaskPriority
 * @see TaskStatus
 */
public class TaskPriorityManager {

    /** Base score multiplier weights indexed by priority level. */
    private static final Map<TaskPriority, Integer> PRIORITY_WEIGHTS = Map.of(
        TaskPriority.LOW,    1,
        TaskPriority.MEDIUM, 2,
        TaskPriority.HIGH,   3,
        TaskPriority.URGENT, 4
    );

    /** Tags that give a task an extra score boost. */
    private static final List<String> BOOST_TAGS = List.of("blocker", "critical", "urgent");

    /**
     * Calculates a composite importance score for a single {@link Task}.
     *
     * <p>The score is the sum of five additive components:
     *
     * <table border="1" summary="Scoring components">
     *   <tr><th>Component</th><th>Condition</th><th>Points</th></tr>
     *   <tr><td>Base priority</td><td>LOW / MEDIUM / HIGH / URGENT</td><td>+10 / +20 / +30 / +40</td></tr>
     *   <tr><td>Due-date urgency</td><td>overdue</td><td>+30</td></tr>
     *   <tr><td></td><td>due today (0 whole days remaining)</td><td>+20</td></tr>
     *   <tr><td></td><td>due within 2 days</td><td>+15</td></tr>
     *   <tr><td></td><td>due within 7 days</td><td>+10</td></tr>
     *   <tr><td></td><td>no due date or due in &gt;7 days</td><td>+0</td></tr>
     *   <tr><td>Status penalty</td><td>DONE</td><td>−50</td></tr>
     *   <tr><td></td><td>REVIEW</td><td>−15</td></tr>
     *   <tr><td></td><td>TODO / IN_PROGRESS</td><td>+0</td></tr>
     *   <tr><td>Boost-tag bonus</td><td>has "blocker", "critical", or "urgent" tag</td><td>+8</td></tr>
     *   <tr><td>Recency bonus</td><td>updated within the last whole day</td><td>+5</td></tr>
     * </table>
     *
     * <p><b>Important edge cases:</b>
     * <ul>
     *   <li><b>Null due date</b> — no urgency bonus is applied; the due-date block is
     *       skipped entirely.</li>
     *   <li><b>Negative scores</b> — possible when the status penalty outweighs all
     *       positive components (e.g. DONE + LOW = −40).</li>
     *   <li><b>DONE task can outscore a TODO task</b> — if a DONE task is overdue and
     *       URGENT it scores 40 + 30 − 50 = 20, which beats a no-date LOW task (10).
     *       This is by design: completed tasks sink but are not hidden.</li>
     *   <li><b>Recency truncation</b> — {@link ChronoUnit#DAYS} truncates to whole days,
     *       not a rolling 24-hour window. A task updated 23 h 59 m ago may still receive
     *       the bonus; a task updated exactly 24 h ago will not.</li>
     *   <li><b>Tie-breaking</b> — tasks with identical scores are ordered by the
     *       underlying stream encounter order, which is non-deterministic when tasks are
     *       loaded from a {@code HashMap}.</li>
     * </ul>
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * Task t = new Task("Deploy hotfix", "Fix NPE in prod",
     *         TaskPriority.HIGH, LocalDateTime.now().minusDays(1), List.of("blocker"));
     * int score = TaskPriorityManager.calculateTaskScore(t);
     * // score == 30 (HIGH base) + 30 (overdue) + 8 (blocker tag) + 5 (just created) = 73
     * }</pre>
     *
     * @param task the {@link Task} to score; must not be {@code null}.
     *             The task's {@code priority}, {@code status}, {@code updatedAt},
     *             {@code dueDate} (may be {@code null}), and {@code tags} fields
     *             are all read by this method.
     * @return the composite integer importance score; may be negative for completed tasks
     * @throws NullPointerException if {@code task} is {@code null}, or if
     *                              {@code task.getPriority()}, {@code task.getStatus()},
     *                              or {@code task.getUpdatedAt()} returns {@code null}
     */
    public static int calculateTaskScore(Task task) {
        // Calculate base score from priority
        int score = PRIORITY_WEIGHTS.getOrDefault(task.getPriority(), 0) * 10;

        // Add due date factor (higher score for tasks due sooner)
        if (task.getDueDate() != null) {
            long daysUntilDue = ChronoUnit.DAYS.between(
                LocalDateTime.now(),
                task.getDueDate()
            );

            if (daysUntilDue < 0) { // Overdue tasks
                score += 30;
            } else if (daysUntilDue == 0) { // Due today
                score += 20;
            } else if (daysUntilDue <= 2) { // Due in next 2 days
                score += 15;
            } else if (daysUntilDue <= 7) { // Due in next week
                score += 10;
            }
        }

        // Reduce score for tasks that are completed or in review
        if (task.getStatus() == TaskStatus.DONE) {
            score -= 50;
        } else if (task.getStatus() == TaskStatus.REVIEW) {
            score -= 15;
        }

        // Boost score for tasks with certain tags
        if (task.getTags().stream().anyMatch(BOOST_TAGS::contains)) {
            score += 8;
        }

        // Boost score for recently updated tasks
        long daysSinceUpdate = ChronoUnit.DAYS.between(
            task.getUpdatedAt(),
            LocalDateTime.now()
        );
        if (daysSinceUpdate < 1) {
            score += 5;
        }

        return score;
    }

    /**
     * Returns a new list containing all tasks from {@code tasks}, sorted by
     * their {@linkplain #calculateTaskScore composite importance score} in
     * descending order (highest score first).
     *
     * <p>The original list is <em>not</em> modified. A fresh {@link java.util.List}
     * is returned for every call.
     *
     * <p><b>Tie-breaking:</b> tasks with equal scores retain the encounter order of
     * the source list (stable sort via {@link java.util.stream.Stream#sorted}), except
     * when the source collection is unordered (e.g. a {@code HashSet} or the values of
     * a {@code HashMap}), in which case the relative order is non-deterministic.
     *
     * <p><b>Performance:</b> {@code calculateTaskScore} is invoked once per task during
     * the sort comparison. For large lists, consider caching scores externally if
     * repeated sorting is required.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * List<Task> ranked = TaskPriorityManager.sortTasksByImportance(allTasks);
     * ranked.forEach(t -> System.out.println(t.getTitle()));
     * }</pre>
     *
     * @param tasks the list of tasks to sort; must not be {@code null}.
     *              The list itself and its elements must not be {@code null}.
     *              An empty list returns an empty list.
     * @return a new, mutable {@link java.util.List} of all tasks sorted by score descending;
     *         never {@code null}
     * @throws NullPointerException if {@code tasks} is {@code null} or contains a
     *                              {@code null} element
     * @see #calculateTaskScore(Task)
     * @see #getTopPriorityTasks(List, int)
     */
    public static List<Task> sortTasksByImportance(List<Task> tasks) {
        return tasks
            .stream()
            .sorted(
                Comparator.comparing(
                    TaskPriorityManager::calculateTaskScore
                ).reversed()
            )
            .collect(Collectors.toList());
    }

    /**
     * Returns the {@code limit} highest-scoring tasks from {@code tasks},
     * sorted by descending {@linkplain #calculateTaskScore importance score}.
     *
     * <p>This is a convenience wrapper around
     * {@link #sortTasksByImportance(List)} followed by
     * {@link java.util.stream.Stream#limit(long)}. The original list is not modified.
     *
     * <p><b>Edge cases:</b>
     * <ul>
     *   <li>If {@code limit} is greater than or equal to {@code tasks.size()},
     *       all tasks are returned in score-descending order (equivalent to
     *       {@link #sortTasksByImportance}).</li>
     *   <li>If {@code limit} is {@code 0}, an empty list is returned.</li>
     *   <li>If {@code tasks} is empty, an empty list is returned regardless of
     *       {@code limit}.</li>
     *   <li>Negative {@code limit} values are forwarded to
     *       {@link java.util.stream.Stream#limit(long)}, which will throw
     *       {@link IllegalArgumentException}.</li>
     * </ul>
     *
     * <p><b>Example — fetch the three most urgent tasks for a dashboard widget:</b>
     * <pre>{@code
     * List<Task> dashboard = TaskPriorityManager.getTopPriorityTasks(allTasks, 3);
     * dashboard.forEach(t ->
     *     System.out.printf("[%d] %s%n",
     *         TaskPriorityManager.calculateTaskScore(t), t.getTitle()));
     * }</pre>
     *
     * @param tasks the pool of tasks to select from; must not be {@code null} and
     *              must not contain {@code null} elements
     * @param limit the maximum number of tasks to return; must be ≥ 0
     * @return a new, mutable {@link java.util.List} containing at most {@code limit}
     *         tasks ordered by score descending; never {@code null}
     * @throws NullPointerException     if {@code tasks} is {@code null} or contains a
     *                                  {@code null} element
     * @throws IllegalArgumentException if {@code limit} is negative
     * @see #sortTasksByImportance(List)
     * @see #calculateTaskScore(Task)
     */
    public static List<Task> getTopPriorityTasks(List<Task> tasks, int limit) {
        return sortTasksByImportance(tasks)
            .stream()
            .limit(limit)
            .collect(Collectors.toList());
    }
}
