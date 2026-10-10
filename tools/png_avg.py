#!/usr/bin/env python3
"""平均取色：解码 PNG（8bit，RGB/RGBA，无隔行）并输出指定矩形区域的平均颜色。
用法: python3 tools/png_avg.py shot.png x0 y0 x1 y1
只解到需要的行，省时间。"""
import sys
import struct
import zlib


def decode(path, max_row):
    d = open(path, 'rb').read()
    if d[:8] != b'\x89PNG\r\n\x1a\n':
        raise SystemExit('not a png')
    pos, idat, w, h, color = 8, b'', 0, 0, 6
    while pos + 8 <= len(d):
        ln = struct.unpack('>I', d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b'IHDR':
            w, h, depth, color = struct.unpack('>IIBB', data[:10])
            if depth != 8:
                raise SystemExit('only 8-bit png supported')
        elif typ == b'IDAT':
            idat += data
        elif typ == b'IEND':
            break
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[color]
    raw = zlib.decompress(idat)
    stride = w * ch
    prev = bytearray(stride)
    rows = []
    i = 0
    stop = min(h, max_row)
    for y in range(h):
        f = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        if f == 1:
            for x in range(ch, stride):
                line[x] = (line[x] + line[x - ch]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x - ch] if x >= ch else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - ch] if x >= ch else 0
                b = prev[x]
                c = prev[x - ch] if x >= ch else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        if y < stop:
            rows.append(bytes(line))
        else:
            break
        prev = line
    return w, h, ch, rows


def avg(path, x0, y0, x1, y1):
    w, h, ch, rows = decode(path, y1)
    r = g = b = n = 0
    for y in range(max(0, y0), min(h, y1)):
        row = rows[y]
        for x in range(max(0, x0), min(w, x1)):
            o = x * ch
            r += row[o]
            g += row[o + 1]
            b += row[o + 2]
            n += 1
    if n == 0:
        return None
    return (r // n, g // n, b // n)


if __name__ == '__main__':
    if len(sys.argv) < 6:
        raise SystemExit('usage: png_avg.py file.png x0 y0 x1 y1')
    a = [int(v) for v in sys.argv[2:6]]
    print(avg(sys.argv[1], a[0], a[1], a[2], a[3]))
