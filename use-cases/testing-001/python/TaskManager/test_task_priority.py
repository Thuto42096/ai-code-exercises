"""test_task_priority.py

Comprehensive test suite for task_priority.py

Exercise Coverage:
  Part 1   – Behavior analysis: list of 5+ identified test cases
  Part 2.1 – Improved unit test for calculate_task_score base behavior
  Part 2.2 – Comprehensive due-date calculation tests
  Part 3.1 – TDD: assignee score boost (+12)
  Part 3.2 – TDD / Bug fix: days_since_update uses .days not .seconds
  Part 4   – Integration test for the full scoring workflow
"""

import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

import unittest
from datetime import datetime, timedelta

from models import Task, TaskPriority, TaskStatus
from task_priority import (
    calculate_task_score,
    sort_tasks_by_importance,
    get_top_priority_tasks,
)


# ---------------------------------------------------------------------------
# Helper
# ---------------------------------------------------------------------------

def make_task(
    priority=TaskPriority.MEDIUM,
    due_date=None,
    status=TaskStatus.TODO,
    tags=None,
    days_since_update=2,
    assigned_to=None,
):
    """Return a Task with a controlled updated_at so the 'recent update'
    boost is predictable.  Default: updated 2 days ago → no +5 boost."""
    task = Task("Test Task", priority=priority, due_date=due_date, tags=tags or [])
    task.updated_at = datetime.now() - timedelta(days=days_since_update)
    task.status = status
    task.assigned_to = assigned_to
    return task


# ===========================================================================
# Part 1 + Part 2.1 – Base priority scores
# Five fundamental behaviors identified in the behavior-analysis conversation
# ===========================================================================

class TestBaseScore(unittest.TestCase):
    """Verify that each priority level maps to the correct base score."""

    def test_low_priority_base_score(self):
        task = make_task(priority=TaskPriority.LOW)
        self.assertEqual(calculate_task_score(task), 10)

    def test_medium_priority_base_score(self):
        task = make_task(priority=TaskPriority.MEDIUM)
        self.assertEqual(calculate_task_score(task), 20)

    def test_high_priority_base_score(self):
        task = make_task(priority=TaskPriority.HIGH)
        self.assertEqual(calculate_task_score(task), 40)

    def test_urgent_priority_base_score(self):
        task = make_task(priority=TaskPriority.URGENT)
        self.assertEqual(calculate_task_score(task), 60)

    def test_score_can_go_negative(self):
        """Edge case: DONE penalty can push score below zero."""
        task = make_task(priority=TaskPriority.LOW, status=TaskStatus.DONE)
        # 10 (LOW) - 50 (DONE) = -40
        self.assertEqual(calculate_task_score(task), -40)


# ===========================================================================
# Part 2.2 – Comprehensive due-date tests (all branches + boundaries)
# ===========================================================================

class TestDueDateScore(unittest.TestCase):
    """Verify every branch of the due-date calculation."""

    def test_no_due_date_adds_nothing(self):
        task = make_task(due_date=None)
        self.assertEqual(calculate_task_score(task), 20)  # MEDIUM base only

    def test_overdue_task_adds_35(self):
        task = make_task(due_date=datetime.now() - timedelta(days=1))
        self.assertEqual(calculate_task_score(task), 55)  # 20 + 35

    def test_due_today_adds_20(self):
        task = make_task(due_date=datetime.now() + timedelta(hours=3))
        self.assertEqual(calculate_task_score(task), 40)  # 20 + 20

    def test_due_in_one_day_adds_15(self):
        # Add 12-hour buffer so timedelta.days stays at 1 despite sub-second drift
        task = make_task(due_date=datetime.now() + timedelta(days=1, hours=12))
        self.assertEqual(calculate_task_score(task), 35)  # 20 + 15

    def test_due_in_two_days_boundary_adds_15(self):
        """Boundary value: exactly 2 days away must still add +15."""
        task = make_task(due_date=datetime.now() + timedelta(days=2))
        self.assertEqual(calculate_task_score(task), 35)  # 20 + 15

    def test_due_in_three_days_adds_10(self):
        task = make_task(due_date=datetime.now() + timedelta(days=3, hours=12))
        self.assertEqual(calculate_task_score(task), 30)  # 20 + 10

    def test_due_in_seven_days_boundary_adds_10(self):
        """Boundary value: exactly 7 days away must still add +10."""
        task = make_task(due_date=datetime.now() + timedelta(days=7))
        self.assertEqual(calculate_task_score(task), 30)  # 20 + 10

    def test_due_in_eight_days_adds_nothing(self):
        task = make_task(due_date=datetime.now() + timedelta(days=8, hours=12))
        self.assertEqual(calculate_task_score(task), 20)  # 20 + 0


# ===========================================================================
# Status penalty tests
# ===========================================================================

class TestStatusPenalty(unittest.TestCase):

    def test_todo_status_no_penalty(self):
        task = make_task(status=TaskStatus.TODO)
        self.assertEqual(calculate_task_score(task), 20)

    def test_in_progress_status_no_penalty(self):
        task = make_task(status=TaskStatus.IN_PROGRESS)
        self.assertEqual(calculate_task_score(task), 20)

    def test_review_status_subtracts_15(self):
        task = make_task(status=TaskStatus.REVIEW)
        self.assertEqual(calculate_task_score(task), 5)  # 20 - 15

    def test_done_status_subtracts_50(self):
        task = make_task(status=TaskStatus.DONE)
        self.assertEqual(calculate_task_score(task), -30)  # 20 - 50




# ===========================================================================
# Tag boost tests
# ===========================================================================

class TestTagBoost(unittest.TestCase):

    def test_blocker_tag_adds_8(self):
        task = make_task(tags=["blocker"])
        self.assertEqual(calculate_task_score(task), 28)  # 20 + 8

    def test_critical_tag_adds_8(self):
        task = make_task(tags=["critical"])
        self.assertEqual(calculate_task_score(task), 28)

    def test_urgent_tag_adds_8(self):
        task = make_task(tags=["urgent"])
        self.assertEqual(calculate_task_score(task), 28)

    def test_non_boost_tag_no_change(self):
        task = make_task(tags=["feature", "backend"])
        self.assertEqual(calculate_task_score(task), 20)

    def test_multiple_boost_tags_still_adds_8_once(self):
        """Having two boost tags should only trigger the +8 once (any())."""
        task = make_task(tags=["blocker", "critical"])
        self.assertEqual(calculate_task_score(task), 28)

    def test_empty_tags_no_change(self):
        task = make_task(tags=[])
        self.assertEqual(calculate_task_score(task), 20)


# ===========================================================================
# Part 3.2 – Bug demonstration & fix: days_since_update must use .days
#
# Bug scenario: if the code used timedelta.seconds instead of timedelta.days,
# a task updated 23 hours ago would yield seconds=82800 which is NOT < 1,
# silently dropping the +5 boost even though the task was updated recently.
# The correct implementation (timedelta.days) gives 0 < 1 → boost applied.
# ===========================================================================

class TestDaysSinceUpdateBug(unittest.TestCase):

    def test_task_updated_moments_ago_gets_boost(self):
        """timedelta(0).days == 0 → qualifies for +5 recent-update boost."""
        task = make_task(days_since_update=0)
        self.assertEqual(calculate_task_score(task), 25)  # 20 + 5

    def test_task_updated_23h_ago_gets_boost(self):
        """23 hours ago: timedelta.days == 0 → boost must be applied.
        With the buggy .seconds approach, seconds=82800 is NOT < 1 so
        the boost would be skipped — this test catches that regression."""
        task = make_task()
        task.updated_at = datetime.now() - timedelta(hours=23)
        self.assertEqual(calculate_task_score(task), 25)  # 20 + 5

    def test_task_updated_more_than_24h_ago_no_boost(self):
        """25 hours ago: timedelta.days == 1 → no boost."""
        task = make_task()
        task.updated_at = datetime.now() - timedelta(hours=25)
        self.assertEqual(calculate_task_score(task), 20)

    def test_task_updated_two_days_ago_no_boost(self):
        task = make_task(days_since_update=2)
        self.assertEqual(calculate_task_score(task), 20)


# ===========================================================================
# Part 3.1 – TDD: assignee score boost (+12)
#
# TDD cycle followed:
#   RED   – write these tests first; they fail because the feature is absent
#   GREEN – add current_user param + assigned_to field; implement +12 boost
#   REFACTOR – no structural changes needed
# ===========================================================================

class TestAssigneeBoost(unittest.TestCase):

    def test_task_assigned_to_current_user_gets_boost(self):
        task = make_task(assigned_to="alice")
        score_without = calculate_task_score(task)
        score_with = calculate_task_score(task, current_user="alice")
        self.assertEqual(score_with, score_without + 12)

    def test_task_assigned_to_different_user_no_boost(self):
        task = make_task(assigned_to="bob")
        score_without = calculate_task_score(task)
        score_with = calculate_task_score(task, current_user="alice")
        self.assertEqual(score_with, score_without)

    def test_unassigned_task_no_boost(self):
        task = make_task(assigned_to=None)
        score_without = calculate_task_score(task)
        score_with = calculate_task_score(task, current_user="alice")
        self.assertEqual(score_with, score_without)

    def test_no_current_user_provided_no_boost(self):
        """Backwards-compatible default: omitting current_user changes nothing."""
        task = make_task(assigned_to="alice")
        # MEDIUM, 2 days old, no due date → base 20 only
        self.assertEqual(calculate_task_score(task), 20)

    def test_assignee_boost_absolute_value(self):
        """Concrete score check: MEDIUM + assignee boost = 20 + 12 = 32."""
        task = make_task(assigned_to="carol")
        self.assertEqual(calculate_task_score(task, current_user="carol"), 32)



# ===========================================================================
# sort_tasks_by_importance tests
# ===========================================================================

class TestSortTasksByImportance(unittest.TestCase):

    def test_sorts_higher_priority_first(self):
        low = make_task(priority=TaskPriority.LOW)
        high = make_task(priority=TaskPriority.HIGH)
        result = sort_tasks_by_importance([low, high])
        self.assertEqual(result[0], high)
        self.assertEqual(result[1], low)

    def test_empty_list_returns_empty(self):
        self.assertEqual(sort_tasks_by_importance([]), [])

    def test_single_task_returns_list_with_that_task(self):
        task = make_task()
        self.assertEqual(sort_tasks_by_importance([task]), [task])

    def test_done_task_sorted_to_bottom(self):
        """A DONE medium task (20-50=-30) scores well below an active LOW task (10)."""
        done = make_task(status=TaskStatus.DONE, priority=TaskPriority.MEDIUM)
        active = make_task(priority=TaskPriority.LOW)
        result = sort_tasks_by_importance([done, active])
        self.assertEqual(result[-1], done)


# ===========================================================================
# get_top_priority_tasks tests
# ===========================================================================

class TestGetTopPriorityTasks(unittest.TestCase):

    def test_returns_up_to_limit(self):
        tasks = [make_task() for _ in range(10)]
        result = get_top_priority_tasks(tasks, limit=3)
        self.assertEqual(len(result), 3)

    def test_default_limit_is_five(self):
        tasks = [make_task() for _ in range(10)]
        result = get_top_priority_tasks(tasks)
        self.assertEqual(len(result), 5)

    def test_limit_greater_than_tasks_returns_all(self):
        tasks = [make_task() for _ in range(3)]
        result = get_top_priority_tasks(tasks, limit=10)
        self.assertEqual(len(result), 3)

    def test_empty_list_returns_empty(self):
        self.assertEqual(get_top_priority_tasks([]), [])


# ===========================================================================
# Part 4 – Integration tests (full workflow)
# Verify calculate_task_score → sort_tasks_by_importance →
# get_top_priority_tasks work correctly together on realistic data.
# ===========================================================================

class TestIntegrationWorkflow(unittest.TestCase):

    def setUp(self):
        # urgent_overdue  → 60 + 35 = 95
        self.urgent_overdue = make_task(
            priority=TaskPriority.URGENT,
            due_date=datetime.now() - timedelta(days=1),
        )
        # high_due_today  → 40 + 20 = 60
        self.high_due_today = make_task(
            priority=TaskPriority.HIGH,
            due_date=datetime.now() + timedelta(hours=3),
        )
        # medium_blocker  → 20 + 8 = 28
        self.medium_blocker = make_task(
            priority=TaskPriority.MEDIUM,
            tags=["blocker"],
        )
        # low_no_due      → 10
        self.low_no_due = make_task(priority=TaskPriority.LOW)
        # done_urgent     → 60 - 50 = 10
        self.done_urgent = make_task(
            priority=TaskPriority.URGENT,
            status=TaskStatus.DONE,
        )
        self.all_tasks = [
            self.low_no_due,
            self.done_urgent,
            self.medium_blocker,
            self.high_due_today,
            self.urgent_overdue,
        ]

    def test_highest_scoring_task_is_first(self):
        result = sort_tasks_by_importance(self.all_tasks)
        self.assertEqual(result[0], self.urgent_overdue)

    def test_correct_top_3_ordering(self):
        top3 = get_top_priority_tasks(self.all_tasks, limit=3)
        self.assertEqual(top3[0], self.urgent_overdue)
        self.assertEqual(top3[1], self.high_due_today)
        self.assertEqual(top3[2], self.medium_blocker)

    def test_done_task_not_in_top_3(self):
        top3 = get_top_priority_tasks(self.all_tasks, limit=3)
        self.assertNotIn(self.done_urgent, top3)

    def test_top_priority_respects_limit(self):
        top2 = get_top_priority_tasks(self.all_tasks, limit=2)
        self.assertEqual(len(top2), 2)

    def test_full_workflow_single_top_task(self):
        top1 = get_top_priority_tasks(self.all_tasks, limit=1)
        self.assertEqual(top1[0], self.urgent_overdue)


if __name__ == "__main__":
    unittest.main()
