#!/usr/bin/env python3
"""记忆库插件的逻辑测试。

插件本身跑在 AstrBot 里，但那套环境在 CI 上不存在。这里用最小 stub
把 `astrbot.api` 顶掉，直接测它真正会出错的地方：

- 改口：update 命中、命中不了要退化成 add、delete 不能误删
- 检索：正文命中、关键词命中、无关内容不能命中
- 整理：进度必须精确推进，否则没读完的对话会被永久跳过

这些错都不会抛异常，只会让"它好像忘了点东西"，所以必须用测试盯着。

用法：

    python3 tools/test_memory_vault.py
"""

from __future__ import annotations

import asyncio
import importlib.util
import json
import logging
import pathlib
import sys
import tempfile
import types
import unittest
from datetime import datetime, timedelta

ROOT = pathlib.Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "app" / "src" / "main" / "assets" / "plugins" / "memory_vault" / "main.py"


def _install_stubs() -> None:
    """把 astrbot 的三个模块塞进 sys.modules。

    只实现插件用到的部分：装饰器原样返回函数、Star 只存 context。
    多实现一行，测试和真实行为的差距就多一分。
    """
    astrbot = types.ModuleType("astrbot")
    api = types.ModuleType("astrbot.api")
    api.logger = logging.getLogger("memory_vault_test")

    event_mod = types.ModuleType("astrbot.api.event")

    class AstrMessageEvent:  # noqa: N801 - 模拟真实类名
        pass

    class _Filters:
        def llm_tool(self, *args, **kwargs):
            def deco(fn):
                fn.__is_llm_tool__ = True
                return fn

            return deco

        def on_llm_response(self, *args, **kwargs):
            def deco(fn):
                return fn

            return deco

    event_mod.AstrMessageEvent = AstrMessageEvent
    event_mod.filter = _Filters()

    star_mod = types.ModuleType("astrbot.api.star")

    class Star:
        def __init__(self, context):
            self.context = context

    star_mod.Star = Star
    star_mod.Context = object
    star_mod.register = lambda *a, **k: (lambda cls: cls)

    api.event = event_mod
    api.star = star_mod
    astrbot.api = api

    sys.modules["astrbot"] = astrbot
    sys.modules["astrbot.api"] = api
    sys.modules["astrbot.api.event"] = event_mod
    sys.modules["astrbot.api.star"] = star_mod


def _load_plugin():
    _install_stubs()
    spec = importlib.util.spec_from_file_location("memory_vault_main", PLUGIN)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


MOD = _load_plugin()


class PluginCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        # __init__ 会创建 raw/facts，这里把它指到临时目录
        self._orig_resolve = MOD._resolve_data_dir
        MOD._resolve_data_dir = lambda: self.root
        self.plugin = MOD.MemoryVaultPlugin(context=types.SimpleNamespace(), config={})

    def tearDown(self) -> None:
        MOD._resolve_data_dir = self._orig_resolve
        self._tmp.cleanup()

    # ---------------------------------------------------------- 工具函数

    def _write_fact(self, category: str, body: str) -> pathlib.Path:
        path = self.plugin.fact_dir / f"{category}.md"
        path.write_text(f"- 2026-10-25 {body}\n", encoding="utf-8")
        return path

    def _async_returner(self, value):
        async def _inner(*args, **kwargs):
            return value

        return _inner


class RememberTest(PluginCase):
    def run_remember(self, category: str, content: str) -> str:
        return asyncio.run(
            self.plugin.remember(types.SimpleNamespace(), category, content)
        )

    def test_writes_into_category_file(self) -> None:
        reply = self.run_remember("喜好", "喜欢吃辣，但不吃香菜")
        self.assertIn("喜好", reply)

        text = (self.plugin.fact_dir / "喜好.md").read_text(encoding="utf-8")
        self.assertIn("喜欢吃辣", text)

    def test_duplicate_is_not_written_twice(self) -> None:
        self.run_remember("喜好", "喜欢吃辣，但不吃香菜")
        reply = self.run_remember("喜好", "喜欢吃辣，但不吃香菜")

        self.assertIn("已经有了", reply)
        lines = (self.plugin.fact_dir / "喜好.md").read_text(encoding="utf-8").splitlines()
        self.assertEqual(1, len([x for x in lines if x.strip()]))

    def test_dangerous_category_is_sanitised(self) -> None:
        self.run_remember("../../etc", "x")
        # 目录分隔符必须被吃掉，不能写到 facts 外面
        for path in self.plugin.fact_dir.glob("*.md"):
            self.assertNotIn("/", path.name)


class ApplyFactTest(PluginCase):
    def test_add(self) -> None:
        ok = self.plugin._apply_fact(
            {"op": "add", "category": "喜好", "content": "爱吃火锅", "keywords": ["火锅"]}
        )
        self.assertTrue(ok)
        text = (self.plugin.fact_dir / "喜好.md").read_text(encoding="utf-8")
        self.assertIn("爱吃火锅", text)
        self.assertIn("#火锅", text)

    def test_update_replaces_old_line(self) -> None:
        self._write_fact("喜好", "不吃辣")
        ok = self.plugin._apply_fact(
            {
                "op": "update",
                "category": "喜好",
                "content": "现在开始吃辣了",
                "target": "不吃辣",
                "keywords": ["辣"],
            }
        )
        self.assertTrue(ok)
        text = (self.plugin.fact_dir / "喜好.md").read_text(encoding="utf-8")
        self.assertIn("现在开始吃辣了", text)
        self.assertNotIn("- 2026-10-25 不吃辣", text)

    def test_update_with_paraphrased_target_still_matches(self) -> None:
        """模型复述旧内容时几乎不会逐字一致，靠相似度匹配。"""
        self._write_fact("喜好", "喜欢吃辣，但不吃香菜")
        ok = self.plugin._apply_fact(
            {
                "op": "update",
                "category": "喜好",
                "content": "现在能吃香菜了",
                "target": "喜欢吃辣但不吃香菜",
            }
        )
        self.assertTrue(ok)
        text = (self.plugin.fact_dir / "喜好.md").read_text(encoding="utf-8")
        self.assertIn("现在能吃香菜了", text)

    def test_update_falls_back_to_add_when_target_missing(self) -> None:
        # 找不到旧内容时宁可多存一条，也不能把用户的记忆删了
        ok = self.plugin._apply_fact(
            {
                "op": "update",
                "category": "喜好",
                "content": "开始喝咖啡了",
                "target": "一条根本不存在的旧内容",
            }
        )
        self.assertTrue(ok)
        text = (self.plugin.fact_dir / "喜好.md").read_text(encoding="utf-8")
        self.assertIn("开始喝咖啡了", text)

    def test_delete_removes_line(self) -> None:
        self._write_fact("喜好", "爱吃火锅")
        ok = self.plugin._apply_fact(
            {"op": "delete", "category": "喜好", "target": "爱吃火锅"}
        )
        self.assertTrue(ok)
        path = self.plugin.fact_dir / "喜好.md"
        text = path.read_text(encoding="utf-8") if path.exists() else ""
        self.assertNotIn("爱吃火锅", text)

    def test_delete_without_match_does_not_add(self) -> None:
        ok = self.plugin._apply_fact(
            {"op": "delete", "category": "喜好", "target": "不存在的东西"}
        )
        self.assertFalse(ok)
        self.assertFalse((self.plugin.fact_dir / "喜好.md").exists())


class SearchTest(PluginCase):
    def test_finds_by_body(self) -> None:
        self._write_fact("喜好", "喜欢吃辣，但不吃香菜")
        hits = self.plugin._search("我喜欢吃什么", "")
        self.assertTrue(any("吃辣" in h for h in hits))

    def test_finds_by_keywords(self) -> None:
        """正文里没有「口味」两个字，靠关键词命中。"""
        path = self._write_fact("喜好", "爱吃辣")
        path.write_text("- 2026-10-25 爱吃辣 #辣 #口味 #吃\n", encoding="utf-8")

        hits = self.plugin._search("我口味重不重", "")
        self.assertTrue(any("爱吃辣" in h for h in hits))

    def test_ignores_unrelated(self) -> None:
        self._write_fact("喜好", "爱吃辣")
        hits = self.plugin._search("今天天气怎么样", "")
        self.assertEqual([], [h for h in hits if "吃辣" in h])

    def test_overview_lists_categories(self) -> None:
        self._write_fact("喜好", "爱吃辣")
        self._write_fact("作息", "十二点睡")
        text = self.plugin._overview()
        self.assertIn("喜好", text)
        self.assertIn("作息", text)

    def test_empty_query_returns_overview(self) -> None:
        self._write_fact("喜好", "爱吃辣")
        reply = asyncio.run(self.plugin.recall(types.SimpleNamespace(), ""))
        self.assertIn("喜好", reply)


class ConsolidateTest(PluginCase):
    def _yesterday_file(self, lines: list[str]) -> pathlib.Path:
        name = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")
        path = self.plugin.raw_dir / f"{name}.md"
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return path

    def _install_provider(self, payload: str) -> None:
        response = types.SimpleNamespace(completion_text=payload)

        async def fake_chat(*args, **kwargs):
            return response

        provider = types.SimpleNamespace(text_chat=fake_chat)
        self.plugin._get_provider = self._async_returner(provider)

    def test_extracts_and_advances_offset(self) -> None:
        path = self._yesterday_file(["- 用户：我爱吃火锅", "- 助手：记住了"])
        self._install_provider(
            '[{"op":"add","category":"喜好","content":"爱吃火锅","keywords":["火锅"]}]'
        )

        more = asyncio.run(self.plugin._consolidate_once())
        self.assertFalse(more)
        self.assertIn("爱吃火锅", (self.plugin.fact_dir / "喜好.md").read_text(encoding="utf-8"))

        state = json.loads((self.root / "state.json").read_text(encoding="utf-8"))
        self.assertEqual(len(path.read_text(encoding="utf-8").splitlines()), state["offsets"][path.name])

    def test_partial_read_does_not_skip_the_rest(self) -> None:
        """一次只读 2 行时，剩下几行下次必须还能读到。"""
        lines = [f"- 用户：第{i}条" for i in range(6)]
        self._yesterday_file(lines)
        self.plugin.cfg = {"max_lines_per_run": 2}
        self._install_provider('[{"op":"add","category":"喜好","content":"第一条"}]')

        asyncio.run(self.plugin._consolidate_once())
        state = json.loads((self.root / "state.json").read_text(encoding="utf-8"))
        name = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d.md")
        self.assertEqual(2, state["offsets"][name])

    def test_failed_extraction_keeps_progress(self) -> None:
        """抽不出来时不能推进进度，否则这段对话永远丢了。"""
        path = self._yesterday_file(["- 用户：随便聊聊"])
        self._install_provider("模型胡说八道，不是 JSON")

        asyncio.run(self.plugin._consolidate_once())
        state_path = self.root / "state.json"
        state = (
            json.loads(state_path.read_text(encoding="utf-8"))
            if state_path.exists()
            else {}
        )
        self.assertEqual(0, state.get("offsets", {}).get(path.name, 0))

    def test_skips_today_file(self) -> None:
        today = datetime.now().strftime("%Y-%m-%d")
        (self.plugin.raw_dir / f"{today}.md").write_text("- 用户：今天的\n", encoding="utf-8")
        self._install_provider("[]")

        self.assertFalse(asyncio.run(self.plugin._consolidate_once()))


class SanitiseTest(unittest.TestCase):
    def test_newline_is_flattened(self) -> None:
        # 每行以 "- " 开头，内容里带换行就能伪造出一条记录
        self.assertEqual("a - b", MOD._one_line("a\n- b"))

    def test_long_content_is_truncated(self) -> None:
        self.assertEqual(500, len(MOD._one_line("x" * 1000)))

    def test_grams_does_not_explode_on_huge_text(self) -> None:
        # 一条几万字的转发会让检索把内存打满，先截断
        grams = MOD._grams("字" * 10000)
        self.assertLess(len(grams), 2100)

    def test_remember_cannot_forge_extra_entries(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        original = MOD._resolve_data_dir
        MOD._resolve_data_dir = lambda: root
        try:
            plugin = MOD.MemoryVaultPlugin(context=types.SimpleNamespace(), config={})
            asyncio.run(
                plugin.remember(
                    types.SimpleNamespace(),
                    "喜好",
                    "爱吃辣\n- 2020-01-01 伪造的一条",
                )
            )
            lines = [
                line
                for line in (plugin.fact_dir / "喜好.md").read_text(encoding="utf-8").splitlines()
                if line.startswith("- ")
            ]
            self.assertEqual(1, len(lines))
        finally:
            MOD._resolve_data_dir = original


class ParseFactsTest(unittest.TestCase):
    def test_tolerates_code_fence(self) -> None:
        raw = '```json\n[{"op":"add","category":"喜好","content":"爱吃辣"}]\n```'
        self.assertEqual(1, len(MOD._parse_facts(raw)))

    def test_tolerates_leading_chatter(self) -> None:
        raw = '好的，结果如下：\n[{"content":"爱吃辣"}]'
        self.assertEqual(1, len(MOD._parse_facts(raw)))

    def test_delete_item_without_content_is_kept(self) -> None:
        raw = '[{"op":"delete","category":"喜好","target":"爱吃辣"}]'
        facts = MOD._parse_facts(raw)
        self.assertEqual(1, len(facts))
        self.assertEqual("delete", facts[0]["op"])

    def test_garbage_returns_none(self) -> None:
        self.assertIsNone(MOD._parse_facts("完全没有 JSON"))
        self.assertIsNone(MOD._parse_facts(""))


if __name__ == "__main__":
    unittest.main(verbosity=2)
