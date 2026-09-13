"""生成可直接安装的原版小地图复原包（目标版本见 gradle.properties，无客户端模组）。

python tools/build_minimap_reproduction.py
依赖 Pillow；中文字体可通过 --font 指定。输出同一个 ZIP 可分别安装为数据包和资源包。
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import zipfile
from PIL import Image, ImageDraw, ImageFont
from mc_target import VERSION, RESOURCE_FORMAT, DATA_FORMAT

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'src/main/resources/assets/gtshaders/portable_v2'
SLOTS = 120
DEFAULT_OUTPUT = ROOT / f'build/distributions/GTMinimap-原版复原版-{VERSION}.zip'
LABELS = ['原版小地图', '体素光追', '横坐标', '高度', '纵坐标', '朝向', '种子', '猪', '路点', '米', '北', '南', '西', '东', '暂无路点', '度', '程序地形', '真实底图', '未载入底图', '地表地图']


def dumps(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':'))


def build(font_path, output, atlas_path=None, meta_path=None, classic=False):
    files = {}

    def put(name, content):
        files[name] = content.encode('utf-8') if isinstance(content, str) else content

    def js(name, obj):
        put(name, dumps(obj))

    def fn(name, lines):
        put(f'data/gtminimap/function/{name}.mcfunction', '\n'.join(lines) + '\n')

    def tell(text, color='aqua'):
        return 'tellraw @s ' + dumps({'text': text, 'color': color})

    # Shared root pack metadata deliberately spans the exact resource/data formats.
    # Both registries must parse this same file in the vanilla integration test.
    js('pack.mcmeta', {'pack': {'description': '原版小地图｜猪群追踪 · 射箭路点 · 体素光追｜两处安装', 'min_format': RESOURCE_FORMAT, 'max_format': DATA_FORMAT}})
    js('data/minecraft/tags/function/load.json', {'values': ['gtminimap:load']})
    js('data/minecraft/tags/function/tick.json', {'values': ['gtminimap:tick']})
    icons_root = ROOT / 'src/main/resources/assets/gtshaders'
    catalog = json.loads((icons_root/'minimap/icons.json').read_text('utf-8'))
    icons_png = (icons_root/'textures/effect/minimap_icons.png').read_bytes()
    icons_image = Image.open(io.BytesIO(icons_png))
    put('assets/gtminimap/textures/effect/icons.png', icons_png)
    js('图标协议.json', catalog)
    put('图标来源.txt', (icons_root/'minimap/图标来源.txt').read_text('utf-8'))
    for sprite in (icons_root/'textures/gui/sprites/minimap/entity').glob('*.png'):
        put('assets/gtshaders/textures/gui/sprites/minimap/entity/'+sprite.name,sprite.read_bytes())

    chars = ''.join(dict.fromkeys('0123456789-+. ' + ''.join(LABELS)))
    font = ImageFont.truetype(str(font_path), 15)
    glyphs = Image.new('RGBA', (256, ((len(chars) + 15) // 16) * 16))
    draw = ImageDraw.Draw(glyphs)
    for i, char in enumerate(chars):
        draw.text(((i % 16) * 16, (i // 16) * 16 - 2), char, font=font, fill='white', stroke_width=0)
    png = io.BytesIO()
    glyphs.save(png, 'PNG')
    put('assets/gtminimap/textures/effect/font.png', png.getvalue())
    lookup = {char: i for i, char in enumerate(chars)}
    labels = 'float word(vec2 p, int id) {\n'
    for i, word in enumerate(LABELS):
        indices = ','.join(str(lookup[c]) for c in word)
        labels += f'if(id=={i}){{if(p.x<0.0||p.x>={len(word)*16}.0||p.y<0.0||p.y>=16.0)return 0.0; int a[{len(word)}]=int[]({indices}); int k=int(p.x)/16;return glyph(p-vec2(k*16,0),a[k]);}}\n'
    labels += 'return 0.0; }\n'
    for p in SOURCE.iterdir():
        if p.suffix in ('.fsh', '.vsh', '.glsl'):
            text = p.read_text('utf-8').replace('// GENERATED_LABELS', labels)
            text = text.replace('// ICON_CONSTANTS', '#define PIG_ICON_SLOT ' + str(catalog['icons']['minecraft:pig']['slot']))
            if classic and p.name == 'view.fsh':
                text = text.replace('#version 330', '#version 330\n#define CLASSIC_MAP 1')
            folder = 'include' if p.suffix == '.glsl' else 'post'
            put(f'assets/gtminimap/shaders/{folder}/{p.name}', text)

    if atlas_path:
        atlas = Image.open(atlas_path).convert('RGBA')
        meta = json.loads(meta_path.read_text('utf-8'))
        atlas_present = 1
    else:
        atlas = Image.new('RGBA', (1, 1), (24, 35, 45, 255))
        meta = {'origin': [0, 0], 'blocksPerPixel': 1, 'dimension': 'minecraft:overworld'}
        atlas_present = int(classic)  # Authoring template; Java replaces this placeholder before export.
    if not (1 <= atlas.width <= 4096 and 1 <= atlas.height <= 4096):
        raise ValueError('底图尺寸必须在 1 至 4096 之间')
    if not (1 <= meta['blocksPerPixel'] <= 16):
        raise ValueError('底图像素间隔不合法')
    if any(abs(x) > 30_000_000 for x in meta['origin']):
        raise ValueError('底图原点超出世界范围')
    png = io.BytesIO()
    atlas.save(png, 'PNG')
    put('assets/gtminimap/textures/effect/terrain.png', png.getvalue())

    def uniform(name, type_, value):
        return {'name': name, 'type': type_, 'value': value}

    def target(name, sampler):
        return {'sampler_name': sampler, 'target': name}

    def texture(name, sampler, width, height):
        return {'sampler_name': sampler, 'location': name, 'width': width, 'height': height}

    def effect_pass(shader, inputs, out, uniforms=None, vertex='minecraft:core/screenquad'):
        obj = {'vertex_shader': vertex, 'fragment_shader': 'gtminimap:post/' + shader, 'inputs': inputs, 'output': out}
        if uniforms:
            obj['uniforms'] = uniforms
        return obj

    def blit(source, destination):
        return {'vertex_shader': 'minecraft:core/screenquad', 'fragment_shader': 'minecraft:post/blit',
                'inputs': [target(source, 'In')], 'output': destination,
                'uniforms': {'BlitConfig': [uniform('ColorModulate', 'vec4', [1, 1, 1, 1])]}}

    js('assets/gtminimap/post_effect/reset.json', {'passes': [effect_pass('reset', [], 'minecraft:main', vertex='gtminimap:post/reset')]})
    for slot in range(SLOTS):
        for value in range(1, 16):
            js(f'assets/gtminimap/post_effect/w/{slot}_{value}.json', {'passes': [effect_pass('write', [], 'minecraft:main',
                {'Signal': [uniform('Slot', 'int', slot), uniform('Value', 'int', value)]}, vertex='gtminimap:post/write')]})
    atlas_config = [uniform('OriginX', 'int', meta['origin'][0]), uniform('OriginZ', 'int', meta['origin'][1]),
                    uniform('Step', 'float', meta['blocksPerPixel']), uniform('HasAtlas', 'int', atlas_present)]
    js('assets/gtminimap/post_effect/view.json', {'targets': {
        'orientation': {'width': 2, 'height': 1, 'persistent': True, 'clear_color': [0, 0, 0, 0]},
        'new_orientation': {'width': 2, 'height': 1}, 'rays': {'width': 640, 'height': 360}, 'swap': {}},
        'passes': [
            effect_pass('orientation', [target('minecraft:main', 'In'), target('orientation', 'Previous')], 'new_orientation'),
            blit('new_orientation', 'orientation'),
            effect_pass('raytrace', [target('minecraft:main', 'In'), target('new_orientation', 'Orientation')], 'rays'),
            effect_pass('view', [target('minecraft:main', 'In'), target('new_orientation', 'Orientation'), target('rays', 'Rays'),
                        texture('gtminimap:font', 'Font', glyphs.width, glyphs.height), texture('gtminimap:terrain', 'Atlas', atlas.width, atlas.height),
                        texture('gtminimap:icons', 'Icons', icons_image.width, icons_image.height)],
                        'swap', {'AtlasConfig': atlas_config}),
            blit('swap', 'minecraft:main')]})

    objectives = {'gtm': 'trigger', 'gtm_seed': 'trigger', 'gtm_tmp': 'dummy', 'gtm_epoch': 'dummy', 'gtm_live': 'dummy',
                  'gtm_on': 'dummy', 'gtm_ray': 'dummy', 'gtm_head': 'dummy', 'gtm_zoom': 'dummy', 'gtm_s': 'dummy',
                  'gtm_wx': 'dummy', 'gtm_wz': 'dummy', 'gtm_wdim': 'dummy', 'gtm_wp': 'dummy', 'gtm_fov': 'dummy', 'gtm_atlas': 'dummy',
                  'gtm_dim': 'dummy', 'gtm_seen': 'dummy', 'gtm_grace': 'dummy', 'gtm_pigs': 'dummy'}
    objectives.update({f'gtm_o{i}': 'dummy' for i in range(SLOTS)})
    load = ['data remove storage gtminimap:state stopped']
    load += [f'scoreboard objectives add {name} {kind}' for name, kind in objectives.items()]
    load += ['scoreboard objectives modify gtm displayname ' + dumps({'text': '小地图操作'}),
             'scoreboard objectives modify gtm_seed displayname ' + dumps({'text': '地图种子'})]
    load += ['scoreboard players add #epoch gtm_tmp 1']
    for key, value in {'#16': 16, '#8': 8, '#3600': 3600, '#65536': 65536, '#30000000': 30000000, '#atlas': atlas_present}.items():
        load += [f'scoreboard players set {key} gtm_tmp {value}']
    fn('load', load)
    defaults = {'gtm_on': 1, 'gtm_ray': 0, 'gtm_head': 0, 'gtm_zoom': 1, 'gtm_s': 42, 'gtm_wp': 0, 'gtm_fov': 0, 'gtm_atlas': int(classic), 'gtm_pigs': 1}
    fn('join', [f'execute unless score @s {k} matches 0.. run scoreboard players set @s {k} {v}' for k, v in defaults.items()] +
       [f'posteffect remove @s gtminimap:map_{radius}' for radius in (32,64,128,256)] +
       ['function gtminimap:remove', 'scoreboard players operation @s gtm_epoch = #epoch gtm_tmp', 'function gtminimap:menu'])
    tick = ['scoreboard players add #clock gtm_tmp 1']
    for dim in ('overworld', 'the_nether', 'the_end'):
        for arrow in ('arrow', 'spectral_arrow'):
            tick += [f'execute unless data storage gtminimap:state {{stopped:1b}} in minecraft:{dim} as @e[type=minecraft:{arrow},tag=!gtm_used,nbt={{inGround:1b}},limit=16] at @s run function gtminimap:arrow']
    tick += ['execute as @a at @s run function gtminimap:guard']
    fn('tick', tick)
    # 26.3-pre-2 through rc-2 queue a live mutable post-effect list on dimension transfer
    # (ServerPlayer.sendPostEffects is byte-identical across them).
    # Leave it untouched for four ticks after transfer/login/death; packet encoding
    # is asynchronous. This mitigates the observed race, not arbitrary Netty stalls.
    guard = ['scoreboard players set #gap gtm_tmp 0',
             'execute if score @s gtm_seen matches -2147483648..2147483647 run scoreboard players operation #gap gtm_tmp = #clock gtm_tmp',
             'scoreboard players operation #gap gtm_tmp -= @s gtm_seen',
             'execute unless score #gap gtm_tmp matches 1 run scoreboard players set @s gtm_grace 4',
             'scoreboard players operation @s gtm_seen = #clock gtm_tmp',
             'scoreboard players set #dimension gtm_tmp -1']
    for i, dim in enumerate(('overworld', 'the_nether', 'the_end')):
        guard += [f'execute if dimension minecraft:{dim} run scoreboard players set #dimension gtm_tmp {i}']
    guard += ['execute unless score @s gtm_dim = #dimension gtm_tmp run scoreboard players set @s gtm_grace 4',
              'scoreboard players operation @s gtm_dim = #dimension gtm_tmp',
              'execute if entity @s[nbt={Health:0.0f}] run scoreboard players set @s gtm_grace 4',
              'execute if score @s gtm_grace matches 1.. run return run scoreboard players remove @s gtm_grace 1',
              'execute if data storage gtminimap:state {stopped:1b} run return run function gtminimap:remove',
              'execute unless score @s gtm_epoch = #epoch gtm_tmp run function gtminimap:join',
              'function gtminimap:player']
    fn('guard', guard)
    menu = [tell('━━ 原版小地图 · 视频功能复原 ━━', 'gold'),
            tell('程序地形与体素光追由着色器生成，不会改变真实世界或碰撞。'),
            tell('下方按钮可直接点击；向方块射箭，箭落地后设置你的路点。', 'gray')]
    if classic:
        menu = [tell('━━ 地表小地图 · 真实底图 ━━', 'gold'), tell('底图来自作者导出的真实世界；头像与射箭路点实时更新。'),
                tell('底图不会随挖掘刷新。向方块射箭建立路点；按 T 点击下面按钮。', 'gray')]
    for name, number in [('显示／隐藏地图', 1), ('切换缩放', 2), ('切换体素光追', 4), ('北向／朝向地图', 5), ('随机地图种子', 6), ('清除路点', 7), ('光追视野 70／90／110 度', 8), ('修复显示', 9), ('程序地形／真实底图', 10), ('显示／隐藏猪的头像',11)]:
        if classic and number in (4,6,8,10):
            continue
        menu += ['tellraw @s ' + dumps({'text': f'【{name}】', 'color': 'green', 'click_event': {'action': 'run_command', 'command': f'/trigger gtm set {number}'}})]
    menu += [tell('指定种子：/trigger gtm_seed set 数字（0 至 65535）；打开菜单：/trigger gtm set 3', 'yellow'),
             tell('若只有文字、没有地图：启用同版本资源包后按 F3＋T，再点击“修复显示”。', 'gray')]
    if classic:
        menu[-2] = tell('打开菜单：/trigger gtm set 3；需要其他区域，请重新导出对应世界底图。', 'yellow')
    fn('menu', menu)
    controls = ['execute if score @s gtm matches 1 run function gtminimap:toggle_on',
                'execute if score @s gtm matches 2 run function gtminimap:zoom',
                'execute if score @s gtm matches 3 run function gtminimap:menu',
                'execute if score @s gtm matches 4 run function gtminimap:toggle_ray',
                'execute if score @s gtm matches 5 run function gtminimap:toggle_head',
                'execute if score @s gtm matches 6 store result score @s gtm_s run random value 0..65535',
                'execute if score @s gtm matches 7 run scoreboard players set @s gtm_wp 0',
                'execute if score @s gtm matches 8 run function gtminimap:fov',
                'execute if score @s gtm matches 9 run function gtminimap:remove',
                'execute if score @s gtm matches 10 run function gtminimap:toggle_atlas',
                'execute if score @s gtm matches 11 run function gtminimap:toggle_pigs',
                'execute if score @s gtm_seed matches 65536.. run ' + tell('种子应为 0 至 65535，已保留原种子。', 'red'),
                'execute if score @s gtm_seed matches ..-2 run ' + tell('种子应为 0 至 65535，已保留原种子。', 'red'),
                'execute if score @s gtm_seed matches 0..65535 run scoreboard players operation @s gtm_s = @s gtm_seed',
                'scoreboard players operation @s gtm_s %= #65536 gtm_tmp',
                'scoreboard players set @s gtm_seed -1', 'scoreboard players set @s gtm 0',
                'scoreboard players enable @s gtm', 'scoreboard players enable @s gtm_seed',
                'execute if score @s gtm_on matches 0 if score @s gtm_ray matches 0 run return run function gtminimap:remove',
                'execute unless score @s gtm_live matches 1 run function gtminimap:start',
                'function gtminimap:frame']
    if classic:
        controls = [line for line in controls if not any(f'gtm matches {n} ' in line for n in (4,6,8,10))]
        controls = ['scoreboard players set @s gtm_ray 0', 'scoreboard players set @s gtm_atlas 1'] + controls
    fn('player', controls)
    for key in ('on', 'ray', 'head', 'atlas', 'pigs'):
        lines = [f'scoreboard players add @s gtm_{key} 1', f'execute if score @s gtm_{key} matches 2.. run scoreboard players set @s gtm_{key} 0']
        if key == 'atlas':
            lines += ['execute if score #atlas gtm_tmp matches 0 run scoreboard players set @s gtm_atlas 0',
                      'execute if score #atlas gtm_tmp matches 0 run ' + tell('本包未附带你的世界底图，继续使用程序地形。请通过作者工具指定底图 PNG 和元数据。', 'yellow')]
        fn('toggle_' + key, lines)
    for key, count in [('zoom', 4), ('fov', 3)]:
        fn(key, [f'scoreboard players add @s gtm_{key} 1', f'execute if score @s gtm_{key} matches {count}.. run scoreboard players set @s gtm_{key} 0'])
    fn('start', ['posteffect add @s gtminimap:reset'] + [f'scoreboard players set @s gtm_o{i} -1' for i in range(SLOTS)] + ['scoreboard players set @s gtm_live 1'])
    fn('wire/remove', ['$posteffect remove @s gtminimap:w/$(slot)_$(value)'])
    fn('wire/add', ['$posteffect add @s gtminimap:w/$(slot)_$(value)'])
    remove = ['execute unless score @s gtm_live matches 1 run return 0', 'posteffect remove @s gtminimap:view', 'posteffect remove @s gtminimap:reset']
    for i in range(SLOTS):
        remove += [f'execute if score @s gtm_o{i} matches 1..15 run function gtminimap:drop/{i}']
        fn(f'drop/{i}', [f'data modify storage gtminimap:wire slot set value {i}',
                         f'execute store result storage gtminimap:wire value int 1 run scoreboard players get @s gtm_o{i}',
                         'function gtminimap:wire/remove with storage gtminimap:wire'])
        fn(f'send/{i}', [f'execute if score @s gtm_o{i} matches 1..15 run function gtminimap:drop/{i}',
                         f'data modify storage gtminimap:wire slot set value {i}',
                         f'execute store result storage gtminimap:wire value int 1 run scoreboard players get #v{i} gtm_tmp',
                         f'execute if score #v{i} gtm_tmp matches 1..15 run function gtminimap:wire/add with storage gtminimap:wire',
                         f'scoreboard players operation @s gtm_o{i} = #v{i} gtm_tmp', 'scoreboard players set #changed gtm_tmp 1'])
    fn('remove', remove + ['scoreboard players set @s gtm_live 0'])
    fn('cleanup', ['execute as @a run function gtminimap:remove'])
    fn('uninstall', ['data modify storage gtminimap:state stopped set value 1b',
                     'tellraw @a ' + dumps({'text': '原版小地图已停用。保留数据包时，离线玩家上线也会自动清理；移除前请确保需要清理的玩家已经上线。', 'color': 'yellow'})])

    def pack(value, offset, count):
        commands = [f'scoreboard players operation #encode gtm_tmp = {value}']
        for i in range(offset, offset + count):
            commands += [f'scoreboard players operation #v{i} gtm_tmp = #encode gtm_tmp', f'scoreboard players operation #v{i} gtm_tmp %= #16 gtm_tmp',
                         'scoreboard players operation #encode gtm_tmp /= #16 gtm_tmp']
        return commands

    frame = [f'scoreboard players set #v{i} gtm_tmp 0' for i in range(SLOTS)] + ['scoreboard players set #v0 gtm_tmp 13']
    for obj, bit in [('on', 1), ('ray', 2), ('head', 4), ('atlas', 8)]:
        frame += [f'execute if score @s gtm_{obj} matches 1 run scoreboard players add #v1 gtm_tmp {bit}']
    frame += ['scoreboard players operation #v2 gtm_tmp = @s gtm_zoom', 'scoreboard players operation #v31 gtm_tmp = @s gtm_fov',
              'execute store result score #yaw gtm_tmp run data get entity @s Rotation[0] 10',
              'scoreboard players operation #yaw gtm_tmp %= #3600 gtm_tmp',
              'execute if score #yaw gtm_tmp matches ..-1 run scoreboard players add #yaw gtm_tmp 3600',
              'execute store result score #pitch gtm_tmp run data get entity @s Rotation[1] 10',
              'scoreboard players add #pitch gtm_tmp 900']
    frame += pack('#yaw gtm_tmp', 4, 3) + pack('#pitch gtm_tmp', 7, 3) + pack('@s gtm_s', 10, 4)
    frame += ['scoreboard players set #dim gtm_tmp -1']
    for i, dim in enumerate(('overworld', 'the_nether', 'the_end')):
        frame += [f'execute if dimension minecraft:{dim} run scoreboard players set #dim gtm_tmp {i}']
    frame += ['execute if score @s gtm_wp matches 1 if score @s gtm_wdim = #dim gtm_tmp run function gtminimap:waypoint/encode']
    # An optional atlas only belongs to the dimension recorded by its author.
    if atlas_present:
        import re
        if not re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+', meta['dimension']):
            raise ValueError('底图维度标识无效')
        frame += [f'execute unless dimension {meta["dimension"]} if score @s gtm_atlas matches 1 run scoreboard players remove #v1 gtm_tmp 8']
    for axis, nbt, offset in [('ax', 0, 32), ('az', 2, 40)]:
        frame += [f'execute store result score #{axis} gtm_tmp run data get entity @s Pos[{nbt}]',
                  f'scoreboard players operation #absolute gtm_tmp = #{axis} gtm_tmp', 'scoreboard players add #absolute gtm_tmp 30000000']
        frame += pack('#absolute gtm_tmp', offset, 8)
        frame += [f'scoreboard players operation #{axis} gtm_tmp *= #8 gtm_tmp']
    frame += ['scoreboard players set #pig gtm_tmp 0',
              'execute if score @s gtm_pigs matches 1 as @e[type=minecraft:pig,distance=..192,sort=nearest,limit=12] run function gtminimap:pig',
              'scoreboard players operation #v3 gtm_tmp = #pig gtm_tmp', 'scoreboard players set #changed gtm_tmp 0']
    frame += [f'execute unless score #v{i} gtm_tmp = @s gtm_o{i} run function gtminimap:send/{i}' for i in range(SLOTS)]
    frame += ['execute if score #changed gtm_tmp matches 1 run posteffect remove @s gtminimap:view',
              'execute if score #changed gtm_tmp matches 1 run posteffect add @s gtminimap:view']
    fn('frame', frame)
    fn('pig', ['execute store result score #px gtm_tmp run data get entity @s Pos[0] 8',
               'execute store result score #pz gtm_tmp run data get entity @s Pos[2] 8',
               'scoreboard players operation #px gtm_tmp -= #ax gtm_tmp', 'scoreboard players operation #pz gtm_tmp -= #az gtm_tmp',
               'scoreboard players add #px gtm_tmp 2048', 'scoreboard players add #pz gtm_tmp 2048'] +
       [f'execute if score #pig gtm_tmp matches {i} run function gtminimap:pig/{i}' for i in range(12)] + ['scoreboard players add #pig gtm_tmp 1'])
    for i in range(12):
        fn(f'pig/{i}', pack('#px gtm_tmp', 48 + i * 6, 3) + pack('#pz gtm_tmp', 51 + i * 6, 3))
    fn('waypoint/encode', ['scoreboard players set #v30 gtm_tmp 1', 'scoreboard players operation #wx gtm_tmp = @s gtm_wx',
                           'scoreboard players operation #wz gtm_tmp = @s gtm_wz', 'scoreboard players add #wx gtm_tmp 30000000',
                           'scoreboard players add #wz gtm_tmp 30000000'] + pack('#wx gtm_tmp', 14, 8) + pack('#wz gtm_tmp', 22, 8))
    fn('arrow', ['tag @s add gtm_used', 'execute store result score #arrowx gtm_tmp run data get entity @s Pos[0]',
                 'execute store result score #arrowz gtm_tmp run data get entity @s Pos[2]',
                 'scoreboard players set #arrowdim gtm_tmp -1'] +
       [f'execute if dimension minecraft:{dim} run scoreboard players set #arrowdim gtm_tmp {i}' for i, dim in enumerate(('overworld', 'the_nether', 'the_end'))] +
       ['execute on origin if entity @s[type=minecraft:player] run function gtminimap:waypoint/set'])
    fn('waypoint/set', ['scoreboard players operation @s gtm_wx = #arrowx gtm_tmp', 'scoreboard players operation @s gtm_wz = #arrowz gtm_tmp',
                       'scoreboard players operation @s gtm_wdim = #arrowdim gtm_tmp', 'scoreboard players set @s gtm_wp 1',
                       tell('已将箭的落点设为你的路点。地图显示水平距离，超出范围时箭头指向目标。', 'gold')])
    protocol = {'schema': 'gtminimap.wire.v2', 'minecraft': VERSION, 'nibbles': SLOTS,
                'fields': {'0': '协议标识＝13', '1': '标志位：地图＝1，光追＝2，朝向地图＝4，真实底图＝8', '2': '缩放序号', '3': '猪的数量',
                           '4..6': '水平朝向角度×10，范围 0—3599', '7..9': '俯仰角度×10＋900', '10..13': '16 位无符号种子',
                           '14..21': '路点横坐标＋30000000', '22..29': '路点纵坐标＋30000000', '30': '路点位于当前维度',
                           '31': '视野序号：70／90／110 度', '32..39': '玩家锚点横坐标＋30000000', '40..47': '玩家锚点纵坐标＋30000000',
                           '48..119': '12 头猪：每头先 3 个横坐标半字节，再 3 个纵坐标半字节；编码＝（位置－玩家整数锚点）×8＋2048'},
                'encoding': '半字节按低位在前排列；红通道值＝半字节值／15；像素位于（槽号，0）；先清零，再写变化的值，最后解码绘图',
                'terrain': '作者提供的有限真实地形快照，不使用程序地形替代' if classic else '默认使用程序化高度场；可选作者提供的有限真实地形快照'}
    js('协议.json', protocol)
    put('安装与使用.txt', f'''原版小地图 · 视频功能复原版（完全简体中文）
适用：Minecraft Java {VERSION}。玩家不需要 Fabric 或 GTShaders。

这份 ZIP 可以直接安装，不需要解压，也不是“里面还有两个 ZIP”的外层压缩包。
必须安装两处（同一份 ZIP 复制两次）：
① 当前游戏实例/resourcepacks/ → 游戏选项 → 资源包 → 启用本包。
② 当前世界/datapacks/ → 在世界内执行 /reload 或重进世界。
只装第①处不会运行数据包；只装第②处只能看到文字菜单，无法绘图。
服务器安装第②处，由服务器提供同版本资源包给所有玩家。
升级：停用旧 GTMinimap 资源包和数据包，避免同名函数、tick 标签同时运行。

进入世界自动显示中文菜单。/trigger gtm set 3 可再次打开，点击绿色按钮操作。
/trigger gtm set 1 显隐地图；set 2 缩放；set 4 开关体素光追；set 5 北向/朝向。
/trigger gtm set 6 随机种子；/trigger gtm_seed set 12345 指定种子（0—65535）。
set 7 清除路点；set 8 切换光追视野（70/90/110 度）；set 9 修复显示。
跟踪同维度 192 格内最近 12 头猪。粉色菱形代表猪，金色箭头代表相机朝向。
向方块射箭，箭落地后设置射手自己的路点；显示水平距离与方向。
路点跨退出保存，仅在射箭所在维度显示；支持主世界、下界、末地。
光追画面与地图使用同一个程序种子；不会改变真正的存档地形、碰撞或世界种子。
体素光追使用固定 640×360 内部分辨率、最多 192 次 DDA 步进；世界原版界面仍可操作。
视角数据来自服务端 20 Hz，GPU 做短时平滑，会有少量延迟；平移使用原版每帧相机位置。
第一人称最准确；第三人称正面或旁观其他实体的真实相机旋转不能从数据包获得。

故障排查：/datapack list enabled 应列出本包；启用资源包后按 F3＋T；打开菜单点“修复显示”。
若提示未知 posteffect 命令，版本不支持；不能在 26.2/1.21 上直接使用。
若黑屏仍可按 T 输入 /trigger gtm set 4 退出光追，或 /function gtminimap:uninstall 停用。
只安装了资源包且无任何菜单时，请检查世界/datapacks/ 是否也放入同一份文件。
卸载前 /function gtminimap:uninstall，再移除数据包；离线玩家上线后需管理员清理其本包效果。

地图默认是程序地形，不是假装扫描当前世界。可在构建时传 --atlas PNG --metadata atlas.json
附带有限真实底图，再在菜单切换；底图不随建造/挖掘刷新。
实现是依据视频口述独立复原，未获得原作者源码，不保证画面逐像素相同。
''')
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, content in sorted(files.items()):
            archive.writestr(name, content)
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        assert {'pack.mcmeta', 'data/minecraft/tags/function/load.json', 'assets/gtminimap/post_effect/view.json'} <= set(archive.namelist())
    print(dumps({'output': str(output), 'files': len(files), 'bytes': output.stat().st_size, 'sha256': hashlib.sha256(output.read_bytes()).hexdigest()}))
    if output.resolve() == DEFAULT_OUTPUT.resolve():
        # Repair the exact legacy download path: it is now a directly installable
        # pack too, not a wrapper containing other ZIP files.
        (output.parent / f'GTMinimap-Vanilla-Snapshot-{VERSION}.zip').write_bytes(output.read_bytes())
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font', type=Path, default=Path('C:/Windows/Fonts/msyh.ttc'))
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument('--atlas', type=Path)
    parser.add_argument('--metadata', type=Path)
    parser.add_argument('--classic-template', action='store_true', help='生成真实地形版作者模板，由 Java 导出器注入实际底图后才能交付')
    args = parser.parse_args()
    if bool(args.atlas) != bool(args.metadata):
        parser.error('--atlas 与 --metadata 必须同时提供')
    if args.classic_template and args.output == DEFAULT_OUTPUT:
        parser.error('作者模板必须通过 --output 指定单独路径，不能覆盖可安装成品')
    build(args.font, args.output, args.atlas, args.metadata, args.classic_template)


if __name__ == '__main__':
    main()
