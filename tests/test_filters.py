import re
from datetime import datetime, timezone

import pytest

from alv.filters import match_entry, parse_datetime, parse_status_filter
from alv.parser import parse_line


@pytest.fixture
def sample_entry():
    entry = parse_line(
        '127.0.0.1 - - [20/Jun/2025:08:01:12 +0900] '
        '"GET /api/users HTTP/1.1" 500 0 "-" "curl/8.0"'
    )
    assert entry is not None
    return entry


class TestParseStatusFilter:
    def test_empty(self) -> None:
        assert parse_status_filter("") is None

    def test_exact_code(self) -> None:
        assert parse_status_filter("500") == {500}

    def test_class_pattern(self) -> None:
        result = parse_status_filter("4xx")
        assert 404 in result
        assert 499 in result
        assert 500 not in result

    def test_multiple(self) -> None:
        assert parse_status_filter("500,502") == {500, 502}


class TestParseDatetime:
    def test_none(self) -> None:
        assert parse_datetime(None) is None

    def test_space_separated(self) -> None:
        dt = parse_datetime("2025-06-20 08:01:12")
        assert dt == datetime(2025, 6, 20, 8, 1, 12, tzinfo=timezone.utc)

    def test_invalid_raises(self) -> None:
        with pytest.raises(ValueError, match="日時形式"):
            parse_datetime("invalid")


class TestMatchEntry:
    def test_status_match(self, sample_entry) -> None:
        assert match_entry(sample_entry, status={500}) is True
        assert match_entry(sample_entry, status={200}) is False

    def test_method_match(self, sample_entry) -> None:
        assert match_entry(sample_entry, method="GET") is True
        assert match_entry(sample_entry, method="POST") is False

    def test_path_regex(self, sample_entry) -> None:
        assert match_entry(sample_entry, path=re.compile(r"/api/")) is True
        assert match_entry(sample_entry, path=re.compile(r"/admin")) is False

    def test_host_includes_client(self, sample_entry) -> None:
        assert match_entry(sample_entry, host=re.compile(r"127\.0\.0")) is True

    def test_grep_requires_read_raw(self, sample_entry) -> None:
        grep = re.compile("curl")
        assert match_entry(sample_entry, query=grep) is False
        assert match_entry(
            sample_entry,
            query=grep,
            read_raw=lambda _entry: '127.0.0.1 ... "curl/8.0"',
        ) is True

    def test_source_name_filter(self, sample_entry) -> None:
        pat = re.compile(r"access_log\.1$")
        assert match_entry(sample_entry, source=pat, source_name="access_log.1") is True
        assert match_entry(sample_entry, source=pat, source_name="other.log") is False
