#!/usr/bin/env python3
"""Fail unless an APK carries a real signature.

The check that motivated this script: an earlier release run published an
APK with the correct product name, a valid manifest and 319 zip entries, and
no signature at all. It was only caught by eye, after the fact.

Why not just look for META-INF/*.RSA:

    Under v2/v3-only signing there is no META-INF signature block, because
    the signature lives in a dedicated structure between the last local file
    header and the central directory. So a v2-only signed APK and an
    unsigned one both have an empty META-INF, and checking META-INF alone
    reports a correct APK as broken and, worse, would have let the unsigned
    one through had v1 signing merely been skipped.

What this actually looks for is the APK Signing Block:

    ... [local file headers] [APK Signing Block] [central directory] [EOCD]
                                                       ^ EOCD gives us the
                                                         CD offset

The block is introduced by a uint64 size, then ends with the same size
followed by the 16-byte magic "APK Sig Block 42". The v2/v3 signature
pairs are id/value pairs inside it, so finding the magic and a consistent
size proves the block exists and is well formed.

Usage: verify-apk-signature.py <file.apk> [<file.apk> ...]
Exit 0 if every file is signed, non-zero otherwise.
"""

import struct
import sys

EOCD_MAGIC = b"PK\x05\x06"
SIG_BLOCK_MAGIC = b"APK Sig Block 42"
EOCD_MIN_SIZE = 22
# Signature scheme ids from the APK Signature Scheme v2/v3 block spec.
V2_ID = 0x7109871A
V3_ID = 0xF05368C0
V31_ID = 0x1B93AD61
V4_ID = 0x42726577
KNOWN = {V2_ID: "v2", V3_ID: "v3", V3_ID | 0xFFFF0000: "v3.1", V4_ID: "v4"}


def find_sig_block(data: bytes):
    """Return (start, size) of the APK Signing Block, or None."""
    eocd = data.rfind(EOCD_MAGIC)
    if eocd == -1:
        return None
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]

    magic = SIG_BLOCK_MAGIC
    # The magic sits 8 bytes in from the end of the block, after the trailing
    # size field. Scan backwards from the central directory.
    start = cd_offset - len(magic) - 8
    floor = max(0, cd_offset - 2_000_000)
    while start >= floor:
        if data[start:start + len(magic)] == magic:
            size = struct.unpack_from("<Q", data, start - 8)[0]
            # The footer size must equal the header size, which sits at
            # (block_end - size - 8). Cheap consistency check.
            header_size = struct.unpack_from("<Q", data, cd_offset - size - 8)[0]
            if header_size == size:
                return (cd_offset - size, size)
        start -= 1
    return None


def schemes(data: bytes, start: int, size: int) -> list:
    """Pull the signature scheme ids out of the block's id/value pairs."""
    found = []
    pos = start + 8  # skip the leading size
    end = start + size - 24  # drop trailing size + magic
    while pos + 12 <= end:
        pair_len = struct.unpack_from("<Q", data, pos)[0]
        if pair_len < 4 or pos + 4 + pair_len > end + 8:
            break
        pair_id = struct.unpack_from("<I", data, pos + 4)[0]
        found.append(pair_id)
        pos += 4 + pair_len
    return found


def check(path: str) -> bool:
    with open(path, "rb") as fh:
        data = fh.read()

    if not data.startswith(b"PK\x03\x04"):
        print(f"FAIL {path}: not a zip, so not an APK")
        return False

    block = find_sig_block(data)
    if block is None:
        print(f"FAIL {path}: unsigned - no APK Signing Block present")
        return False

    start, size = block
    ids = schemes(data, start, size)
    names = [KNOWN.get(i, hex(i)) for i in ids] or ["unrecognised id"]
    if V2_ID not in ids and V3_ID not in ids and (V3_ID | 0xFFFF0000) not in ids:
        print(f"FAIL {path}: block found but no v2/v3 signature pair {names}")
        return False
    print(f"ok   {path}: signed, schemes={','.join(names)} ({len(data)} bytes)")
    return True


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__.strip().splitlines()[-1])
        return 2
    if not all(check(p) for p in sys.argv[1:]):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
