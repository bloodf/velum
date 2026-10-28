#!/usr/bin/env python3
"""Unit tests for exact body dedupe (#99). Run: python3 -m unittest tools.templates.test_body_dedupe"""
from __future__ import annotations

import copy
import random
import sys
import unittest
from pathlib import Path

# Allow `python3 tools/templates/test_body_dedupe.py` and unittest discovery.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from body_dedupe import (  # noqa: E402
    dedupe_by_exact_body,
    safe_usage_count,
    survivor_sort_key,
)


def row(
    *,
    id: str,
    name: str,
    body: str,
    category: str = "meeting",
    official: bool = False,
    featured: bool = False,
    tags: list[str] | None = None,
    usageCount: int = 0,
) -> dict:
    return {
        "id": id,
        "category": category,
        "name": name,
        "official": official,
        "featured": featured,
        "usageCount": usageCount,
        "description": "d",
        "bodyType": "outline",
        "body": body,
        "lang": "en",
        "tags": tags or ["summary"],
    }


class BodyDedupeTest(unittest.TestCase):
    def test_identical_bodies_different_names_collapse(self) -> None:
        a = row(id="a", name="Zebra", body="SAME BODY TEXT HERE FOR TEST")
        b = row(id="b", name="Alpha", body="SAME BODY TEXT HERE FOR TEST", tags=["minutes"])
        result = dedupe_by_exact_body([a, b])
        self.assertEqual(result.input_rows, 2)
        self.assertEqual(result.duplicate_body_groups, 1)
        self.assertEqual(result.removed_rows, 1)
        self.assertEqual(result.output_rows, 1)
        survivor = result.survivors[0]
        # Lexicographic name: Alpha before Zebra
        self.assertEqual(survivor["id"], "b")
        self.assertEqual(survivor["name"], "Alpha")
        self.assertEqual(sorted(survivor["tags"]), ["minutes", "summary"])
        self.assertEqual(result.remap, {"a": "b"})

    def test_official_beats_featured_and_name(self) -> None:
        featured = row(
            id="feat",
            name="AAA",
            body="BODY",
            featured=True,
            tags=["minutes"],
        )
        official = row(
            id="off",
            name="ZZZ",
            body="BODY",
            official=True,
            featured=True,
            tags=["decisions"],
        )
        result = dedupe_by_exact_body([featured, official])
        self.assertEqual(result.survivors[0]["id"], "off")
        self.assertTrue(result.survivors[0]["official"])
        self.assertEqual(sorted(result.survivors[0]["tags"]), ["decisions", "minutes"])

    def test_featured_beats_lexicographic_name(self) -> None:
        plain = row(id="plain", name="AAA", body="BODY")
        featured = row(id="feat", name="ZZZ", body="BODY", featured=True)
        result = dedupe_by_exact_body([plain, featured])
        self.assertEqual(result.survivors[0]["id"], "feat")

    def test_whitespace_or_case_difference_is_not_duplicate(self) -> None:
        a = row(id="a", name="A", body="Hello world")
        b = row(id="b", name="B", body="Hello world ")  # trailing space
        c = row(id="c", name="C", body="hello world")  # case
        d = row(id="d", name="D", body="Hello world\n")  # trailing newline
        result = dedupe_by_exact_body([a, b, c, d])
        self.assertEqual(result.duplicate_body_groups, 0)
        self.assertEqual(result.removed_rows, 0)
        self.assertEqual(result.output_rows, 4)

    def test_shuffle_yields_identical_survivors_and_order(self) -> None:
        bodies = [
            row(id="1", name="M", body="dup", official=True, featured=True, category="meeting"),
            row(id="2", name="A", body="dup", featured=True, category="meeting"),
            row(id="3", name="B", body="unique-b", category="call"),
            row(id="4", name="C", body="unique-c", category="sales"),
            row(id="5", name="D", body="dup2", category="general"),
            row(id="6", name="E", body="dup2", category="general", tags=["risks"]),
        ]
        baseline = dedupe_by_exact_body(bodies)
        for seed in range(20):
            shuffled = copy.deepcopy(bodies)
            random.Random(seed).shuffle(shuffled)
            again = dedupe_by_exact_body(shuffled)
            self.assertEqual(
                [r["id"] for r in again.survivors],
                [r["id"] for r in baseline.survivors],
            )
            self.assertEqual(again.remap, baseline.remap)
            self.assertEqual(
                [r["tags"] for r in again.survivors],
                [r["tags"] for r in baseline.survivors],
            )

    def test_second_pass_is_noop(self) -> None:
        rows = [
            row(id="1", name="Z", body="x"),
            row(id="2", name="A", body="x", tags=["minutes"]),
            row(id="3", name="B", body="y"),
        ]
        first = dedupe_by_exact_body(rows)
        second = dedupe_by_exact_body(first.survivors)
        self.assertEqual(second.duplicate_body_groups, 0)
        self.assertEqual(second.removed_rows, 0)
        self.assertEqual(second.output_rows, first.output_rows)
        self.assertEqual(
            [r["id"] for r in second.survivors],
            [r["id"] for r in first.survivors],
        )
        self.assertEqual(second.remap, {})

    def test_survivor_sort_key_orders_official_then_featured_then_name(self) -> None:
        o = row(id="o", name="Z", body="b", official=True)
        f = row(id="f", name="A", body="b", featured=True)
        n = row(id="n", name="M", body="b")
        ordered = sorted([n, f, o], key=survivor_sort_key)
        self.assertEqual([r["id"] for r in ordered], ["o", "f", "n"])

    def test_empty_or_whitespace_bodies_are_not_grouped(self) -> None:
        a = row(id="a", name="A", body="")
        b = row(id="b", name="B", body="   ")
        c = row(id="c", name="C", body="\t\n")
        d = row(id="d", name="D", body="real body content")
        e = row(id="e", name="E", body="real body content")
        # Missing / non-string body
        f = {"id": "f", "name": "F", "category": "meeting", "body": None, "tags": ["summary"]}
        result = dedupe_by_exact_body([a, b, c, d, e, f])
        # Only the two real identical bodies collapse; blanks stay as distinct rows.
        self.assertEqual(result.duplicate_body_groups, 1)
        self.assertEqual(result.removed_rows, 1)
        self.assertEqual(result.output_rows, 5)
        ids = {r["id"] for r in result.survivors}
        self.assertTrue({"a", "b", "c", "f"}.issubset(ids))
        self.assertEqual(len(ids & {"d", "e"}), 1)
        self.assertNotIn("a", result.remap)
        self.assertNotIn("b", result.remap)
        self.assertNotIn("c", result.remap)

    def test_safe_usage_count_guards_missing_and_non_numeric(self) -> None:
        self.assertEqual(safe_usage_count({}), 0)
        self.assertEqual(safe_usage_count({"usageCount": None}), 0)
        self.assertEqual(safe_usage_count({"usageCount": ""}), 0)
        self.assertEqual(safe_usage_count({"usageCount": "nope"}), 0)
        self.assertEqual(safe_usage_count({"usageCount": "12"}), 12)
        self.assertEqual(safe_usage_count({"uses": "3"}), 3)
        self.assertEqual(safe_usage_count({"usageCount": 7.9}), 7)
        self.assertEqual(safe_usage_count({"usageCount": True}), 0)
        a = row(id="a", name="Z", body="BODY", usageCount=0)
        a["usageCount"] = "not-a-number"
        b = row(id="b", name="A", body="BODY", usageCount=5)
        result = dedupe_by_exact_body([a, b])
        self.assertEqual(result.survivors[0]["usageCount"], 5)


if __name__ == "__main__":
    unittest.main()
