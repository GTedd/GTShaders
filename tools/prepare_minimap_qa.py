"""给 verify_portable_minimap_client.py 创建的独立副本加入验收函数，不修改用户存档。"""
import argparse
import json
from pathlib import Path
from mc_target import DATA_FORMAT

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('game', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
game = args.game.resolve()
assert game.is_relative_to((root / 'build/portable-vanilla').resolve()) and (game / 'validation.json').is_file()
pack = game / 'saves/Atlas Check/datapacks/gtminimap-qa'
functions = pack / 'data/gtminimap_qa/function'
functions.mkdir(parents=True, exist_ok=True)
(pack / 'pack.mcmeta').write_text(json.dumps({'pack': {'description': '小地图独立测试世界工具', 'min_format': DATA_FORMAT, 'max_format': DATA_FORMAT}}, ensure_ascii=False), encoding='utf-8')
commands = ['gamemode creative @s', 'scoreboard players set @s gtm_ray 0', 'tp @s -115 65 -60 30 25', 'time set day',
            'give @s minecraft:crossbow[minecraft:charged_projectiles=[{id:"minecraft:arrow",count:1}]]']
commands += ['execute at @s run summon minecraft:pig ~'+str(i%4+3)+' ~ ~'+str(i//4+3)+' {NoAI:1b,Tags:["gtm_qa"]}' for i in range(14)]
(functions / 'setup.mcfunction').write_text('\n'.join(commands)+'\n', encoding='utf-8')
(functions / 'status.mcfunction').write_text('\n'.join([
    'scoreboard players get @s gtm_wp', 'scoreboard players get @s gtm_wx', 'scoreboard players get @s gtm_wz',
    'scoreboard players get @s gtm_wdim', 'scoreboard players get @s gtm_s', 'scoreboard players get @s gtm_o3',
    'execute as @e[type=arrow,tag=gtm_used,limit=1] run data get entity @s Owner',
    'data get entity @s UUID'])+'\n', encoding='utf-8')
print(pack)

def fn(name, commands):
    (functions / (name+'.mcfunction')).write_text('\n'.join(commands)+'\n',encoding='utf-8')

def message(text):
    return 'tellraw @s '+json.dumps({'text':'【小地图验收】'+text,'color':'aqua'},ensure_ascii=False)

fn('lifecycle', ['gamemode spectator @s', 'scoreboard players set @s gtm_ray 0',
    'execute in minecraft:overworld run tp @s -115 90 45 125 25', 'time set day',
    'schedule function gtminimap_qa:nether_dispatch 60t replace'])
fn('nether_dispatch',['execute as @a[name=PortableCheck] at @s run function gtminimap_qa:nether'])
fn('nether',['execute in minecraft:the_nether run tp @s 0 90 0 125 25',
    'schedule function gtminimap_qa:nether_check 20t replace',
    'schedule function gtminimap_qa:return_dispatch 100t replace'])
fn('nether_check',['execute as @a[name=PortableCheck] if score @s gtm_o30 matches 0 run '+message('下界不显示主世界路点：通过。')])
fn('return_dispatch',['execute as @a[name=PortableCheck] at @s run function gtminimap_qa:return'])
fn('return',['execute in minecraft:overworld run tp @s -115 85 45 125 25',
    'scoreboard players set @s gtm_head 1', 'scoreboard players set @s gtm 9',
    'schedule function gtminimap_qa:return_check 20t replace'])
fn('return_check',['execute as @a[name=PortableCheck] if score @s gtm_o30 matches 1 run '+message('返回主世界恢复路点：通过。'),
    'execute as @a[name=PortableCheck] if score @s gtm_live matches 1 run '+message('重新挂载恢复数据通道：通过。')])
fn('photo',['gamemode spectator @s','execute in minecraft:overworld run tp @s -115 76 -60 125 25','time set day',
    'scoreboard players set @s gtm_ray 1','scoreboard players set @s gtm_head 0','scoreboard players set @s gtm_s 42',
    'scoreboard players set @s gtm_zoom 1','scoreboard players set @s gtm_on 1'])
