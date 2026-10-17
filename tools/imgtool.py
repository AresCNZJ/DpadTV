from PIL import Image, ImageFilter
import os, json, sys
outdir = r"D:\Develop\chat\dpadtv-native\docs"

def fix_alipay():
    src = r"D:\33178\Desktop\Picsew_20261017193444.jpeg"
    im = Image.open(src).convert("RGB"); g = im.convert("L"); w,h = g.size; px = g.load()
    y0 = int(h*0.40)
    minx,miny,maxx,maxy = w,h,0,0
    for y in range(y0,h,2):
        for x in range(0,w,2):
            if px[x,y] < 60:
                minx=min(minx,x); miny=min(miny,y); maxx=max(maxx,x); maxy=max(maxy,y)
    m = int(max(maxx-minx, maxy-miny) * 0.06) + 16   # 留足边距，避免再次切到码体
    box=(max(0,minx-m),max(0,miny-m),min(w,maxx+m),min(h,maxy+m))
    im2=im.crop(box)
    if im2.width>420: im2=im2.resize((420,int(im2.height*420/im2.width)),Image.LANCZOS)
    im2.save(os.path.join(outdir,"donate-alipay.png"))
    print("alipay:", box, "->", im2.size)

def blur_rects(png, rects):
    """把给定矩形区域做马赛克，返回处理后的图片"""
    im = Image.open(png).convert("RGB")
    for (x1,y1,x2,y2) in rects:
        x1,y1 = max(0,x1), max(0,y1)
        x2,y2 = min(im.width,x2), min(im.height,y2)
        if x2-x1 < 4 or y2-y1 < 4: continue
        box = (x1,y1,x2,y2)
        crop = im.crop(box)
        small = crop.resize((max(1,crop.width//12), max(1,crop.height//12)), Image.BILINEAR)
        im.paste(small.resize((crop.width, crop.height), Image.NEAREST), box)
    im.save(png, optimize=True)
    print("masked", os.path.basename(png), len(rects), "rects ->", im.size)

if __name__ == "__main__":
    if sys.argv[1] == "qr": fix_alipay()
    elif sys.argv[1] == "mask":
        png = sys.argv[2]; rects = json.loads(sys.argv[3]); blur_rects(png, rects)
