#!/usr/bin/env python3
"""Minimal zipalign replacement (no native binary available on aarch64).

Aligns the data offset of every STORED (uncompressed) entry to the requested
boundary by appending a padding extra field, then verifies the result.

Usage: zipalign.py in.apk out.apk [alignment]
"""
import struct
import sys
import zipfile

PAD_ID = 0xD935  # same id zipalign uses for its padding extra field


def pad_field(payload_len):
    return struct.pack('<HH', PAD_ID, payload_len) + b'\x00' * payload_len


def align(src, dst, alignment=4):
    zin = zipfile.ZipFile(src, 'r')
    infos = zin.infolist()
    with zipfile.ZipFile(dst, 'w') as zout:
        for info in infos:
            data = zin.read(info.filename)
            name_len = len(info.filename.encode('utf-8'))
            extra = info.extra
            if info.compress_type == zipfile.ZIP_STORED:
                base = zout.fp.tell() + 30 + name_len + len(extra)
                payload = (-(base + 4)) % alignment
                extra = extra + pad_field(payload)
            newinfo = zipfile.ZipInfo(info.filename, info.date_time)
            newinfo.compress_type = info.compress_type
            newinfo.external_attr = info.external_attr
            newinfo.internal_attr = info.internal_attr
            newinfo.create_system = info.create_system
            newinfo.comment = info.comment
            newinfo.extra = extra
            zout.writestr(newinfo, data, compress_type=info.compress_type, compresslevel=9)
    zin.close()


def verify(path, alignment=4):
    ok = True
    z = zipfile.ZipFile(path, 'r')
    for info in z.infolist():
        offset = info.header_offset + 30 + len(info.filename.encode('utf-8')) + len(info.extra)
        stored = info.compress_type == zipfile.ZIP_STORED
        bad = stored and (offset % alignment != 0)
        if bad:
            ok = False
        flag = 'STORED' if stored else 'DEFLATE'
        print('  %-28s %-8s offset=%-8d align_ok=%s'
              % (info.filename, flag, offset, 'yes' if (offset % alignment == 0) else ('n/a' if not stored else 'NO')))
    z.close()
    return ok


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    alignment = int(sys.argv[3]) if len(sys.argv) > 3 else 4
    align(sys.argv[1], sys.argv[2], alignment)
    print('alignment report (%d bytes):' % alignment)
    if verify(sys.argv[2], alignment):
        print('zip alignment OK')
        return 0
    print('zip alignment FAILED')
    return 1


if __name__ == '__main__':
    sys.exit(main())