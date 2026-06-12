#!/usr/bin/env python3
"""Re-pull all 17 Lichess-evaluation parquet shards into
`nnue-train/data/shards/` with the FLAT `train-NNNNN-of-00017.parquet` layout
that the trainer's `--local-shards` and `extract_shards.py --local-dir` expect.

Idempotent + resumable: a shard already present at its final flat path (with a
plausible size) is skipped; a partial download resumes via the hf cache. Each
shard is retried to ride out flaky links (mirrors the trainer's `dl()`)."""
import os
import shutil
import time

from huggingface_hub import hf_hub_download

REPO = "Lichess/chess-position-evaluations"
NSHARDS = 17
MIN_BYTES = 1_000_000_000  # ~1 GB floor; real shards are ~2.2-2.4 GB

HERE = os.path.dirname(os.path.abspath(__file__))
SHARDS = os.path.join(HERE, "data", "shards")
DLROOT = os.path.join(HERE, "data", "_dl")


def fetch(si):
    flat = os.path.join(SHARDS, f"train-{si:05d}-of-{NSHARDS:05d}.parquet")
    if os.path.isfile(flat) and os.path.getsize(flat) >= MIN_BYTES:
        print(f"[shard {si}] present ({os.path.getsize(flat)/1e9:.2f} GB) — skip", flush=True)
        return
    cache = os.path.join(DLROOT, f"shard-{si}")
    fn = f"data/train-{si:05d}-of-{NSHARDS:05d}.parquet"
    t0 = time.time()
    for attempt in range(1, 9):
        try:
            p = hf_hub_download(REPO, fn, repo_type="dataset", local_dir=cache)
            os.makedirs(SHARDS, exist_ok=True)
            shutil.move(p, flat)
            shutil.rmtree(cache, ignore_errors=True)
            print(f"[shard {si}] {os.path.getsize(flat)/1e9:.2f} GB in "
                  f"{time.time()-t0:.0f}s (attempt {attempt})", flush=True)
            return
        except Exception as e:
            print(f"[shard {si}] attempt {attempt} failed: {type(e).__name__}: {e}"
                  f" — retry in 15s", flush=True)
            time.sleep(15)
    raise SystemExit(f"shard {si} failed after 8 attempts")


def main():
    os.makedirs(SHARDS, exist_ok=True)
    t0 = time.time()
    for si in range(NSHARDS):
        fetch(si)
    files = [f for f in os.listdir(SHARDS) if f.endswith(".parquet")]
    total = sum(os.path.getsize(os.path.join(SHARDS, f)) for f in files)
    print(f"DONE: {len(files)}/{NSHARDS} shards, {total/1e9:.1f} GB total, "
          f"{time.time()-t0:.0f}s", flush=True)


if __name__ == "__main__":
    main()
