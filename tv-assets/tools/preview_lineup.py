"""Renders the My Favourites lineup from favourites.txt as the TV will lay it out (1920x1080).

usage: python3 -I preview_lineup.py <tv-assets dir> <out.png> [selected file name]
"""
import os
import sys

from PIL import Image, ImageDraw, ImageEnhance, ImageOps

W, H = 1920, 1080


def read(root):
    out = []
    for line in open(os.path.join(root, 'tv', 'favourites.txt'), encoding='utf-8'):
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        f = [p.strip() for p in line.split('|')]
        out.append(dict(file=f[0], title=f[1], app=f[2], link=f[3], row=f[4],
                        cx=float(f[5]), bottom=float(f[6]), h=float(f[7]), color=f[8]))
    return out


def main(root, out_path, selected=None):
    chars = read(root)
    canvas = Image.new('RGBA', (W, H), (0, 0, 0, 255))
    wall = os.path.join(root, 'tv', 'scenes', (selected or 'none') + '.png')
    if selected and os.path.exists(wall):
        canvas.paste(Image.open(wall).convert('RGBA'), (0, 0))
    order = [c for c in chars if c['row'] == 'back'] + [c for c in chars if c['row'] == 'front']
    if selected:
        sel = next(c for c in chars if c['file'] == selected)
        # A selected back-row character grows but stays behind the front row.
        order.remove(sel)
        idx = len([c for c in order if c['row'] == 'back']) if sel['row'] == 'back' else len(order)
        order.insert(idx, sel)
    for c in order:
        im = Image.open(os.path.join(root, "tv", "characters", c["file"] + ".png")).convert('RGBA')
        h = int(c['h'] * H * (1.06 if c['file'] == selected else 1))
        w = int(im.width * h / im.height)
        im = im.resize((w, h), Image.LANCZOS)
        if c['file'] != selected:
            alpha = im.getchannel('A')
            im = ImageOps.grayscale(im.convert('RGB')).convert('RGBA')
            im = ImageEnhance.Brightness(im).enhance(0.62 if selected else 0.8)
            im.putalpha(alpha)
        x = int(c['cx'] * W - w / 2)
        y = int(c['bottom'] * H - h)
        canvas.paste(im, (x, y), im)
    # Legs fade into black.
    fade = Image.new('L', (1, 256))
    for i in range(256):
        fade.putpixel((0, i), i)
    fade = fade.resize((W, int(H * 0.30)))
    black = Image.new('RGBA', (W, fade.height), (0, 0, 0, 255))
    canvas.paste(black, (0, H - fade.height), fade)
    if selected:
        d = ImageDraw.Draw(canvas)
        d.text((W // 2 - 100, 70), sel['title'], fill='white')
    canvas.convert('RGB').save(out_path, quality=88)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
