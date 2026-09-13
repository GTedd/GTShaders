"""Package the built GTShaders client mod and the minimap guide (stdlib only).

Run gradlew build first, then python tools/package_minimap.py.
The Fabric API dependency is documented, not bundled.
"""
from pathlib import Path
import argparse
import hashlib
import json
import zipfile
from mc_target import RESOURCE_FORMAT, DATA_FORMAT

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--portable', type=Path, help='An exported vanilla-* directory to package as a separate snapshot example')
    args = parser.parse_args()
    props = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines()
                 if "=" in line and not line.startswith("#"))
    jar = ROOT / "build/libs" / f"{props['archives_base_name']}-{props['mod_version']}.jar"
    if not jar.is_file():
        raise SystemExit("Missing mod jar. Run gradlew build first.")
    for directory in (ROOT / "src/main/java", ROOT / "src/main/resources"):
        if any(p.stat().st_mtime > jar.stat().st_mtime for p in directory.rglob("*") if p.is_file()):
            raise SystemExit("The jar is older than its sources. Run gradlew build first.")
    with zipfile.ZipFile(jar) as built:
        assert built.testzip() is None
        for name in ("MinimapRuntime", "MinimapHud", "MinimapScreen", "WaypointScreen", "TerrainGrid", "MinimapMath", "MinimapStore", "PortableMinimapExporter"):
            assert f"mc/GTedd/cn/gtshaders/minimap/{name}.class" in built.namelist(), name
        for lang in ("zh_cn", "en_us"):
            labels = json.loads(built.read(f"assets/gtshaders/lang/{lang}.json"))
            assert "key.gtshaders.minimap.open" in labels
        metadata = json.loads(built.read("fabric.mod.json"))
        assert metadata["version"] == props["mod_version"]
    output = ROOT / "build/distributions" / f"GTShaders-Minimap-{props['minecraft_version']}.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    checksum = hashlib.sha256(jar.read_bytes()).hexdigest()
    staging = output.with_suffix(".zip.part")
    with zipfile.ZipFile(staging, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(jar, "mods/" + jar.name)
        archive.write(ROOT / "docs/guide/真实地形小地图.md", "使用说明.md")
        archive.write(ROOT / "docs/guide/原版小地图与插件迁移.md", "原版小地图与插件迁移.md")
        archive.writestr("SHA256SUMS.txt", checksum + "  mods/" + jar.name + "\n")
        preview = ROOT / "build/minimap-classic-preview.png"
        if preview.is_file():
            archive.write(preview, "实机截图.png")
        archive.write(ROOT / 'src/main/resources/assets/gtshaders/minimap/图标来源.txt', '图标来源.txt')
    staging.replace(output)
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        print("Contents: " + ", ".join(archive.namelist()))
    print(output)
    print("Jar SHA-256: " + checksum)
    if args.portable:
        package_portable(args.portable, props['minecraft_version'])


def package_portable(source, version):
    resources = source / 'GTMinimap-Resources.zip'
    data = source / 'GTMinimap-Data.zip'
    with zipfile.ZipFile(resources) as archive:
        assert archive.testzip() is None
        assert archive.read('assets/gtminimap/shaders/post/minimap.fsh') == (ROOT / 'src/main/resources/assets/gtshaders/portable/minimap.fsh').read_bytes(), 'Stale shader export'
        assert 'assets/gtminimap/textures/effect/map/terrain.png' in archive.namelist()
        assert json.loads(archive.read('gtminimap-atlas.json'))['terrainMode'] == 'snapshot'
        for radius in (32, 64, 128, 256):
            config = json.loads(archive.read(f'assets/gtminimap/post_effect/map_{radius}.json'))
            constants = config['passes'][0]['uniforms']['AtlasConfig']
            assert [c['type'] for c in constants[:2]] == ['int', 'int']
    with zipfile.ZipFile(data) as archive:
        assert archive.testzip() is None
        assert 'stopped:1b' in archive.read('data/gtminimap/function/tick.mcfunction').decode('utf-8')
    # Documentation bundles must be named as such, never presented as loadable packs.
    output = ROOT / 'build/distributions' / f'GTMinimap-Terrain-Atlas-{version}-交付文档.zip'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in (resources, data, source / 'atlas.json', source / 'README.txt'):
            archive.write(file, file.name)
        archive.write(ROOT / 'docs/guide/原版小地图与插件迁移.md', '使用与迁移说明.md')
        archive.writestr('示例底图说明.txt', '这是验证世界的真实海岸地形快照，不会适配其他世界，也不会实时刷新。请用新版 GTShaders 的 M → 导出原版包为自己的世界重新生成两份 ZIP。播放端无需 Fabric 或 GTShaders。\n')
        archive.writestr('SHA256SUMS.txt', ''.join(hashlib.sha256(file.read_bytes()).hexdigest() + '  ' + file.name + '\n' for file in (resources, data)))
        for name in ('portable-minimap-preview.png', 'portable-gpu-check.json', 'portable-validation.json'):
            if (ROOT / 'build' / name).is_file():
                archive.write(ROOT / 'build' / name, name)
    print(output)
    direct = ROOT / 'build/distributions' / f'GTMinimap-Terrain-Atlas-{version}.zip'
    with zipfile.ZipFile(direct, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('pack.mcmeta', json.dumps({'pack': {'description': '原版真实地形快照小地图（两处安装）', 'min_format': RESOURCE_FORMAT, 'max_format': DATA_FORMAT}}, ensure_ascii=False))
        for source_zip in (resources, data):
            with zipfile.ZipFile(source_zip) as source_archive:
                for name in source_archive.namelist():
                    if name != 'pack.mcmeta':
                        archive.writestr(name, source_archive.read(name))
        archive.write(ROOT / 'docs/guide/原版小地图与插件迁移.md', '安装说明.md')
    print(direct)


if __name__ == "__main__":
    main()
