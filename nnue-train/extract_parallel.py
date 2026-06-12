#!/usr/bin/env python3
"""Parallel front-end for `extract_shards.py` — builds the shared Lichess-eval
TSV from already-downloaded local shards using ALL cores instead of one.

`extract_shards.py` streams the 17 shards SEQUENTIALLY on a single core
(`fen_to_features`-free, but the per-row grouping/emit is pure-Python and
GIL-bound). Crucially, the extractor does NOT deduplicate ACROSS shards — each
`process_parquet` call groups consecutive FENs WITHIN one file and writes
independently — so the shards are embarrassingly parallel: process each on its
own worker, then concatenate the parts. The row semantics are byte-for-byte the
sequential extractor's, because each worker calls the SAME `process_parquet` /
`emit_group`; only the fan-out across shards is new here.

Output is a single gzipped TSV identical in content to
`extract_shards.py --local-dir`, with exactly one header line (only the shard-0
part writes it; the rest are concatenated headerless). gzip members concatenate
losslessly, so the result decompresses as one stream for both the Python
(`train_incremental.parse_tsv_chunk`) and Scala (`LichessEvalReader`) readers.

Run:
  .venv-nnue/bin/python nnue-train/extract_parallel.py \
      --shards-dir nnue-train/data/shards \
      --out nnue-train/data/lichess-eval.tsv.gz \
      --min-depth 24 --multipv 4 --workers 12
"""
import argparse
import glob
import gzip
import multiprocessing as mp
import os
import shutil
import sys
import time
from types import SimpleNamespace

# Spawned workers (Windows mp default) re-import this module, so ensure the
# script's own dir is importable for `extract_shards` (mirrors train_incremental).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract_shards import process_parquet

HEADER = "fen\tcp\tmate\tbest\tdepth\tknodes\tmpv\n"


def _work(job):
    """Process ONE shard into its own gzipped part. `job` is a plain tuple so
    it pickles cleanly to spawned workers (Windows). Returns (idx, read, written)."""
    (idx, shard_path, part_path, min_depth, min_knodes, multipv,
     per_analysis, read_batch, limit_rows, write_header) = job
    a = SimpleNamespace(
        min_depth=min_depth, min_knodes=min_knodes, multipv=multipv,
        per_analysis=per_analysis, read_batch=read_batch, limit_rows=limit_rows,
    )
    with gzip.open(part_path, "wt", compresslevel=6) as out:
        if write_header:
            out.write(HEADER)
        r, w = process_parquet(shard_path, out, a)
    return (idx, r, w)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shards-dir", default="nnue-train/data/shards")
    ap.add_argument("--out", default="nnue-train/data/lichess-eval.tsv.gz")
    ap.add_argument("--min-depth", type=int, default=24)
    ap.add_argument("--min-knodes", type=int, default=0)
    ap.add_argument("--multipv", type=int, default=0)
    ap.add_argument("--per-analysis", action="store_true")
    ap.add_argument("--read-batch", type=int, default=131072)
    ap.add_argument("--limit-rows", type=int, default=0,
                    help="per-shard input-row cap (smoke test)")
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 4) - 2))
    a = ap.parse_args()

    shards = sorted(glob.glob(os.path.join(a.shards_dir, "train-*.parquet")))
    if not shards:
        raise SystemExit(f"no train-*.parquet under {a.shards_dir}")
    workers = max(1, min(a.workers, len(shards)))
    partdir = a.out + ".parts"
    os.makedirs(partdir, exist_ok=True)
    parts = [os.path.join(partdir, f"part-{i:05d}.tsv.gz") for i in range(len(shards))]

    jobs = [
        (i, shards[i], parts[i], a.min_depth, a.min_knodes, a.multipv,
         a.per_analysis, a.read_batch, a.limit_rows, i == 0)
        for i in range(len(shards))
    ]
    print(f"extracting {len(shards)} shards on {workers} workers "
          f"(min_depth={a.min_depth}, multipv={a.multipv}, "
          f"per_analysis={a.per_analysis}) -> {a.out}", flush=True)

    t0 = time.time()
    total_r = total_w = 0
    with mp.Pool(workers) as pool:
        for (idx, r, w) in pool.imap_unordered(_work, jobs):
            total_r += r
            total_w += w
            print(f"[shard {idx}] read={r} kept={w} "
                  f"(running kept={total_w}, {time.time()-t0:.0f}s)", flush=True)

    # Binary-concatenate the gzip parts in shard order → one multi-member gzip.
    with open(a.out, "wb") as dst:
        for p in parts:
            with open(p, "rb") as src:
                shutil.copyfileobj(src, dst, length=1 << 20)
    shutil.rmtree(partdir, ignore_errors=True)

    pct = (100.0 * total_w / total_r) if total_r else 0.0
    sz = os.path.getsize(a.out) / 1e9
    print(f"DONE: read={total_r} kept={total_w} ({pct:.1f}%) -> {a.out} "
          f"({sz:.2f} GB gz, {time.time()-t0:.0f}s)", flush=True)


if __name__ == "__main__":
    main()
