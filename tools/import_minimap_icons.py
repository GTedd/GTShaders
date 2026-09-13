"""从用户提供的定位栏资源包提取原版生物头像，同时生成客户端与着色器共用精灵图。"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import zipfile
from PIL import Image
from mc_target import VERSION

ROOT = Path(__file__).resolve().parent.parent

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pack', type=Path)
    parser.add_argument('--vanilla', type=Path, default=Path.home()/'.gradle/caches/fabric-loom'/VERSION/'minecraft-client.jar')
    args = parser.parse_args()
    with zipfile.ZipFile(args.vanilla) as vanilla:
        language = json.loads(vanilla.read('assets/minecraft/lang/en_us.json'))
    names = {k.removeprefix('entity.minecraft.') for k in language if k.startswith('entity.minecraft.')}
    names.add('steve')
    output = ROOT/'src/main/resources/assets/gtshaders'
    sprites = output/'textures/gui/sprites/minimap/entity'
    sprites.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.pack) as source:
        candidates = {Path(n).stem:n for n in source.namelist() if '/locator_bar_dot/' in n and n.endswith('.png') and Path(n).stem in names}
        atlas = Image.new('RGBA',(256, ((len(candidates)+15)//16)*16))
        catalog = {}
        for index,name in enumerate(sorted(candidates)):
            raw = source.read(candidates[name])
            icon = Image.open(io.BytesIO(raw)).convert('RGBA')
            if max(icon.size)>16:
                raise ValueError(f'头像超出 16×16 槽位：{name}')
            (sprites/(name+'.png')).write_bytes(raw)
            atlas.alpha_composite(icon,((index%16)*16+(16-icon.width)//2,(index//16)*16+(16-icon.height)//2))
            catalog['minecraft:player' if name=='steve' else 'minecraft:'+name] = {'sprite':'gtshaders:minimap/entity/'+name,'slot':index}
        (output/'minimap').mkdir(exist_ok=True)
        (output/'minimap/icons.json').write_text(json.dumps({'schema':'gtminimap.icons.v1','cell':16,'columns':16,'icons':catalog},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        (output/'textures/effect').mkdir(exist_ok=True)
        atlas.save(output/'textures/effect/minimap_icons.png')
        original_meta = source.read(next(n for n in source.namelist() if n.endswith('pack.mcmeta'))).decode('utf-8')
    (output/'minimap/图标来源.txt').write_text('图标来自用户提供的 betterlocatorbar.zip，仅提取与原版实体 ID 对应的生物头像和史蒂夫头像，保留原始像素尺寸。\n'
        '未打包个人头像、原包定位栏逻辑或其旧版 pack.mcmeta。此来源记录不构成重新授权或原创声明。\n'
        '源文件 SHA-256：'+hashlib.sha256(args.pack.read_bytes()).hexdigest()+'\n原始元数据：\n'+original_meta,encoding='utf-8')
    print(f'已导入 {len(catalog)} 个实体头像；共享精灵图 {atlas.width}×{atlas.height}。')

if __name__=='__main__':
    main()
