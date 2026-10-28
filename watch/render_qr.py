from PIL import Image
import sys

src = open(sys.argv[1], encoding='utf-8').read()
lines = [ln for ln in src.splitlines() if ln.strip()]
H = len(lines)
W = max(len(ln) for ln in lines)

MOD = 12           # пикселей на модуль
QUIET = 4 * MOD    # тихая зона 4 модуля
imgW = W * MOD + 2 * QUIET
imgH = 2 * H * MOD + 2 * QUIET
img = Image.new('RGB', (imgW, imgH), 'white')
px = img.load()

def set_mod(x, y, black):
    if not black:
        return
    x0 = QUIET + x * MOD
    y0 = QUIET + y * MOD
    for dy in range(MOD):
        for dx in range(MOD):
            px[x0 + dx, y0 + dy] = (0, 0, 0)

for ly, ln in enumerate(lines):
    for cx, ch in enumerate(ln):
        top = bot = False
        if ch == '█':
            top = bot = True
        elif ch == '▀':
            top = True
        elif ch == '▄':
            bot = True
        # пробел и прочее — оба белые
        set_mod(cx, ly * 2, top)
        set_mod(cx, ly * 2 + 1, bot)

img.save(sys.argv[2])
print('saved', imgW, 'x', imgH)
