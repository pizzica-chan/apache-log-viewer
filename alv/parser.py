"""Apache Common / Combined ログパーサー."""

from __future__ import annotations

import heapq
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable, Iterator

# bytes フィールドまで解析し、referer / user_agent は読み飛ばす。
_LOG_RE = re.compile(
    r"^(?:\S+:\d+\s+)?"  # 任意: VirtualHost
    r"(?P<leading>.+?)\s+"
    r"(?P<ident>\S+)\s+"
    r"(?P<authuser>\S+)\s+"
    r"\[(?P<timestamp>[^\]]+)\]\s+"
    r'"(?P<request>[^"]*)"\s+'
    r"(?P<status>\d+|-)\s+"
    r"(?P<bytes>\S+)"
)

_REQUEST_RE = re.compile(r"^(\S+)\s+(\S+)\s+(\S+)$")

_MONTH = {
    "Jan": 1,
    "Feb": 2,
    "Mar": 3,
    "Apr": 4,
    "May": 5,
    "Jun": 6,
    "Jul": 7,
    "Aug": 8,
    "Sep": 9,
    "Oct": 10,
    "Nov": 11,
    "Dec": 12,
}


@dataclass(slots=True)
class LogEntry:
    file_id: int
    line_no: int
    byte_offset: int
    host: str
    client_host: str
    forwarded_for: str
    timestamp: datetime
    method: str
    path: str
    status: int | None

    def to_row_dict(self, source_name: str) -> dict:
        """一覧 API 用の軽量 dict."""
        return {
            "timestamp": self.timestamp.isoformat(),
            "status": self.status,
            "method": self.method,
            "path": self.path,
            "client_host": self.client_host,
            "host": self.host,
            "forwarded_for": self.forwarded_for,
            "source": source_name,
            "line_no": self.line_no,
        }


def parse_timestamp(value: str) -> datetime:
    """Apache 日時 `[10/Oct/2000:13:55:36 -0700]` を解析する."""
    if value[-5] not in "+-":
        value = value + " +0000"
    day, month, rest = value.split("/", 2)
    year_time, tz = rest.rsplit(" ", 1)
    year, time_part = year_time.split(":", 1)
    hour, minute, second = map(int, time_part.split(":"))
    dt = datetime(
        int(year),
        _MONTH[month],
        int(day),
        hour,
        minute,
        second,
    )
    sign = 1 if tz[0] == "+" else -1
    offset_hours = int(tz[1:3])
    offset_minutes = int(tz[3:5])
    from datetime import timezone, timedelta

    offset = sign * timedelta(hours=offset_hours, minutes=offset_minutes)
    return dt.replace(tzinfo=timezone(offset))


def split_leading_hosts(leading: str) -> tuple[str, str]:
    """leading 部分から X-Forwarded-For と remote host (%h) を分離する."""
    leading = leading.strip()
    if not leading:
        return "", ""

    parts = leading.rsplit(None, 1)
    if len(parts) == 1:
        return "", parts[0]

    forwarded_for, host = parts[0], parts[1]
    if forwarded_for == "-":
        forwarded_for = ""
    return forwarded_for, host


def _client_host(forwarded_for: str, host: str) -> str:
    if forwarded_for:
        first = forwarded_for.split(",", 1)[0].strip()
        if first and first != "-":
            return first
    return host


def parse_line(
    raw: str, *, file_id: int = 0, line_no: int = 0, byte_offset: int = 0
) -> LogEntry | None:
    line = raw.rstrip("\n\r")
    match = _LOG_RE.match(line)
    if not match:
        return None

    groups = match.groupdict()
    forwarded_for, host = split_leading_hosts(groups["leading"])
    request = groups["request"]
    status = groups["status"]

    req_match = _REQUEST_RE.match(request)
    if req_match:
        method, path, _protocol = req_match.groups()
    else:
        method, path = request, "-"

    status_val = None if status == "-" else int(status)

    return LogEntry(
        file_id=file_id,
        line_no=line_no,
        byte_offset=byte_offset,
        host=host,
        client_host=_client_host(forwarded_for, host),
        forwarded_for=forwarded_for,
        timestamp=parse_timestamp(groups["timestamp"]),
        method=method,
        path=path,
        status=status_val,
    )


def _iter_file_entries(file_id: int, path: Path) -> Iterator[LogEntry]:
    with path.open("rb") as fh:
        line_no = 0
        while True:
            offset = fh.tell()
            line_bytes = fh.readline()
            if not line_bytes:
                break
            line_no += 1
            if not line_bytes.strip():
                continue
            line = line_bytes.decode("utf-8", errors="replace")
            entry = parse_line(
                line, file_id=file_id, line_no=line_no, byte_offset=offset
            )
            if entry:
                yield entry


def iter_entries(paths: list[Path]) -> Iterator[LogEntry]:
    for file_id, path in enumerate(paths):
        yield from _iter_file_entries(file_id, path)


def load_entries(
    paths: list[Path],
    *,
    sort: bool = True,
    progress_callback: Callable[[int], None] | None = None,
) -> list[LogEntry]:
    if not sort:
        entries = list(iter_entries(paths))
        if progress_callback:
            progress_callback(len(entries))
        return entries

    heap: list[tuple[datetime, int, int, LogEntry, Iterator[LogEntry]]] = []
    for file_id, path in enumerate(paths):
        it = _iter_file_entries(file_id, path)
        try:
            entry = next(it)
        except StopIteration:
            continue
        heapq.heappush(heap, (entry.timestamp, entry.file_id, entry.line_no, entry, it))

    result: list[LogEntry] = []
    while heap:
        _, _, _, entry, it = heapq.heappop(heap)
        result.append(entry)
        if progress_callback and len(result) % 50000 == 0:
            progress_callback(len(result))
        try:
            next_entry = next(it)
            heapq.heappush(
                heap,
                (
                    next_entry.timestamp,
                    next_entry.file_id,
                    next_entry.line_no,
                    next_entry,
                    it,
                ),
            )
        except StopIteration:
            pass

    if progress_callback:
        progress_callback(len(result))
    return result
