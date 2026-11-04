from __future__ import annotations

from argparse import ArgumentParser, Namespace
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, DefaultDict, Dict, Iterable, List, Optional, Sequence, Set, Tuple

import asyncio
import json
import random
import re
import sys


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[0]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except AttributeError:
    pass

from analyze_bot_phrase_patterns import (  # noqa: E402
    BotMessage,
    DEFAULT_BOT_CONFIG_PATH,
    DEFAULT_DB_PATH,
    DEFAULT_MODEL_CONFIG_PATH,
    DEFAULT_OUTPUT_DIR,
    build_corpus_stats,
    build_final_prompt,
    build_session_stats,
    build_statistical_candidates,
    deduplicate_accounts,
    enrich_pattern_examples,
    fetch_bot_messages,
    format_datetime_for_sql,
    load_bot_accounts_from_config,
    parse_datetime_filter,
    parse_platform_account,
    parse_recent_filter,
    progress,
    request_llm_json,
    split_chunks,
    validate_task_uses_non_thinking_models,
    write_payload,
)


REQUEST_TYPE = "bot_phrase_regex_capture_evaluation"
REGEX_MATCHING_MODE = "strict_v2"


@dataclass(frozen=True)
class RegexSpec:
    name: str
    description: str
    pattern: str
    source: str
    flags: int
    extraction_count: int
    extraction_example_indexes: List[int]

    def to_dict(self, extraction_total: int) -> Dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "pattern": self.pattern,
            "source": self.source,
            "flags": describe_regex_flags(self.flags),
            "extraction_count": self.extraction_count,
            "extraction_ratio": safe_ratio(self.extraction_count, extraction_total),
            "extraction_example_indexes": self.extraction_example_indexes,
        }


@dataclass(frozen=True)
class RegexTemplate:
    name: str
    description: str
    pattern: str
    flags: int = 0


REGEX_TEMPLATES: Tuple[RegexTemplate, ...] = (
    RegexTemplate(
        name="ending_shi_ba",
        description="以“是吧”收尾的反问/确认句",
        pattern=r"是吧[。！？!?]*$",
    ),
    RegexTemplate(
        name="ni_cai",
        description="你才/你才是 反击句式",
        pattern=r"(?:^|[，,。！？!?\s])你才[^\n，,。！？!?]{0,12}[。！？!?]*$",
    ),
    RegexTemplate(
        name="ge_zhe",
        description="搁这/你搁这 嘲讽句式",
        pattern=r"(?:^|[，,。！？!?\s])(?:你们?|它们?)?搁这(?:儿)?[^\n，,。！？!?]{1,18}(?:呢|啊|呀|[。！？!?])*$",
    ),
    RegexTemplate(
        name="bie_x_le",
        description="别 + 行为 + 了 的制止句式",
        pattern=r"(?:^|[，,。！？!?\s])别[^\n，,。！？!?]{1,10}了[。！？!?]*$",
    ),
    RegexTemplate(
        name="ban_threat",
        description="再做某事就禁言/踢/封的警告句式",
        pattern=(
            r"(?:^|[，,。！？!?\s])再[^\n，,。！？!?]{0,24}"
            r"(?:禁言|禁|踢|封|罚|套餐|处理|小黑屋)"
            r"|(?:给你禁言|直接禁言|禁言套餐|全禁|永封|封你|踢出去|踢了)"
        ),
    ),
    RegexTemplate(
        name="ni_ge_tou",
        description="你个头 否定/回怼句式",
        pattern=r"[^\n，,。！？!?]{1,12}你个头[。！？!?]*$",
    ),
    RegexTemplate(
        name="ganma",
        description="干嘛 反问句式",
        pattern=r"干嘛[。！？!?]*$",
    ),
    RegexTemplate(
        name="bu_ran_ne",
        description="不然呢 固定反问",
        pattern=r"^不然呢[。！？!?]*$",
    ),
    RegexTemplate(
        name="xiang_de_mei",
        description="想得美 固定拒绝",
        pattern=r"^想得美[。！？!?]*$",
    ),
    RegexTemplate(
        name="guan_wo_shi",
        description="关我/关你什么事或关我/关你屁事",
        pattern=r"关[我你](?:什么事|啥事|屁事)[。！？!?]*$",
    ),
    RegexTemplate(
        name="ni_shei_a",
        description="你谁啊 固定反问",
        pattern=r"^你谁啊[。！？!?]*$",
    ),
    RegexTemplate(
        name="tech_group",
        description="技术群规则提醒",
        pattern=r"技术群(?:不|别|禁止|不是|发什么|少|请|能不能|要)[^\n，,。！？!?]{0,24}",
    ),
    RegexTemplate(
        name="v_transfer",
        description="v我/转我 金额玩梗",
        pattern=r"(?:^|[，,。！？!?\s])(?:v|转)我\s*\d+",
        flags=re.IGNORECASE,
    ),
    RegexTemplate(
        name="zi_yue",
        description="子曰 文言引用句式",
        pattern=r"^子曰[：:「]",
    ),
    RegexTemplate(
        name="queue",
        description="排队相关固定回应",
        pattern=r"^(?:又一个)?排队(?:去|吧|[，,。！？!?]|$)|排队[^\n，,。！？!?]{0,12}(?:后面|表白|调戏)",
    ),
)


def safe_ratio(count: int, total: int) -> float:
    if total <= 0:
        return 0.0
    return round(count / total, 6)


def describe_regex_flags(flags: int) -> List[str]:
    names: List[str] = []
    if flags & re.IGNORECASE:
        names.append("IGNORECASE")
    return names


def build_output_path() -> Path:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    return DEFAULT_OUTPUT_DIR / f"bot_phrase_regex_capture_{timestamp}.json"


def compile_regex(pattern: str, flags: int) -> re.Pattern[str]:
    return re.compile(pattern, flags)


def message_examples(
    message_by_index: Dict[int, BotMessage],
    indexes: Iterable[int],
    max_examples: int,
) -> List[Dict[str, str]]:
    examples: List[Dict[str, str]] = []
    for index in indexes:
        message = message_by_index.get(index)
        if message is None:
            continue
        examples.append(message.to_example_dict())
        if len(examples) >= max_examples:
            break
    return examples


def count_regex_matches(messages: Sequence[BotMessage], pattern: str, flags: int) -> List[int]:
    compiled_pattern = compile_regex(pattern, flags)
    return [message.index for message in messages if compiled_pattern.search(message.text)]


def _add_regex_spec(
    specs: List[RegexSpec],
    seen: Set[Tuple[str, int]],
    *,
    name: str,
    description: str,
    pattern: str,
    source: str,
    flags: int,
    extraction_indexes: Sequence[int],
) -> None:
    key = (pattern, flags)
    if key in seen:
        return
    seen.add(key)
    specs.append(
        RegexSpec(
            name=name,
            description=description,
            pattern=pattern,
            source=source,
            flags=flags,
            extraction_count=len(extraction_indexes),
            extraction_example_indexes=list(extraction_indexes[:5]),
        )
    )


def is_useful_generated_fragment(fragment: str) -> bool:
    if len(fragment) < 6:
        return False
    if not re.search(r"[\u4e00-\u9fff]", fragment):
        return False
    if re.fullmatch(r"[\W_]+", fragment):
        return False
    return True


def build_regex_specs_from_extraction(
    messages: Sequence[BotMessage],
    min_support: int,
    regex_limit: int,
    max_exact_chars: int,
) -> List[RegexSpec]:
    specs: List[RegexSpec] = []
    seen: Set[Tuple[str, int]] = set()

    for template in REGEX_TEMPLATES:
        indexes = count_regex_matches(messages, template.pattern, template.flags)
        if len(indexes) < min_support:
            continue
        _add_regex_spec(
            specs,
            seen,
            name=template.name,
            description=template.description,
            pattern=template.pattern,
            source="template_catalog",
            flags=template.flags,
            extraction_indexes=indexes,
        )

    exact_counter: Counter[str] = Counter()
    prefix_counter: Counter[str] = Counter()
    suffix_counter: Counter[str] = Counter()
    exact_examples: DefaultDict[str, List[int]] = defaultdict(list)
    prefix_examples: DefaultDict[str, List[int]] = defaultdict(list)
    suffix_examples: DefaultDict[str, List[int]] = defaultdict(list)

    for message in messages:
        text = message.text.strip()
        if not text:
            continue
        compact_text = re.sub(r"\s+", "", text)
        if 2 <= len(compact_text) <= max_exact_chars:
            exact_counter[text] += 1
            if len(exact_examples[text]) < 5:
                exact_examples[text].append(message.index)

        for length in (6, 8, 10, 12):
            if len(compact_text) < length:
                continue
            prefix = compact_text[:length]
            suffix = compact_text[-length:]
            if is_useful_generated_fragment(prefix):
                prefix_counter[prefix] += 1
                if len(prefix_examples[prefix]) < 5:
                    prefix_examples[prefix].append(message.index)
            if is_useful_generated_fragment(suffix):
                suffix_counter[suffix] += 1
                if len(suffix_examples[suffix]) < 5:
                    suffix_examples[suffix].append(message.index)

    for text, count in exact_counter.most_common(regex_limit):
        if count < min_support:
            continue
        _add_regex_spec(
            specs,
            seen,
            name=f"exact_{len(specs) + 1}",
            description=f"完全重复发言: {text}",
            pattern=f"^{re.escape(text)}$",
            source="exact_repeat",
            flags=0,
            extraction_indexes=exact_examples[text],
        )

    for prefix, count in prefix_counter.most_common(regex_limit):
        if count < min_support:
            continue
        _add_regex_spec(
            specs,
            seen,
            name=f"prefix_{len(specs) + 1}",
            description=f"固定开头: {prefix}",
            pattern=f"^{re.escape(prefix)}",
            source="frequent_prefix",
            flags=0,
            extraction_indexes=prefix_examples[prefix],
        )

    for suffix, count in suffix_counter.most_common(regex_limit):
        if count < min_support:
            continue
        _add_regex_spec(
            specs,
            seen,
            name=f"suffix_{len(specs) + 1}",
            description=f"固定结尾: {suffix}",
            pattern=f"{re.escape(suffix)}$",
            source="frequent_suffix",
            flags=0,
            extraction_indexes=suffix_examples[suffix],
        )

    specs.sort(key=lambda spec: (-spec.extraction_count, spec.source, spec.name))
    return specs[:regex_limit]


def evaluate_regex_specs(
    messages: Sequence[BotMessage],
    regex_specs: Sequence[RegexSpec],
    max_examples: int,
) -> Dict[str, Any]:
    message_by_index = {message.index: message for message in messages}
    regex_hits_by_index: DefaultDict[int, List[str]] = defaultdict(list)
    per_regex: List[Dict[str, Any]] = []

    for spec in regex_specs:
        indexes = count_regex_matches(messages, spec.pattern, spec.flags)
        for index in indexes:
            regex_hits_by_index[index].append(spec.name)
        per_regex.append(
            {
                **spec.to_dict(extraction_total=0),
                "experiment_count": len(indexes),
                "experiment_ratio": safe_ratio(len(indexes), len(messages)),
                "experiment_examples": message_examples(message_by_index, indexes, max_examples),
            }
        )

    covered_indexes = set(regex_hits_by_index)
    uncovered_indexes = [message.index for message in messages if message.index not in covered_indexes]
    return {
        "message_count": len(messages),
        "covered_count": len(covered_indexes),
        "covered_ratio": safe_ratio(len(covered_indexes), len(messages)),
        "uncovered_count": len(uncovered_indexes),
        "per_regex": per_regex,
        "uncovered_examples": message_examples(message_by_index, uncovered_indexes, max_examples),
        "regex_hits_by_index": {str(index): names for index, names in regex_hits_by_index.items()},
    }


def split_messages(
    messages: Sequence[BotMessage],
    extraction_ratio: float,
    split_mode: str,
    seed: int,
) -> Tuple[List[BotMessage], List[BotMessage]]:
    if len(messages) < 2:
        raise ValueError("至少需要 2 条消息才能拆分提取集和实验集")

    normalized_ratio = min(max(extraction_ratio, 0.05), 0.95)
    ordered_messages = list(messages)
    if split_mode == "random":
        rng = random.Random(seed)
        rng.shuffle(ordered_messages)

    split_at = int(len(ordered_messages) * normalized_ratio)
    split_at = min(max(split_at, 1), len(ordered_messages) - 1)
    return ordered_messages[:split_at], ordered_messages[split_at:]


def select_top_session_messages(
    messages: Sequence[BotMessage],
    top_sessions: int,
    session_id: Optional[str],
    group_id: Optional[str],
) -> Tuple[List[BotMessage], Dict[str, Any]]:
    if top_sessions <= 0 or session_id or group_id:
        return list(messages), {
            "applied": False,
            "top_session_limit": max(0, top_sessions),
            "source_message_count": len(messages),
            "selected_message_count": len(messages),
            "selected_sessions": [],
        }

    selected_sessions = build_session_stats(messages, limit=top_sessions)
    selected_session_ids = {str(session["session_id"]) for session in selected_sessions}
    selected_messages = [message for message in messages if message.session_id in selected_session_ids]
    return selected_messages, {
        "applied": True,
        "top_session_limit": top_sessions,
        "source_message_count": len(messages),
        "selected_message_count": len(selected_messages),
        "selected_sessions": selected_sessions,
    }


def collect_llm_support_indexes(payload: Dict[str, Any]) -> Set[int]:
    indexes: Set[int] = set()
    for section_name in ("fixed_patterns", "patterns"):
        patterns = payload.get(section_name)
        if not isinstance(patterns, list):
            continue
        for pattern in patterns:
            if not isinstance(pattern, dict):
                continue
            raw_indexes = pattern.get("supporting_message_indexes") or pattern.get("message_indexes") or []
            if not isinstance(raw_indexes, list):
                continue
            for raw_index in raw_indexes:
                try:
                    indexes.add(int(raw_index))
                except (TypeError, ValueError):
                    continue
    return indexes


def build_llm_regex_overlap(final_payload: Dict[str, Any], regex_evaluation: Dict[str, Any]) -> Dict[str, Any]:
    regex_hits_by_index = {
        int(index): names for index, names in regex_evaluation.get("regex_hits_by_index", {}).items()
    }
    llm_support_indexes = collect_llm_support_indexes(final_payload)
    regex_covered_llm_indexes = {index for index in llm_support_indexes if index in regex_hits_by_index}
    pattern_rows: List[Dict[str, Any]] = []

    fixed_patterns = final_payload.get("fixed_patterns")
    if isinstance(fixed_patterns, list):
        for pattern in fixed_patterns:
            if not isinstance(pattern, dict):
                continue
            raw_indexes = pattern.get("supporting_message_indexes") or []
            support_indexes: List[int] = []
            if isinstance(raw_indexes, list):
                for raw_index in raw_indexes:
                    try:
                        support_indexes.append(int(raw_index))
                    except (TypeError, ValueError):
                        continue
            matched_indexes = [index for index in support_indexes if index in regex_hits_by_index]
            matched_regex_names = sorted(
                {name for index in matched_indexes for name in regex_hits_by_index.get(index, [])}
            )
            pattern_rows.append(
                {
                    "pattern_id": pattern.get("pattern_id") or pattern.get("pattern_name") or "",
                    "pattern_name": pattern.get("pattern_name") or "",
                    "template": pattern.get("template") or "",
                    "support_count": len(support_indexes),
                    "regex_matched_support_count": len(matched_indexes),
                    "regex_matched_support_ratio": safe_ratio(len(matched_indexes), len(support_indexes)),
                    "matched_regex_names": matched_regex_names,
                }
            )

    return {
        "llm_support_count": len(llm_support_indexes),
        "regex_covered_llm_support_count": len(regex_covered_llm_indexes),
        "regex_covered_llm_support_ratio": safe_ratio(len(regex_covered_llm_indexes), len(llm_support_indexes)),
        "per_llm_pattern": pattern_rows,
    }


def build_experiment_llm_final_prompt(
    chunk_payloads: Sequence[Dict[str, Any]],
    candidates: Dict[str, Any],
    stats: Dict[str, Any],
    min_support: int,
) -> str:
    return build_final_prompt(
        chunk_payloads=chunk_payloads,
        candidates=candidates,
        stats=stats,
        min_support=min_support,
    )


async def run_llm_experiment(
    args: Namespace, experiment_messages: Sequence[BotMessage]
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    chunks = split_chunks(
        messages=experiment_messages,
        chunk_char_limit=max(1000, args.chunk_char_limit),
        max_text_chars=max(50, args.max_text_chars),
    )
    candidates = build_statistical_candidates(
        messages=experiment_messages,
        min_support=max(2, args.min_support),
        limit=max(1, args.candidate_limit),
    )
    stats = build_corpus_stats(experiment_messages)
    progress(f"实验集共 {len(experiment_messages)} 条，分为 {len(chunks)} 个 LLM 分块。")

    from analyze_bot_phrase_patterns import build_chunk_prompt  # noqa: PLC0415

    chunk_results = []
    for chunk in chunks:
        progress(f"正在分析实验集分块 {chunk.chunk_id}/{len(chunks)}，消息数 {len(chunk.messages)}。")
        prompt = build_chunk_prompt(
            chunk=chunk,
            min_support=max(2, args.min_support),
            max_text_chars=max(50, args.max_text_chars),
        )
        chunk_results.append(
            await request_llm_json(
                prompt=prompt,
                task_name=args.task_name,
                temperature=args.temperature,
                max_tokens=args.chunk_max_tokens,
            )
        )

    final_prompt = build_experiment_llm_final_prompt(
        chunk_payloads=[result.payload for result in chunk_results],
        candidates=candidates,
        stats=stats,
        min_support=max(2, args.min_support),
    )
    progress("正在整合实验集 LLM 常见发言结果。")
    final_result = await request_llm_json(
        prompt=final_prompt,
        task_name=args.task_name,
        temperature=args.temperature,
        max_tokens=args.final_max_tokens,
    )

    message_by_index = {message.index: message for message in experiment_messages}
    final_payload = enrich_pattern_examples(
        payload=final_result.payload,
        message_by_index=message_by_index,
        max_examples=max(1, args.max_examples),
    )
    usage = {
        "chunk_models": [result.model_name for result in chunk_results],
        "final_model": final_result.model_name,
        "prompt_tokens": sum(result.prompt_tokens for result in chunk_results) + final_result.prompt_tokens,
        "completion_tokens": sum(result.completion_tokens for result in chunk_results) + final_result.completion_tokens,
        "total_tokens": sum(result.total_tokens for result in chunk_results) + final_result.total_tokens,
    }
    return final_payload, usage


async def evaluate(args: Namespace) -> Dict[str, Any]:
    now = datetime.now()
    has_explicit_since = bool(args.since and args.since.strip())
    since = parse_datetime_filter(args.since) or parse_recent_filter(args.recent, now)
    until = parse_datetime_filter(args.until) or now
    if since is None:
        raise ValueError("必须指定 --since 或 --recent")
    if until <= since:
        raise ValueError("--until 必须晚于起始时间")

    config_accounts, qq_fallback_account = load_bot_accounts_from_config(args.bot_config.resolve())
    cli_accounts = [
        parsed_account
        for raw_account in args.bot_account
        if (parsed_account := parse_platform_account(raw_account, source="cli"))
    ]
    accounts = deduplicate_accounts([*cli_accounts, *config_accounts])
    if not accounts and not qq_fallback_account:
        raise ValueError("未能识别 bot 账号，请检查 bot_config.toml 或通过 --bot-account platform:user_id 指定")

    source_messages = fetch_bot_messages(
        db_path=args.db.resolve(),
        since=since,
        until=until,
        accounts=accounts,
        qq_fallback_account=qq_fallback_account,
        legacy_qq_fallback=not args.no_legacy_qq_fallback,
        session_id=args.session_id,
        platform=args.platform,
        group_id=args.group_id,
        keep_media_placeholders=args.keep_media_placeholders,
        min_text_length=max(1, args.min_text_length),
        limit=max(0, args.limit),
    )
    selected_messages, session_selection = select_top_session_messages(
        messages=source_messages,
        top_sessions=max(0, args.top_sessions),
        session_id=args.session_id,
        group_id=args.group_id,
    )
    extraction_messages, experiment_messages = split_messages(
        messages=selected_messages,
        extraction_ratio=args.extraction_ratio,
        split_mode=args.split_mode,
        seed=args.seed,
    )
    regex_specs = build_regex_specs_from_extraction(
        messages=extraction_messages,
        min_support=max(2, args.min_support),
        regex_limit=max(1, args.regex_limit),
        max_exact_chars=max(2, args.max_exact_chars),
    )
    regex_evaluation = evaluate_regex_specs(
        messages=experiment_messages,
        regex_specs=regex_specs,
        max_examples=max(1, args.max_examples),
    )
    regex_evaluation["per_regex"] = [
        {
            **row,
            "extraction_ratio": safe_ratio(int(row["extraction_count"]), len(extraction_messages)),
        }
        for row in regex_evaluation["per_regex"]
    ]

    metadata: Dict[str, Any] = {
        "generated_at": now.isoformat(timespec="seconds"),
        "analysis_range": {
            "since": format_datetime_for_sql(since),
            "until": format_datetime_for_sql(until),
            "recent": None if has_explicit_since else args.recent,
        },
        "filters": {
            "session_id": args.session_id,
            "platform": args.platform,
            "group_id": args.group_id,
            "min_text_length": max(1, args.min_text_length),
            "limit": max(0, args.limit),
            "top_sessions": max(0, args.top_sessions),
        },
        "split": {
            "mode": args.split_mode,
            "seed": args.seed if args.split_mode == "random" else None,
            "extraction_ratio": min(max(args.extraction_ratio, 0.05), 0.95),
            "source_message_count": len(source_messages),
            "selected_message_count": len(selected_messages),
            "extraction_message_count": len(extraction_messages),
            "experiment_message_count": len(experiment_messages),
        },
        "bot_accounts": [account.display() for account in accounts],
        "legacy_qq_fallback_account": qq_fallback_account if not args.no_legacy_qq_fallback else "",
        "session_selection": session_selection,
        "source_corpus_stats": build_corpus_stats(source_messages),
        "selected_corpus_stats": build_corpus_stats(selected_messages),
        "extraction_corpus_stats": build_corpus_stats(extraction_messages),
        "experiment_corpus_stats": build_corpus_stats(experiment_messages),
        "regex_specs": [spec.to_dict(extraction_total=len(extraction_messages)) for spec in regex_specs],
        "regex_evaluation": regex_evaluation,
        "regex_matching_mode": REGEX_MATCHING_MODE,
    }

    if args.skip_llm:
        return {
            **metadata,
            "llm_skipped": True,
            "llm_experiment": {},
            "llm_regex_overlap": {},
        }

    metadata["model_thinking_states"] = validate_task_uses_non_thinking_models(
        model_config_path=args.model_config.resolve(),
        task_name=args.task_name,
        allow_thinking=args.allow_thinking,
    )
    llm_payload, llm_usage = await run_llm_experiment(args, experiment_messages)
    return {
        **metadata,
        "llm_skipped": False,
        "llm_experiment": llm_payload,
        "llm_regex_overlap": build_llm_regex_overlap(llm_payload, regex_evaluation),
        "llm_usage": llm_usage,
    }


def parse_args() -> Namespace:
    parser = ArgumentParser(description="拆分 bot 发言语料，用提取集正则评估实验集固定发言捕捉能力。")
    parser.add_argument("--db", type=Path, default=DEFAULT_DB_PATH, help=f"数据库路径，默认: {DEFAULT_DB_PATH}")
    parser.add_argument("--bot-config", type=Path, default=DEFAULT_BOT_CONFIG_PATH, help="bot_config.toml 路径")
    parser.add_argument("--model-config", type=Path, default=DEFAULT_MODEL_CONFIG_PATH, help="model_config.toml 路径")
    parser.add_argument("--recent", default="30d", help="分析最近多久，例如 30m、24h、30d、2w；--since 优先")
    parser.add_argument("--since", help="起始时间，格式: YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS")
    parser.add_argument("--until", help="结束时间，格式: YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS；默认当前时间")
    parser.add_argument("--session-id", help="只分析指定 session_id")
    parser.add_argument("--platform", help="只分析指定平台")
    parser.add_argument("--group-id", help="只分析指定群号/群聊 ID")
    parser.add_argument(
        "--bot-account",
        action="append",
        default=[],
        help="手动补充 bot 账号，格式 platform:user_id；也可只填 user_id 表示任意平台，可重复传入",
    )
    parser.add_argument("--no-legacy-qq-fallback", action="store_true", help="关闭旧数据的 QQ 账号全平台兜底匹配")
    parser.add_argument("--keep-media-placeholders", action="store_true", help="保留 [图片]、[表情包] 等媒体占位文本")
    parser.add_argument("--min-text-length", type=int, default=1, help="忽略短于该长度的发言")
    parser.add_argument("--limit", type=int, default=0, help="最多读取多少条 bot 发言，0 表示不限制")
    parser.add_argument(
        "--top-sessions",
        type=int,
        default=10,
        help="默认只分析消息数最多的 N 个 session_id；0 表示不限制。指定 --session-id/--group-id 时不截取",
    )
    parser.add_argument(
        "--split-mode", choices=("chronological", "random"), default="chronological", help="语料拆分方式"
    )
    parser.add_argument("--extraction-ratio", type=float, default=0.5, help="提取集比例，默认前 50%% 作为提取集")
    parser.add_argument("--seed", type=int, default=42, help="随机拆分种子，仅 --split-mode random 生效")
    parser.add_argument("--min-support", type=int, default=3, help="正则/LLM 句式至少需要多少条消息支持")
    parser.add_argument("--regex-limit", type=int, default=40, help="从提取集保留多少条正则")
    parser.add_argument("--max-exact-chars", type=int, default=40, help="完全重复发言生成正则时允许的最大长度")
    parser.add_argument("--candidate-limit", type=int, default=20, help="程序统计候选每类最多保留多少条")
    parser.add_argument("--max-examples", type=int, default=5, help="每项最多回填多少条原始例句")
    parser.add_argument("--skip-llm", action="store_true", help="只做拆分和正则评估，不调用 LLM")
    parser.add_argument("--task-name", default="learner", help="使用 model_task_config 下的哪个任务配置调用 LLM")
    parser.add_argument("--allow-thinking", action="store_true", help="允许使用 thinking enabled 的模型，默认会拒绝")
    parser.add_argument("--temperature", type=float, default=0.2, help="LLM 温度")
    parser.add_argument("--chunk-char-limit", type=int, default=16000, help="单个 LLM 分块的字符预算")
    parser.add_argument("--max-text-chars", type=int, default=800, help="单条消息进入 prompt 时的最大字符数")
    parser.add_argument("--chunk-max-tokens", type=int, default=2048, help="分块分析的最大输出 token")
    parser.add_argument("--final-max-tokens", type=int, default=8192, help="最终整合的最大输出 token")
    parser.add_argument(
        "--output", type=Path, default=None, help="输出 JSON 路径；不指定时写入 data/analysis，填 - 输出到 stdout"
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        payload = asyncio.run(evaluate(args))
    except Exception as exc:
        print(f"评估失败: {exc}", file=sys.stderr)
        return 1

    if args.output is not None and str(args.output) == "-":
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0

    output_path = args.output if args.output is not None else build_output_path()
    write_payload(payload, output_path.resolve())
    print(f"评估结果已保存到: {output_path.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
