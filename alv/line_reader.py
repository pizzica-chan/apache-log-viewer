"""ログファイルから行をオンデマンドで読み出す."""

from __future__ import annotations

from pathlib import Path


class LineReader:
    """byte_offset から生ログ行を読み出す（grep / 詳細表示用）."""

    def __init__(self, paths: list[Path]) -> None:
        self._paths = paths
        self._handles: dict[int, object] = {}

    def read(self, file_id: int, byte_offset: int) -> str:
        fh = self._handles.get(file_id)
        if fh is None:
            fh = self._paths[file_id].open("rb")
            self._handles[file_id] = fh
        fh.seek(byte_offset)
        return fh.readline().decode("utf-8", errors="replace").rstrip("\n\r")

    def close(self) -> None:
        for fh in self._handles.values():
            fh.close()
        self._handles.clear()

    def __enter__(self) -> LineReader:
        return self

    def __exit__(self, *args: object) -> None:
        self.close()
