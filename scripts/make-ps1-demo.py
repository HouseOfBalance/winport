#!/usr/bin/env python3
# SPDX-License-Identifier: CC0-1.0
"""Original CC0 PS1 homebrew: GPU-drawn title, color bars and moving square.

No BIOS, SDK, commercial code, artwork or assembler is required.
Usage: python3 scripts/make-ps1-demo.py /path/outside/repo/winport-demo.exe
The output is a PS-X EXE for a PS1 emulator, not a Windows executable.
"""
import pathlib
import struct
import sys

code = []


def emit(instruction):
    code.append(instruction)


def immediate(register, value):
    emit(0x3C000000 | register << 16 | (value >> 16) & 0xFFFF)
    emit(0x34000000 | register << 21 | register << 16 | value & 0xFFFF)


def gpu(value, control=False):
    immediate(9, value)
    emit(0xAC000000 | 16 << 21 | 9 << 16 | (4 if control else 0))


def rectangle(x, y, w, h, color):
    gpu(0x60000000 | color)
    gpu(x | y << 16)
    gpu(w | h << 16)


font = {
    'W': ['10101', '10101', '10101', '10101', '10101', '11011', '10001'],
    'I': ['111', '010', '010', '010', '010', '010', '111'],
    'N': ['10001', '11001', '11001', '10101', '10011', '10011', '10001'],
    'P': ['11110', '10001', '10001', '11110', '10000', '10000', '10000'],
    'O': ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
    'R': ['11110', '10001', '10001', '11110', '10100', '10010', '10001'],
    'T': ['11111', '00100', '00100', '00100', '00100', '00100', '00100'],
    'S': ['01111', '10000', '10000', '01110', '00001', '00001', '11110'],
    '1': ['010', '110', '010', '010', '010', '010', '111'],
    'C': ['0111', '1000', '1000', '1000', '1000', '1000', '0111'],
    '0': ['0110', '1001', '1001', '1001', '1001', '1001', '0110'],
}


def text(message, x, y, scale, color):
    for char in message:
        rows = font[char]
        for row, bits in enumerate(rows):
            for column, bit in enumerate(bits):
                if bit == '1':
                    rectangle(x + column * scale, y + row * scale, scale, scale, color)
        x += (len(rows[0]) + 1) * scale


emit(0x40806000)  # mtc0 zero, Status: interrupts off
immediate(16, 0x1F801810)
for value in [0, 0x03000000, 0x05000000, 0x06C60260, 0x07042018, 0x08000001]:
    gpu(value, control=True)
for value in [0xE1000400, 0xE3000000, 0xE403BD3F, 0xE5000000, 0xE6000000]:
    gpu(value)
immediate(8, 0)
loop = len(code)
gpu(0x02180C08)
gpu(0)
gpu(320 | 240 << 16)
for index, color in enumerate([0xFF7755, 0x55DDBB, 0x55AAFF, 0xDD77DD]):
    rectangle(24 + index * 70, 22, 62, 10, color)
text('WINPORT', 80, 58, 4, 0xF0F0F0)
text('PS1', 128, 106, 3, 0x55DDBB)
text('CC0', 140, 205, 2, 0xBBAA99)
gpu(0x60BBEE55)
immediate(9, 168 << 16)
emit(0x01284825)  # or t1,t1,t0
emit(0xAE090000)  # sw t1,0(s0)
gpu(24 | 16 << 16)
emit(0x25080001)  # addiu t0,t0,1
emit(0x310800FF)  # andi t0,t0,255
immediate(10, 100000)
delay_loop = len(code)
emit(0x254AFFFF)
emit(0x15400000 | ((delay_loop - len(code) - 1) & 0xFFFF))
emit(0)
emit(0x08000000 | ((0x80010000 + loop * 4) >> 2 & 0x03FFFFFF))
emit(0)
payload = struct.pack('<' + 'I' * len(code), *code)
payload += bytes((-len(payload)) % 2048)
header = bytearray(2048)
header[:8] = b'PS-X EXE'
for offset, value in [(0x10, 0x80010000), (0x18, 0x80010000), (0x1C, len(payload)), (0x30, 0x801FFF00)]:
    struct.pack_into('<I', header, offset, value)
target = pathlib.Path(sys.argv[1])
if not target.parent.is_dir():
    raise SystemExit('Output parent directory must already exist')
target.write_bytes(header + payload)
print(f'{target}: {len(header) + len(payload)} bytes, original CC0 PS-X EXE')
