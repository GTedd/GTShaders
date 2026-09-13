"""Launch a genuinely vanilla offline client against a COPY of a disposable test world.

No Fabric/GTShaders jars, tokens, launcher profiles or user saves are loaded.
Requires the exact Mojang libraries and assets already cached by gradlew runClient.
Example: python tools/verify_portable_minimap_client.py --packs run/config/gtshaders/minimap/exports/vanilla-... --world "run/saves/New World"
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from mc_target import VERSION


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packs', required=True, type=Path)
    parser.add_argument('--world', required=True, type=Path)
    parser.add_argument('--java', default='D:/zulu25/bin/java.exe', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    cache = Path.home() / '.gradle/caches'
    loom = cache / 'fabric-loom'
    version = VERSION
    info = json.loads((loom / version / 'mojang_minecraft_info.json').read_text('utf-8'))
    jars = {}
    for p in cache.rglob('*.jar'):
        jars.setdefault(p.name, []).append(p)
    classpath = []
    for lib in info['libraries']:
        allowed = not lib.get('rules')
        for rule in lib.get('rules', []):
            os_rule = rule.get('os', {})
            if os_rule.get('name', 'windows') == 'windows' and os_rule.get('arch', 'x86_64') in ('x86_64', 'amd64'):
                allowed = rule['action'] == 'allow'
        if not allowed:
            continue
        artifact = lib['downloads']['artifact']
        matches = [p for p in jars.get(Path(artifact['path']).name, [])
                   if hashlib.sha1(p.read_bytes()).hexdigest() == artifact['sha1']]
        if not matches:
            raise SystemExit('Missing official cached library; run gradlew runClient first: ' + lib['name'])
        classpath.append(str(matches[0]))
    client = loom / version / 'minecraft-client.jar'
    assert hashlib.sha1(client.read_bytes()).hexdigest() == info['downloads']['client']['sha1']
    classpath.append(str(client))
    assert not any('fabric-' in Path(p).name.lower() or 'gtshaders' in Path(p).name.lower() for p in classpath)
    output = root / 'build/portable-vanilla'
    output.mkdir(parents=True, exist_ok=True)
    game = Path(tempfile.mkdtemp(prefix='check-', dir=output))
    world = game / 'saves/Atlas Check'
    shutil.copytree(args.world, world, ignore=shutil.ignore_patterns('session.lock'))
    (world / 'datapacks').mkdir(exist_ok=True)
    (game / 'resourcepacks').mkdir()
    # A root-layout combined ZIP must work in BOTH registries without unpacking.
    data_pack = args.packs if args.packs.is_file() else args.packs / 'GTMinimap-Data.zip'
    resource_pack = args.packs if args.packs.is_file() else args.packs / 'GTMinimap-Resources.zip'
    shutil.copy2(data_pack, world / 'datapacks/GTMinimap-Data.zip')
    shutil.copy2(resource_pack, game / 'resourcepacks/GTMinimap-Resources.zip')
    (game / 'options.txt').write_text('resourcePacks:["vanilla","file/GTMinimap-Resources.zip"]\nlang:zh_cn\nguiScale:2\nfullscreen:false\nrenderDistance:12\ntutorialStep:none\n', encoding='utf-8')
    assets = loom / 'assets'
    asset_index = version + '-' + info['assetIndex']['id']
    assert (assets / 'indexes' / (asset_index + '.json')).is_file()
    command = [str(args.java), '-Xmx2G', '-XX:StackShadowPages=32', '--enable-native-access=ALL-UNNAMED',
               '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED', '-cp', ';'.join(classpath),
               info['mainClass'], '--username', 'PortableCheck', '--uuid', '00000000000000000000000000000001',
               '--accessToken', '0', '--version', version, '--gameDir', str(game), '--assetsDir', str(assets),
               '--assetIndex', asset_index, '--quickPlaySingleplayer', 'Atlas Check', '--width', '1280', '--height', '800']
    (game / 'validation.json').write_text(json.dumps({'minecraft': version, 'clientSha1': info['downloads']['client']['sha1'],
        'mainClass': info['mainClass'], 'libraries': classpath, 'mods': [], 'sourcePacks': str(args.packs.resolve())}, indent=2), encoding='utf-8')
    # Python launches the console-less process; no extra visible terminal helper is created.
    with (game / 'client.log').open('w', encoding='utf-8') as log:
        proc = subprocess.Popen(command, cwd=game, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    print(json.dumps({'gameDir': str(game), 'pid': proc.pid, 'client': 'official, no mod loader'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
