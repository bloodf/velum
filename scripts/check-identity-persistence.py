#!/usr/bin/env python3
"""Verify synthetic Android identity persistence in new processes; emulator only."""

import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.velum.app"
RUNNER = PACKAGE + ".test/dev.velum.app.identity.IdentityPersistenceTestRunner"
TEST = "dev.velum.app.identity.IdentityPersistenceDeviceTest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit emulator-NNNN serial")
    parser.add_argument("--variant", choices=("debug", "minifiedTest", "all"), default="all")
    parser.add_argument("--output", type=Path, required=True, help="Local evidence directory; do not publish raw logs")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not args.serial[9:].isdigit():
        parser.error("Only an explicitly selected emulator is supported")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb_binary = str(Path(sdk) / "platform-tools/adb") if sdk else shutil.which("adb")
    if not adb_binary:
        parser.error("Set ANDROID_HOME or put adb on PATH")
    adb_command = [adb_binary, "-s", args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    report = dict(started_utc=utc(), status="running", serial=args.serial, variants=[])
    signature = base64.b64encode(bytes((i * 7) % 251 for i in range(256))).decode()
    needles = [signature, signature.replace("/", "\\/"), "SYNTHETIC-INVALID-SIGNATURE"]
    for kind in ("json", "paste"):
        needles.extend(f"SYNTHETIC-{kind}-{field}" for field in ("SERIAL", "BIND-TOKEN", "PUBLIC-PEM", "PRIVATE-PEM"))

    def redact(raw):
        text = raw.decode(errors="replace")
        for value in needles:
            text = text.replace(value, "[redacted synthetic identity]")
        return text

    def save():
        (args.output / "results.json").write_text(json.dumps(report, indent=2) + "\n")

    def adb(*command, timeout=60):
        result = subprocess.run(adb_command + list(command), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode:
            evidence = args.output / ("adb-failure-" + uuid.uuid4().hex + ".txt")
            evidence.write_text(redact(result.stdout + result.stderr))
            raise RuntimeError("ADB command failed: " + command[0] + "; redacted details: " + str(evidence))
        return result.stdout

    def guard():
        if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT).strip():
            raise RuntimeError("Commit a clean verification tree before running")
        if subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() != report["head"]:
            raise RuntimeError("Source HEAD changed during verification")

    try:
        report["head"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        guard()
        if adb("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
            raise RuntimeError("Target is not an emulator")
        report["api"] = adb("shell", "getprop", "ro.build.version.sdk").decode().strip()
        variants = ["debug", "minifiedTest"] if args.variant == "all" else [args.variant]
        for variant in variants:
            guard()
            run = uuid.uuid4().hex
            dest = args.output / variant / run
            dest.mkdir(parents=True)
            cell = dict(variant=variant, fixture_uuid=run, status="running", phase="install", phases={})
            report["variants"].append(cell)
            save()
            apks = [ROOT / f"composeApp/build/outputs/apk/{variant}/composeApp-{variant}.apk", ROOT / f"composeApp/build/outputs/apk/androidTest/{variant}/composeApp-{variant}-androidTest.apk"]
            cell["APKs"] = {}
            for apk in apks:
                cell["APKs"][str(apk.relative_to(ROOT))] = hashlib.sha256(apk.read_bytes()).hexdigest()
                if b"Success" not in adb("install", "-r", "-t", str(apk), timeout=120):
                    raise RuntimeError("Test APK installation failed")
            registered = adb("shell", "pm", "list", "instrumentation")
            if ("instrumentation:" + RUNNER).encode() not in registered:
                raise RuntimeError("Isolated runner is not registered; build the test APK with -PvelumIdentityPersistenceTest=true")
            metadata_path = "files/synthetic-identity-verification/" + run + ".json"

            def record_safe(path, raw):
                text = raw.decode(errors="replace")
                leaked = any(value in text for value in needles)
                # Even a failing verifier must not echo identity into its own reports.
                path.write_text(redact(raw))
                if leaked:
                    raise AssertionError("Captured output contained synthetic identity; evidence was redacted")

            for phase in ("prepare", "verify", "cleanup"):
                guard()
                cell["phase"] = phase
                save()
                adb("shell", "am", "force-stop", PACKAGE)
                alive = subprocess.run(adb_command + ["shell", "pidof", PACKAGE], capture_output=True, timeout=30)
                if alive.stdout.strip():
                    raise RuntimeError("Target process did not stop")
                raw = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", TEST, "-e", "identityFixtureRun", run, "-e", "identityFixturePhase", phase, RUNNER, timeout=180)
                record_safe(dest / (phase + "-instrumentation.log"), raw)
                if b"OK (1 test)" not in raw or b"FAILURES!!!" in raw:
                    raise AssertionError("Fixture phase failed; see local redacted instrumentation evidence")
                metadata = json.loads(adb("exec-out", "run-as", PACKAGE, "cat", metadata_path))
                expected = {"prepare": "prepared", "verify": "verified", "cleanup": "cleaned"}[phase]
                if metadata.get("run") != run or metadata.get("phase") != expected:
                    raise AssertionError("Fixture metadata did not confirm phase completion")
                (dest / (phase + "-metadata.json")).write_text(json.dumps(metadata, indent=2) + "\n")
                if phase in ("prepare", "verify"):
                    pid = metadata["first_pid" if phase == "prepare" else "verified_pid"]
                    logs = adb("logcat", "-d", "-v", "threadtime", "--pid=" + str(pid))
                    begin = f"begin run={run} phase={phase} pid={pid}".encode()
                    end = f"end run={run} phase={phase} pid={pid}".encode()
                    first = logs.find(begin)
                    last = logs.find(end, first + len(begin))
                    if first < 0 or last < 0:
                        raise AssertionError("Missing fresh process-scoped log boundaries")
                    record_safe(dest / (phase + "-logcat.txt"), logs[first:last + len(end)])
                cell["phases"][phase] = dict(status="passed", metadata=metadata, identity_log_scan="passed" if phase != "cleanup" else "not_needed")
                save()
            prepared = cell["phases"]["prepare"]["metadata"]
            verified = cell["phases"]["verify"]["metadata"]
            if prepared["first_pid"] == verified["verified_pid"] or prepared["first_start_elapsed"] == verified["verified_start_elapsed"]:
                raise AssertionError("No genuine new process proved")
            # Delete only this test's metadata after its local evidence was retained.
            adb("shell", "run-as", PACKAGE, "rm", metadata_path)
            cell.update(status="passed", phase=None, genuine_new_process=True, encrypted_private_preferences=True, synthetic_fixture_cleaned=True)
            save()
            print("PASS", variant, "new process, encrypted private preferences, redacted logs; synthetic vaults cleaned", flush=True)
        guard()
        report.update(status="passed", finished_utc=utc())
    except Exception as error:
        for cell in report["variants"]:
            if cell["status"] == "running":
                cell.update(status="failed", error=str(error))
        report.update(status="failed", error=str(error), finished_utc=utc(), failure_policy="No automatic cleanup/reset: inspect the recorded UUID and last confirmed phase before further action")
        raise
    finally:
        save()


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


if __name__ == "__main__":
    main()
