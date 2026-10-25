#!/usr/bin/env python3
"""校验容器启动脚本：bash 语法、内嵌 python 语法、shellcheck。

`app/src/main/assets/scripts/*.sh` 跑在手机里的 proot 容器中，坏了的表现是
「装到一半失败」或者「容器起不来」——这两种故障在真机上极难定位，
而它们大多数时候只是打错一个引号、少一个缩进。所以把语法检查放进 CI，
在合并前就挡掉。

脚本里有 `python3 - <<'PY'` 形式的内嵌代码块，`bash -n` 看不出问题，
必须单独抽出来用 `ast.parse` 校验。

用法：

    python3 tools/check_scripts.py

全部通过返回 0，任何一项失败返回 1。
"""

from __future__ import annotations

import ast
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPTS_DIR = ROOT / "app" / "src" / "main" / "assets" / "scripts"

# 行内形如：  python3 - <<'PY' || echo "..."
HEREDOC_START = re.compile(r"python3?\s+-\s*<<\s*'(?P<tag>\w+)'")

problems: list[str] = []


def check_bash(path: pathlib.Path) -> None:
    result = subprocess.run(
        ["bash", "-n", str(path)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        problems.append(f"{path.name}: bash -n 失败\n{result.stderr.strip()}")


def check_python_blocks(path: pathlib.Path, text: str) -> int:
    """校验所有内嵌 python 块，返回块数量。"""
    lines = text.splitlines()
    count = 0
    index = 0
    while index < len(lines):
        match = HEREDOC_START.search(lines[index])
        if not match:
            index += 1
            continue

        tag = match.group("tag")
        body_start = index + 1
        cursor = body_start
        while cursor < len(lines) and lines[cursor].strip() != tag:
            cursor += 1

        if cursor >= len(lines):
            problems.append(
                f"{path.name}:{body_start} 内嵌 python 块缺少结束标记 {tag}"
            )
            return count

        body = "\n".join(lines[body_start:cursor])
        count += 1
        try:
            ast.parse(body)
        except SyntaxError as exc:
            # 报告脚本里的绝对行号，方便直接跳过去
            absolute = body_start + (exc.lineno or 1)
            problems.append(
                f"{path.name}:{absolute} 内嵌 python 语法错误：{exc.msg}"
            )

        index = cursor + 1

    return count


def check_shellcheck(paths: list[pathlib.Path]) -> None:
    executable = shutil.which("shellcheck")
    if executable is None:
        print("! 未找到 shellcheck，跳过静态检查（CI 里默认已预装）")
        return

    result = subprocess.run(
        [executable, "-S", "warning", "-f", "gcc", *[str(p) for p in paths]],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = (result.stdout or result.stderr).strip()
        problems.append(f"shellcheck 发现告警：\n{detail}")


def main() -> int:
    scripts = sorted(SCRIPTS_DIR.glob("*.sh"))
    if not scripts:
        print(f"! {SCRIPTS_DIR} 下没有 .sh 文件")
        return 1

    for script in scripts:
        text = script.read_text(encoding="utf-8")
        check_bash(script)
        blocks = check_python_blocks(script, text)
        note = f"，内嵌 python {blocks} 块" if blocks else ""
        print(f"✓ {script.name}{note}")

    check_shellcheck(scripts)

    # 内置插件是 Python，语法错误在 CI 里只是一行 log，
    # 在手机上就是"插件加载失败"，而且原因藏在几千行日志里。
    plugins_root = ROOT / "app" / "src" / "main" / "assets" / "plugins"
    plugin_files = sorted(plugins_root.rglob("*.py")) if plugins_root.exists() else []
    for path in plugin_files:
        try:
            ast.parse(path.read_text(encoding="utf-8"))
            print(f"✓ 插件 {path.relative_to(ROOT)}")
        except SyntaxError as exc:
            problems.append(f"{path.relative_to(ROOT)}: 语法错误 第 {exc.lineno} 行 {exc.msg}")

    if problems:
        print("\n失败：", file=sys.stderr)
        for item in problems:
            print(f"  - {item}", file=sys.stderr)
        return 1

    print(f"\n全部通过（{len(scripts)} 个脚本）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
