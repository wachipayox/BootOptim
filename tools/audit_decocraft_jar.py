#!/usr/bin/env python3
import argparse
import collections
import hashlib
import math
import pathlib
import statistics
import struct
import zipfile

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def percentile(values, fraction):
    if not values:
        return 0
    values = sorted(values)
    pos = (len(values) - 1) * fraction
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return values[lo]
    return values[lo] + (values[hi] - values[lo]) * (pos - lo)


def fmt_bytes(value):
    return f"{value / (1024 * 1024):.3f} MiB"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=pathlib.Path)
    parser.add_argument("report", type=pathlib.Path)
    args = parser.parse_args()

    jar_size = args.jar.stat().st_size
    with zipfile.ZipFile(args.jar) as jar:
        all_entries = jar.infolist()
        pngs = [e for e in all_entries if e.filename.startswith("assets/decocraft/") and e.filename.lower().endswith(".png")]
        textures = [e for e in pngs if e.filename.startswith("assets/decocraft/textures/")]
        metadata_names = set(e.filename for e in all_entries if e.filename.startswith("assets/decocraft/") and e.filename.endswith(".png.mcmeta"))

        dims = collections.Counter()
        categories = collections.Counter()
        category_bytes = collections.Counter()
        compression = collections.Counter()
        compression_bytes = collections.Counter()
        hashes = collections.defaultdict(list)
        encoded_sizes = []
        areas = []
        malformed = []

        for entry in textures:
            compression[entry.compress_type] += 1
            compression_bytes[(entry.compress_type, "compressed")] += entry.compress_size
            compression_bytes[(entry.compress_type, "encoded")] += entry.file_size
            encoded_sizes.append(entry.file_size)

            rel = entry.filename[len("assets/decocraft/textures/"):]
            category = rel.split("/", 1)[0] if "/" in rel else "root"
            categories[category] += 1
            category_bytes[category] += entry.file_size

            with jar.open(entry) as stream:
                data = stream.read()
            if len(data) < 24 or data[:8] != PNG_SIGNATURE or data[12:16] != b"IHDR":
                malformed.append(entry.filename)
                continue
            width, height = struct.unpack(">II", data[16:24])
            dims[(width, height)] += 1
            areas.append(width * height)
            hashes[hashlib.sha256(data).digest()].append(entry.filename)

        duplicate_groups = [names for names in hashes.values() if len(names) > 1]
        duplicate_files = sum(len(names) - 1 for names in duplicate_groups)
        duplicate_encoded_bytes = 0
        for names in duplicate_groups:
            first = jar.getinfo(names[0]).file_size
            duplicate_encoded_bytes += first * (len(names) - 1)

        physical = sorted(textures, key=lambda e: e.header_offset)
        physical_span = 0
        if physical:
            first_offset = physical[0].header_offset
            last = physical[-1]
            physical_span = (last.header_offset + last.compress_size) - first_offset
        total_zip_bytes = sum(e.compress_size for e in textures)
        total_encoded_bytes = sum(e.file_size for e in textures)
        density = total_zip_bytes / physical_span if physical_span > 0 else 0.0

        lex = sorted(textures, key=lambda e: e.filename)
        lex_gaps = []
        for previous, current in zip(lex, lex[1:]):
            lex_gaps.append(abs(current.header_offset - previous.header_offset))
        physical_gaps = []
        for previous, current in zip(physical, physical[1:]):
            physical_gaps.append(max(0, current.header_offset - (previous.header_offset + previous.compress_size)))

        method_names = {
            zipfile.ZIP_STORED: "stored",
            zipfile.ZIP_DEFLATED: "deflated",
            zipfile.ZIP_BZIP2: "bzip2",
            zipfile.ZIP_LZMA: "lzma",
        }

        lines = []
        emit = lines.append
        emit("# Decocraft 3.0.11 JAR resource audit")
        emit("")
        emit(f"JAR bytes: {jar_size} ({fmt_bytes(jar_size)})")
        emit(f"Total JAR entries: {len(all_entries)}")
        emit(f"Decocraft PNG entries: {len(pngs)}")
        emit(f"Decocraft texture PNG entries: {len(textures)}")
        emit(f"Texture PNGs with .mcmeta: {sum(1 for e in textures if e.filename + '.mcmeta' in metadata_names)}")
        emit(f"Texture encoded PNG bytes after JAR decompression: {total_encoded_bytes} ({fmt_bytes(total_encoded_bytes)})")
        emit(f"Texture compressed bytes inside JAR: {total_zip_bytes} ({fmt_bytes(total_zip_bytes)})")
        emit(f"JAR compressed/encoded ratio for texture PNGs: {(total_zip_bytes / total_encoded_bytes if total_encoded_bytes else 0):.6f}")
        emit(f"Physical texture-entry span: {physical_span} ({fmt_bytes(physical_span)})")
        emit(f"Texture compressed-byte density inside span: {density:.6f}")
        emit("")
        emit("## ZIP methods")
        for method, count in sorted(compression.items(), key=lambda item: (-item[1], item[0])):
            encoded = compression_bytes[(method, "encoded")]
            compressed = compression_bytes[(method, "compressed")]
            emit(f"- {method_names.get(method, str(method))}: files={count} encoded={encoded} compressed={compressed} ratio={(compressed / encoded if encoded else 0):.6f}")
        emit("")
        emit("## Encoded PNG size distribution")
        if encoded_sizes:
            emit(f"min={min(encoded_sizes)} median={statistics.median(encoded_sizes):.1f} p90={percentile(encoded_sizes, 0.90):.1f} p99={percentile(encoded_sizes, 0.99):.1f} max={max(encoded_sizes)} bytes")
        emit("")
        emit("## Pixel-area distribution")
        if areas:
            emit(f"min={min(areas)} median={statistics.median(areas):.1f} p90={percentile(areas, 0.90):.1f} p99={percentile(areas, 0.99):.1f} max={max(areas)} pixels")
        emit("")
        emit("## Top dimensions")
        for (width, height), count in dims.most_common(40):
            emit(f"- {width}x{height}: {count}")
        emit("")
        emit("## Texture categories")
        for category, count in categories.most_common():
            emit(f"- {category}: files={count} encoded_bytes={category_bytes[category]}")
        emit("")
        emit("## Exact duplicate PNG content")
        emit(f"duplicate_groups={len(duplicate_groups)} duplicate_files_beyond_first={duplicate_files} duplicate_encoded_bytes_beyond_first={duplicate_encoded_bytes}")
        for names in sorted(duplicate_groups, key=len, reverse=True)[:30]:
            emit(f"- copies={len(names)} first={names[0]}")
        emit("")
        emit("## JAR locality indicators")
        if lex_gaps:
            emit(f"lexical-path consecutive physical offset gap: median={statistics.median(lex_gaps):.1f} p90={percentile(lex_gaps, 0.90):.1f} p99={percentile(lex_gaps, 0.99):.1f} max={max(lex_gaps)} bytes")
        if physical_gaps:
            emit(f"physical-order inter-entry gap: median={statistics.median(physical_gaps):.1f} p90={percentile(physical_gaps, 0.90):.1f} p99={percentile(physical_gaps, 0.99):.1f} max={max(physical_gaps)} bytes")
        emit(f"lexical consecutive gaps >1MiB: {sum(1 for gap in lex_gaps if gap > 1024 * 1024)} / {len(lex_gaps)}")
        emit("")
        emit("## Malformed/non-IHDR PNG entries")
        emit(str(len(malformed)))
        for name in malformed[:30]:
            emit(f"- {name}")

    args.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(args.report.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
