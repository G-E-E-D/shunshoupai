#!/usr/bin/env python3
"""Check 64-bit native SDKs in the actual APK, without requiring an NDK install.

This complements zipalign's APK-entry checks: an aligned ZIP cannot fix a
third-party ELF whose load segments were linked for 4 KB pages.
https://developer.android.com/guide/practices/page-sizes
"""

import struct
import sys
import zipfile


def check_elf(name: str, data: bytes) -> None:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{name}: expected a little-endian ELF64 library")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or not count or offset + entry_size * count > len(data):
        raise ValueError(f"{name}: invalid program header table")
    loads = 0
    for index in range(count):
        kind, _, file_offset, address, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset + index * entry_size
        )
        if kind != 1:  # PT_LOAD
            continue
        loads += 1
        if alignment < 16384 or alignment & (alignment - 1):
            raise ValueError(f"{name}: LOAD alignment {alignment} is not 16 KB compatible")
        if (address - file_offset) % 16384:
            raise ValueError(f"{name}: LOAD address and file offset do not match at 16 KB")
    if not loads:
        raise ValueError(f"{name}: no loadable ELF segments")
    print(f"16 KB ELF OK: {name} ({loads} LOAD segments)")


def main() -> None:
    if len(sys.argv) != 2:
        raise ValueError("Usage: check-native-alignment.py path/to/app.apk")
    with zipfile.ZipFile(sys.argv[1]) as apk:
        libraries = [
            name for name in apk.namelist()
            if name.endswith(".so") and name.startswith(("lib/arm64-v8a/", "lib/x86_64/"))
        ]
        for name in sorted(libraries):
            check_elf(name, apk.read(name))
    print(f"Verified {len(libraries)} 64-bit native libraries")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, struct.error, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
