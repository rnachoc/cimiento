"""Command line entry point: cimiento-data --seed 42 --split dev --out DIR."""

from __future__ import annotations

import argparse
from pathlib import Path

from .generate import generate


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate Cimiento synthetic data")
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--split", choices=["dev", "eval"], required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    manifest = generate(args.seed, args.split, args.out)
    print(f"{args.split}: {len(manifest['ficheros'])} files in {args.out}")  # type: ignore[arg-type]
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
