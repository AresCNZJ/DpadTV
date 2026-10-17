import re, json, sys
from PIL import Image
xml_path, png_path = sys.argv[1], sys.argv[2]
names = ["袋鼠资源","暴风资源","量子","红牛","360","个来源"]
xml = open(xml_path, encoding="utf-8", errors="ignore").read()
rects = []
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t = m.group(1)
    if any(n in t for n in names):
        x1, y1, x2, y2 = map(int, m.group(2,3,4,5))
        rects.append((x1-8, y1-4, x2+8, y2+4))
im = Image.open(png_path).convert("RGB")
for (x1,y1,x2,y2) in rects:
    x1,y1 = max(0,x1), max(0,y1); x2,y2 = min(im.width,x2), min(im.height,y2)
    if x2-x1 < 4 or y2-y1 < 4: continue
    box=(x1,y1,x2,y2); crop=im.crop(box)
    small=crop.resize((max(1,crop.width//14), max(1,crop.height//14)), Image.BILINEAR)
    im.paste(small.resize((crop.width, crop.height), Image.NEAREST), box)
im.save(png_path, optimize=True)
print(f"{png_path.split(chr(92))[-1]}: 打码 {len(rects)} 处 -> {im.size}")
