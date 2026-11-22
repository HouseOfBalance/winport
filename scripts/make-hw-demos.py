#!/usr/bin/env python3
# SPDX-License-Identifier: CC0-1.0
"""Generate original CC0 PSP and PS2 MIPS ELF tests, without an SDK or BIOS.

Usage: python3 scripts/make-hw-demos.py /existing/output/directory
PSP writes VRAM through CPU stores and imports the HLE display functions.
PS2 configures the CRT and submits original GS sprites through the GIF FIFO.
"""
import pathlib
import struct
import sys

FONT = {
    'W': ['10101', '10101', '10101', '10101', '10101', '11011', '10001'],
    'I': ['111', '010', '010', '010', '010', '010', '111'],
    'N': ['10001', '11001', '11001', '10101', '10011', '10011', '10001'],
    'P': ['11110', '10001', '10001', '11110', '10000', '10000', '10000'],
    'O': ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
    'R': ['11110', '10001', '10001', '11110', '10100', '10010', '10001'],
    'T': ['11111', '00100', '00100', '00100', '00100', '00100', '00100'],
    'S': ['01111', '10000', '10000', '01110', '00001', '00001', '11110'],
    '2': ['11110', '00001', '00001', '01110', '10000', '10000', '11111'],
    'C': ['0111', '1000', '1000', '1000', '1000', '1000', '0111'],
    '0': ['0110', '1001', '1001', '1001', '1001', '1001', '0110'],
}


class Mips:
    def __init__(self, base):
        self.base, self.code = base, []

    def emit(self, word):
        self.code.append(word)

    def li(self, reg, value):
        self.emit(0x3C000000 | reg << 16 | (value >> 16 & 65535))
        self.emit(0x34000000 | reg << 21 | reg << 16 | (value & 65535))

    def jump(self, address, link=False):
        self.emit((0x0C000000 if link else 0x08000000) | (address >> 2 & 0x3FFFFFF))
        self.emit(0)

    def branch(self, reg, target):
        self.emit(0x1C000000 | reg << 21 | ((target - len(self.code) - 1) & 65535))
        self.emit(0)

    def bytes(self):
        return struct.pack('<' + 'I' * len(self.code), *self.code)


def title(rect, label, x, y, scale, color):
    for char in label:
        rows = FONT[char]
        for row, bits in enumerate(rows):
            for col, bit in enumerate(bits):
                if bit == '1':
                    rect(x + col * scale, y + row * scale, scale, scale, color)
        x += (len(rows[0]) + 1) * scale


def elf(base, payload, sections=()):
    names = b'\0.text\0.shstrtab\0' + b''.join(name.encode() + b'\0' for name, _, _ in sections)
    name_offset = lambda name: names.index(name.encode() + b'\0')
    section_offset = (4096 + len(payload) + len(names) + 15) & ~15
    header = struct.pack('<16sHHIIIIIHHHHHH', b'\x7fELF\x01\x01\x01' + bytes(9), 2, 8, 1,
                         base, 52, section_offset, 0x10A23001 if base == 0x08804000 else 0x20924001,
                         52, 32, 1, 40, 3 + len(sections), 2)
    program = struct.pack('<IIIIIIII', 1, 4096, base, base, len(payload), len(payload), 7, 4096)
    result = bytearray(header + program)
    result += bytes(4096 - len(result))
    result += payload + names
    result += bytes(section_offset - len(result))
    result += bytes(40)
    result += struct.pack('<10I', 1, 1, 6, base, 4096, len(payload), 0, 0, 16, 0)
    result += struct.pack('<10I', 7, 3, 0, 0, 4096 + len(payload), len(names), 0, 0, 1, 0)
    for name, address, size in sections:
        result += struct.pack('<10I', name_offset(name), 1, 2, address, 4096 + address - base, size, 0, 0, 4, 0)
    return result


def psp():
    base, info, imports, nids, stubs, library = 0x08804000, 0x08830000, 0x08830100, 0x08830200, 0x08830300, 0x08830400
    m = Mips(base)
    m.li(29, 0x09FFF000)
    for reg, value in [(4, 0), (5, 480), (6, 272)]:
        m.li(reg, value)
    m.jump(stubs, True)

    def rect(x, y, width, height, color):
        m.li(8, 0x04000000 + (y * 512 + x) * 4)
        m.li(10, height)
        m.li(11, color | 0xFF000000)
        row = len(m.code)
        m.li(9, width)
        pixel = len(m.code)
        m.emit(0xAD0B0000)
        m.emit(0x25080004)
        m.emit(0x2529FFFF)
        m.branch(9, pixel)
        m.emit(0x25080000 | ((512 - width) * 4))
        m.emit(0x254AFFFF)
        m.branch(10, row)

    rect(0, 0, 480, 272, 0x24130C)
    for i, color in enumerate([0xFF8855, 0x55DDBB, 0x55AAFF, 0xDD77DD]):
        rect(30 + i * 110, 22, 95, 12, color)
    title(rect, 'WINPORT', 115, 65, 6, 0xF0F0F0)
    title(rect, 'PSP', 194, 135, 5, 0x55DDBB)
    title(rect, 'CC0', 212, 220, 3, 0xBBAA99)
    for reg, value in [(4, 0x04000000), (5, 512), (6, 3), (7, 1)]:
        m.li(reg, value)
    m.jump(stubs + 8, True)
    loop = base + len(m.code) * 4
    m.jump(stubs + 16, True)
    m.jump(loop)
    data = bytearray(m.bytes())
    data += bytes(library + 16 - base - len(data))

    def put(address, value):
        data[address-base:address-base+len(value)] = value
    put(info, struct.pack('<H2B28s5I', 0, 1, 0, b'WinportCC0', 0, 0, 0, imports, imports + 20))
    put(imports, struct.pack('<IHHBBHII', library, 0, 0x4001, 5, 0, 3, nids, stubs))
    put(nids, struct.pack('<3I', 0x0E20F177, 0x289D82FE, 0x984C27E7))
    put(stubs, struct.pack('<6I', *([0x03E00008, 0] * 3)))
    put(library, b'sceDisplay\0')
    return elf(base, data, [('.rodata.sceModuleInfo', info, 52), ('.lib.stub', imports, 20)])


def ps2():
    base, packet = 0x00100000, 0x00110000
    m = Mips(base)
    for reg, value in [(4, 0), (5, 2), (6, 0), (3, 2)]:
        m.li(reg, value)
    m.emit(0x0000000C)
    m.emit(0)

    def gs(address, value):
        m.li(8, address)
        m.li(9, value & 0xFFFFFFFF)
        m.li(10, value >> 32)
        m.emit(0xAD090000)
        m.emit(0xAD0A0004)

    gs(0x12000000, 0xFF61)
    gs(0x12000020, 0)
    gs(0x12000070, 10 << 9)
    gs(0x12000080, 656 | (36 << 12) | (3 << 23) | (2559 << 32) | (447 << 44))
    regs = [(10 << 16, 0x4C), (0, 0x18), (639 << 16 | 447 << 48, 0x40), (1, 0x1A), (0, 0x47), (1, 0x46)]

    def rect(x, y, width, height, color):
        regs.extend([(6, 0x00), (0x80000000 | color, 0x01),
                     (x * 16 | y * 16 << 16, 0x05),
                     ((x + width) * 16 | (y + height) * 16 << 16, 0x05)])

    rect(0, 0, 640, 448, 0x24130C)
    for i, color in enumerate([0xFF8855, 0x55DDBB, 0x55AAFF, 0xDD77DD]):
        rect(40 + i * 145, 35, 125, 20, color)
    title(rect, 'WINPORT', 150, 110, 8, 0xF0F0F0)
    title(rect, 'PS2', 258, 230, 7, 0x55DDBB)
    title(rect, 'CC0', 280, 370, 4, 0xBBAA99)
    gif = struct.pack('<QQ', len(regs) | (1 << 15) | (1 << 60), 0xE)
    gif += b''.join(struct.pack('<QQ', value, register) for value, register in regs)
    m.li(8, packet)
    m.li(10, 0x10006000)
    m.li(11, len(gif) // 16)
    loop = len(m.code)
    m.emit(0x78000000 | 8 << 21 | 9 << 16)
    m.emit(0x7C000000 | 10 << 21 | 9 << 16)
    m.emit(0x25080010)
    m.emit(0x256BFFFF)
    m.branch(11, loop)
    m.jump(base + len(m.code) * 4)
    payload = m.bytes()
    payload += bytes(packet - base - len(payload)) + gif
    return elf(base, payload)


if len(sys.argv) != 2:
    raise SystemExit(__doc__)
target = pathlib.Path(sys.argv[1])
if not target.is_dir():
    raise SystemExit('Output directory must already exist')
for name, content in [('winport-psp.elf', psp()), ('winport-ps2.elf', ps2())]:
    path = target / name
    path.write_bytes(content)
    print(f'{path}: {len(content)} bytes, original CC0 MIPS ELF')
