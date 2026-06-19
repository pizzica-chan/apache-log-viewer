"""Apache Common / Combined ログパーサー."""

from __future__ import annotations

import re
from dataclasses import dataclass, asdict
from datetime import datetime
from pathlib import Path
from typing import Iterator

# Common / Combined / VirtualHost / X-Forwarded-For 付き Combined に対応。
# 先頭の可変部分 (VirtualHost, X-Forwarded-For, remote host) は
# ident 直前までを leading として取得し、末尾から remote host を分離する。
_LOG_RE = re.compile(
    r"^(?:\S+:\d+\s+)?"  # 任意: VirtualHost
    r"(?P<leading>.+?)\s+"
    r"(?P<ident>\S+)\s+"
    r"(?P<authuser>\S+)\s+"
    r"\[(?P<timestamp>[^\]]+)\]\s+"
    r'"(?P<request>[^"]*)"\s+'
    r"(?P<status>\d+|-)\s+"
    r"(?P<bytes>\S+)"
    r'(?:\s+"(?P<referer>[^"]*)")?'
    r'(?:\s+"(?P<user_agent>[^"]*)")?'
    r"(?:\s+.*)?$"
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
    source: str
    line_no: int
    raw: str
    host: str
    forwarded_for: str
    ident: str
    authuser: str
    timestamp: datetime
    method: str
    path: str
    protocol: str
    status: int | None
    bytes_sent: int | None
    referer: str
    user_agent: str

    @property
    def client_host(self) -> str:
        """実クライアント IP（X-Forwarded-For 先頭、なければ remote host）."""
        if self.forwarded_for:
            first = self.forwarded_for.split(",")[0].strip()
            if first and first != "-":
                return first
        return self.host

    def to_dict(self) -> dict:
        data = asdict(self)
        data["timestamp"] = self.timestamp.isoformat()
        data["client_host"] = self.client_host
        return data


def parse_timestamp(value: str) -> datetime:
    """Apache 日時 `[10/Oct/2000:13:55:36 -0700]` を解析する."""
    # タイムゾーン省略時
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


def parse_line(raw: str, *, source: str = "", line_no: int = 0) -> LogEntry | None:
    line = raw.rstrip("\n\r")
    match = _LOG_RE.match(line)
    if not match:
        return None

    groups = match.groupdict()
    forwarded_for, host = split_leading_hosts(groups["leading"])
    request = groups["request"]
    status = groups["status"]
    nbytes = groups["bytes"]
    referer = groups["referer"] or "-"
    user_agent = groups["user_agent"] or "-"

    req_match = _REQUEST_RE.match(request)
    if req_match:
        method, path, protocol = req_match.groups()
    else:
        method, path, protocol = request, "-", "-"

    status_val = None if status == "-" else int(status)
    bytes_val = None if nbytes == "-" else int(nbytes)

    return LogEntry(
        source=source,
        line_no=line_no,
        raw=line,
        host=host,
        forwarded_for=forwarded_for,
        ident=groups["ident"],
        authuser=groups["authuser"],
        timestamp=parse_timestamp(groups["timestamp"]),
        method=method,
        path=path,
        protocol=protocol,
        status=status_val,
        bytes_sent=bytes_val,
        referer=referer if referer != "-" else "",
        user_agent=user_agent if user_agent != "-" else "",
    )


def iter_entries(paths: list[Path]) -> Iterator[LogEntry]:
    for path in paths:
        with path.open(encoding="utf-8", errors="replace") as fh:
            for line_no, line in enumerate(fh, start=1):
                if not line.strip():
                    continue
                entry = parse_line(line, source=str(path.resolve()), line_no=line_no)
                if entry:
                    yield entry


def load_entries(paths: list[Path], *, sort: bool = True) -> list[LogEntry]:
    entries = list(iter_entries(paths))
    if sort:
        entries.sort(key=lambda e: (e.timestamp, e.source, e.line_no))
    return entries
