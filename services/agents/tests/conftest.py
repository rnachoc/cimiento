from __future__ import annotations

import subprocess
import time
import uuid
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[3]
DATA = REPO / "data" / "generated"
PG_IMAGE = "pgvector/pgvector:0.8.0-pg16"


@pytest.fixture(scope="session")
def dev_split() -> Path:
    path = DATA / "dev"
    if not (path / "manifest.json").exists():
        pytest.fail(f"synthetic data missing in {path}; run `make data`")
    return path


@pytest.fixture(scope="session")
def pg_dsn():
    """A throwaway Postgres with pgvector, started with the docker CLI."""
    import psycopg

    name = f"cimiento-test-{uuid.uuid4().hex[:8]}"
    password = uuid.uuid4().hex
    subprocess.run(
        [
            "docker",
            "run",
            "-d",
            "--rm",
            "--name",
            name,
            "-e",
            f"POSTGRES_PASSWORD={password}",
            "-p",
            "127.0.0.1::5432",
            PG_IMAGE,
        ],
        check=True,
        capture_output=True,
    )
    try:
        port = (
            subprocess.run(
                ["docker", "port", name, "5432/tcp"], check=True, capture_output=True, text=True
            )
            .stdout.strip()
            .splitlines()[0]
            .rsplit(":", 1)[1]
        )
        dsn = f"postgresql://postgres:{password}@127.0.0.1:{port}/postgres"
        deadline = time.time() + 60
        while True:
            try:
                psycopg.connect(dsn, connect_timeout=2).close()
                break
            except psycopg.OperationalError:
                if time.time() > deadline:
                    raise
                time.sleep(0.5)
        yield dsn
    finally:
        subprocess.run(["docker", "stop", name], capture_output=True)
