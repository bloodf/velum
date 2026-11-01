#!/usr/bin/env python3
"""Regression tests for exact-body dedupe (#99) and the Featured/official gate (#101).

Run: python3 -m unittest discover -s tools/templates -p "test_*.py"
(also wired into `:core:jvmTest` via the `verifyTemplateCatalog` Gradle task).
"""
from __future__ import annotations

import gzip
import json
import random
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))

import assert_hygiene  # noqa: E402
import dedupe_catalog  # noqa: E402
from clean import dedupe_exact_bodies  # noqa: E402

BODY = "## Summary\n- Key points discussed in the meeting\n- Action items with owners"


def row(tid: str, name: str, body: str = BODY, *, category: str = "meeting", **extra) -> dict:
    r = {
        "id": tid,
        "categoryId": "cat_" + category,
        "category": category,
        "name": name,
        "author": "Velum",
        "official": False,
        "featured": False,
        "usageCount": 0,
        "description": "",
        "bodyType": "prompt",
        "body": body,
        "lang": "en",
        "tags": ["meeting-minutes"],
        "source": "Velum template catalog",
        "attribution": "Community template (cleaned for Velum)",
    }
    r.update(extra)
    return r


class DedupeExactBodiesTest(unittest.TestCase):
    def test_identical_bodies_with_different_names_collapse_to_one(self):
        res = dedupe_exact_bodies([row("b", "Beta"), row("a", "Alpha"), row("c", "Gamma")])
        self.assertEqual(["a"], [r["id"] for r in res.survivors])
        self.assertEqual({"b": "a", "c": "a"}, res.aliases)
        self.assertEqual((3, 1, 2, 1), (res.input_rows, res.duplicate_groups, res.removed_rows, res.output_rows))

    def test_official_beats_featured_beats_name(self):
        rows = [row("a", "Alpha"), row("f", "Zeta", featured=True), row("o", "Zulu", official=True)]
        self.assertEqual(["o"], [r["id"] for r in dedupe_exact_bodies(rows).survivors])
        rows = [row("a", "Alpha"), row("f", "Zeta", featured=True)]
        self.assertEqual(["f"], [r["id"] for r in dedupe_exact_bodies(rows).survivors])

    def test_featured_precedence_from_id_set(self):
        rows = [row("a", "Alpha"), row("z", "Zeta")]
        res = dedupe_exact_bodies(rows, featured_ids=frozenset({"z"}))
        self.assertEqual(["z"], [r["id"] for r in res.survivors])

    def test_flags_are_preserved_on_survivor(self):
        rows = [row("a", "Alpha", featured=True), row("o", "Omega", official=True)]
        (survivor,) = dedupe_exact_bodies(rows).survivors
        self.assertEqual("o", survivor["id"])
        self.assertTrue(survivor["official"])
        self.assertTrue(survivor["featured"])
        self.assertFalse(rows[1]["featured"], "input rows must not be mutated")

    def test_same_name_tie_breaks_on_canonical_row_content(self):
        rows = [row("y", "Same", usageCount=5), row("x", "Same", usageCount=9)]
        res = dedupe_exact_bodies(rows)
        # Canonical JSON (sorted keys) compares "id":"x" < "id":"y".
        self.assertEqual(["x"], [r["id"] for r in res.survivors])

    def test_name_order_is_plain_codepoint_order(self):
        rows = [row("lower", "alpha"), row("upper", "Beta")]
        self.assertEqual(["upper"], [r["id"] for r in dedupe_exact_bodies(rows).survivors])

    def test_whitespace_or_case_differences_are_distinct_bodies(self):
        rows = [
            row("a", "A", BODY),
            row("b", "B", BODY + " "),
            row("c", "C", BODY.upper()),
            row("d", "D", BODY.replace("\n", "\r\n")),
        ]
        res = dedupe_exact_bodies(rows)
        self.assertEqual(4, res.output_rows)
        self.assertEqual(0, res.duplicate_groups)

    def test_shuffled_input_gives_identical_survivors_and_order(self):
        rows = [row(f"id{i}", f"Name {i % 7}", f"{BODY}\n- variant {i % 5}", official=(i == 11)) for i in range(40)]
        baseline = dedupe_exact_bodies(rows)
        for seed in range(5):
            shuffled = rows[:]
            random.Random(seed).shuffle(shuffled)
            again = dedupe_exact_bodies(shuffled)
            self.assertEqual(baseline.survivors, again.survivors)
            self.assertEqual(baseline.aliases, again.aliases)

    def test_second_run_is_a_no_op(self):
        rows = [row("b", "Beta"), row("a", "Alpha"), row("c", "Gamma", BODY + "!")]
        once = dedupe_exact_bodies(rows)
        twice = dedupe_exact_bodies(once.survivors)
        self.assertEqual(once.survivors, twice.survivors)
        self.assertEqual(0, twice.removed_rows)


class DedupeCatalogTest(unittest.TestCase):
    def _write(self, d: Path, full: list[dict]) -> tuple[Path, Path, Path, Path]:
        full_path = d / "full.jsonl.gz"
        with gzip.open(full_path, "wt", encoding="utf-8") as f:
            for r in full:
                f.write(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n")
        featured_path = d / "featured.jsonl"
        featured_path.write_text(
            "".join(json.dumps(r, separators=(",", ":")) + "\n" for r in full if r["featured"]),
            encoding="utf-8",
        )
        return full_path, featured_path, d / "Aliases.kt", d / "report.json"

    def _run(self, paths) -> dict:
        full_path, featured_path, kt, report = paths
        return dedupe_catalog.run(
            full=full_path, featured=[featured_path], aliases_kt=kt, report=report
        )

    def test_catalog_run_writes_aliases_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            full = [row("plaud.a", "Alpha", featured=True), row("plaud.b", "Beta"), row("plaud.c", "C", BODY + "?")]
            paths = self._write(d, full)
            first = self._run(paths)
            self.assertEqual((3, 1, 1, 2), (first["input_rows"], first["duplicate_body_groups"], first["removed_rows"], first["output_rows"]))
            kt = paths[2].read_text(encoding="utf-8")
            self.assertIn('"plaud.b" to "plaud.a"', kt)
            gz_bytes = paths[0].read_bytes()
            second = self._run(paths)
            self.assertEqual(0, second["removed_rows"])
            self.assertEqual(gz_bytes, paths[0].read_bytes(), "second run must not change the gzip")
            self.assertIn('"plaud.b" to "plaud.a"', paths[2].read_text(encoding="utf-8"), "aliases accumulate")

    def test_removed_featured_row_is_replaced_by_its_survivor(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            full = [row("plaud.o", "Zulu", official=True), row("plaud.f", "Alpha", featured=True)]
            paths = self._write(d, full)
            self._run(paths)
            featured = [json.loads(l) for l in paths[1].read_text(encoding="utf-8").splitlines()]
            self.assertEqual(["plaud.o"], [r["id"] for r in featured])
            self.assertTrue(featured[0]["featured"] and featured[0]["official"])


def featured_set(per_cat: int, *, cats=assert_hygiene.CATEGORY_ORDER, dup_in: str | None = None) -> list[dict]:
    rows = []
    for cat in cats:
        for i in range(per_cat):
            body = f"{BODY}\n- {cat} item {i}"
            if dup_in == cat and i == per_cat - 1:
                body = f"{BODY}\n- {cat} item 0"  # same body as item 0, different name
            rows.append(row(f"plaud.{cat}.{i}", f"{cat} {i}", body, category=cat, featured=True, official=(cat == "general" and i == 0)))
    return rows


class FeaturedGateTest(unittest.TestCase):
    def gate(self, featured, full=None, baseline=("plaud.general.0",)):
        full = featured if full is None else full
        return assert_hygiene.featured_gate_errors(featured, full, min_per_category=3, official_baseline=list(baseline))

    def test_passes_with_enough_distinct_bodies(self):
        self.assertEqual([], self.gate(featured_set(3)))

    def test_category_below_minimum_fails(self):
        rows = [r for r in featured_set(3) if r["id"] != "plaud.legal.2"]
        self.assertTrue(any("legal" in e for e in self.gate(rows)))

    def test_missing_category_fails(self):
        rows = [r for r in featured_set(3) if r["category"] != "it"]
        self.assertTrue(any("it has 0" in e for e in self.gate(rows)))

    def test_duplicate_bodies_with_different_names_do_not_inflate_count(self):
        rows = featured_set(3, dup_in="sales")
        errors = self.gate(rows)
        self.assertTrue(any("sales has 2 distinct" in e for e in errors), errors)

    def test_missing_official_template_fails(self):
        rows = featured_set(3)
        errors = self.gate(rows, baseline=("plaud.general.0", "plaud.gone"))
        self.assertTrue(any("plaud.gone" in e for e in errors), errors)
        demoted = [dict(r, official=False) if r["id"] == "plaud.general.0" else r for r in rows]
        self.assertTrue(any("plaud.general.0" in e for e in self.gate(demoted)))

    def test_featured_entry_absent_from_full_fails(self):
        rows = featured_set(3)
        errors = self.gate(rows, full=rows[1:])
        self.assertTrue(any(rows[0]["id"] in e for e in errors), errors)


class ShippedCatalogTest(unittest.TestCase):
    """The bundled catalogs themselves must pass the #99/#101 gate."""

    def test_shipped_catalog_passes_gate(self):
        res = ROOT / "core" / "src" / "commonMain" / "resources"
        code = assert_hygiene.main([
            "--featured", str(res / "plaud_templates.jsonl"),
            "--full", str(res / "plaud_templates_full.jsonl.gz"),
            "--official-baseline", str(ROOT / "docs" / "templates" / "official_baseline.txt"),
        ])
        self.assertEqual(0, code)

    def test_docs_featured_matches_shipped_resource(self):
        docs = (ROOT / "docs" / "templates" / "plaud_templates.jsonl").read_bytes()
        res = (ROOT / "core" / "src" / "commonMain" / "resources" / "plaud_templates.jsonl").read_bytes()
        self.assertEqual(docs, res)


if __name__ == "__main__":
    unittest.main()
