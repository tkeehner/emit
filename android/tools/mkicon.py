import random, os
from fontTools.ttLib import TTCollection
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from PIL import Image, ImageDraw, ImageFont, ImageFilter
random.seed(7)
RES='app/src/main/res'
FONT='/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc'
tt=TTCollection(FONT).fonts[0]; gs=tt.getGlyphSet(); cmap=tt.getBestCmap(); upm=tt['head'].unitsPerEm
KATA="ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"
# columns of glyphs: (x, y, size, alpha, char) in 108 viewport
cells=[]
for col in range(10):
    x=2+col*10.8; head=random.uniform(45,118); n=random.randint(7,11)
    for k in range(n):
        y=head-k*10
        if y<-4 or y>108: continue
        a=max(0.08, 0.55*(1-k/n)) if k else 0.75
        cells.append((x,y,9,a,random.choice(KATA)))
def glyph_path(ch,x,y,size):
    g=cmap[ord(ch)]; pen=SVGPathPen(gs)
    s=size/upm
    # font y-up -> vector y-down; baseline at y
    tp=TransformPen(pen,(s,0,0,-s,x,y)); gs[g].draw(tp); return pen.getCommands()
os.makedirs(RES+'/drawable',exist_ok=True); os.makedirs(RES+'/mipmap-anydpi-v26',exist_ok=True)
def hexa(a,rgb='00FF41'): return '#%02X%s'%(int(a*255),rgb)
paths='\n'.join(f'    <path android:fillColor="{hexa(a*0.42)}" android:pathData="{glyph_path(c,x,y,s)}"/>' for x,y,s,a,c in cells)
open(RES+'/drawable/ic_launcher_background.xml','w').write(f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#000000" android:pathData="M0,0h108v108h-108z"/>
{paths}
</vector>
''')
BUBBLE="M36,33 h36 a6,6 0 0 1 6,6 v22 a6,6 0 0 1 -6,6 h-24 l-10,9 v-9 h-2 a6,6 0 0 1 -6,-6 v-22 a6,6 0 0 1 6,-6 z"
PROMPT="M39,43 L47,50 L39,57"; CURSOR="M51,57.5 L64,57.5"
def fg(glow, color="#FF00FF41"):
    g=''
    if glow:
        for w,a in ((9,0.12),(6,0.22)):
            for d in (BUBBLE,PROMPT,CURSOR):
                g+=f'    <path android:strokeColor="{hexa(a)}" android:strokeWidth="{w}" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}"/>\n'
    if glow: g+=f'    <path android:fillColor="#C8000000" android:pathData="{BUBBLE}"/>\n'
    for d,w in ((BUBBLE,3),(PROMPT,3.5),(CURSOR,3.5)):
        g+=f'    <path android:strokeColor="{color}" android:strokeWidth="{w}" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}"/>\n'
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
{g}</vector>
'''
open(RES+'/drawable/ic_launcher_foreground.xml','w').write(fg(True))
open(RES+'/drawable/ic_launcher_monochrome.xml','w').write(fg(False,"#FFFFFFFF"))
ad='''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
'''
open(RES+'/mipmap-anydpi-v26/ic_launcher.xml','w').write(ad); open(RES+'/mipmap-anydpi-v26/ic_launcher_round.xml','w').write(ad)

# ---- raster render (same geometry) at 108-unit scale
def render(px, mask=None):
    S=4; W=px*S; k=W/108
    img=Image.new('RGBA',(W,W),(0,0,0,255))
    font=ImageFont.truetype(FONT, int(9*k))
    lay=Image.new('RGBA',(W,W),(0,0,0,0)); d=ImageDraw.Draw(lay)
    for x,y,s,a,c in cells:
        d.text((x*k, y*k), c, font=font, fill=(0,255,65,int(a*0.42*255)), anchor='ls')
    img.alpha_composite(lay)
    def strokes(dr, w_add, col):
        # bubble as rounded rect + tail
        dr.rounded_rectangle([30*k,33*k,78*k,67*k], radius=6*k, outline=col, width=int((3+w_add)*k))
        dr.line([(48*k,67*k),(38*k,76*k),(38*k,67*k)], fill=col, width=int((3+w_add)*k), joint='curve')
        dr.line([(39*k,43*k),(47*k,50*k),(39*k,57*k)], fill=col, width=int((3.5+w_add)*k), joint='curve')
        dr.line([(51*k,57.5*k),(64*k,57.5*k)], fill=col, width=int((3.5+w_add)*k))
    glow=Image.new('RGBA',(W,W),(0,0,0,0)); strokes(ImageDraw.Draw(glow),4,(0,255,65,150))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(3*k)))
    fgl=Image.new('RGBA',(W,W),(0,0,0,0)); dd=ImageDraw.Draw(fgl)
    dd.rounded_rectangle([30*k,33*k,78*k,67*k], radius=6*k, fill=(0,0,0,200))
    strokes(dd,0,(0,255,65,255)); img.alpha_composite(fgl)
    # crop to the visible 72/108 legacy area like launchers do
    c0=int(W*18/108); img=img.crop((c0,c0,W-c0,W-c0)).resize((px,px),Image.LANCZOS)
    if mask=='round':
        m=Image.new('L',(px*4,px*4),0); ImageDraw.Draw(m).ellipse([0,0,px*4-1,px*4-1],fill=255); m=m.resize((px,px),Image.LANCZOS)
    else:
        m=Image.new('L',(px*4,px*4),0); ImageDraw.Draw(m).rounded_rectangle([0,0,px*4-1,px*4-1],radius=px*4*0.18,fill=255); m=m.resize((px,px),Image.LANCZOS)
    img.putalpha(m); return img
for dens,px in (('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)):
    os.makedirs(f'{RES}/mipmap-{dens}',exist_ok=True)
    render(px).save(f'{RES}/mipmap-{dens}/ic_launcher.png'); render(px,'round').save(f'{RES}/mipmap-{dens}/ic_launcher_round.png')
# preview: big square-ish + round + small on dark card
big=render(512); rnd=render(256,'round'); sm=render(96)
pv=Image.new('RGBA',(960,600),(18,18,18,255)); pv.alpha_composite(big,(40,44)); pv.alpha_composite(rnd,(620,60)); pv.alpha_composite(sm,(700,400))
d=ImageDraw.Draw(pv); f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf',28)
d.text((600,530),"Emit",font=f,fill=(0,255,65,255))
os.makedirs('dist',exist_ok=True); pv.convert('RGB').save('dist/icon-preview.png'); print(len(cells),'glyphs')
