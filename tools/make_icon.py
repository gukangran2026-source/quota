#!/usr/bin/env python3
"""Generate a simple launcher icon PNG using only the standard library."""
import struct
import zlib
import os

W = H = 192
BG = (0x0D, 0x47, 0xA1)      # deep blue
BAR = (0x4C, 0xAF, 0x50)     # green  (remaining part)
BAR2 = (0xEF, 0x53, 0x50)    # red    (used part)
FRAME = (0xFF, 0xFF, 0xFF)
TEXT = (0xFF, 0xFF, 0xFF)


def px(buf, x, y, c):
    if 0 <= x < W and 0 <= y < H:
        i = (y * W + x) * 3
        buf[i:i + 3] = bytes(c)


def rect(buf, x0, y0, x1, y1, c):
    for y in range(y0, y1):
        for x in range(x0, x1):
            px(buf, x, y, c)


def main():
    buf = bytearray(BG * (W * H))
    # inner rounded-ish panel
    rect(buf, 28, 44, 164, 148, (0x14, 0x2B, 0x52))
    # frame
    rect(buf, 28, 44, 164, 48, FRAME)
    rect(buf, 28, 144, 164, 148, FRAME)
    rect(buf, 28, 44, 32, 148, FRAME)
    rect(buf, 160, 44, 164, 148, FRAME)
    # two bars: used (red) + remaining (green)
    rect(buf, 44, 66, 106, 84, BAR2)
    rect(buf, 110, 66, 148, 84, BAR)
    # lower accents, looks like a second line of data
    rect(buf, 44, 98, 148, 104, (0x4D, 0x8D, 0xF5))
    rect(buf, 44, 112, 130, 118, (0x4D, 0x8D, 0xF5))
    rect(buf, 44, 126, 100, 132, (0x4D, 0x8D, 0xF5))

    raw = bytearray()
    for y in range(H):
        raw.append(0)
        raw += buf[y * W * 3:(y + 1) * W * 3]

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0)
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr)
           + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b''))

    proj = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(proj, 'res', 'drawable', 'ic_launcher.png')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'wb') as f:
        f.write(png)
    print('wrote', out, len(png), 'bytes')


if __name__ == '__main__':
    main()
