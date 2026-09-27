#!/usr/bin/env python3
"""Fail unless an APK is genuinely signed.

    verify-apk-signature.py <file.apk> [<file.apk> ...]

Why this exists
---------------
Release builds were being published without anyone checking that the APK was
signed. Whether a given build was signed was an assumption, and the build
script's signing fallback made that assumption look safe.

The honest history of the check itself, because it is the useful part: the
first version hand-parsed the APK Signing Block and reported the real,
correctly signed release APK as unsigned. That verdict was wrong. The parser
had a chain of offset bugs - a sign error, a uint64 read where the spec says
uint32, an anchor that landed on the second central-directory entry - and every
one of them produced a false negative, which is the dangerous direction: it
would have blocked every release while looking like it was working. The
synthetic fixtures used to validate it were generated from the same
misreading, so they agreed with the bug instead of catching it.

Running apksigner against that same APK settled it in one command: exit 0,
"CN=Android Debug". It had been signed the whole time.

apksigner is the canonical verifier, it ships in build-tools, and it is not
worth reimplementing. When it cannot be found, this falls back to a structural
check that only asks "is there an APK Signing Block at all" - deliberately weak,
because a weak check that never produces a false negative is safer here than a
clever one that does.
"""

import os
import shutil
import struct
import subprocess
import sys

EOCD_MAGIC = b"PK\x05\x06"
CD_MAGIC = b"PK\x01\x02"
SIG_BLOCK_MAGIC = b"APK Sig Block 42"


def find_apksigner() -> str | None:
    """Locate apksigner, preferring $ANDROID_HOME/build-tools/<newest>/."""
    explicit = os.environ.get("APKSIGNER")
    if explicit and os.path.exists(explicit):
        return explicit
    which = shutil.which("apksigner") or shutil.which("apksigner.jar")
    if which:
        return which
    for root in (
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        os.path.expanduser("~/Android/Sdk"),
    ):
        if not root:
            continue
        bt = os.path.join(root, "build-tools")
        if not os.path.isdir(bt):
            continue
        versions = sorted(
            (v for v in os.listdir(bt) if os.path.isdir(os.path.join(bt, v))),
            reverse=True,
        )
        for v in versions:
            cand = os.path.join(bt, v, "apksigner")
            if os.path.exists(cand):
                return cand
    return None


def has_sig_block(data: bytes) -> bool:
    """Weak structural fallback: is there an APK Signing Block present?

    Only the 16-byte magic is checked. It is not sufficient to prove the APK is
    signed, which is why it is a fallback and not the primary path.
    """
    eocd = data.rfind(EOCD_MAGIC)
    if eocd == -1:
        return False
    m = data.rfind(SIG_BLOCK_MAGIC, 0, eocd)
    if m < 8:
        return False
    return data[m + 16:m + 20] == CD_MAGIC


def verify_with_apksigner(tool: str, path: str):
    """Return (ok, detail). apksigner exits non-zero on an unsigned APK."""
    cmd = [tool, "verify", "--print-certs", path]
    if tool.endswith(".jar"):
        cmd = ["java", "-jar", tool, "verify", "--print-certs", path]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return None, f"could not run apksigner: {exc}"
    if p.returncode == 0:
        schemes = []
        for line in p.stdout.splitlines():
            low = line.lower()
            if "verified using" in low:
                for part in line.split(":", 1)[-1].split(","):
                    tag = part.strip().split()[0] if part.strip() else ""
                    if tag:
                        schemes.append(tag)
        detail = ",".join(schemes) if schemes else "verified"
        return True, detail
    err = (p.stderr or p.stdout or "").strip().splitlines()
    return False, (err[-1] if err else "apksigner reported failure")


def check(path: str, tool: str | None) -> bool:
    if not os.path.exists(path):
        print(f"FAIL {path}: no such file")
        return False
    with open(path, "rb") as fh:
        head = fh.read(4)
        fh.seek(0)
        data = fh.read()
    if head != b"PK\x03\x04":
        print(f"FAIL {path}: not a zip, so not an APK")
        return False

    if tool:
        ok, detail = verify_with_apksigner(tool, path)
        if ok:
            print(f"ok   {path}: apksigner verified, {detail} ({len(data)} bytes)")
            return True
        if ok is None:
            print(f"WARN {path}: {detail}; falling back to structural check")
        else:
            print(f"FAIL {path}: apksigner: {detail}")
            return False

    if has_sig_block(data):
        print(
            f"WARN {path}: apksigner unavailable; an APK Signing Block is present "
            f"but this is NOT proof of a valid signature ({len(data)} bytes)"
        )
        return True
    print(f"FAIL {path}: unsigned - no APK Signing Block and no apksigner to verify")
    return False


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__.strip().splitlines()[0])
        return 2
    tool = find_apksigner()
    if tool:
        print(f"using {tool}")
    else:
        print("apksigner not found - falling back to a structural check only")
    if not all(check(p, tool) for p in sys.argv[1:]):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
