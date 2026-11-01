#!/usr/bin/env python3
"""从 CHANGELOG.md 抽取指定版本的段落，用于 Release 正文。

用法: python3 tools/changelog.py CHANGELOG.md 0.2.0 > release_body.md
退出码 0 = 找到非空段落，1 = 未找到。
"""
import re
import sys


def section(text, version):
    pattern = re.compile(
        r"^##\s+\[?v?%s\]?[^\n]*\n(.*?)(?=^##\s|\Z)" % re.escape(version),
        re.S | re.M,
    )
    match = pattern.search(text)
    if not match:
        return ""
    return match.group(1).strip()


def main():
    if len(sys.argv) < 3:
        print("usage: changelog.py <file> <version>", file=sys.stderr)
        return 2
    with open(sys.argv[1], encoding="utf-8") as fh:
        body = section(fh.read(), sys.argv[2])
    if not body:
        return 1
    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
