#!/usr/bin/env python3
"""Shared Lichess-evaluation dataset extractor — the ONE pipeline that all
downstream trainers read, so we download the 40 GB of shards once instead of
per-experiment.

Streams the `Lichess/chess-position-evaluations` shards one at a time
(download -> extract -> delete; peak disk = one shard), reads the columns the
NNUE trainer currently ignores (`line` / `depth` / `knodes` / multi-PV), and
emits one compact, depth-filtered, gzipped TSV:

    fen <TAB> cp <TAB> mate <TAB> best <TAB> depth <TAB> knodes <TAB> mpv

  * cp      White-POV centipawns ("\\N" when the eval is a forced mate)
  * mate    mate-in-N, White-POV sign ("\\N" when not a mate)
  * best    SF's best move = line[0] in UCI — the policy target ("\\N" if absent)
  * depth   search depth of the CANONICAL (deepest) row for this FEN
  * knodes  kilonodes of that row — depth/knodes let consumers depth-filter
            or confidence-weight (the teacher is browser-Stockfish at depth
            1..245, so quality varies wildly)
  * mpv     optional top-K distinct moves "uci:cp,..." (White-POV cp), SF's
            move ranking for policy / move-ordering priors ("" when --multipv 0)

Consumers (all read this one file):
  * NNUE retrain (value)   -> fen + cp/mate, weight by depth/knodes
  * HCE distillation (Scala TexelTuner) -> fen + cp/mate
  * policy ordering priors (Scala) -> fen + best (+ mpv)

Each FEN appears in several rows (the dataset aggregates many users' browser
analyses); we keep the DEEPEST row as canonical so the label is the most
reliable eval available for that position.

Run (full):  python extract_shards.py --out data/lichess-eval.tsv.gz --min-depth 24 --multipv 4
Test (local): python extract_shards.py --local sample.parquet --out /tmp/o.tsv.gz --min-depth 0 --multipv 3
"""
import argparse
import glob
import gzip
import os
import shutil
import time

import pyarrow.parquet as pq
from huggingface_hub import hf_hub_download

REPO = "Lichess/chess-position-evaluations"
NSHARDS = 17
MATE_CP = 32000  # sentinel White-POV cp for a forced mate (sign follows mate)


def best_move_of(line):
    """First move of a UCI principal variation (SF's recommended move)."""
    if not line:
        return None
    head = line.split(" ", 1)[0]
    return head or None


def white_to_move(fen):
    # FEN side-to-move field is a lone ' w ' / ' b ' between spaces.
    return " w " in fen


def emit_group(out, fen, rows, min_depth, min_knodes, multipv, per_analysis=False):
    """`rows`: list of (line, depth, knodes, cp, mate) for ONE fen.
    Default: keep the single DEEPEST row as canonical (one row/FEN).
    per_analysis=True: keep pv[0] — the best-move row, i.e. the FIRST row, since
    the dataset orders PV lines best-first — of EACH distinct (depth, knodes)
    analysis. This preserves FREQUENCY (a FEN analysed N times at N depths → N
    rows) and VOLUME (all depths), while dropping the multi-PV pv[1+] rows
    (worse-move evals = wrong position-values). Depth soft-weighting happens later
    at train time. Returns the number of rows written."""
    if per_analysis:
        written = 0
        seen = set()
        for (line, depth, knodes, cp, mate) in rows:
            key = (depth, knodes)
            if key in seen:
                continue                       # pv[1+] of an already-emitted analysis
            seen.add(key)
            if (depth or 0) < min_depth or (knodes or 0) < min_knodes:
                continue
            best = best_move_of(line)
            cp_s = "\\N" if cp is None else str(cp)
            mate_s = "\\N" if mate is None else str(mate)
            best_s = best if best else "\\N"
            out.write(f"{fen}\t{cp_s}\t{mate_s}\t{best_s}\t{depth or 0}\t{knodes or 0}\t\n")
            written += 1
        return written
    canon = max(rows, key=lambda r: ((r[1] or 0), (r[2] or 0)))
    line, depth, knodes, cp, mate = canon
    if (depth or 0) < min_depth or (knodes or 0) < min_knodes:
        return 0
    best = best_move_of(line)

    mpv = ""
    if multipv > 0:
        by_move = {}  # uci -> (best_depth, white_pov_cp)
        for (l, d, _k, c, m) in rows:
            mv = best_move_of(l)
            if not mv:
                continue
            wcp = c if c is not None else (MATE_CP if (m or 0) > 0 else -MATE_CP)
            prev = by_move.get(mv)
            if prev is None or (d or 0) > prev[0]:
                by_move[mv] = ((d or 0), wcp)
        # Rank from the side-to-move's POV: White prefers high cp, Black low.
        ranked = sorted(by_move.items(), key=lambda kv: kv[1][1], reverse=white_to_move(fen))
        mpv = ",".join(f"{mv}:{v[1]}" for mv, v in ranked[:multipv])

    cp_s = "\\N" if cp is None else str(cp)
    mate_s = "\\N" if mate is None else str(mate)
    best_s = best if best else "\\N"
    out.write(f"{fen}\t{cp_s}\t{mate_s}\t{best_s}\t{depth or 0}\t{knodes or 0}\t{mpv}\n")
    return 1


def process_parquet(path, out, a):
    """Stream a parquet, grouping CONSECUTIVE rows by fen (carrying the open
    group across batch boundaries)."""
    pf = pq.ParquetFile(path)
    read = written = 0
    cur_fen, cur_rows = None, []
    cols = ["fen", "line", "depth", "knodes", "cp", "mate"]
    for rb in pf.iter_batches(batch_size=a.read_batch, columns=cols):
        fens = rb.column("fen").to_pylist()
        lines = rb.column("line").to_pylist()
        depths = rb.column("depth").to_pylist()
        knodes = rb.column("knodes").to_pylist()
        cps = rb.column("cp").to_pylist()
        mates = rb.column("mate").to_pylist()
        for i in range(len(fens)):
            read += 1
            f = fens[i]
            row = (lines[i], depths[i], knodes[i], cps[i], mates[i])
            if f == cur_fen:
                cur_rows.append(row)
            else:
                if cur_fen is not None:
                    written += emit_group(out, cur_fen, cur_rows, a.min_depth, a.min_knodes, a.multipv, a.per_analysis)
                cur_fen, cur_rows = f, [row]
            if a.limit_rows and read >= a.limit_rows:
                break
        if a.limit_rows and read >= a.limit_rows:
            break
    if cur_fen is not None and cur_rows:
        written += emit_group(out, cur_fen, cur_rows, a.min_depth, a.min_knodes, a.multipv, a.per_analysis)
    return read, written


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data/lichess-eval.tsv.gz")
    ap.add_argument("--shards", type=int, default=NSHARDS, help="how many shards to process")
    ap.add_argument("--min-depth", type=int, default=0, help="drop FENs whose deepest eval is shallower")
    ap.add_argument("--min-knodes", type=int, default=0)
    ap.add_argument("--multipv", type=int, default=0, help="top-K moves for policy (0 = off)")
    ap.add_argument("--per-analysis", action="store_true",
                    help="keep pv[0] of EVERY analysis per FEN (frequency + all depths, "
                         "drops multi-PV pv[1+]) instead of only the single deepest row")
    ap.add_argument("--read-batch", type=int, default=131072)
    ap.add_argument("--dldir", default="/tmp/lichess-shard")
    ap.add_argument("--keep-shards", action="store_true", help="don't delete shards after use")
    ap.add_argument("--limit-rows", type=int, default=0, help="cap input rows (smoke test)")
    ap.add_argument("--local", default=None, help="process a single local parquet instead of downloading")
    ap.add_argument("--local-dir", default=None,
                    help="process ALL train-*.parquet already in this local dir (NO network) "
                         "into one TSV — use after downloading shards out-of-band")
    a = ap.parse_args()

    total_read = total_written = 0
    with gzip.open(a.out, "wt") as out:
        out.write("fen\tcp\tmate\tbest\tdepth\tknodes\tmpv\n")
        if a.local:
            r, w = process_parquet(a.local, out, a)
            total_read, total_written = r, w
        elif a.local_dir:
            files = sorted(glob.glob(os.path.join(a.local_dir, "train-*.parquet")))
            print(f"processing {len(files)} local shards from {a.local_dir} (no download)", flush=True)
            for i, p in enumerate(files):
                t0 = time.time()
                r, w = process_parquet(p, out, a)
                total_read += r; total_written += w
                print(f"[{i+1}/{len(files)} {os.path.basename(p)}] read={r} kept={w} "
                      f"(total kept={total_written}, {time.time()-t0:.0f}s)", flush=True)
        else:
            for si in range(a.shards):
                t0 = time.time()
                d = f"{a.dldir}-{si}"
                fn = f"data/train-{si:05d}-of-{NSHARDS:05d}.parquet"
                # No pre-rmtree: hf_hub_download REUSES an already-complete shard
                # (etag check) and RESUMES a partial one — so a dropped connection
                # costs a retry, not a restart. Retry to ride out flaky internet.
                p = None
                for attempt in range(1, 9):
                    try:
                        p = hf_hub_download(REPO, fn, repo_type="dataset", local_dir=d)
                        break
                    except Exception as e:
                        print(f"[shard {si}] download attempt {attempt} failed: "
                              f"{type(e).__name__}: {e} — retry in 15s", flush=True)
                        time.sleep(15)
                if p is None:
                    raise RuntimeError(f"shard {si} download failed after 8 attempts")
                print(f"[shard {si}] ready in {time.time()-t0:.0f}s", flush=True)
                r, w = process_parquet(p, out, a)
                total_read += r
                total_written += w
                print(f"[shard {si}] read={r} kept={w} (total kept={total_written})", flush=True)
                if not a.keep_shards:
                    shutil.rmtree(d, ignore_errors=True)
                if a.limit_rows and total_read >= a.limit_rows:
                    break
    pct = (100.0 * total_written / total_read) if total_read else 0.0
    print(f"DONE: read={total_read} kept={total_written} ({pct:.1f}%) -> {a.out}", flush=True)


if __name__ == "__main__":
    main()
