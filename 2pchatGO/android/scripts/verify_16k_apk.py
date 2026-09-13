"""Fail when an APK's arm64 native libraries are not 16 KiB page compatible."""

import argparse
import struct
import zipfile


PAGE_SIZE = 16 * 1024


def native_data_offset(apk_path, info):
    with open(apk_path, "rb") as apk:
        apk.seek(info.header_offset + 26)
        name_len, extra_len = struct.unpack("<HH", apk.read(4))
    return info.header_offset + 30 + name_len + extra_len


def load_alignments(blob):
    if blob[:4] != b"\x7fELF" or blob[4] != 2 or blob[5] != 1:
        raise ValueError("not a little-endian ELF64 library")
    program_offset = struct.unpack_from("<Q", blob, 32)[0]
    entry_size, entry_count = struct.unpack_from("<HH", blob, 54)
    return [
        struct.unpack_from("<IIQQQQQQ", blob, program_offset + index * entry_size)[7]
        for index in range(entry_count)
        if struct.unpack_from("<I", blob, program_offset + index * entry_size)[0] == 1
    ]


def verify(apk_path):
    with zipfile.ZipFile(apk_path) as apk:
        libraries = [
            entry for entry in apk.infolist()
            if entry.filename.startswith("lib/arm64-v8a/") and entry.filename.endswith(".so")
        ]
        if not libraries:
            raise ValueError("APK has no arm64 native libraries")
        for library in libraries:
            offset = native_data_offset(apk_path, library)
            direct_from_apk = library.compress_type == zipfile.ZIP_STORED
            if direct_from_apk and offset % PAGE_SIZE:
                raise ValueError(f"{library.filename}: ZIP data offset {offset} is not 16 KiB aligned")
            alignments = load_alignments(apk.read(library))
            if not alignments or min(alignments) < PAGE_SIZE:
                raise ValueError(f"{library.filename}: PT_LOAD alignment {alignments} is below 16 KiB")
            packaging = f"ZIP={offset}" if direct_from_apk else "extracted"
            print(f"PASS {library.filename}: {packaging}, LOAD={alignments}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    args = parser.parse_args()
    verify(args.apk)
    print("PASS: APK is 16 KiB compatible")
