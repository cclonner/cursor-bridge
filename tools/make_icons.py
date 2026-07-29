from PIL import Image, ImageDraw, ImageFont
import os
import shutil

repo_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
out_dir = os.path.join(repo_dir, 'assets', 'icons')
os.makedirs(out_dir, exist_ok=True)

# По умолчанию: используем уже лежащие PNG в assets/icons.
# Перегенерация из ASCII-шаблонов включается только явно через env.
RENDER_PROPOSALS = os.environ.get('RENDER_PROPOSALS') == '1'

#
# ASCII-предложение для иконки:
# делаем именно логотип Cursor (cube), а не просто букву "C".
#
ART_A = [
    "         XXXX",
    "       XXXXXXXXX",
    "    XX...........X",
    "    X.###########X",
    "    XXX..#######.X",
    "    X.XXXX.####.XX",
    "    X......###.XXX",
    "    X......###...X",
    "     XX....##..XX",
    "        XX..XXX",
    "          XX",
]

def render(art, path, bg=(15, 23, 42), fg=(34, 197, 94), accent=(34, 211, 238), size=1024):
    img = Image.new('RGBA', (size, size), bg + (255,))
    draw = ImageDraw.Draw(img)
    step = size // 16
    for i in range(0, size, step):
        draw.line([(i, 0), (i, size)], fill=(30, 41, 59, 255), width=1)
        draw.line([(0, i), (size, i)], fill=(30, 41, 59, 255), width=1)
    rows = len(art)
    cols = max(len(r) for r in art)
    font = None
    for fp in [
        r'C:\Windows\Fonts\consola.ttf',
        r'C:\Windows\Fonts\cour.ttf',
        r'C:\Windows\Fonts\lucon.ttf',
    ]:
        if os.path.exists(fp):
            font = ImageFont.truetype(fp, size // (cols + 2))
            break
    if font is None:
        font = ImageFont.load_default()
    sample = 'M' * cols
    bbox = draw.textbbox((0, 0), sample, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    line_h = max(th + 4, size // (rows + 2))
    total_h = line_h * rows
    y0 = (size - total_h) // 2
    for i, row in enumerate(art):
        x = (size - tw) // 2
        for ch in row.ljust(cols):
            color = accent if ch in '#X█■' else fg
            if ch != ' ':
                draw.text((x, y0 + i * line_h), ch, font=font, fill=color + (255,))
            cb = draw.textbbox((0, 0), 'M' if ch == ' ' else ch, font=font)
            x += cb[2] - cb[0]
    img.save(path)
    print('wrote', path)


if RENDER_PROPOSALS:
    render(ART_A, os.path.join(out_dir, 'icon-proposal-A-ascii-C.png'))

# Default apply A to launcher mipmaps
if os.environ.get('SKIP_MIPMAPS') != '1':
    base = Image.open(os.path.join(out_dir, 'icon-proposal-A-ascii-C.png')).convert('RGBA')
    mip = {
        'mipmap-mdpi': 48,
        'mipmap-hdpi': 72,
        'mipmap-xhdpi': 96,
        'mipmap-xxhdpi': 144,
        'mipmap-xxxhdpi': 192,
    }
    res = os.path.join(repo_dir, 'android', 'app', 'src', 'main', 'res')
    for folder, px in mip.items():
        d = os.path.join(res, folder)
        os.makedirs(d, exist_ok=True)
        base.resize((px, px), Image.Resampling.LANCZOS).save(os.path.join(d, 'ic_launcher.png'))
        print('mip', folder, px)

print('done')
