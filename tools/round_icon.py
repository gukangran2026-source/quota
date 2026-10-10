#!/usr/bin/env python3
"""把图标修成圆形：读入 PNG，加圆形 alpha 遮罩，输出 RGBA PNG。
图标内容一个像素都不改，只把四角裁成透明（边缘做抗锯齿）。
用法: python3 tools/round_icon.py 输入.png 输出.png
"""
import struct
import sys
import zlib


def decode(path):
    d = open(path, 'rb').read()
    if d[:8] != b'\x89PNG\r\n\x1a\n':
        raise SystemExit('not a png')
    pos, idat, w, h, color = 8, b'', 0, 0, 6
    plte, trns = b'', b''
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
        elif typ == b'PLTE':
            plte = data
        elif typ == b'tRNS':
            trns = data
        elif typ == b'IEND':
            break
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    raw = zlib.decompress(idat)
    stride = w * ch
    prev = bytearray(stride)
    rows = []
    i = 0
    for _ in range(h):
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
        rows.append(line)
        prev = line
    if color == 3:
        # 调色板 PNG（aapt2 重编码图标时会用这种）：索引 -> 调色板颜色 + tRNS 透明度
        out = []
        for line in rows:
            row = bytearray(w * 4)
            for x in range(w):
                idx = line[x]
                o = idx * 3
                row[x * 4] = plte[o]
                row[x * 4 + 1] = plte[o + 1]
                row[x * 4 + 2] = plte[o + 2]
                row[x * 4 + 3] = trns[idx] if idx < len(trns) else 255
            out.append(row)
        return w, h, 4, out
    return w, h, ch, rows


def chunk(typ, data):
    return (struct.pack('>I', len(data)) + typ + data
            + struct.pack('>I', zlib.crc32(typ + data) & 0xffffffff))


def write_rgba(path, w, h, rows):
    raw = b''.join(b'\x00' + bytes(r) for r in rows)
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    open(path, 'wb').write(png)


def main(src, dst, radius=None):
    w, h, ch, rows = decode(src)
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    half_w, half_h = w / 2.0, h / 2.0
    if radius is None:
        radius = min(w, h) / 2.0 - 0.5
    out = []
    for y in range(h):
        src_row = rows[y]
        row = bytearray(w * 4)
        for x in range(w):
            o = x * ch
            r, g, b = src_row[o], src_row[o + 1], src_row[o + 2]
            a0 = src_row[o + 3] if ch == 4 else 255
            # 圆角矩形：只有四个角按半径裁掉，边缘做抗锯齿
            dx = abs(x - cx) - (half_w - radius)
            dy = abs(y - cy) - (half_h - radius)
            if dx < 0.0:
                dx = 0.0
            if dy < 0.0:
                dy = 0.0
            dist = (dx * dx + dy * dy) ** 0.5
            cov = radius + 0.5 - dist
            if cov > 1.0:
                cov = 1.0
            elif cov < 0.0:
                cov = 0.0
            a = int(round(a0 * cov))
            t = x * 4
            row[t] = r
            row[t + 1] = g
            row[t + 2] = b
            row[t + 3] = a
        out.append(row)
    write_rgba(dst, w, h, out)
    print('round icon written: %s (%dx%d)' % (dst, w, h))


if __name__ == '__main__':
    r = float(sys.argv[3]) if len(sys.argv) > 3 else None
    main(sys.argv[1], sys.argv[2], r)