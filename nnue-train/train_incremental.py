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

Usage:
  .venv-nnue/bin/python nnue-train/train_incremental.py \
      --out bot-engine/src/main/resources/nnue-v1.bin \
      --shards 4 --rows-per-shard 6000000 --batch 16384 --lr 0.003 --wd 5e-5
"""
import argparse, os, shutil, time
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    ap.add_argument('--shards', type=int, default=4, help='how many of the 17 shards to use')
    ap.add_argument('--rows-per-shard', type=int, default=6_000_000,
                    help='cap raw rows read per shard (multi-PV, so ~/3 unique positions)')
    ap.add_argument('--read-batch', type=int, default=500_000)
    ap.add_argument('--batch', type=int, default=16384)
    ap.add_argument('--lr', type=float, default=3e-3)
    ap.add_argument('--wd', type=float, default=5e-5)
    ap.add_argument('--dldir', default='/tmp/lichess-shard')
    a = ap.parse_args()

    dev = 'mps' if torch.backends.mps.is_available() else 'cpu'
    print(f"device={dev}", flush=True)
    net = Net().to(dev)
    opt = torch.optim.Adam(net.parameters(), lr=a.lr, weight_decay=a.wd)
    lossf = nn.MSELoss()

    for si in range(min(a.shards, NSHARDS)):
        fn = f"data/train-{si:05d}-of-{NSHARDS:05d}.parquet"
        t0 = time.time()
        print(f"[shard {si}] downloading {fn} ...", flush=True)
        # local_dir mode -> a real file we can delete; nuke the dir after.
        if os.path.exists(a.dldir):
            shutil.rmtree(a.dldir, ignore_errors=True)
        path = hf_hub_download(REPO, fn, repo_type="dataset", local_dir=a.dldir)
        print(f"[shard {si}] downloaded in {time.time()-t0:.0f}s; streaming...", flush=True)
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
        shutil.rmtree(a.dldir, ignore_errors=True)   # free disk
        export_bin(net, a.out)                        # checkpoint each shard
        print(f"[shard {si}] checkpoint -> {a.out}", flush=True)


if __name__ == '__main__':
    main()
