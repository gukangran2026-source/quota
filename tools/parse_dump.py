#!/usr/bin/env python3
"""Print text + tap coordinates from a uiautomator dump."""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "/sdcard/window_dump.xml"
data = open(path, encoding="utf-8").read()

pattern = re.compile(r'text=(?:"([^"]*)"|\'([^\']*)\')[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
for m in pattern.finditer(data):
    text = m.group(1) if m.group(1) is not None else m.group(2)
    if not text.strip():
        continue
    x1, y1, x2, y2 = (int(m.group(i)) for i in range(3, 7))
    print("%-46s tap %d %d" % (repr(text)[:46], (x1 + x2) // 2, (y1 + y2) // 2))

print("---- resource ids ----")
for m in re.finditer(r'resource-id="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', data):
    rid = m.group(1)
    if "subtraffic" in rid:
        x1, y1, x2, y2 = (int(m.group(i)) for i in range(2, 6))
        print("%-46s tap %d %d" % (rid, (x1 + x2) // 2, (y1 + y2) // 2))