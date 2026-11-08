"""Prepares character cut-outs for the TV: at most 1200 px tall, and any edge where the source
poster cropped the character (alpha touching the image border) feathered out, so no straight
cut lines show in the lineup.

usage: python3 -I prep_characters.py <tv-assets dir>
"""
import os
import sys

from PIL import Image, ImageChops

FEATHER = 0.14  # fraction of width/height


def ramp(size, length, horizontal, reverse):
    """An L-mode mask: 0 at the cropped edge rising to 255 over `length` pixels."""
    w, h = size
    m = Image.new('L', (w, h), 255)
    px = m.load()
    for i in range(length):
        v = int(255 * (i / length) ** 1.6)
        if horizontal:
            x = w - 1 - i if reverse else i
            for y in range(h):
                px[x, y] = min(px[x, y], v)
        else:
            y = h - 1 - i if reverse else i
            for x in range(w):
                px[x, y] = min(px[x, y], v)
    return m


def prep(src, dst):
    im = Image.open(src).convert('RGBA')
    if im.height > 1200:
        im = im.resize((int(im.width * 1200 / im.height), 1200), Image.LANCZOS)
    a = im.getchannel('A')
    w, h = im.size
    # The bottom edge is left alone: legs run off the screen and fade there anyway.
    edges = {
        'left': max(a.getpixel((0, y)) for y in range(0, h, 4)) > 128,
        'right': max(a.getpixel((w - 1, y)) for y in range(0, h, 4)) > 128,
        'top': max(a.getpixel((x, 0)) for x in range(0, w, 4)) > 128,
    }
    for edge, cut in edges.items():
        if not cut:
            continue
        if edge in ('left', 'right'):
            m = ramp((w, h), int(w * FEATHER), True, edge == 'right')
        else:
            m = ramp((w, h), int(h * FEATHER), False, False)
        a = ImageChops.multiply(a, m)
    im.putalpha(a)
    im.save(dst, optimize=True)
    return [e for e, c in edges.items() if c]


def main(root):
    src = os.path.join(root, 'characters')
    dst = os.path.join(root, 'tv', 'characters')
    os.makedirs(dst, exist_ok=True)
    for f in sorted(os.listdir(src)):
        if f.endswith('.png'):
            print(f, 'feathered:', prep(os.path.join(src, f), os.path.join(dst, f)) or '-')


if __name__ == '__main__':
    main(sys.argv[1])
