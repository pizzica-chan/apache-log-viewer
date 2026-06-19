"""Web UI サーバー."""

from __future__ import annotations

import json
import mimetypes
import re
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .discovery import find_log_files
from .filters import match_entry, parse_datetime, parse_status_filter
from .parser import load_entries

STATIC_DIR = Path(__file__).parent / "static"


class LogViewerHandler(BaseHTTPRequestHandler):
    log_paths: list[Path] = []
    log_root: Path | None = None
    entries_cache: list | None = None

    def log_message(self, format: str, *args) -> None:  # noqa: A003
        return

    def _send_json(self, payload: object, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_file(self, path: Path) -> None:
        if not path.exists():
            self.send_error(404)
            return
        content = path.read_bytes()
        mime, _ = mimetypes.guess_type(str(path))
        self.send_response(200)
        self.send_header("Content-Type", mime or "application/octet-stream")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def _read_json_body(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("JSON オブジェクトを指定してください")
        return data

    def _get_entries(self) -> list:
        if self.entries_cache is None:
            self.entries_cache = load_entries(self.log_paths, sort=True)
        return self.entries_cache

    def _meta_payload(self) -> dict:
        entries = self._get_entries()
        return {
            "directory": str(self.log_root) if self.log_root else None,
            "files": [str(p) for p in self.log_paths],
            "total": len(entries),
            "first": entries[0].timestamp.isoformat() if entries else None,
            "last": entries[-1].timestamp.isoformat() if entries else None,
        }

    def _browse_directory(self, raw_path: str) -> dict:
        if raw_path:
            current = Path(raw_path).expanduser().resolve()
        elif self.log_root is not None:
            current = self.log_root
        else:
            current = Path.cwd().resolve()

        if not current.is_dir():
            return {"error": f"ディレクトリが見つかりません: {current}"}

        parent = str(current.parent) if current.parent != current else None
        directories: list[str] = []
        try:
            for entry in sorted(current.iterdir(), key=lambda p: p.name.lower()):
                if entry.is_dir() and not entry.name.startswith("."):
                    directories.append(str(entry.resolve()))
        except OSError as exc:
            return {"error": f"ディレクトリを読み取れません: {exc}"}

        return {
            "current": str(current),
            "parent": parent,
            "directories": directories,
        }

    def _load_directory(self, raw_path: str) -> tuple[dict, int]:
        if not raw_path:
            return {"error": "directory を指定してください"}, 400

        root = Path(raw_path).expanduser().resolve()
        if not root.is_dir():
            return {"error": f"ディレクトリが見つかりません: {root}"}, 400

        paths = find_log_files(root)
        LogViewerHandler.log_root = root
        LogViewerHandler.log_paths = paths
        LogViewerHandler.entries_cache = None
        return self._meta_payload(), 200

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)

        if parsed.path == "/":
            return self._send_file(STATIC_DIR / "index.html")
        if parsed.path.startswith("/static/"):
            rel = parsed.path.removeprefix("/static/")
            return self._send_file(STATIC_DIR / rel)

        if parsed.path == "/api/meta":
            return self._send_json(self._meta_payload())

        if parsed.path == "/api/browse":
            raw_path = params.get("path", [""])[0]
            payload = self._browse_directory(raw_path)
            if "error" in payload:
                return self._send_json(payload, 400)
            return self._send_json(payload)

        if parsed.path == "/api/logs":
            entries = self._get_entries()
            status = parse_status_filter(params.get("status", [""])[0])
            path_pat = params.get("path", [""])[0]
            host_pat = params.get("host", [""])[0]
            method = params.get("method", [""])[0] or None
            grep = params.get("grep", [""])[0]
            source_pat = params.get("source", [""])[0]
            since = parse_datetime(params.get("since", [""])[0] or None)
            until = parse_datetime(params.get("until", [""])[0] or None)
            try:
                limit = min(int(params.get("limit", ["500"])[0]), 5000)
                offset = max(int(params.get("offset", ["0"])[0]), 0)
            except ValueError:
                return self._send_json({"error": "limit/offset は整数で指定してください"}, 400)

            path_re = re.compile(path_pat, re.IGNORECASE) if path_pat else None
            host_re = re.compile(host_pat, re.IGNORECASE) if host_pat else None
            grep_re = re.compile(grep, re.IGNORECASE) if grep else None
            source_re = re.compile(source_pat, re.IGNORECASE) if source_pat else None

            filtered = [
                e
                for e in entries
                if match_entry(
                    e,
                    status=status,
                    path=path_re,
                    host=host_re,
                    method=method,
                    since=since,
                    until=until,
                    query=grep_re,
                    source=source_re,
                )
            ]
            page = filtered[offset : offset + limit]
            return self._send_json(
                {
                    "total": len(filtered),
                    "offset": offset,
                    "limit": limit,
                    "items": [e.to_dict() for e in page],
                }
            )

        self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/api/load":
            self.send_error(404)
            return

        try:
            body = self._read_json_body()
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as exc:
            return self._send_json({"error": str(exc)}, 400)

        payload, status = self._load_directory(body.get("directory", ""))
        return self._send_json(payload, status)


def serve(
    paths: list[Path],
    *,
    host: str = "127.0.0.1",
    port: int = 8765,
    log_root: Path | None = None,
) -> None:
    LogViewerHandler.log_paths = [p.resolve() for p in paths]
    LogViewerHandler.log_root = log_root.resolve() if log_root else None
    LogViewerHandler.entries_cache = None
    server = ThreadingHTTPServer((host, port), LogViewerHandler)
    print(f"Apache Log Viewer: http://{host}:{port}")
    if LogViewerHandler.log_root:
        print(f"ログディレクトリ: {LogViewerHandler.log_root}")
    print(f"読み込みファイル ({len(paths)}):")
    for p in paths:
        print(f"  - {p}")
    if not paths:
        print("  (未読み込み — ブラウザからディレクトリを選択してください)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n停止しました。")
    finally:
        server.server_close()
