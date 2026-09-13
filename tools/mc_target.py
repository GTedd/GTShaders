"""编译目标的版本号与包格式号（Python 侧），只认 gradle.properties。

小地图这组工具曾把 26.3-pre-2 和数据包格式 120 各抄一份，跟到 rc-2 时数据包格式已涨到 121，
抄下来的旧值不会报错，只会让导出的数据包在游戏里显示「不兼容」。
Java 侧的对应实现是 mc.GTedd.cn.gtshaders.core.TargetVersion。

用法（工具以 python tools/xxx.py 运行时 tools/ 就在 sys.path 上）：
    from mc_target import VERSION, RESOURCE_FORMAT, DATA_FORMAT
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _properties() -> dict:
    props = {}
    for line in (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            props[key.strip()] = value.strip()
    return props


def _format(props: dict, key: str) -> list:
    major, minor = props[key].split('.')
    return [int(major), int(minor)]


_PROPS = _properties()
#: 官方版本 id，例如 26.3-rc-2
VERSION = _PROPS['minecraft_version']
#: pack.mcmeta 用的 [major, minor]
RESOURCE_FORMAT = _format(_PROPS, 'resource_pack_format')
DATA_FORMAT = _format(_PROPS, 'data_pack_format')
#: 解包好的原版着色器，见 README「docs/vanilla」一节
VANILLA_SHADERS = ROOT / 'docs/vanilla' / VERSION / 'shaders'
