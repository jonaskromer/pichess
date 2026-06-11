#!/usr/bin/env python3
"""Incremental NNUE training over the Lichess Stockfish-evaluation
dataset (HF: Lichess/chess-position-evaluations), one shard at a time.

Disk-friendly: download a shard (~2.4 GB) -> stream-train the net on it
(warm-started from the prior shard) -> DELETE it -> next shard. Peak
disk stays at one shard regardless of how many shards we use. The net
state persists in memory across shards, so it's continuous training
over the whole corpus.

Labels: pure Stockfish cp (White-POV), converted to side-to-move POV,
target = sigmoid(stm_cp / 400). These positions have no game outcome,
so it's pure eval distillation of a STRONG, HCE-DECORRELATED teacher —
exactly what the hybrid blend wants (unlike distilling our own HCE).

Each FEN appears once per multi-PV line; rows are best-move-first, so
we keep the first row of each consecutive FEN group (= the position's
own eval).

Two input modes:
  * raw shards (default) — download/stream/delete the 17 parquet shards.
  * `--tsv <file>` (roadmap 6b) — train from the shared-pipeline TSV
    (`extract_shards.py`): already deduplicated + depth-filtered, no
    re-download, and **depth-weighted** (deep SF evals dominate the loss via
    `--depth-norm`). This is the recommended path now the pipeline exists.

Usage:
  # raw shards
  .venv-nnue/bin/python nnue-train/train_incremental.py \
      --out bot-engine/src/main/resources/nnue-v1.bin \
      --shards 4 --rows-per-shard 6000000 --batch 16384 --lr 0.003 --wd 5e-5
  # shared TSV (6b) — after `make nnue-data`
  .venv-nnue/bin/python nnue-train/train_incremental.py \
      --out bot-engine/src/main/resources/nnue-v1.bin \
      --tsv nnue-train/data/lichess-eval.tsv.gz --epochs 3 --depth-norm 40
"""
import argparse, functools, multiprocessing as mp, os, random, shutil, time
from concurrent.futures import ThreadPoolExecutor
import numpy as np
import torch
import torch.nn as nn
import pyarrow.parquet as pq
from huggingface_hub import hf_hub_download

import sys
sys.path.insert(0, os.path.dirname(__file__))
from train_nnue import Net, fen_to_features, export_bin, H, IN, SCALE, PAD, MAXP

REPO = "Lichess/chess-position-evaluations"
NSHARDS = 17
MATE_CP = 2000


def parse_batch(fens, cps, mates, prev_fen):
    """Vectorised-ish parse of a record batch -> (S, Nt, T) numpy arrays,
    keeping only the first row of each consecutive FEN group (pv[0])."""
    S, Nt, T = [], [], []
    for fen, cp, mate in zip(fens, cps, mates):
        if fen == prev_fen:
            continue                      # skip non-first multi-PV rows
        prev_fen = fen
        if cp is None:
            if mate is None:
                continue
            cp = MATE_CP if mate > 0 else -MATE_CP
        cp = max(-MATE_CP, min(MATE_CP, cp))
        s_idx, n_idx, stm_white = fen_to_features(fen)
        if not s_idx or len(s_idx) > MAXP:
            continue
        stm_cp = cp if stm_white else -cp
        T.append(1.0 / (1.0 + np.exp(-stm_cp / 400.0)))
        S.append(s_idx + [PAD] * (MAXP - len(s_idx)))
        Nt.append(n_idx + [PAD] * (MAXP - len(n_idx)))
    return (np.array(S, np.int64), np.array(Nt, np.int64),
            np.array(T, np.float32), prev_fen)


def train_chunk(net, opt, lossf, dev, S, Nt, T, batch):
    if len(T) == 0:
        return 0.0, 0
    S = torch.from_numpy(S).to(dev); Nt = torch.from_numpy(Nt).to(dev)
    T = torch.from_numpy(T).to(dev)
    n = len(T); perm = torch.randperm(n, device=dev)
    tot = 0.0; nb = 0
    for i in range(0, n, batch):
        r = perm[i:i + batch]
        pred = net(S[r], Nt[r])
        loss = lossf(torch.sigmoid(pred), T[r])
        opt.zero_grad(); loss.backward(); opt.step()
        with torch.no_grad():
            net.ft.weight[PAD].zero_()
        tot += loss.item(); nb += 1
    return tot, nb


def parse_tsv_chunk(lines, depth_norm, endgame_pieces=0, endgame_boost=1.0):
    """Parse shared-pipeline TSV lines (fen\\tcp\\tmate\\tbest\\tdepth\\tknodes\\tmpv)
    -> (S, Nt, T, W). The TSV is already deduplicated (one canonical row per
    FEN), so no multi-PV skipping is needed. W is a depth-confidence weight in
    (0, 1] (deep SF evals dominate the loss); see train_chunk_weighted.

    Endgame emphasis (for strong LOCAL endgames without tablebases): positions
    with <= endgame_pieces total pieces get their weight multiplied by
    endgame_boost, so the net learns sparse-position eval — where its ordinary
    training is thin — far better. The SF labels for <=7-piece positions are
    already near-tablebase-perfect, so this distils most of that knowledge into
    the committable ~193 KB net."""
    S, Nt, T, W = [], [], [], []
    for ln in lines:
        f = ln.rstrip("\n").split("\t")
        if len(f) < 6 or f[0] == "fen":
            continue
        fen, cp_s, mate_s, depth_s = f[0], f[1], f[2], f[4]
        cp = None if cp_s == "\\N" else int(cp_s)
        if cp is None:
            mate = None if mate_s == "\\N" else int(mate_s)
            if mate is None:
                continue
            cp = MATE_CP if mate > 0 else -MATE_CP
        cp = max(-MATE_CP, min(MATE_CP, cp))
        s_idx, n_idx, stm_white = fen_to_features(fen)
        if not s_idx or len(s_idx) > MAXP:
            continue
        stm_cp = cp if stm_white else -cp
        depth = int(depth_s) if depth_s.lstrip("-").isdigit() else 0
        w = min(1.0, depth / depth_norm)                  # soft depth filter
        if endgame_boost != 1.0:                          # up-weight sparse endgames
            npieces = sum(c.isalpha() for c in fen.split(' ', 1)[0])
            if npieces <= endgame_pieces:
                w *= endgame_boost
        T.append(1.0 / (1.0 + np.exp(-stm_cp / 400.0)))
        W.append(w)
        S.append(s_idx + [PAD] * (MAXP - len(s_idx)))
        Nt.append(n_idx + [PAD] * (MAXP - len(n_idx)))
    return (np.array(S, np.int64), np.array(Nt, np.int64),
            np.array(T, np.float32), np.array(W, np.float32))


def train_chunk_weighted(net, opt, lossf_none, dev, S, Nt, T, W, batch):
    """Like train_chunk but with a per-sample loss weight (depth confidence):
    loss = Σ w·(pred−target)² / Σ w."""
    if len(T) == 0:
        return 0.0, 0
    S = torch.from_numpy(S).to(dev); Nt = torch.from_numpy(Nt).to(dev)
    T = torch.from_numpy(T).to(dev); W = torch.from_numpy(W).to(dev)
    n = len(T); perm = torch.randperm(n, device=dev)
    tot = 0.0; nb = 0
    for i in range(0, n, batch):
        r = perm[i:i + batch]
        pred = net(S[r], Nt[r])
        per  = lossf_none(torch.sigmoid(pred), T[r])
        wr   = W[r]
        loss = (per * wr).sum() / wr.sum().clamp_min(1e-6)
        opt.zero_grad(); loss.backward(); opt.step()
        with torch.no_grad():
            net.ft.weight[PAD].zero_()
        tot += loss.item(); nb += 1
    return tot, nb


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    ap.add_argument('--shards', type=int, default=4, help='how many of the 17 shards to use')
    ap.add_argument('--rows-per-shard', type=int, default=6_000_000,
                    help='cap raw rows read per shard (multi-PV, so ~/3 unique positions)')
    ap.add_argument('--read-batch', type=int, default=500_000)
    ap.add_argument('--batch', type=int, default=16384)
    ap.add_argument('--lr', type=float, default=3e-3)
    ap.add_argument('--lr-decay', type=float, default=1.0,
                    help='multiply LR by this after each shard (e.g. 0.9 for a long run)')
    ap.add_argument('--wd', type=float, default=5e-5)
    ap.add_argument('--dldir', default='/tmp/lichess-shard')
    ap.add_argument('--tsv', default=None,
                    help='train from the shared-pipeline TSV (extract_shards.py) with '
                         'depth-weighting, instead of downloading raw shards (roadmap 6b)')
    ap.add_argument('--epochs', type=int, default=1, help='passes over the TSV (--tsv mode)')
    ap.add_argument('--depth-norm', type=float, default=40.0,
                    help='search depth at which a sample reaches full loss weight (--tsv mode)')
    ap.add_argument('--endgame-pieces', type=int, default=7,
                    help='positions with <= this many pieces count as endgames (--tsv mode)')
    ap.add_argument('--endgame-boost', type=float, default=1.0,
                    help='loss-weight multiplier for endgame positions (1.0 = off; '
                         'e.g. 6.0 to emphasise local endgame eval) (--tsv mode)')
    ap.add_argument('--hidden', type=int, default=128,
                    help='NNUE hidden width. MUST match Scala NnueEvaluator.HiddenSize '
                         'for the .bin to load (128 = current shipped, 256 = 2x control)')
    ap.add_argument('--parse-workers', type=int,
                    default=max(1, (os.cpu_count() or 4) - 2),
                    help='parallel TSV-parse worker processes (--tsv mode). The parse '
                         '(fen_to_features per row) is pure-Python, GIL-bound to one '
                         'core; fanning it out is the dominant speedup over 100M+ rows')
    ap.add_argument('--sample-frac', type=float, default=1.0,
                    help='randomly keep this fraction of TSV rows (--tsv mode) — an '
                         'inline subsample for a quick run, no recompress to a temp file')
    ap.add_argument('--max-rows', type=int, default=0,
                    help='read only the first N raw TSV rows then stop (--tsv mode) — a '
                         'fast PREFIX subset. Unlike --sample-frac it does NOT scan the '
                         'whole file single-threaded (which starves the parse workers)')
    a = ap.parse_args()

    dev = 'mps' if torch.backends.mps.is_available() else 'cpu'
    print(f"device={dev}  hidden={a.hidden}", flush=True)
    net = Net(h=a.hidden).to(dev)
    opt = torch.optim.Adam(net.parameters(), lr=a.lr, weight_decay=a.wd)
    lossf = nn.MSELoss()

    # ── 6b: train from the shared TSV with depth-weighting ──
    if a.tsv:
        import gzip
        lossf_none = nn.MSELoss(reduction='none')
        # The TSV parse (`fen_to_features` per row) is pure-Python and GIL-bound
        # to ONE core (~24K rows/s) — the dominant cost over 100M+ rows. Fan it
        # out across worker PROCESSES: each parses a chunk -> numpy arrays (never
        # touches torch/MPS), the main process trains on the results on MPS.
        nworkers = max(1, a.parse_workers)
        print(f"TSV mode: {a.tsv} (epochs={a.epochs}, depth_norm={a.depth_norm}, "
              f"parse_workers={nworkers})", flush=True)
        worker = functools.partial(parse_tsv_chunk, depth_norm=a.depth_norm,
                                   endgame_pieces=a.endgame_pieces,
                                   endgame_boost=a.endgame_boost)

        def read_chunks(path, batch, frac, max_rows):
            """Stream the gzip in `batch`-line chunks (one parse task each).
            `max_rows`>0 stops after that many RAW rows (a fast prefix). `frac`<1
            keeps a random fraction — but `frac` must still scan the whole file
            single-threaded, which can STARVE the parse workers; prefer `max_rows`
            for a quick subset. The parse worker drops the header line itself."""
            with gzip.open(path, 'rt') as fh:
                buf = []; nread = 0
                for ln in fh:
                    nread += 1
                    if max_rows and nread > max_rows:
                        break
                    if frac < 1.0 and random.random() >= frac:
                        continue
                    buf.append(ln)
                    if len(buf) >= batch:
                        yield buf; buf = []
                if buf:
                    yield buf

        def waves(it, n):
            """Group chunks into waves of `n` so at most `n` are ever in flight
            (bounds peak memory regardless of total rows)."""
            w = []
            for x in it:
                w.append(x)
                if len(w) >= n:
                    yield w; w = []
            if w:
                yield w

        with mp.Pool(nworkers) as pool:
            for ep in range(a.epochs):
                ts = time.time(); trained = 0; tloss = 0.0; tnb = 0; nchunks = 0
                for wave in waves(read_chunks(a.tsv, a.read_batch, a.sample_frac, a.max_rows), nworkers):
                    # Parse up to `nworkers` chunks in parallel, then train each
                    # result on MPS in the main process.
                    for (S, Nt, T, W) in pool.map(worker, wave):
                        l, nb = train_chunk_weighted(net, opt, lossf_none, dev, S, Nt, T, W, a.batch)
                        tloss += l; tnb += nb; trained += len(T); nchunks += 1
                        if nchunks % 10 == 0:
                            el = time.time() - ts
                            print(f"[epoch {ep}] {trained:,} rows  {trained/max(el,1):.0f} rows/s  "
                                  f"loss={tloss/max(tnb,1):.5f}  {el:.0f}s", flush=True)
                print(f"[epoch {ep}] trained on {trained} positions "
                      f"(loss={tloss/max(tnb,1):.5f}, {time.time()-ts:.0f}s)", flush=True)
                export_bin(net, a.out)
                print(f"[epoch {ep}] checkpoint -> {a.out}", flush=True)
                if a.lr_decay != 1.0:
                    for g in opt.param_groups:
                        g['lr'] *= a.lr_decay
                    print(f"[epoch {ep}] lr -> {opt.param_groups[0]['lr']:.2e}", flush=True)
        return

    nshards = min(a.shards, NSHARDS)

    def dl(si):
        """Download shard si into its own dir; return the parquet path."""
        d = f"{a.dldir}-{si}"
        shutil.rmtree(d, ignore_errors=True)
        fn = f"data/train-{si:05d}-of-{NSHARDS:05d}.parquet"
        t0 = time.time()
        p = hf_hub_download(REPO, fn, repo_type="dataset", local_dir=d)
        print(f"[shard {si}] downloaded in {time.time()-t0:.0f}s", flush=True)
        return p

    # Prefetch: keep the NEXT shard downloading in a background thread
    # while the current one trains (peak disk = 2 shards ~5 GB).
    pool = ThreadPoolExecutor(max_workers=1)
    fut = pool.submit(dl, 0)
    for si in range(nshards):
        path = fut.result()                       # current shard ready
        if si + 1 < nshards:
            fut = pool.submit(dl, si + 1)          # kick off next download
        print(f"[shard {si}] streaming...", flush=True)
        pf = pq.ParquetFile(path)
        seen = 0; prev_fen = None; tloss = 0.0; tnb = 0; trained = 0
        ts = time.time()
        for rb in pf.iter_batches(batch_size=a.read_batch, columns=["fen", "cp", "mate"]):
            d = rb.to_pydict()
            S, Nt, T, prev_fen = parse_batch(d["fen"], d["cp"], d["mate"], prev_fen)
            l, nb = train_chunk(net, opt, lossf, dev, S, Nt, T, a.batch)
            tloss += l; tnb += nb; trained += len(T)
            seen += len(d["fen"])
            if seen >= a.rows_per_shard:
                break
        print(f"[shard {si}] trained on {trained} positions "
              f"(loss={tloss/max(tnb,1):.5f}, {time.time()-ts:.0f}s)", flush=True)
        shutil.rmtree(f"{a.dldir}-{si}", ignore_errors=True)  # free this shard
        export_bin(net, a.out)                                # checkpoint each shard
        print(f"[shard {si}] checkpoint -> {a.out}", flush=True)
        if a.lr_decay != 1.0:                                 # decay LR per shard
            for g in opt.param_groups:
                g['lr'] *= a.lr_decay
            print(f"[shard {si}] lr -> {opt.param_groups[0]['lr']:.2e}", flush=True)
    pool.shutdown(wait=False)


if __name__ == '__main__':
    main()
