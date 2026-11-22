#!/usr/bin/env python3
# SPDX-License-Identifier: CC0-1.0
"""Generate only original test content outside the repository.

Usage: python3 scripts/make-step3-fixture.py /existing/output/directory
The PS4 folder is metadata only, not an executable or a commercial game.
"""
import pathlib
import struct
import subprocess
import sys
import zlib

if len(sys.argv) != 2 or not pathlib.Path(sys.argv[1]).is_dir():
    raise SystemExit(__doc__)
output = pathlib.Path(sys.argv[1]).resolve()
scripts = pathlib.Path(__file__).parent
subprocess.run([sys.executable, str(scripts / 'make-ps1-demo.py'), str(output / 'winport-demo.exe')], check=True)
subprocess.run([sys.executable, str(scripts / 'make-hw-demos.py'), str(output)], check=True)
keys, data, entries = bytearray(), bytearray(), bytearray()
for key, value in [('TITLE', 'Thư viện PS4 · kiểm tra'), ('TITLE_ID', 'CUSA00000'), ('CATEGORY', 'gd')]:
    value = value.encode('utf-8') + b'\0'
    entries += struct.pack('<HHIII', len(keys), 0x0204, len(value), len(value), len(data))
    keys += key.encode() + b'\0'
    data += value
header = struct.pack('<4s4I', b'\0PSF', 0x101, 20 + len(entries), 20 + len(entries) + len(keys), 3)
folder = output / 'PS4-library-test' / 'sce_sys'
folder.mkdir(parents=True, exist_ok=True)
(folder / 'param.sfo').write_bytes(header + entries + keys + data)
print('Generated original PS4 metadata fixture:', folder / 'param.sfo')
def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
pixels = b''.join(b'\0' + bytes(channel for x in range(256) for channel in (20 + x // 3, 28 + y // 2, 80 + x // 2)) for y in range(144))
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', 256, 144, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')
(folder / 'ICON0.PNG').write_bytes(png)
print('Generated original gradient cover:', folder / 'ICON0.PNG')
(folder.parent / 'eboot.bin').write_bytes(b'WINPORT_METADATA_ONLY\nNot an executable game.\n')
