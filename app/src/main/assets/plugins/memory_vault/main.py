"""记忆库：把对话存档，按需检索，平时一个字都不读。

设计上就一条规矩：**不主动往上下文里塞东西**。

大多数记忆插件会在每轮请求前把"相关记忆"检索出来注入 prompt，代价是
每一轮都要多付几千 token，而且注入错了还会带偏回复。这个插件反过来：

- 聊天时它什么都不做，只是把原话按天写进文件（本地 IO，零 token）
- 模型觉得某句话值得记，自己调 `remember` 存进分类文件
- 用户在对话里问到"我之前说过什么"，模型调 `recall` 去翻

所以它的成本是：常驻两个工具定义（几百 token），加上偶尔一次检索。
不聊天的时候，它连模型都不碰。
"""

from __future__ import annotations

import re
import time
from datetime import datetime
from pathlib import Path

from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star, register

PLUGIN_NAME = "astrbot_plugin_memory_vault"

# 单次检索返回的字符上限。给太多会挤掉真正在聊的内容，
# 而且模型看不完，反而把结论说糊。
MAX_RECALL_CHARS = 2000

# 原文检索只看最近几天。翻三个月前的原话基本不会有正确结果，
# 只会把一堆不相关的句子塞进上下文。
RAW_SEARCH_DAYS = 14

_CATEGORY_SAFE = re.compile(r"[^\w\u4e00-\u9fff-]")


def _resolve_data_dir() -> Path:
    """优先用 AstrBot 的数据目录，拿不到就退到插件自己的目录。

    不直接 import 具体路径函数：不同版本的模块位置变过，
    import 失败会连插件加载一起挂掉，这里必须能兜住。
    """
    try:
        from astrbot.core.utils.astrbot_path import get_astrbot_data_path

        return Path(get_astrbot_data_path()) / "plugin_data" / PLUGIN_NAME
    except Exception:  # noqa: BLE001
        return Path(__file__).resolve().parent / "data"


def _safe_category(name: str) -> str:
    """分类名会被拼进文件名，挡掉斜杠和上跳。"""
    cleaned = _CATEGORY_SAFE.sub("", (name or "").strip())[:24]
    return cleaned or "杂项"


def _grams(text: str) -> set[str]:
    """中文没有空格，用 2-gram 当检索单位。

    比"分字"准：单字「吃」会命中「吃亏」，而「爱吃」不会。
    也没引 jieba——少一个依赖，手机上就少一个装不上的理由。
    """
    text = re.sub(r"\s+", "", text.lower())
    if len(text) <= 2:
        return {text} if text else set()
    return {text[i : i + 2] for i in range(len(text) - 1)}


@register(
    "astrbot_plugin_memory_vault",
    "AstrMobile",
    "对话按天存档、事实按分类记，平时不读，问到才检索",
    "1.0.0",
)
class MemoryVaultPlugin(Star):
    def __init__(self, context: Context, config: dict | None = None):
        super().__init__(context)
        self.cfg = config or {}
        self.root = _resolve_data_dir()
        self.raw_dir = self.root / "raw"
        self.fact_dir = self.root / "facts"
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        self.fact_dir.mkdir(parents=True, exist_ok=True)
        # 同一个事件可能触发多次 LLM 请求（工具循环），只记一次
        self._logged: set[str] = set()

    # ------------------------------------------------------------ 存档

    @filter.on_llm_response()
    async def archive_reply(self, event: AstrMessageEvent, resp) -> None:
        """把一轮问答原样写进当天的文件。

        这里刻意不做任何加工：抽取事实要花一次模型调用，而大多数对话
        其实没有被再问到的价值。先全部留下，用不用得上交给 recall。
        """
        if self.cfg.get("enable_archive", True) is False:
            return
        try:
            key = f"{id(event)}"
            if key in self._logged:
                return
            self._logged.add(key)
            if len(self._logged) > 500:
                self._logged.clear()

            user_text = (event.message_str or "").strip()
            reply_text = (getattr(resp, "completion_text", "") or "").strip()
            if not user_text and not reply_text:
                return

            now = datetime.now()
            sender = ""
            try:
                sender = event.get_sender_name() or ""
            except Exception:  # noqa: BLE001
                sender = ""

            session = ""
            try:
                session = event.unified_msg_origin or ""
            except Exception:  # noqa: BLE001
                session = ""

            line = (
                f"\n### {now.strftime('%H:%M')} [{session}]{(' ' + sender) if sender else ''}\n"
                f"- 用户：{user_text}\n"
                f"- 助手：{reply_text}\n"
            )
            path = self.raw_dir / f"{now.strftime('%Y-%m-%d')}.md"
            with path.open("a", encoding="utf-8") as fh:
                fh.write(line)
        except Exception as exc:  # noqa: BLE001
            # 存档失败绝不能影响正常回复
            logger.debug(f"[memory_vault] 存档失败：{exc}")

    # ------------------------------------------------------------ 工具

    @filter.llm_tool(name="remember")
    async def remember(self, event: AstrMessageEvent, category: str, content: str) -> str:
        """把一条值得长期记住的事实存进指定分类。

        什么时候用：用户主动说了关于自己的稳定信息（喜好、习惯、作息、
        人际关系、正在做的事），而这条信息以后很可能还会被问到。
        不要用它存寒暄、临时状态、或者你刚从网上查到的东西。

        Args:
            category(string): 分类名，例如「喜好」「作息」「人际关系」「工作」。同一个类别请始终用同一个名字，不要每次换说法
            content(string): 一句话写清楚的事，带上必要的限定，例如「喜欢吃辣，但不吃香菜」
        """
        try:
            text = (content or "").strip()
            if not text:
                return "内容为空，没有记录。"
            name = _safe_category(category)
            path = self.fact_dir / f"{name}.md"
            if self._already_known(path, text):
                return f"这条「{name}」里已经有了，没有重复记。"

            stamp = datetime.now().strftime("%Y-%m-%d")
            with path.open("a", encoding="utf-8") as fh:
                fh.write(f"- {stamp} {text}\n")
            return f"已记到「{name}」。"
        except Exception as exc:  # noqa: BLE001
            logger.warning(f"[memory_vault] 写入失败：{exc}")
            return f"记录失败：{exc}"

    @filter.llm_tool(name="recall")
    async def recall(self, event: AstrMessageEvent, query: str, category: str = "") -> str:
        """检索以前记下的内容。用户问到过去的事、个人偏好、之前聊过的话题时调用。

        没有命中就如实说没有，不要编。

        Args:
            query(string): 要查的关键词，用用户的原话里的词，例如「喜欢吃什么」「几点睡」
            category(string): 只查某个分类时填分类名，不确定就留空
        """
        try:
            if not (query or "").strip():
                return self._overview()
            hits = self._search(query or "", category or "")
            if not hits:
                return "记忆库里没有相关内容。"
            out = "\n".join(hits)
            if len(out) > MAX_RECALL_CHARS:
                out = out[:MAX_RECALL_CHARS] + "\n…（还有更多，可以缩小关键词）"
            return out
        except Exception as exc:  # noqa: BLE001
            logger.warning(f"[memory_vault] 检索失败：{exc}")
            return f"检索失败：{exc}"

    # ------------------------------------------------------------ 检索

    @staticmethod
    def _already_known(path: Path, text: str) -> bool:
        """同样的句子别记第二遍。

        Mem0 用一次额外的 LLM 调用来做 ADD/UPDATE/NOOP 判定，那在手机上
        太贵。这里退一步：只看字面重复。改口（「我现在不吃辣了」）识别不了，
        但至少不会把同一句话存成五条，检索时把上下文占满。
        """
        if not path.exists():
            return False
        try:
            target = _grams(text)
            for line in path.read_text(encoding="utf-8").splitlines():
                if not line.startswith("- "):
                    continue
                body = line[2:]
                # 去掉日期前缀再比
                body = body[11:] if len(body) > 11 and body[4] == "-" else body
                if body.strip() == text:
                    return True
                grams = _grams(body)
                if target and grams:
                    overlap = len(target & grams) / max(len(target), 1)
                    if overlap >= 0.9:
                        return True
        except Exception:  # noqa: BLE001
            return False
        return False

    def _overview(self) -> str:
        """没给关键词时，告诉模型库里有哪些分类、各多少条。

        Letta 的做法是把「记忆统计」常驻在上下文里，让模型知道自己有什么。
        那要一直占 token，这里改成按需问一次。
        """
        rows: list[str] = []
        total = 0
        for path in sorted(self.fact_dir.glob("*.md")):
            try:
                lines = [
                    line
                    for line in path.read_text(encoding="utf-8").splitlines()
                    if line.startswith("- ")
                ]
            except Exception:  # noqa: BLE001
                continue
            if not lines:
                continue
            total += len(lines)
            rows.append(f"- {path.stem}：{len(lines)} 条")

        raw_files = list(self.raw_dir.glob("*.md"))
        if not rows and not raw_files:
            return "记忆库还是空的，还没有记下任何东西。"

        head = f"共 {total} 条事实，分类如下：" if rows else "还没有分类事实。"
        tail = f"另有 {len(raw_files)} 天的对话原文可查。"
        return "\n".join([head, *rows, tail])

    def _search(self, query: str, category: str) -> list[str]:
        target = _grams(query)
        if not target:
            return []

        scored: list[tuple[float, str]] = []

        # 分类事实优先：一句话就是一条结论，粒度最合适
        for path in sorted(self.fact_dir.glob("*.md")):
            if category and path.stem != _safe_category(category):
                continue
            try:
                lines = path.read_text(encoding="utf-8").splitlines()
            except Exception:  # noqa: BLE001
                continue
            for line in lines:
                line = line.strip()
                if not line.startswith("- "):
                    continue
                body = line[2:]
                score = self._score(target, body)
                if score > 0:
                    scored.append((score + 1.0, f"[{path.stem}] {body}"))

        # 原文兜底：只在最近几天里找，且权重更低
        cutoff = time.time() - RAW_SEARCH_DAYS * 86400
        for path in sorted(self.raw_dir.glob("*.md"), reverse=True):
            try:
                if path.stat().st_mtime < cutoff:
                    continue
                text = path.read_text(encoding="utf-8")
            except Exception:  # noqa: BLE001
                continue
            for line in text.splitlines():
                line = line.strip()
                if not line.startswith("- "):
                    continue
                body = line[2:]
                score = self._score(target, body)
                if score > 0:
                    scored.append((score, f"[{path.stem} 原话] {body}"))

        scored.sort(key=lambda item: item[0], reverse=True)
        # 同一条被两处命中时只留一次，避免把上下文塞满重复内容
        seen: set[str] = set()
        result: list[str] = []
        for _, text in scored:
            if text in seen:
                continue
            seen.add(text)
            result.append(text)
            if len(result) >= 12:
                break
        return result

    @staticmethod
    def _score(target: set[str], text: str) -> float:
        """2-gram 为主，单字兜底。

        只做 2-gram 会漏得很干脆：问「我喜欢吃什么」时 query 切成
        「喜欢/欢吃/吃什/什么」，而记的是「爱吃火锅」，一个都不重合。
        单字兜底虽然糙，但它保证了「吃」这个字还能把两边连起来。
        """
        if not text:
            return 0.0
        grams = _grams(text)
        hit = len(target & grams)
        if hit == 0:
            # 退化成单字：权重压到 1/3，避免「我」「的」这种字把无关内容顶上来
            chars = set(re.sub(r"\s+", "", text))
            query_chars = set("".join(target))
            common = len(chars & query_chars)
            return (common / max(len(query_chars), 1)) * 0.33
        return hit / len(target)
