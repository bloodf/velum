#!/usr/bin/env python3
"""List the entries of an APK's APK Signing Block; optionally fail when AGP's dependency-metadata block is present.

    python3 scripts/apk-signing-blocks.py [--forbid-dependency-metadata] app.apk

AGP writes the app's dependency list into the signing block, encrypted with a Google key
(`dependenciesInfo.includeInApk`). Nobody but Google can read it, so F-Droid and IzzyOnDroid scanners flag it.
Exit codes: 0 ok, 1 forbidden block found, 2 not an APK / no signing block.
"""
import struct
import sys

MAGIC = b"APK Sig Block 42"
NAMES = {
    0x7109871A: "v2 signature",
    0xF05368C0: "v3 signature",
    0x1B93AD61: "v3.1 signature",
    0x42726577: "verity padding",
    0x504B4453: "dependency metadata (AGP)",
    0x6DFF800D: "source stamp",
    0x2146444E: "Google Play metadata",
}
DEPENDENCY_METADATA = 0x504B4453


def signing_block_ids(data: bytes) -> list[int]:
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if eocd < 0:
        raise ValueError("no end of central directory record")
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    if cd_offset < 32 or data[cd_offset - 16:cd_offset] != MAGIC:
        raise ValueError("no APK Signing Block (v1-only or unsigned APK)")
    size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    start = cd_offset - size - 8
    if start < 0 or struct.unpack_from("<Q", data, start)[0] != size:
        raise ValueError("APK Signing Block sizes disagree")
    ids, pos, end = [], start + 8, cd_offset - 24
    while pos < end:
        length = struct.unpack_from("<Q", data, pos)[0]
        if length < 4 or pos + 8 + length > end:
            raise ValueError("malformed APK Signing Block entry")
        ids.append(struct.unpack_from("<I", data, pos + 8)[0])
        pos += 8 + length
    return ids


def main(argv: list[str]) -> int:
    forbid = "--forbid-dependency-metadata" in argv
    paths = [a for a in argv if not a.startswith("--")]
    if len(paths) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    with open(paths[0], "rb") as f:
        data = f.read()
    try:
        ids = signing_block_ids(data)
    except ValueError as e:
        print(f"{paths[0]}: {e}", file=sys.stderr)
        return 2
    for i in ids:
        print(f"0x{i:08x} {NAMES.get(i, 'unknown')}")
    if forbid and DEPENDENCY_METADATA in ids:
        print(f"{paths[0]}: contains the dependency-metadata block", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
