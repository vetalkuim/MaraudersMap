#!/usr/bin/env python3
"""Генерирует фон «старый пергамент» (bg_parchment.webp) для живых обоев.

Ровный лист без рваных краёв, 9:19.5: кремовая основа, размытые чайные пятна,
светлые потёртости, пятнистость, зерно и волокна бумаги, едва заметные заломы
у краёв и сепиевая виньетка. Центр оставлен спокойным, чтобы рисунки поверх читались.

Использование:  python3 tools/generate_parchment.py [выходной_файл] [--seed N]
Зависимости: numpy, Pillow.
"""
import argparse
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1080, 2340  # 9:19.5

BASE = (0xE6, 0xD5, 0xB0)
STAIN = (0x96, 0x6E, 0x3C)
LIGHT = (0xFA, 0xF0, 0xD7)
SEPIA = (0x3B, 0x2A, 0x14)


def rgb(c):
    return np.array(c, dtype=np.float32) / 255.0


def noise(rng, cell, octaves=1, persistence=0.5):
    """Фрактальный шум значений в [0, 1]: случайные сетки, растянутые бикубически."""
    total = np.zeros((H, W), dtype=np.float32)
    amp, norm = 1.0, 0.0
    for _ in range(octaves):
        gw, gh = max(2, W // cell + 2), max(2, H // cell + 2)
        grid = Image.fromarray(rng.random((gh, gw), dtype=np.float32), mode="F")
        total += amp * np.asarray(grid.resize((W, H), Image.BICUBIC), dtype=np.float32)
        norm += amp
        amp *= persistence
        cell = max(1, cell // 2)
    total /= norm
    lo, hi = np.percentile(total, 0.5), np.percentile(total, 99.5)
    return np.clip((total - lo) / (hi - lo), 0, 1)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def blur(a, sigma):
    """Раздельное гауссово размытие float-массива (Pillow не размывает режим F)."""
    r = max(1, int(sigma * 3))
    k = np.exp(-(np.arange(-r, r + 1, dtype=np.float32) ** 2) / (2 * sigma * sigma))
    k /= k.sum()
    for axis in (0, 1):
        p = np.pad(a, [(r, r) if i == axis else (0, 0) for i in range(2)], mode="reflect")
        n = a.shape[axis]
        a = sum(w * np.take(p, np.arange(i, i + n), axis=axis) for i, w in enumerate(k))
    return a.astype(np.float32)


def mix(img, color, alpha):
    a = alpha[..., None]
    return img * (1 - a) + rgb(color) * a


def blotches(rng, count, calm):
    """Отдельные чайные пятна с тёмным ободком, в основном вне центра."""
    mask = np.zeros((H, W), dtype=np.float32)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    wobble = noise(rng, 60, octaves=3)
    placed = 0
    while placed < count:
        cx, cy = rng.uniform(0, W), rng.uniform(0, H)
        if calm[int(cy), int(cx)] > 0.3:
            continue  # центр держим чистым
        r = rng.uniform(25, 140)
        sx, sy = rng.uniform(0.7, 1.3), rng.uniform(0.7, 1.3)
        d = np.sqrt(((xx - cx) / (r * sx)) ** 2 + ((yy - cy) / (r * sy)) ** 2)
        d += (wobble - 0.5) * 0.45
        body = 1 - smoothstep(0.55, 1.0, d)
        ring = np.exp(-((d - 0.97) ** 2) / 0.004) * rng.uniform(0.4, 0.9)
        strength = rng.uniform(0.25, 0.6)
        mask = np.maximum(mask, (body * 0.45 + ring) * strength)
        placed += 1
    return blur(mask, 3)


def fibers(rng, count):
    """Тонкие волокна бумаги: короткие изогнутые штрихи, чуть светлее или темнее."""
    dark = Image.new("L", (W, H), 0)
    light = Image.new("L", (W, H), 0)
    dd, dl = ImageDraw.Draw(dark), ImageDraw.Draw(light)
    for _ in range(count):
        x, y = rng.uniform(0, W), rng.uniform(0, H)
        ang = rng.uniform(0, math.pi)
        length = rng.uniform(8, 45)
        pts = []
        for i in range(6):
            ang += rng.normal(0, 0.25)
            x += math.cos(ang) * length / 6
            y += math.sin(ang) * length / 6
            pts.append((x, y))
        target = dd if rng.random() < 0.6 else dl
        target.line(pts, fill=int(rng.uniform(60, 160)), width=1)
    d = np.asarray(dark.filter(ImageFilter.GaussianBlur(0.6)), dtype=np.float32) / 255
    l = np.asarray(light.filter(ImageFilter.GaussianBlur(0.6)), dtype=np.float32) / 255
    return d, l


def creases(rng, edge):
    """Едва заметные заломы: пара светлая/тёмная линия, только у краёв листа."""
    dark = Image.new("L", (W, H), 0)
    light = Image.new("L", (W, H), 0)
    dd, dl = ImageDraw.Draw(dark), ImageDraw.Draw(light)
    for _ in range(7):
        side = rng.integers(4)
        if side == 0:
            p0 = (rng.uniform(0, W), 0)
        elif side == 1:
            p0 = (rng.uniform(0, W), H)
        elif side == 2:
            p0 = (0, rng.uniform(0, H))
        else:
            p0 = (W, rng.uniform(0, H))
        ang = math.atan2(H / 2 - p0[1], W / 2 - p0[0]) + rng.normal(0, 0.5)
        length = rng.uniform(150, 420)
        pts, (x, y) = [p0], p0
        for _ in range(12):
            ang += rng.normal(0, 0.05)
            x += math.cos(ang) * length / 12
            y += math.sin(ang) * length / 12
            pts.append((x, y))
        dd.line(pts, fill=150, width=2)
        dl.line([(px + 2, py + 1) for px, py in pts], fill=170, width=2)
    d = np.asarray(dark.filter(ImageFilter.GaussianBlur(1.2)), dtype=np.float32) / 255
    l = np.asarray(light.filter(ImageFilter.GaussianBlur(1.5)), dtype=np.float32) / 255
    return d * edge, l * edge


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", nargs="?", default="app/src/main/res/drawable-nodpi/bg_parchment.webp")
    ap.add_argument("--seed", type=int, default=1337)
    args = ap.parse_args()
    rng = np.random.default_rng(args.seed)

    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    nx, ny = (xx / W - 0.5) * 2, (yy / H - 0.5) * 2
    # Спокойная центральная зона (эллипс) и близость к краям.
    calm = 1 - smoothstep(0.35, 0.8, np.sqrt(nx ** 2 + ny ** 2))
    edge = smoothstep(0.55, 1.0, np.sqrt((nx ** 4 + ny ** 4) / 2) ** 0.5 * 1.19)

    img = np.broadcast_to(rgb(BASE), (H, W, 3)).copy()

    # Крупная пятнистость и обесцвечивание.
    mottle = noise(rng, 220, octaves=4) - 0.5
    img *= (1 + mottle * 0.07)[..., None]
    fine = noise(rng, 24, octaves=3) - 0.5
    img *= (1 + fine * 0.035)[..., None]

    # Размытые чайные разводы, затухающие в бумагу; в центре почти нет.
    s = noise(rng, 380, octaves=5, persistence=0.55)
    stain_a = smoothstep(0.45, 1.0, s) * 0.32 * (1 - calm * 0.8)
    img = mix(img, STAIN, stain_a)

    # Отдельные пятна с ободком.
    img = mix(img, STAIN, blotches(rng, 12, calm) * 0.6)

    # Светлые потёртости.
    l = noise(rng, 200, octaves=5)
    img = mix(img, LIGHT, smoothstep(0.65, 1.0, l) * 0.25)

    # Износ у краёв.
    wear = noise(rng, 120, octaves=4)
    img = mix(img, STAIN, edge * smoothstep(0.2, 1.0, wear) * 0.2)

    # Заломы.
    cd, cl = creases(rng, smoothstep(0.35, 0.9, np.maximum(np.abs(nx), np.abs(ny))))
    img = mix(img, STAIN, cd * 0.25)
    img = mix(img, LIGHT, cl * 0.35)

    # Волокна.
    fd, fl = fibers(rng, 9000)
    img = mix(img, STAIN, fd * 0.18)
    img = mix(img, LIGHT, fl * 0.22)

    # Мелкое зерно бумаги.
    grain = blur(rng.random((H, W), dtype=np.float32), 0.7) - 0.5
    img += (grain * 0.09)[..., None]

    # Сепиевая виньетка к углам.
    r = np.sqrt((nx * 0.95) ** 2 + (ny * 0.9) ** 2) / math.sqrt(2)
    vig = smoothstep(0.3, 1.0, r) ** 1.3 * 0.85
    # Умножение, а не смешивание: углы темнеют в тёплую сепию, а не в серый.
    tint = 1 - (1 - rgb(SEPIA) / rgb(BASE))
    img *= 1 - vig[..., None] * (1 - tint)

    out = Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8), mode="RGB")
    out.save(args.out, "WEBP", quality=88, method=6)
    print(f"saved {args.out} {out.size}")


if __name__ == "__main__":
    main()
