// Build script for the committed crumpled-paper texture tiles. Dependency-free
// (Node zlib + hand-rolled CRC32/PNG). Run from the repo root:
//
//   node scripts/gen-paper-textures.js
//
// Outputs two SEAMLESS (periodic-lattice) grayscale tiles into the web assets:
//   crumple-height.png — the panels' heightmap. Full-range; fed to
//                        feDiffuseLighting (#crumple-hard/-soft in HtmlPage) for
//                        directional "notebook crumple". 80px base folds.
//   clip-grain.png     — the newspaper cuttings' grain. Mostly-light, soft,
//                        low-frequency; VALUE-blended (background-blend-mode,
//                        --clip-blend) onto the cutting colour. A full-range
//                        tile would scorch light paper, hence the high floor.
//
// Both wrap exactly at the tile edge (lattice taken mod an integer cell count),
// so background-repeat / SVG <pattern> tiling shows no seam.
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

const OUT_DIR = path.join(__dirname, '..', 'gateway', 'src', 'main', 'resources', 'web');
const T = 640; // tile size (px)

// deterministic lattice value in [0,1] for cell (i,j) at octave o
function hash(i, j, o) {
  let h = (Math.imul(i, 374761393) + Math.imul(j, 668265263) + Math.imul(o + 1, 2147483647)) >>> 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177) >>> 0;
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}
// periodic value noise: corners taken mod `cells` so it tiles
function vnoise(x, y, cells, o) {
  const gx = x / T * cells, gy = y / T * cells;
  const x0 = Math.floor(gx), y0 = Math.floor(gy);
  const fx = gx - x0, fy = gy - y0;
  const sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy); // smoothstep
  const i0 = ((x0 % cells) + cells) % cells, i1 = (i0 + 1) % cells;
  const j0 = ((y0 % cells) + cells) % cells, j1 = (j0 + 1) % cells;
  const a = hash(i0, j0, o) + (hash(i1, j0, o) - hash(i0, j0, o)) * sx;
  const b = hash(i0, j1, o) + (hash(i1, j1, o) - hash(i0, j1, o)) * sx;
  return a + (b - a) * sy;
}
function fbm(x, y, baseCells, oct) {
  let v = 0, amp = 1, sum = 0;
  for (let o = 0; o < oct; o++) { v += amp * vnoise(x, y, baseCells << o, o); sum += amp; amp *= 0.5; }
  return v / sum; // [0,1]
}

// PNG (8-bit grayscale) encode
const crcTable = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1); t[n] = c >>> 0; } return t; })();
const crc32 = b => { let c = 0xFFFFFFFF; for (let i = 0; i < b.length; i++) c = crcTable[(c ^ b[i]) & 0xFF] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0; };
function chunk(type, data) { const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0); const tb = Buffer.from(type, 'ascii'); const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([tb, data])), 0); return Buffer.concat([len, tb, data, crc]); }
function writePng(file, baseCells, oct, lo, hi) {
  const raw = Buffer.alloc((T + 1) * T);
  for (let y = 0; y < T; y++) {
    raw[y * (T + 1)] = 0; // filter: none
    for (let x = 0; x < T; x++) {
      raw[y * (T + 1) + 1 + x] = Math.max(0, Math.min(255, Math.round(lo + fbm(x, y, baseCells, oct) * (hi - lo))));
    }
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(T, 0); ihdr.writeUInt32BE(T, 4); ihdr[8] = 8; ihdr[9] = 0;
  const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
  fs.writeFileSync(path.join(OUT_DIR, file), png);
  console.log(`wrote ${file} ${T}x${T} ${png.length} bytes`);
}

// panels: full-range coarse crumple (fed to feDiffuseLighting)
writePng('crumple-height.png', 8, 3, 0, 255);
// cuttings: mostly-light, soft, low-frequency grain (value-blended)
writePng('clip-grain.png', 10, 2, 230, 255);
