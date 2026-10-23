#!/usr/bin/env python3
"""校验版本号在三处是否一致。

版本号目前写在两个地方（`app/build.gradle.kts` 的 versionName / versionCode，
`CHANGELOG.md` 的章节标题），README 的徽章是动态取的，不用管。

不一致的后果不会让构建失败，只会让「安装包显示的版本」和「更新日志说的版本」
对不上——这种错不会有人主动发现，但看发布页的人第一眼就会迷惑。所以放进 CI。

用法：

    python3 tools/check_version.py

一致返回 0，不一致返回 1。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD_FILE = ROOT / "app" / "build.gradle.kts"
CHANGELOG = ROOT / "CHANGELOG.md"

VERSION_NAME = re.compile(r'versionName\s*=\s*"([^"]+)"')
VERSION_CODE = re.compile(r"versionCode\s*=\s*(\d+)")
CHANGELOG_HEADING = re.compile(r"^##\s*\[([^\]]+)\]", re.MULTILINE)
SEMVER = re.compile(r"^\d+\.\d+\.\d+$")

problems: list[str] = []


def main() -> int:
    build_text = BUILD_FILE.read_text(encoding="utf-8")

    name_match = VERSION_NAME.search(build_text)
    code_match = VERSION_CODE.search(build_text)
    if name_match is None or code_match is None:
        print("找不到 versionName / versionCode", file=sys.stderr)
        return 1

    version_name = name_match.group(1)
    version_code = int(code_match.group(1))

    if not SEMVER.match(version_name):
        problems.append(f"versionName 不是 x.y.z 形式：{version_name}")

    changelog_text = CHANGELOG.read_text(encoding="utf-8")
    headings = CHANGELOG_HEADING.findall(changelog_text)

    if version_name not in headings:
        problems.append(
            f"CHANGELOG.md 里没有版本 {version_name} 的章节"
            f"（现有：{', '.join(headings[:5]) or '无'}）"
        )
    elif headings[0] != version_name:
        problems.append(
            f"CHANGELOG.md 最上面一节是 {headings[0]}，但代码里是 {version_name}；"
            "新版本要加在最前面"
        )

    print(f"versionName = {version_name}")
    print(f"versionCode = {version_code}")
    print(f"CHANGELOG 最新 = {headings[0] if headings else '（空）'}")

    if problems:
        print("\n失败：", file=sys.stderr)
        for item in problems:
            print(f"  - {item}", file=sys.stderr)
        return 1

    print("\n版本号一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
