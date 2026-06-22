"""Web API の検索フィルタ入力検証テスト."""

from __future__ import annotations

import io
import json
from pathlib import Path
from urllib.parse import urlencode

import pytest

from alv.discovery import find_log_files
from alv.parser import load_entries
from alv.web import LogViewerHandler


@pytest.fixture(autouse=True)
def setup_handler_state() -> None:
    samples = Path(__file__).resolve().parent.parent / "samples"
    paths = find_log_files(samples)
    LogViewerHandler.log_paths = paths
    LogViewerHandler.log_root = samples
    LogViewerHandler.load_status = "ready"
    LogViewerHandler.load_error = None
    LogViewerHandler.entries_cache = load_entries(paths, sort=True)


def _call_logs(query: dict[str, str]) -> tuple[int, dict]:
    handler = LogViewerHandler.__new__(LogViewerHandler)
    handler.path = "/api/logs?" + urlencode(query)
    handler.headers = {}
    handler.rfile = io.BytesIO(b"")
    handler.wfile = io.BytesIO()
    status_code = 200

    def send_response(code: int) -> None:
        nonlocal status_code
        status_code = code

    handler.send_response = send_response  # type: ignore[method-assign]
    handler.send_header = lambda *args, **kwargs: None  # type: ignore[method-assign]
    handler.end_headers = lambda: None  # type: ignore[method-assign]

    handler.do_GET()
    body = json.loads(handler.wfile.getvalue().decode("utf-8"))
    return status_code, body


def test_invalid_path_regex_returns_400() -> None:
    status, body = _call_logs({"path": "[", "limit": "10"})
    assert status == 400
    assert "正規表現が不正です" in body["error"]


def test_invalid_status_returns_400() -> None:
    status, body = _call_logs({"status": "abc", "limit": "10"})
    assert status == 400
    assert body["error"] == "ステータス指定が不正です"


def test_invalid_since_returns_400() -> None:
    status, body = _call_logs({"since": "not-a-date", "limit": "10"})
    assert status == 400
    assert "日時形式" in body["error"]


def test_valid_status_filter_returns_200() -> None:
    status, body = _call_logs({"status": "4xx", "limit": "10"})
    assert status == 200
    assert body["total"] == 3
