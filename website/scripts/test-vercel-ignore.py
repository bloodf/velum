#!/usr/bin/env python3
"""Exercise Vercel's inverted exit codes against disposable real Git histories."""
import os
from pathlib import Path
import subprocess
import tempfile

SCRIPT = Path(__file__).with_name("vercel-ignore.sh").resolve()


def check():
    with tempfile.TemporaryDirectory(prefix="velum-deploy-gate-") as temporary:
        repo = Path(temporary) / "repo"
        repo.mkdir()

        def git(*args):
            return subprocess.check_output(
                ["git", "-C", str(repo), *args], stderr=subprocess.PIPE, text=True
            ).strip()

        def commit(path, content="changed"):
            target = repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
            git("add", "--all")
            git("commit", "-qm", path)
            return git("rev-parse", "HEAD")

        checks = 0

        def gate(expected, baseline, branch="main", current=None, cwd=None, **extra):
            nonlocal checks
            env = {key: value for key, value in os.environ.items() if not key.startswith("VERCEL_")}
            env.update(VERCEL_GIT_COMMIT_REF=branch, VERCEL_GIT_PREVIOUS_SHA=baseline,
                       VERCEL_GIT_COMMIT_SHA=current or git("rev-parse", "HEAD"))
            env.update(extra)
            result = subprocess.run(["sh", str(SCRIPT)], cwd=cwd or repo, env=env,
                                    capture_output=True, text=True)
            assert result.returncode == expected, (expected, result.returncode, result.stdout, result.stderr)
            assert "build permitted" in result.stdout if expected == 1 else "build cancelled" in result.stdout
            checks += 1

        git("init", "-q", "-b", "main")
        git("config", "user.name", "Gate test")
        git("config", "user.email", "gate-test@example.invalid")
        base = commit("website/src/site.txt", "initial")
        docs = commit("docs/guide.md")
        gate(0, base)
        site = commit("website/src/site.txt")
        gate(1, docs)
        gate(1, docs, cwd=repo / "website")
        gate(0, docs, branch="feature/topic")
        gate(0, docs, branch="")
        gate(0, docs, VERCEL_GIT_PULL_REQUEST_ID="17")
        gate(0, "")
        gate(0, "0" * 40)
        gate(0, "--bad-ref")
        gate(0, "a" * 39)
        gate(0, docs, current=base)
        gate(0, site)
        commit("docs/second.md")
        gate(1, docs)  # A previous deployment is not necessarily HEAD^.

        for path in (".node-version", ".nvmrc", ".npmrc", "package.json", "pnpm-lock.yaml",
                     "pnpm-workspace.yaml", "yarn.lock", "package-lock.json", "bun.lock",
                     "tsconfig.base.json", "vite.config.js", "vercel.json", "packages/ui/file",
                     "shared/file", "patches/package.patch", ".yarn/plugins/file"):
            before = git("rev-parse", "HEAD")
            commit(path)
            gate(1, before)

        before = git("rev-parse", "HEAD")
        (repo / "website/src/site.txt").unlink()
        commit("docs/deleted.md")
        gate(1, before)
        before = commit("website/src/move.txt")
        git("mv", "website/src/move.txt", "docs/move.txt")
        commit("docs/renamed.md")
        gate(1, before)
        before = git("rev-parse", "HEAD")
        git("mv", "docs/move.txt", "website/src/move.txt")
        commit("docs/moved-in.md")
        gate(1, before)

        before = git("rev-parse", "HEAD")
        git("checkout", "-qb", "topic")
        topic = commit("website/src/merge.txt")
        git("checkout", "-q", "main")
        commit("docs/main.md")
        git("merge", "--no-ff", "-qm", "merge site", "topic")
        gate(1, before)
        git("checkout", "-qb", "squash-topic")
        commit("website/src/squash.txt")
        git("checkout", "-q", "main")
        before = git("rev-parse", "HEAD")
        git("merge", "--squash", "squash-topic")
        git("commit", "-qm", "squash site")
        gate(1, before)
        gate(0, git("rev-parse", "squash-topic"))  # Unrelated baseline lineage.

        clone = Path(temporary) / "shallow"
        subprocess.run(["git", "clone", "-q", "--depth=1", repo.as_uri(), str(clone)], check=True)
        gate(0, topic, cwd=clone)
        outside = Path(temporary) / "outside"
        outside.mkdir()
        gate(0, before, cwd=outside)
        print(f"PASS: {checks} real-Git deployment gate checks")


if __name__ == "__main__":
    check()
