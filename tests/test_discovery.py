from pathlib import Path

from alv.discovery import find_log_files, is_log_file


class TestIsLogFile:
    def test_access_log(self) -> None:
        assert is_log_file("access_log") is True
        assert is_log_file("access_log.1") is True

    def test_compressed_excluded(self) -> None:
        assert is_log_file("access_log.gz") is False

    def test_unrelated_file(self) -> None:
        assert is_log_file("readme.txt") is False


class TestFindLogFiles:
    def test_finds_samples(self, samples_dir: Path) -> None:
        found = find_log_files(samples_dir)
        names = {p.name for p in found}
        assert "access_log.1" in names
        assert "access_log.2" in names
        assert "access_log.xff" in names

    def test_missing_directory(self) -> None:
        assert find_log_files(Path("/nonexistent/path")) == []
