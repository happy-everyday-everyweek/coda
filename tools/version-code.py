#!/usr/bin/env python3
"""版本号计算：把「构建时刻 + 渠道」压成一个不超过 Android 上限的 versionCode。

## 要解决的两个问题

1. **上限**。Android 的 versionCode 是 32 位有符号整数，实际可用上限 2100000000。
   直接拼 `202601011200` + 渠道位（13 位）会溢出，装不上。

2. **两个渠道必须能互相覆盖**。用户的真实流程是：正式版 → dev → 下一个正式版。
   所以两个渠道不能各用一套独立编号（那样 dev 的号永远大于正式版，回不去）。
   它们必须落在**同一条按时间单调递增的序列**上。

## 方案

    versionCode = 距基准日的秒数 * 2 + 渠道位

  - `距基准日的秒数` 单调递增，与真实时间一一对应：
    后发生的构建一定有更大的号，无论渠道。
  - `* 2 + 渠道位` 让渠道位成为最低位，两个渠道相差 1：
      dev  = 偶数（…0）
      正式 = 奇数（…1）
    于是同一秒内：正式版 > dev，dev 能升到正式版 ✓
    而下一秒：dev 一定大于上一秒的正式版，dev 能覆盖正式版 ✓
    两个方向都成立，靠的就是「时间在高位、渠道在最低位」。

## 量级

基准日取 2026-01-01。到 2026-10-25 约 298 天 = 25,747,200 秒，
×2 ≈ 5150 万，距离 21 亿还有 40 倍余量，可用到 2040 年以后。

若将来余量不够，只需把基准日往后挪（EPOCH 常量），
两个渠道同时挪，相对顺序不变。

## 用法

  tools/version-code.py --channel dev
  tools/version-code.py --channel release
  tools/version-code.py --channel dev --at 2026-10-25T12:00:00Z

输出单行数字，供 CI 写进 -PcodaVersionCode。
"""

import argparse
import datetime
import math
import sys

# Android 的 versionCode 上限。
MAX_VERSION_CODE = 2_100_000_000

# 基准时刻（UTC）。所有编号都相对它计算。
EPOCH = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)

# 渠道位：dev=0（偶）、正式=1（奇）。见文件头对双方互更的说明。
CHANNEL_BIT = {"dev": 0, "release": 1}


def compute(channel: str, at: datetime.datetime) -> int:
    if channel not in CHANNEL_BIT:
        raise ValueError(f"未知渠道：{channel}（只接受 dev / release）")
    if at.tzinfo is None:
        at = at.replace(tzinfo=datetime.timezone.utc)

    delta = at - EPOCH
    # 秒粒度：分钟粒度下，同一分钟内的两次构建会算出同一个 versionCode，
    # 与文件头「后发生的构建一定有更大的号」自相矛盾。秒粒度把量级放大 60 倍，
    # 到 2029 年仍只有约 1.9 亿，离 21 亿上限还有十倍余量。
    # 先取整再判负会把「基准前不到 1 秒」漏过去（OCR low #827）：
    # int() 向零截断，-0.5 → 0，于是 2025-12-31T23:59:59.5Z 会静默得到 versionCode 0。
    total = delta.total_seconds()
    if total < 0:
        raise ValueError(f"时刻早于基准 {EPOCH.isoformat()}：{at.isoformat()}")
    seconds = math.floor(total)
    if seconds < 0:
        raise ValueError(f"时刻早于基准 {EPOCH.isoformat()}：{at.isoformat()}")

    code = seconds * 2 + CHANNEL_BIT[channel]
    if code > MAX_VERSION_CODE:
        raise ValueError(
            f"versionCode 溢出：{code} > {MAX_VERSION_CODE}。"
            f"当前基准日 {EPOCH.date()} 已不够用，需要把 EPOCH 往后挪。"
        )
    return code


def main() -> int:
    ap = argparse.ArgumentParser(description="计算 Coda 的 versionCode")
    ap.add_argument("--channel", required=True, choices=["dev", "release"])
    ap.add_argument(
        "--at",
        default=None,
        help="ISO8601 时刻；默认当前 UTC 时间",
    )
    args = ap.parse_args()

    try:
        # --at 的解析也必须在 try 里：非法 ISO8601 会抛 ValueError，
        # 放在外面用户只会看到一段 traceback，而不是一句 error。
        at = (
            datetime.datetime.fromisoformat(args.at.replace("Z", "+00:00"))
            if args.at
            else datetime.datetime.now(datetime.timezone.utc)
        )
        print(compute(args.channel, at))
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())