from __future__ import annotations

from argparse import ArgumentParser, Namespace
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import DefaultDict

import csv
import json
import re
import sqlite3
import sys


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DB_PATH = PROJECT_ROOT / "data" / "MaiBot.db"


@dataclass(frozen=True)
class ToolUsageRow:
    chat_id: str
    tool_name: str
    count: int
    chat_total: int
    percent_in_chat: float
    percent_in_all: float


def parse_datetime_filter(value: str | None) -> str | None:
    if value is None:
        return None

    normalized_value = value.strip()
    if not normalized_value:
        return None

    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d"):
        try:
            parsed = datetime.strptime(normalized_value, fmt)
        except ValueError:
            continue
        return parsed.strftime("%Y-%m-%d %H:%M:%S")

    raise ValueError(f"无法解析时间: {value!r}，请使用 YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS")


def parse_recent_filter(value: str | None) -> str | None:
    if value is None:
        return None

    normalized_value = value.strip().lower()
    if not normalized_value:
        return None

    match = re.fullmatch(r"(\d+(?:\.\d+)?)([mhdw])", normalized_value)
    if match is None:
        raise ValueError(f"无法解析最近时间: {value!r}，请使用 30m、24h、7d 或 2w")

    amount = float(match.group(1))
    unit = match.group(2)
    if amount <= 0:
        raise ValueError(f"最近时间必须大于 0: {value!r}")

    if unit == "m":
        delta = timedelta(minutes=amount)
    elif unit == "h":
        delta = timedelta(hours=amount)
    elif unit == "d":
        delta = timedelta(days=amount)
    else:
        delta = timedelta(weeks=amount)

    return (datetime.now() - delta).strftime("%Y-%m-%d %H:%M:%S")


def connect_readonly(db_path: Path) -> sqlite3.Connection:
    if not db_path.exists():
        raise FileNotFoundError(f"数据库文件不存在: {db_path}")

    database_uri = f"file:{db_path.as_posix()}?mode=ro"
    connection = sqlite3.connect(database_uri, uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA busy_timeout=5000")
    return connection


def fetch_tool_counts(
    db_path: Path,
    since: str | None,
    until: str | None,
    include_empty_chat_id: bool,
    include_empty_tool_name: bool,
) -> list[tuple[str, str, int]]:
    where_clauses: list[str] = []
    params: list[str] = []

    if since is not None:
        where_clauses.append("timestamp >= ?")
        params.append(since)
    if until is not None:
        where_clauses.append("timestamp < ?")
        params.append(until)
    if not include_empty_chat_id:
        where_clauses.append("COALESCE(session_id, '') != ''")
    if not include_empty_tool_name:
        where_clauses.append("COALESCE(tool_name, '') != ''")

    where_sql = f"WHERE {' AND '.join(where_clauses)}" if where_clauses else ""
    query = f"""
        SELECT
            COALESCE(session_id, '') AS chat_id,
            COALESCE(tool_name, '') AS tool_name,
            COUNT(*) AS usage_count
        FROM tool_records
        {where_sql}
        GROUP BY COALESCE(session_id, ''), COALESCE(tool_name, '')
        ORDER BY COALESCE(session_id, ''), usage_count DESC, COALESCE(tool_name, '')
    """

    with connect_readonly(db_path) as connection:
        rows = connection.execute(query, params).fetchall()

    return [(str(row["chat_id"]), str(row["tool_name"]), int(row["usage_count"])) for row in rows]


def build_usage_rows(
    counts: list[tuple[str, str, int]],
    min_chat_total: int,
    top_tools_per_chat: int | None,
) -> list[ToolUsageRow]:
    chat_totals: DefaultDict[str, int] = defaultdict(int)
    for chat_id, _tool_name, count in counts:
        chat_totals[chat_id] += count

    all_total = sum(chat_totals.values())
    rows: list[ToolUsageRow] = []
    emitted_per_chat: DefaultDict[str, int] = defaultdict(int)

    sorted_counts = sorted(counts, key=lambda item: (item[0], -item[2], item[1]))
    for chat_id, tool_name, count in sorted_counts:
        chat_total = chat_totals[chat_id]
        if chat_total < min_chat_total:
            continue
        if top_tools_per_chat is not None and emitted_per_chat[chat_id] >= top_tools_per_chat:
            continue

        emitted_per_chat[chat_id] += 1
        rows.append(
            ToolUsageRow(
                chat_id=chat_id,
                tool_name=tool_name,
                count=count,
                chat_total=chat_total,
                percent_in_chat=count / chat_total * 100 if chat_total else 0.0,
                percent_in_all=count / all_total * 100 if all_total else 0.0,
            )
        )

    return rows


def build_overall_rows(counts: list[tuple[str, str, int]], min_chat_total: int) -> list[tuple[str, int, float]]:
    chat_totals: DefaultDict[str, int] = defaultdict(int)
    for chat_id, _tool_name, count in counts:
        chat_totals[chat_id] += count

    tool_counts: DefaultDict[str, int] = defaultdict(int)
    for chat_id, tool_name, count in counts:
        if chat_totals[chat_id] < min_chat_total:
            continue
        tool_counts[tool_name] += count

    total = sum(tool_counts.values())
    sorted_items = sorted(tool_counts.items(), key=lambda item: (-item[1], item[0]))
    return [(tool_name, count, count / total * 100 if total else 0.0) for tool_name, count in sorted_items]


def print_overall_block(overall_rows: list[tuple[str, int, float]]) -> None:
    print("全部统计")
    total = sum(count for _tool_name, count, _percent in overall_rows)
    print(f"tool_total: {total}")
    if not overall_rows:
        print("  无工具调用记录")
        return

    tool_width = max(len("tool"), *(len(tool_name) for tool_name, _count, _percent in overall_rows))
    count_width = max(len("count"), *(len(str(count)) for _tool_name, count, _percent in overall_rows))
    percent_width = max(len("全局占比"), *(len(f"{percent:.2f}%") for _tool_name, _count, percent in overall_rows))

    print(f"  {'tool':<{tool_width}}  {'count':>{count_width}}  {'全局占比':>{percent_width}}")
    print(f"  {'-' * tool_width}  {'-' * count_width}  {'-' * percent_width}")
    for tool_name, count, percent in overall_rows:
        print(f"  {tool_name:<{tool_width}}  {count:>{count_width}}  {percent:>{percent_width - 1}.2f}%")


def print_markdown(rows: list[ToolUsageRow], overall_rows: list[tuple[str, int, float]]) -> None:
    print_overall_block(overall_rows)
    if rows:
        print()

    grouped_rows: DefaultDict[str, list[ToolUsageRow]] = defaultdict(list)
    for row in rows:
        grouped_rows[row.chat_id].append(row)

    first_group = True
    for chat_id in sorted(grouped_rows):
        chat_rows = grouped_rows[chat_id]
        if not chat_rows:
            continue

        if not first_group:
            print()
        first_group = False

        chat_total = chat_rows[0].chat_total
        print(f"chat_id: {chat_id}")
        print(f"tool_total: {chat_total}")

        tool_width = max(len("tool"), *(len(row.tool_name) for row in chat_rows))
        count_width = max(len("count"), *(len(str(row.count)) for row in chat_rows))
        chat_percent_width = max(len("chat内占比"), *(len(f"{row.percent_in_chat:.2f}%") for row in chat_rows))
        all_percent_width = max(len("全局占比"), *(len(f"{row.percent_in_all:.2f}%") for row in chat_rows))

        header = (
            f"  {'tool':<{tool_width}}  "
            f"{'count':>{count_width}}  "
            f"{'chat内占比':>{chat_percent_width}}  "
            f"{'全局占比':>{all_percent_width}}"
        )
        print(header)
        print(
            f"  {'-' * tool_width}  "
            f"{'-' * count_width}  "
            f"{'-' * chat_percent_width}  "
            f"{'-' * all_percent_width}"
        )
        for row in chat_rows:
            print(
                f"  {row.tool_name:<{tool_width}}  "
                f"{row.count:>{count_width}}  "
                f"{row.percent_in_chat:>{chat_percent_width - 1}.2f}%  "
                f"{row.percent_in_all:>{all_percent_width - 1}.2f}%"
            )


def print_json(rows: list[ToolUsageRow]) -> None:
    payload = [
        {
            "chat_id": row.chat_id,
            "tool_name": row.tool_name,
            "count": row.count,
            "chat_total": row.chat_total,
            "percent_in_chat": round(row.percent_in_chat, 4),
            "percent_in_all": round(row.percent_in_all, 4),
        }
        for row in rows
    ]
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def print_csv(rows: list[ToolUsageRow]) -> None:
    writer = csv.writer(sys.stdout)
    writer.writerow(["chat_id", "tool_name", "count", "chat_total", "percent_in_chat", "percent_in_all"])
    for row in rows:
        writer.writerow(
            [
                row.chat_id,
                row.tool_name,
                row.count,
                row.chat_total,
                f"{row.percent_in_chat:.4f}",
                f"{row.percent_in_all:.4f}",
            ]
        )


def parse_args() -> Namespace:
    parser = ArgumentParser(description="统计不同 chat_id 的工具使用次数和占比。")
    parser.add_argument("--db", type=Path, default=DEFAULT_DB_PATH, help=f"数据库路径，默认: {DEFAULT_DB_PATH}")
    parser.add_argument("--recent", help="统计最近多久的记录，例如: 30m、24h、7d、2w；如果同时指定 --since，则优先使用 --since")
    parser.add_argument("--since", help="仅统计此时间之后的记录，格式: YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS")
    parser.add_argument("--until", help="仅统计此时间之前的记录，格式: YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS")
    parser.add_argument("--min-chat-total", type=int, default=1, help="只显示工具调用总数不低于该值的 chat_id")
    parser.add_argument("--top-tools", type=int, help="每个 chat_id 最多显示前 N 个工具")
    parser.add_argument("--format", choices=("markdown", "json", "csv"), default="markdown", help="输出格式，markdown 为按 chat_id 分块的终端表")
    parser.add_argument("--include-empty-chat-id", action="store_true", help="包含 chat_id 为空的记录")
    parser.add_argument("--include-empty-tool-name", action="store_true", help="包含 tool_name 为空的记录")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    since = parse_datetime_filter(args.since) or parse_recent_filter(args.recent)
    until = parse_datetime_filter(args.until)
    min_chat_total = max(1, int(args.min_chat_total))
    top_tools = args.top_tools if args.top_tools is None else max(1, int(args.top_tools))

    counts = fetch_tool_counts(
        db_path=args.db.resolve(),
        since=since,
        until=until,
        include_empty_chat_id=args.include_empty_chat_id,
        include_empty_tool_name=args.include_empty_tool_name,
    )
    rows = build_usage_rows(
        counts=counts,
        min_chat_total=min_chat_total,
        top_tools_per_chat=top_tools,
    )

    if args.format == "json":
        print_json(rows)
    elif args.format == "csv":
        print_csv(rows)
    else:
        overall_rows = build_overall_rows(counts, min_chat_total=min_chat_total)
        print_markdown(rows, overall_rows)


if __name__ == "__main__":
    main()
