"""Build vanilla packs from any world baker's PNG and atlas.json; no Minecraft runtime.

Run gradlew build first. Then:
python tools/export_portable_minimap.py terrain.png atlas.json output-directory
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('png', type=Path)
    parser.add_argument('metadata', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--java', type=Path, default='D:/zulu25/bin/java.exe')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    props = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text('utf-8').splitlines() if '=' in line and not line.startswith('#'))
    jar = root / 'build/libs' / (props['archives_base_name'] + '-' + props['mod_version'] + '.jar')
    if not jar.is_file():
        raise SystemExit('Run gradlew build first.')
    if any(p.stat().st_mtime > jar.stat().st_mtime for p in (root / 'src/main').rglob('*') if p.is_file()):
        raise SystemExit('Sources changed; run gradlew build first.')
    gson = next((Path.home() / '.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson').rglob('gson-2.11.0.jar'))
    # Avoid Windows' legacy encoding of non-ASCII classpath entries.
    with tempfile.TemporaryDirectory(prefix='gtminimap-export-') as staging:
        staged = Path(staging) / 'gtshaders.jar'
        shutil.copy2(jar, staged)
        subprocess.run([str(args.java), '-cp', str(staged) + ';' + str(gson),
                        'mc.GTedd.cn.gtshaders.minimap.PortableMinimapExporter',
                        str(args.png.resolve()), str(args.metadata.resolve()), str(args.output.resolve())], check=True)


if __name__ == '__main__':
    main()
