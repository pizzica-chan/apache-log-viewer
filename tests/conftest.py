from pathlib import Path

import pytest

SAMPLES_DIR = Path(__file__).resolve().parent.parent / "samples"


@pytest.fixture
def samples_dir() -> Path:
    return SAMPLES_DIR


@pytest.fixture
def sample_log_paths(samples_dir: Path) -> list[Path]:
    return sorted(samples_dir.glob("access_log*"))
