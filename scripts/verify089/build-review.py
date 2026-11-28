#!/usr/bin/env python3
"""Build a self-contained review using provided JPEG crops, NOT a game screenshot renderer.
Usage: python3 scripts/verify089/build-review.py /path/to/original/uploads
Original images are not shipped in runtime resources.
"""
from pathlib import Path
from PIL import Image
import base64,io,json,hashlib,sys,html
R=Path(__file__).resolve().parents[2];U=Path(sys.argv[1]) if len(sys.argv)>1 else R.parent/'uploads'
spec=json.loads((R/'licenses/glass089/reference-spec.json').read_text())
for item in spec['source_images']:
 p=U/item['file'];assert hashlib.sha256(p.read_bytes()).hexdigest()==item['sha256'],p

def uri(im):
 b=io.BytesIO();im.save(b,format='PNG');return 'data:image/png;base64,'+base64.b64encode(b.getvalue()).decode()
rows=[]
items=[('target','TARGET · 目标卡片','宽弯曲反射、近竖直亮侧线、右下外弧。只对齐材质，不复制原图中的头像/名字。',368,106),
 ('counter','COUNTER · 方块计数','细 S 形亮缝与左侧柔光折面；中间文字遮挡的轨迹不是原始可观测信息。',331,96),
 ('clickgui','PANEL · ClickGUI 长条','高透面板、顶部乳白薄层、右上局部回折。右图棋盘是合成背景，不是游戏世界。',250,666),
 ('music','SOFT · 音乐等卡片','低强度下沿柔光。没有把目标卡片的强反射重复贴到每个 HUD。此项尺寸按比例缩放。',370,140)]
for key,title,note,w,h in items:
 reg=spec['regions'][key];im=Image.open(U/reg['file']);x0,y0,x1,y1=reg['box']
 # Crop one material body with 26 output-pixel surroundings to retain the detached arc.
 sx,sy=(x1-x0)/w,(y1-y0)/h
 im=im.crop((round(x0-26*sx),round(y0-26*sy),round(x1+26*sx),round(y1+26*sy)))
 im=im.resize((w+52,h+52),Image.Resampling.LANCZOS)
 outname={'clickgui':'panel','music':'soft'}.get(key,key)
 sample=Image.open(R/f'scripts/verify089/offscreen-{outname}.png')
 rows.append(f'''<section><h2>{html.escape(title)}</h2><p>{html.escape(note)}</p><div class="pair"><figure><figcaption>用户原图局部 · 含原场景与原 UI</figcaption><img src="{uri(im)}" alt="用户原始JPEG中的{key}局部"></figure><figure><figcaption>089 生产 shader 离屏材质 · 非游戏截图</figcaption><img src="{uri(sample)}" alt="089着色器离屏{key}材质，不含游戏内容"></figure></div><small>来源：{reg['file']}，原始框 {reg['box']}。左右背景和文字不同，不能作整图像素差验收。</small></section>''')
page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>089 参考玻璃重建对照</title><style>
*{box-sizing:border-box}body{margin:0;background:#111820;color:#e4eaf1;font:15px/1.7 system-ui,sans-serif}main{max-width:1060px;margin:auto;padding:36px 24px 70px}h1{font-size:30px;line-height:1.25;margin:8px 0 20px}h2{font-size:19px;margin:0 0 8px}p{color:#a9b7c8;margin:0 0 20px}.tag{font-size:12px;color:#ffc6d8;letter-spacing:.08em}.warning{border-left:3px solid #ffcb7a;background:#2b2621;padding:15px 20px;border-radius:4px;margin:20px 0 30px}.warning strong{color:#ffdcab}.warning p{margin:6px 0 0;color:#d6cabb}section{padding:25px;margin:20px 0;border:1px solid #2c3743;border-radius:16px;background:#18212b}.pair{display:grid;grid-template-columns:1fr 1fr;gap:20px;align-items:start}figure{margin:0;background:#202c38;padding:12px;border-radius:10px;overflow:auto}figcaption{font-size:12px;color:#b7c5d7;margin-bottom:12px}img{display:block;max-width:100%;height:auto;margin:auto}small{display:block;color:#7f92a6;font-size:11px;margin-top:16px}footer{margin-top:30px;color:#91a5ba}li{margin:7px 0}code{background:#243240;padding:1px 5px;border-radius:3px}@media(max-width:680px){.pair{grid-template-columns:1fr}main{padding:24px 14px}section{padding:17px}h1{font-size:26px}}
</style><main><span class="tag">089 / REFERENCE-TRACED MATERIAL / SOURCE CANDIDATE</span><h1>光污染玻璃：原图局部与重建材质</h1><p>分开重做长条面板、目标卡片、细亮缝计数卡片和柔反射卡片；不再共用灰底白边。</p><div class="warning"><strong>右侧全部是实际生产 GLSL 的 EGL 离屏输出，不是 Minecraft 截图。</strong><p>没有当前游戏同场景基线；尚未完成游戏内逐像素验收。这里只用于检查反射形状，不能证明颜色、模糊、字体、布局或动态效果一致。文字与皮肤刻意未画入离屏样本。</p></div>'''+''.join(rows)+'''<footer><h2>检查范围</h2><ul><li>材质相关代码隔离编译、生产顶点传输、HUD 作用域/路由与离屏 shader 检查通过；不是完整 Gradle / Mixin / Android 验收。</li><li>13 条参与拟合的局部反射轨迹，在离屏样本中的亮峰位置最大偏差为原图坐标 2 像素；不是整图误差，也不是独立验证集。</li><li>088 MVP、087 编译修复、刀模和原生鼠标相关基线代码均未修改。</li><li>此对照页内的原图裁剪只用于评审，不会成为游戏运行时的玻璃贴图。</li></ul><p>完整状态说明：089-GLASS-REFERENCE-REBUILD.md。源码样本：scripts/verify089/。逐图 SHA256 与轨迹：licenses/glass089/reference-spec.json。</p></footer></main></html>'''
(R/'089-GLASS-REFERENCE-REVIEW.html').write_text(page)
print('Built labelled review HTML; all6 supplied original JPEG SHA256 locks verified; runtime resources contain no screenshot copies')
