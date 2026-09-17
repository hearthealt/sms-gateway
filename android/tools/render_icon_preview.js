/**
 * 把 res/drawable/ic_launcher_{background,foreground}.xml 里的几何手绘成 PNG，
 * 用于在改图标之后肉眼确认它长什么样 —— 本机没有 ImageMagick / Inkscape / cairosvg。
 *
 * 做法：对每个像素算到图形骨架的距离场，小于半个描边宽就算被覆盖，
 * 边界按 1 个渲染像素羽化，于是圆头/圆角接头自然成立（距离场并集自带圆角）。
 *
 * 用法：node tools/render_icon_preview.js
 * 产物写到 tools/preview/ 下。
 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

// ---------------------------------------------------------------- PNG 编码
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

function encodePNG(w, h, rgba) {
  const stride = w * 4;
  const raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 6;  // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------- 几何
const RAD = Math.PI / 180;

/** 点到线段的距离（含端点，因此圆头线帽天然成立） */
function d2seg(px, py, ax, ay, bx, by) {
  const vx = bx - ax, vy = by - ay;
  const wx = px - ax, wy = py - ay;
  const L2 = vx * vx + vy * vy;
  let t = L2 === 0 ? 0 : (wx * vx + wy * vy) / L2;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
}

/** 点到圆弧的距离。角度用 SVG 约定（y 向下，atan2(dy,dx)）。超出弧段范围就取到端点的距离。 */
function d2arc(px, py, cx, cy, r, a0, a1) {
  const dx = px - cx, dy = py - cy;
  let rel = Math.atan2(dy, dx) - a0;
  while (rel < 0) rel += 2 * Math.PI;
  if (rel <= a1 - a0) return Math.abs(Math.hypot(dx, dy) - r);
  const p0x = cx + r * Math.cos(a0), p0y = cy + r * Math.sin(a0);
  const p1x = cx + r * Math.cos(a1), p1y = cy + r * Math.sin(a1);
  return Math.min(Math.hypot(px - p0x, py - p0y), Math.hypot(px - p1x, py - p1y));
}

// --- 前景：三道信号弧，圆心 (54,53)，200° -> 340°（y 向下即跨过正上方）
const ARC_CX = 54, ARC_CY = 53;
const ARC_A0 = 200 * RAD, ARC_A1 = 340 * RAD;

// --- 前景：信封主体圆角矩形 (37,57)-(71,76)，圆角 2.5，拆成 4 段直线 + 4 段圆角
function envelopeRectDist(px, py) {
  const segs = [
    [39.5, 57, 68.5, 57],
    [71, 59.5, 71, 73.5],
    [68.5, 76, 39.5, 76],
    [37, 73.5, 37, 59.5],
  ];
  const corners = [
    [68.5, 59.5, 2.5, -90 * RAD, 0],
    [68.5, 73.5, 2.5, 0, 90 * RAD],
    [39.5, 73.5, 2.5, 90 * RAD, 180 * RAD],
    [39.5, 59.5, 2.5, 180 * RAD, 270 * RAD],
  ];
  let d = Infinity;
  for (const [ax, ay, bx, by] of segs) d = Math.min(d, d2seg(px, py, ax, ay, bx, by));
  for (const [cx, cy, r, a0, a1] of corners) d = Math.min(d, d2arc(px, py, cx, cy, r, a0, a1));
  return d;
}

const PATHS = [
  { d: (x, y) => d2arc(x, y, ARC_CX, ARC_CY, 11, ARC_A0, ARC_A1), sw: 3.5, a: 1.00 },
  { d: (x, y) => d2arc(x, y, ARC_CX, ARC_CY, 17.5, ARC_A0, ARC_A1), sw: 3.5, a: 0.70 },
  { d: (x, y) => d2arc(x, y, ARC_CX, ARC_CY, 24, ARC_A0, ARC_A1), sw: 3.5, a: 0.50 },
  { d: envelopeRectDist, sw: 4, a: 1.00 },
  {
    d: (x, y) => Math.min(
      d2seg(x, y, 39.5, 59, 54, 70.5),
      d2seg(x, y, 54, 70.5, 68.5, 59),
    ),
    sw: 4, a: 1.00,
  },
];

// ---------------------------------------------------------------- 背景渐变
const STOPS = [
  [0.0, [0x2b, 0x6b, 0xd8]],
  [0.5, [0x3d, 0x8b, 0xee]],
  [1.0, [0x36, 0xd3, 0x99]],
];

function gradientAt(x, y) {
  // 线性渐变 (0,0) -> (108,108)：投影参数就是 (x+y)/216
  const t = Math.max(0, Math.min(1, (x + y) / 216));
  let i = 0;
  while (i < STOPS.length - 2 && t > STOPS[i + 1][0]) i++;
  const [t0, c0] = STOPS[i], [t1, c1] = STOPS[i + 1];
  const k = t1 === t0 ? 0 : (t - t0) / (t1 - t0);
  return [0, 1, 2].map((j) => Math.round(c0[j] + (c1[j] - c0[j]) * k));
}

// ---------------------------------------------------------------- 渲染
const S = 4;            // 超采样倍率
const N = 108 * S;      // 渲染边长
const MASK_R = 36;      // 圆形遮罩半径（自适应图标可见区是中央 72x72）

function render(masked) {
  const px = Buffer.alloc(N * N * 4);
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      const vx = (x + 0.5) / S, vy = (y + 0.5) / S;   // 视口坐标
      let [r, g, b] = gradientAt(vx, vy);

      for (const p of PATHS) {
        const alpha = Math.max(0, Math.min(1, ((p.sw / 2) - p.d(vx, vy)) * S + 0.5)) * p.a;
        if (alpha <= 0) continue;
        r = 255 * alpha + r * (1 - alpha);
        g = 255 * alpha + g * (1 - alpha);
        b = 255 * alpha + b * (1 - alpha);
      }

      let outA = 255;
      if (masked && Math.hypot(vx - 54, vy - 54) > MASK_R) outA = 0;

      const o = (y * N + x) * 4;
      px[o] = Math.round(r);
      px[o + 1] = Math.round(g);
      px[o + 2] = Math.round(b);
      px[o + 3] = outA;
    }
  }
  return px;
}

/** 盒式降采样，顺便得到类似真实显示尺寸下的观感 */
function downscale(px, factor) {
  const w = N / factor;
  const out = Buffer.alloc(w * w * 4);
  for (let y = 0; y < w; y++) {
    for (let x = 0; x < w; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let dy = 0; dy < factor; dy++) {
        for (let dx = 0; dx < factor; dx++) {
          const o = ((y * factor + dy) * N + (x * factor + dx)) * 4;
          r += px[o]; g += px[o + 1]; b += px[o + 2]; a += px[o + 3];
        }
      }
      const n = factor * factor;
      const o = (y * w + x) * 4;
      out[o] = Math.round(r / n);
      out[o + 1] = Math.round(g / n);
      out[o + 2] = Math.round(b / n);
      out[o + 3] = Math.round(a / n);
    }
  }
  return { buf: out, size: w };
}

const outDir = path.join(__dirname, 'preview');
fs.mkdirSync(outDir, { recursive: true });

const masked = render(true);
const unmasked = render(false);

for (const [src, tag] of [[masked, 'circle'], [unmasked, 'full']]) {
  for (const [factor, size] of [[4, 108], [9, 48]]) {
    const { buf, size: w } = downscale(src, factor);
    const file = path.join(outDir, `icon_${size}_${tag}.png`);
    fs.writeFileSync(file, encodePNG(w, w, buf));
    console.log('wrote', file);
  }
}
