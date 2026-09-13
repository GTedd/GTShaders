"""Render the actual portable fragment shader on OpenGL; verify axes and far-origin precision.

Requires moderngl and numpy. This complements, not replaces, vanilla client verification.
"""
from pathlib import Path
import json
import struct
import moderngl
import numpy as np
from mc_target import VANILLA_SHADERS

ROOT = Path(__file__).resolve().parent.parent
ctx = moderngl.create_standalone_context(require=330)
source = (ROOT / 'src/main/resources/assets/gtshaders/portable/minimap.fsh').read_text('utf-8')
globals_src = (VANILLA_SHADERS / 'include/globals.glsl').read_text('utf-8')
fragment = source.replace('#include <minecraft:globals.glsl>', globals_src)
vertex = (VANILLA_SHADERS / 'core/screenquad.vsh').read_text('utf-8').replace('gl_VertexIndex', 'gl_VertexID')
program = ctx.program(vertex_shader=vertex, fragment_shader=fragment)
program['Globals'].binding = 0
program['AtlasConfig'].binding = 1
globals_buffer = ctx.buffer(reserve=48)
config_buffer = ctx.buffer(reserve=32)
globals_buffer.bind_to_uniform_block(0)
config_buffer.bind_to_uniform_block(1)
scene = ctx.texture((800, 600), 4, bytes([24, 32, 40, 255]) * (800 * 600))
pixels = np.zeros((256, 256, 4), dtype='u1')
pixels[:128, :128] = [255, 0, 0, 255]
pixels[:128, 128:] = [0, 255, 0, 255]
pixels[128:, :128] = [0, 0, 255, 255]
pixels[128:, 128:] = [255, 255, 0, 255]
atlas = ctx.texture((256, 256), 4, pixels.tobytes())
atlas.filter = (moderngl.NEAREST, moderngl.NEAREST)
scene.use(0)
atlas.use(1)
program['InSampler'] = 0
program['AtlasSampler'] = 1
frame = ctx.simple_framebuffer((800, 600), components=4)
vao = ctx.vertex_array(program, [])


def render(origin_x=0, origin_z=0, fraction=.25, camera_delta=128):
    globals_buffer.write(struct.pack('<3if3ff2f2i', origin_x + camera_delta, 70, origin_z + camera_delta,
                                    0, fraction, 0, fraction, 0, 800, 600, 0, 0))
    config_buffer.write(struct.pack('<2i4f8x', origin_x, origin_z, 256, 256, 1, 64))
    frame.use()
    vao.render(vertices=3)
    return np.frombuffer(frame.read(components=4, alignment=1), dtype='u1').reshape(600, 800, 4)[::-1].copy()


near = render()
# Map center at (702,106), top-left (624,28), size156. PNG row +Z = screen down.
assert tuple(near[66, 662, :3]) == (255, 0, 0), 'northwest'
assert tuple(near[66, 742, :3]) == (0, 255, 0), 'northeast'
assert tuple(near[146, 662, :3]) == (0, 0, 255), 'southwest'
assert tuple(near[146, 742, :3]) == (255, 255, 0), 'southeast'
assert np.array_equal(near, render(29_999_000, -29_999_000)), 'far-origin jitter / precision loss'
assert not np.array_equal(near, render(fraction=.95)), 'fractional camera motion ignored'
assert tuple(render(camera_delta=1024)[66, 662, :3]) == (24, 35, 45), 'out-of-atlas must not wrap'
assert tuple(near[400, 300]) == (24, 32, 40, 255), 'scene outside HUD changed'
report = {'renderer': ctx.info['GL_RENDERER'], 'axes': 'passed', 'worldBorderPrecision': 'passed',
          'subBlockTranslation': 'passed', 'outOfBounds': 'passed', 'scenePreserved': 'passed'}
out = ROOT / 'build/portable-gpu-check.json'
out.parent.mkdir(exist_ok=True)
out.write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report))
