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

import asyncio
import json
import re
import threading
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


def _one_line(value: object, limit: int = 500) -> str:
    """把任意文本压成一行。

    换行不是排版问题：每行以 "- " 开头，一段带换行的内容会伪造出
    第二条记录。模型被诱导时也可能"顺手"写进来的东西。
    """
    return " ".join(str(value or "").split())[:limit]


def _strip_keywords(body: str) -> str:
    """去掉行尾的 #关键词，只留正文。"""
    return body.split(" #")[0].strip()


def _keywords_of(body: str) -> list[str]:
    parts = body.split(" #")
    return [part.strip() for part in parts[1:] if part.strip()] if len(parts) > 1 else []


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

    截前 2000 字：一条转发的长文能到几万字，全量切 gram 会在检索时
    把内存和 CPU 打满，而检索是没有超时保护的。
    """
    text = re.sub(r"\s+", "", text.lower())[:2000]
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
        # 多个群同时说话时，append 会并发写同一个文件。
        # O_APPEND 本身够稳，但一次写被拆成多次 syscall 时仍会交错。
        self._io_lock = threading.Lock()

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
            with self._io_lock:
                with path.open("a", encoding="utf-8") as fh:
                    fh.write(line)
        except Exception as exc:  # noqa: BLE001
            # 存档失败绝不能影响正常回复
            logger.debug(f"[memory_vault] 存档失败：{exc}")

    # ------------------------------------------------------------ 夜间整理

    async def initialize(self) -> None:
        """启动后台整理任务。

        白天只在存档，一个字都不抽——抽取要调模型，摊到每一轮就是每轮多一次
        请求。放到凌晨批量做，一天只花一次，而且这时候没人聊天，失败了也不影响谁。
        """
        if self.cfg.get("enable_nightly", True) is False:
            return
        try:
            self._task = asyncio.create_task(self._nightly_loop())
            logger.info("[memory_vault] 夜间整理已启用")
        except Exception as exc:  # noqa: BLE001
            # 后台任务起不来不该连累插件加载：手动 remember/recall 仍然可用
            logger.warning(f"[memory_vault] 夜间整理启动失败：{exc}")

    async def terminate(self) -> None:
        task = getattr(self, "_task", None)
        if task:
            task.cancel()

    async def _nightly_loop(self) -> None:
        while True:
            try:
                await asyncio.sleep(300)
                if not self._due():
                    continue
                # 积压时一口气追几个文件，但设上限：手机上一轮跑太久会被系统掐掉
                for _ in range(5):
                    if not await self._consolidate_once():
                        break
                self._mark_ran()
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                # 这里绝不能抛出去：后台任务一旦死掉，之后就再也不整理了，
                # 而且没有任何提示。
                logger.warning(f"[memory_vault] 夜间整理异常：{exc}")

    def _due(self) -> bool:
        hour = int(self.cfg.get("run_at_hour", 3) or 3)
        now = datetime.now()
        if now.hour < hour:
            return False
        state = self._load_state()
        return state.get("last_run") != now.strftime("%Y-%m-%d")

    async def _consolidate_once(self) -> bool:
        """处理一个文件。返回是否还有待处理的文件。

        一次只吃一个文件（一天一个），处理完精确推进到"读到的最后一行"。
        之前那版按整文件标记，结果是一天里没读完的部分被永久跳过——
        这种错在界面上完全看不出来，只是"它好像忘了点东西"。
        """
        max_lines = int(self.cfg.get("max_lines_per_run", 200) or 200)
        target = self._next_pending_file()
        if target is None:
            return False

        try:
            lines = target.read_text(encoding="utf-8").splitlines()
        except Exception as exc:  # noqa: BLE001
            logger.debug(f"[memory_vault] 读不到 {target.name}：{exc}")
            return False

        offset = self._offset_of(target)
        chunk = [line for line in lines[offset:] if line.strip()][:max_lines]
        if not chunk:
            self._set_offset(target, len(lines))
            return True

        facts = await self._extract(chunk)
        if facts is None:
            # 今天不再试。否则每 5 分钟重试一次，一天能白烧几百次调用。
            # 进度不推进，明天会从同一段重来。
            return False

        changed = 0
        for item in facts:
            if self._apply_fact(item):
                changed += 1

        self._set_offset(target, _offset_after(lines, offset, len(chunk)))
        logger.info(f"[memory_vault] 夜间整理：读 {len(chunk)} 行，更新 {changed} 条")
        # 返回值统一表示"还有没有下一个待处理文件"，不要用"处理成功"来顶替
        return self._next_pending_file() is not None

    def _apply_fact(self, item: dict) -> bool:
        """按 op 执行一条抽取结果。

        模型给 update/delete 时，target 必须能在库里按字面找到才执行；
        找不到就退化成 add。宁可多留一条旧说法，也不能凭一句相似就删掉
        用户的东西——删错了没有任何地方能看出来。
        """
        op = str(item.get("op", "add") or "add").lower()
        name = _safe_category(item.get("category", ""))
        content = _one_line(item.get("content"))
        target = _one_line(item.get("target"))
        keywords = [
            _one_line(k, 16) for k in (item.get("keywords") or []) if _one_line(k, 16)
        ][:6]
        path = self.fact_dir / f"{name}.md"

        if op in ("update", "delete") and target:
            if self._replace_line(path, target, content if op == "update" else "", keywords):
                return True
            if op == "delete":
                # 删不掉就算了，不要退化成"用空内容新增"
                return False

        if not content:
            return False
        if self._already_known(path, content):
            return False

        stamp = datetime.now().strftime("%Y-%m-%d")
        suffix = "".join(f" #{k}" for k in keywords)
        with self._io_lock:
            with path.open("a", encoding="utf-8") as fh:
                fh.write(f"- {stamp} {content}{suffix}\n")
        return True

    def _replace_line(
        self, path: Path, target: str, new_content: str, keywords: list[str]
    ) -> bool:
        """把库里最像 target 的那条替换或删除。

        用 2-gram 重合度而不是精确相等：模型转述旧内容时几乎不可能逐字复制，
        精确匹配会让 update/delete 永远失败。
        """
        if not path.exists():
            return False
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except Exception:  # noqa: BLE001
            return False

        want = _grams(target)
        if not want:
            return False

        best = -1
        best_score = 0.0
        for index, line in enumerate(lines):
            if not line.startswith("- "):
                continue
            body = _strip_keywords(line[2:])
            body = body[11:] if len(body) > 11 and body[4:5] == "-" else body
            grams = _grams(body)
            if not grams:
                continue
            score = len(want & grams) / max(len(want), 1)
            if score > best_score:
                best_score = score
                best = index

        if best < 0 or best_score < 0.7:
            return False

        if new_content:
            stamp = datetime.now().strftime("%Y-%m-%d")
            suffix = "".join(f" #{k}" for k in keywords)
            lines[best] = f"- {stamp} {new_content}{suffix}"
        else:
            del lines[best]
        _write_atomic(path, "\n".join(lines) + "\n")
        return True

    def _next_pending_file(self) -> Path | None:
        today = datetime.now().strftime("%Y-%m-%d")
        for path in sorted(self.raw_dir.glob("*.md")):
            # 当天的文件还在长，读了也是半截
            if path.stem >= today:
                continue
            if self._offset_of(path) < len(
                path.read_text(encoding="utf-8").splitlines() if path.exists() else []
            ):
                return path
        return None

    def _offset_of(self, path: Path) -> int:
        return int(self._load_state().get("offsets", {}).get(path.name, 0))

    def _set_offset(self, path: Path, value: int) -> None:
        state = self._load_state()
        state.setdefault("offsets", {})[path.name] = value
        self._save_state(state)

    def _state_path(self) -> Path:
        return self.root / "state.json"

    def _load_state(self) -> dict:
        try:
            return json.loads(self._state_path().read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            return {}

    def _save_state(self, state: dict) -> None:
        try:
            # 原子写。这份文件丢了，offsets 全归零，所有历史会被重新抽取一遍——
            # 花钱是次要的，重复条目会立刻把分类文件塞满。
            _write_atomic(
                self._state_path(), json.dumps(state, ensure_ascii=False)
            )
        except Exception as exc:  # noqa: BLE001
            logger.debug(f"[memory_vault] 状态写入失败：{exc}")

    def _mark_ran(self) -> None:
        state = self._load_state()
        state["last_run"] = datetime.now().strftime("%Y-%m-%d")
        self._save_state(state)

    async def _extract(self, lines: list[str]) -> list[dict] | None:
        provider = await self._get_provider()
        if provider is None:
            logger.debug("[memory_vault] 没有可用的模型，跳过整理")
            return None

        # 单行截断：一条转发的长文能把整个 prompt 撑爆
        text = "\n".join(lines)[-12000:]
        existing = self._existing_context()
        payload = f"<existing>\n{existing}\n</existing>\n\n<new>\n{text}\n</new>"

        system = (
            "你是一个记忆整理器。你会看到两部分：<existing> 是已经记住的，"
            "<new> 是新的聊天记录。\n"
            "任务：从 <new> 里抽取关于用户的长期事实，并和 <existing> 比对后决定怎么处理。\n\n"
            "只抽稳定的、以后还会被问到的：偏好、习惯、作息、人际关系、正在做的事、明确的决定。\n"
            "不要抽寒暄、临时情绪、一次性的问答。\n\n"
            "输出严格的 JSON 数组，不要任何解释文字。每项字段：\n"
            '{"op": "add", "category": "喜好", "content": "喜欢吃辣，但不吃香菜", '
            '"keywords": ["辣", "口味", "香菜"], "target": ""}\n\n'
            "op 三种取值：\n"
            "- add：新事实\n"
            "- update：用户改口了（例如以前说不吃辣，现在开始吃）。content 写新的说法，"
            "target 填 <existing> 里那条旧内容的原文\n"
            "- delete：旧事实已经不成立了，但没有新说法。target 填旧内容原文，content 留空\n\n"
            "target 必须从 <existing> 里逐字复制，一个字都不能改。不确定就一律用 add。\n"
            "category 用简短中文名词，同一个意思始终用同一个词（统一用「喜好」，"
            "不要写成「爱好」「偏好」）。\n"
            "keywords 是 2–5 个用户可能用来提问的词，写同义词和口语说法，"
            "让「口味重不重」也能找到「爱吃辣」。\n"
            "没有可抽的就输出 []。"
        )

        try:
            try:
                resp = await provider.text_chat(
                    prompt=payload, context=[], system_prompt=system
                )
            except TypeError:
                resp = await provider.text_chat(
                    prompt=payload, contexts=[], system_prompt=system
                )
        except Exception as exc:  # noqa: BLE001
            logger.warning(f"[memory_vault] 调用模型失败：{exc}")
            return None

        raw = getattr(resp, "completion_text", "") or ""
        return _parse_facts(raw)

    def _existing_context(self, limit: int = 80) -> str:
        """把现有事实摊平成文本喂给模型，供它判断新增还是改口。

        只取每类最近 20 条、总共 80 条：再多会挤掉真正的输入内容，
        而且越老的条目越不可能被改口。
        """
        rows: list[str] = []
        for path in sorted(self.fact_dir.glob("*.md")):
            try:
                lines = [
                    line
                    for line in path.read_text(encoding="utf-8").splitlines()
                    if line.startswith("- ")
                ]
            except Exception:  # noqa: BLE001
                continue
            for line in lines[-20:]:
                body = _strip_keywords(line[2:])
                body = body[11:] if len(body) > 11 and body[4:5] == "-" else body
                if body.strip():
                    rows.append(f"[{path.stem}] {body.strip()}")
        if not rows:
            return "（还没有任何记忆）"
        return "\n".join(rows[-limit:])

    async def _get_provider(self):
        """拿当前使用的模型。不同版本接口不一样，逐级退。"""
        try:
            prov = await self.context.get_using_provider_async()
            if prov:
                return prov
        except Exception:  # noqa: BLE001
            pass
        try:
            prov = self.context.get_using_provider()
            if prov:
                return prov
        except Exception:  # noqa: BLE001
            pass
        try:
            providers = self.context.get_all_providers()
            return providers[0] if providers else None
        except Exception:  # noqa: BLE001
            return None

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
            text = _one_line(content)
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
                body = _strip_keywords(line[2:])
                # 去掉日期前缀再比
                body = body[11:] if len(body) > 11 and body[4:5] == "-" else body
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
                plain = _strip_keywords(body)
                # 关键词是夜间整理时让模型顺手写的同义说法：
                # 问「口味重不重」时，正文里没有「口味」两个字，靠它命中
                kws = _keywords_of(body)
                score = self._score(target, plain)
                if kws:
                    score = max(score, self._score(target, " ".join(kws)) * 0.9)
                if score > 0:
                    scored.append((score + 1.0, f"[{path.stem}] {plain}"))

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


def _parse_facts(raw: str) -> list[dict] | None:
    """从模型输出里抠出 JSON 数组。

    模型经常在 JSON 外面包一层 ```json 或者加一句"好的，以下是"，
    直接 json.loads 会失败。这里只找第一组方括号，并逐个校验元素形状。
    """
    if not raw:
        return None
    text = raw.strip()
    text = re.sub(r"^```(?:json)?", "", text).strip()
    text = re.sub(r"```$", "", text).strip()

    start = text.find("[")
    end = text.rfind("]")
    if start < 0 or end <= start:
        return None
    try:
        data = json.loads(text[start : end + 1])
    except Exception:  # noqa: BLE001
        return None
    if not isinstance(data, list):
        return None

    result: list[dict] = []
    for item in data:
        if not isinstance(item, dict):
            continue
        # delete 项只有 target 没有 content，按 content 过滤会把删除请求全吃掉
        if item.get("content") or item.get("target"):
            result.append(item)
    return result


def _offset_after(lines: list[str], start: int, consumed: int) -> int:
    """给定"从 start 行开始读了 consumed 条非空行"，返回新的起始行号。

    空行不参与计数，但必须被跨过去，否则下次会重复读到同一条。
    """
    count = 0
    for index in range(start, len(lines)):
        if lines[index].strip():
            count += 1
            if count >= consumed:
                return index + 1
    return len(lines)


def _write_atomic(path: Path, text: str) -> None:
    """先写临时文件再改名。

    整理过程中被系统杀掉时，直接覆写会留下半截文件——分类事实丢一半，
    而且没有任何报错。rename 在同一分区上是原子的。
    """
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)
