#!/usr/bin/env python3
"""PyTorch trainer for the pichess NNUE — perspective net (768 -> 128) x2 -> 1.

Reads NnueDataGen output rows (pipe-delimited):

    fen | score | result | mpv | mds | comps | tms | tact | eng

and trains a perspective NNUE whose quantized int16 layout exactly matches
`NnueEvaluator.parse` in the Scala engine, so the exported `.bin` drops
straight into bot-engine resources.

Architecture / quantization (must match NnueEvaluator.scala):
  HiddenSize=128  InputSize=768  Scale=400  QA=255  QB=64
  feature transformer  : shared 768->128, SCReLU activation
  output               : concat(stm_acc, ntm_acc) [256] -> 1
  .bin order (LE int16):
    featureWeights  768*128, feat-major,  *QA
    featureBias     128,                   *QA
    outputWeights   256 (stm128 ++ ntm128),*QB
    outputBias      1,                      *QA*QB

Each position's active features (<=32 pieces) are padded to width 32 with a
zero padding index (768), so the dataset is dense (N,32) tensors — fast to
shuffle/index on MPS. nn.Embedding(769,...,padding_idx=768) keeps that row 0.

Feature index replicates NnueEvaluator.addBitboard (stm-relative perspective,
vertical mirror for the side-not-to-move). ptOrd: P=0 N=1 B=2 R=3 Q=4 K=5;
sq is LERF (a1=0..h8=63).

Usage:
  .venv-nnue/bin/python nnue-train/train_nnue.py \
      --data /tmp/nnue-data-v8-s1.txt --out /tmp/nnue-v2.bin \
      --epochs 40 --batch 16384
"""
import argparse, time
import numpy as np
import torch
import torch.nn as nn

H, IN, SCALE, QA, QB = 128, 768, 400, 255, 64
PAD = IN              # padding feature index (row kept at zero)
MAXP = 32             # max pieces on board
PT = {'p': 0, 'n': 1, 'b': 2, 'r': 3, 'q': 4, 'k': 5}


def fen_to_features(fen):
    board, side = fen.split()[0], fen.split()[1]
    stm_white = (side == 'w')
    stm, ntm = [], []
    rank, file = 7, 0
    for ch in board:
        if ch == '/':
            rank -= 1; file = 0
        elif ch.isdigit():
            file += int(ch)
        else:
            color = 0 if ch.isupper() else 1
            pt = PT[ch.lower()]
            sq = rank * 8 + file
            if stm_white:
                s = color * 384 + pt * 64 + sq
                n = (1 - color) * 384 + pt * 64 + (sq ^ 56)
            else:
                s = (1 - color) * 384 + pt * 64 + (sq ^ 56)
                n = color * 384 + pt * 64 + sq
            stm.append(s); ntm.append(n)
            file += 1
    return stm, ntm, stm_white


def load(path, clip, lam):
    S, Nt, T = [], [], []
    n = 0
    for line in open(path):
        p = line.split('|')
        if len(p) < 3:
            continue
        try:
            score = float(p[1]); result = float(p[2])
        except ValueError:
            continue
        s_idx, n_idx, stm_white = fen_to_features(p[0].strip())
        if not s_idx or len(s_idx) > MAXP:
            continue
        score = max(-clip, min(clip, score))
        stm_score = score if stm_white else -score
        stm_result = result if stm_white else 1.0 - result
        target = lam * stm_result + (1 - lam) * (1.0 / (1.0 + np.exp(-stm_score / 400.0)))
        S.append(s_idx + [PAD] * (MAXP - len(s_idx)))
        Nt.append(n_idx + [PAD] * (MAXP - len(n_idx)))
        T.append(target)
        n += 1
    print(f"loaded {n} positions from {path}", flush=True)
    return (np.array(S, np.int64), np.array(Nt, np.int64), np.array(T, np.float32))


class Net(nn.Module):
    def __init__(self, h=H):
        super().__init__()
        self.h = h
        self.ft = nn.Embedding(IN + 1, h, padding_idx=PAD)
        self.ftb = nn.Parameter(torch.zeros(h))
        self.out = nn.Linear(2 * h, 1)
        nn.init.uniform_(self.ft.weight, -0.1, 0.1)
        with torch.no_grad():
            self.ft.weight[PAD].zero_()
        nn.init.uniform_(self.out.weight, -0.1, 0.1)

    def acc(self, feats):
        # embedding_bag FUSES the per-piece gather + sum, so it never
        # materialises the (batch, MAXP, H) intermediate that `ft(feats).sum(1)`
        # does — ~6x faster on MPS (benchmarked). Math is identical (sum of the
        # active rows; `padding_idx` excludes PAD, which is zero anyway), so the
        # trained weights and the int16 `.bin` export are unchanged.
        bag = nn.functional.embedding_bag(feats, self.ft.weight, mode='sum',
                                          padding_idx=PAD)
        return torch.clamp(bag + self.ftb, 0.0, 1.0) ** 2

    def forward(self, sf, nf):
        return self.out(torch.cat([self.acc(sf), self.acc(nf)], 1)).squeeze(1)


def export_bin(net, path):
    h = net.ftb.detach().cpu().numpy().shape[0]      # hidden size (any width)
    W1 = net.ft.weight.detach().cpu().numpy()[:IN]   # (768, h) feat-major
    b1 = net.ftb.detach().cpu().numpy()
    W2 = net.out.weight.detach().cpu().numpy()[0]    # (2h,)
    b2 = float(net.out.bias.detach().cpu().numpy()[0])
    vals = []
    vals.extend(np.round(W1.reshape(-1) * QA).astype(np.int32))
    vals.extend(np.round(b1 * QA).astype(np.int32))
    vals.extend(np.round(W2 * QB).astype(np.int32))
    vals.append(int(round(b2 * QA * QB)))
    arr = np.clip(np.array(vals, np.int32), -32768, 32767).astype('<i2')
    expect = IN * h + h + 2 * h + 1
    assert arr.size == expect, f"{arr.size} != {expect}"
    arr.tofile(path)
    print(f"wrote {path} ({arr.size*2} bytes; h={h}, expected {expect*2})", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--epochs', type=int, default=40)
    ap.add_argument('--batch', type=int, default=16384)
    ap.add_argument('--lr', type=float, default=1e-2)
    ap.add_argument('--clip', type=float, default=2000)
    ap.add_argument('--lam', type=float, default=0.5)
    ap.add_argument('--wd', type=float, default=0.0, help='Adam weight decay (L2 reg)')
    ap.add_argument('--val', type=float, default=0.0, help='fraction held out for val loss')
    a = ap.parse_args()

    dev = 'mps' if torch.backends.mps.is_available() else 'cpu'
    print(f"device={dev}", flush=True)
    S, Nt, T = load(a.data, a.clip, a.lam)
    N = len(T)
    if N < 1000:
        print(f"WARNING: only {N} positions", flush=True)
    S = torch.from_numpy(S).to(dev); Nt = torch.from_numpy(Nt).to(dev)
    T = torch.from_numpy(T).to(dev)

    # Optional held-out validation split to watch for overfitting.
    nval = int(N * a.val)
    idx = torch.randperm(N, device=dev)
    vidx, tidx = idx[:nval], idx[nval:]
    Ntr = len(tidx)

    net = Net().to(dev)
    opt = torch.optim.Adam(net.parameters(), lr=a.lr, weight_decay=a.wd)
    lossf = nn.MSELoss()
    for ep in range(a.epochs):
        perm = tidx[torch.randperm(Ntr, device=dev)]
        tot = 0.0; nb = 0; t0 = time.time()
        for i in range(0, Ntr, a.batch):
            r = perm[i:i + a.batch]
            pred = net(S[r], Nt[r])
            loss = lossf(torch.sigmoid(pred), T[r])
            opt.zero_grad(); loss.backward(); opt.step()
            with torch.no_grad():
                net.ft.weight[PAD].zero_()
            tot += loss.item(); nb += 1
        vmsg = ""
        if nval:
            with torch.no_grad():
                vp = torch.sigmoid(net(S[vidx], Nt[vidx]))
                vmsg = f" val={lossf(vp, T[vidx]).item():.5f}"
        print(f"epoch {ep+1}/{a.epochs} loss={tot/nb:.5f}{vmsg} ({time.time()-t0:.1f}s)", flush=True)
    export_bin(net, a.out)
    # White-POV cp eval for a few FENs — cross-check vs the Scala
    # NnueEvaluator on the same .bin to confirm feature indexing matches.
    net.eval()
    with torch.no_grad():
        for fen in ["rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1",
                    "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 1"]:
            s, n, w = fen_to_features(fen)
            sf = torch.tensor([s + [PAD] * (MAXP - len(s))], device=dev)
            nf = torch.tensor([n + [PAD] * (MAXP - len(n))], device=dev)
            out = net(sf, nf).item() * SCALE
            print(f"PYEVAL {('w' if w else 'b')} {out * (1 if w else -1):+.1f}cp  {fen}", flush=True)


if __name__ == '__main__':
    main()
