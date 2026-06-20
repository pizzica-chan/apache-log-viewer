from datetime import datetime, timezone, timedelta

from alv.line_reader import LineReader
from alv.parser import (
    load_entries,
    parse_line,
    parse_timestamp,
    split_leading_hosts,
)

COMMON_LINE = (
    '127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] '
    '"GET /index.html HTTP/1.1" 200 4523 "-" "Mozilla/5.0 (very long user agent)"'
)
XFF_LINE = (
    '203.0.113.50 10.0.0.5 - - [20/Jun/2025:07:55:00 +0900] '
    '"GET /api/users HTTP/1.1" 200 1024 "https://app.example.com/" "Mozilla/5.0"'
)


class TestParseTimestamp:
    def test_with_timezone(self) -> None:
        dt = parse_timestamp("20/Jun/2025:08:01:12 +0900")
        assert dt == datetime(
            2025, 6, 20, 8, 1, 12, tzinfo=timezone(timedelta(hours=9))
        )

    def test_without_timezone_defaults_to_utc(self) -> None:
        dt = parse_timestamp("20/Jun/2025:08:01:12")
        assert dt.utcoffset() == timedelta(0)


class TestSplitLeadingHosts:
    def test_no_forwarded_for(self) -> None:
        assert split_leading_hosts("127.0.0.1") == ("", "127.0.0.1")

    def test_with_forwarded_for(self) -> None:
        assert split_leading_hosts("203.0.113.50 10.0.0.5") == (
            "203.0.113.50",
            "10.0.0.5",
        )

    def test_dash_forwarded_for(self) -> None:
        assert split_leading_hosts("- 127.0.0.1") == ("", "127.0.0.1")


class TestParseLine:
    def test_common_log(self) -> None:
        entry = parse_line(COMMON_LINE, file_id=0, line_no=1, byte_offset=10)
        assert entry is not None
        assert entry.host == "127.0.0.1"
        assert entry.client_host == "127.0.0.1"
        assert entry.method == "GET"
        assert entry.path == "/index.html"
        assert entry.status == 200
        assert entry.file_id == 0
        assert entry.line_no == 1
        assert entry.byte_offset == 10

    def test_combined_log_ignores_trailing_fields(self) -> None:
        entry = parse_line(COMMON_LINE)
        assert entry is not None
        assert entry.status == 200

    def test_x_forwarded_for(self) -> None:
        entry = parse_line(XFF_LINE)
        assert entry is not None
        assert entry.forwarded_for == "203.0.113.50"
        assert entry.host == "10.0.0.5"
        assert entry.client_host == "203.0.113.50"

    def test_invalid_line_returns_none(self) -> None:
        assert parse_line("not a log line") is None


class TestLoadEntries:
    def test_loads_sample_files(self, sample_log_paths: list) -> None:
        entries = load_entries(sample_log_paths, sort=True)
        assert len(entries) == 13

    def test_sorted_by_timestamp(self, sample_log_paths: list) -> None:
        entries = load_entries(sample_log_paths, sort=True)
        timestamps = [e.timestamp for e in entries]
        assert timestamps == sorted(timestamps)

    def test_progress_callback(self, sample_log_paths: list) -> None:
        counts: list[int] = []
        load_entries(sample_log_paths, sort=True, progress_callback=counts.append)
        assert counts[-1] == 13


class TestToRowDict:
    def test_excludes_raw(self) -> None:
        entry = parse_line(COMMON_LINE)
        assert entry is not None
        row = entry.to_row_dict("/var/log/access_log")
        assert "raw" not in row
        assert row["source"] == "/var/log/access_log"
        assert row["method"] == "GET"


class TestByteOffsetWithLineReader:
    def test_reads_original_line(self, sample_log_paths: list) -> None:
        path = next(p for p in sample_log_paths if p.name == "access_log.xff")
        entries = load_entries([path], sort=False)
        entry = entries[0]
        with LineReader([path]) as reader:
            raw = reader.read(entry.file_id, entry.byte_offset)
        assert raw == XFF_LINE
