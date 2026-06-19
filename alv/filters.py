"""ログエントリのフィルタリング."""

from __future__ import annotations

import re
from datetime import datetime
from typing import Callable

from .parser import LogEntry


def _match_host(entry: LogEntry, pattern: re.Pattern[str]) -> bool:
    for value in (entry.client_host, entry.host, entry.forwarded_for):
        if value and pattern.search(value):
            return True
    return False


def parse_status_filter(value: str) -> set[int] | None:
    """500 / 4xx / 500,502 などを解釈する."""
    if not value:
        return None
    result: set[int] = set()
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        if part.endswith("xx") and len(part) == 3 and part[0].isdigit():
            base = int(part[0]) * 100
            result.update(range(base, base + 100))
        else:
            result.add(int(part))
    return result or None


def parse_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    for fmt in (
        "%Y-%m-%dT%H:%M:%S%z",
        "%Y-%m-%d %H:%M:%S%z",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%d",
    ):
        try:
            dt = datetime.strptime(value, fmt)
            if dt.tzinfo is None:
                from datetime import timezone

                dt = dt.replace(tzinfo=timezone.utc)
            return dt
        except ValueError:
            continue
    raise ValueError(f"日時形式を解釈できません: {value}")


def match_entry(
    entry: LogEntry,
    *,
    source_name: str = "",
    status: set[int] | None = None,
    path: re.Pattern[str] | None = None,
    method: str | None = None,
    host: re.Pattern[str] | None = None,
    since: datetime | None = None,
    until: datetime | None = None,
    query: re.Pattern[str] | None = None,
    source: re.Pattern[str] | None = None,
    read_raw: Callable[[LogEntry], str] | None = None,
) -> bool:
    # 安価な条件を先に評価する。
    if since is not None and entry.timestamp < since:
        return False
    if until is not None and entry.timestamp > until:
        return False
    if status is not None:
        if entry.status is None or entry.status not in status:
            return False
    if method is not None and entry.method.upper() != method.upper():
        return False
    if source is not None and not source.search(source_name):
        return False
    if path is not None and not path.search(entry.path):
        return False
    if host is not None and not _match_host(entry, host):
        return False
    if query is not None:
        if read_raw is None:
            return False
        if not query.search(read_raw(entry)):
            return False
    return True
