"""原版复原包的协议、安装结构和真实 GPU 回归。先运行构建器；依赖 moderngl、numpy、Pillow。"""
from pathlib import Path
import io
import json
import re
import struct
import unittest
import zipfile
import moderngl
import numpy as np
from PIL import Image
from mc_target import VERSION, RESOURCE_FORMAT, DATA_FORMAT, VANILLA_SHADERS

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / f'build/distributions/GTMinimap-原版复原版-{VERSION}.zip'


class MinimapPackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.zip = zipfile.ZipFile(PACK)
        cls.ctx = moderngl.create_standalone_context(require=330)
        cls.globals = cls.ctx.buffer(reserve=48)
        cls.globals.bind_to_uniform_block(0)
        cls.signal = cls.ctx.buffer(reserve=16)
        cls.signal.bind_to_uniform_block(1)
        cls.programs = {}
        vanilla = VANILLA_SHADERS

        def expand(source):
            def include(match):
                namespace, name = match[1].split(':')
                content = (vanilla / 'include' / name).read_text('utf-8') if namespace == 'minecraft' else cls.zip.read('assets/gtminimap/shaders/include/' + name).decode('utf-8')
                return expand(content)
            return re.sub(r'#include <([^>]+)>', include, source)
        quad = (vanilla / 'core/screenquad.vsh').read_text('utf-8').replace('gl_VertexIndex', 'gl_VertexID')
        for name in ('reset', 'write', 'orientation', 'raytrace', 'view'):
            vertex = expand(cls.zip.read(f'assets/gtminimap/shaders/post/{name}.vsh').decode('utf-8')).replace('gl_VertexIndex', 'gl_VertexID') if name in ('reset','write') else quad
            program = cls.ctx.program(vertex_shader=vertex, fragment_shader=expand(cls.zip.read(f'assets/gtminimap/shaders/post/{name}.fsh').decode('utf-8')))
            if 'Globals' in program:
                program['Globals'].binding = 0
            if 'Signal' in program:
                program['Signal'].binding = 1
            cls.programs[name] = program

    @classmethod
    def tearDownClass(cls):
        cls.zip.close()
        cls.ctx.release()

    def camera(self, x=-120, y=70, z=-60, time=.25):
        self.globals.write(struct.pack('<3if3ff2f2i', x,y,z,0,.25,.5,.25,time,800,600,0,0))

    def values(self, seed=42, yaw=300, pitch=1150, flags=3):
        values = [0] * 120
        values[:4] = [13, flags, 1, 0]
        for start,count,value in [(4,3,yaw),(7,3,pitch),(10,4,seed),(32,8,30_000_000-120),(40,8,30_000_000-60)]:
            for i in range(count):
                values[start+i] = (value >> (4*i)) & 15
        return values

    def transport(self, values):
        image = np.zeros((600,800,4),dtype='u1')
        image[:] = [31,47,61,255]
        image[0,:120,0] = np.array(values,dtype='u1')*17
        image[0,:120,1:3] = 0
        texture = self.ctx.texture((800,600),4,image.tobytes())
        texture.filter = (moderngl.NEAREST,moderngl.NEAREST)
        return texture

    def render(self, shader, inputs, size):
        program = self.programs[shader]
        for i,(name,texture) in enumerate(inputs.items()):
            texture.use(i)
            program[name+'Sampler'] = i
        out = self.ctx.texture(size,4)
        frame = self.ctx.framebuffer([out])
        frame.use()
        vao = self.ctx.vertex_array(program,[])
        vao.render(vertices=3)
        vao.release()
        frame.release()
        return out

    def test_archive_installs_directly_in_both_registries(self):
        self.assertIsNone(self.zip.testzip())
        names = set(self.zip.namelist())
        self.assertIn('pack.mcmeta',names)
        self.assertFalse(any(name.endswith('.zip') for name in names))
        self.assertIn('data/minecraft/tags/function/tick.json',names)
        self.assertIn('assets/gtminimap/post_effect/view.json',names)
        meta=json.loads(self.zip.read('pack.mcmeta'))['pack']
        self.assertLessEqual(tuple(meta['min_format']),tuple(RESOURCE_FORMAT))
        self.assertGreaterEqual(tuple(meta['max_format']),tuple(DATA_FORMAT))
        self.assertEqual(PACK.read_bytes(), (PACK.parent/f'GTMinimap-Vanilla-Snapshot-{VERSION}.zip').read_bytes())

    def test_every_static_function_reference_and_json_command_resolves(self):
        names=set(self.zip.namelist())
        for name in names:
            if name.endswith('.json') or name=='pack.mcmeta':
                json.loads(self.zip.read(name))
            if not name.endswith('.mcfunction'):
                continue
            for line in self.zip.read(name).decode('utf-8').splitlines():
                self.assertNotIn('posteffect clear',line)
                for match in re.finditer(r'\bfunction (gtminimap:[a-z0-9_/]+)',line):
                    self.assertIn('data/gtminimap/function/'+match[1].split(':')[1]+'.mcfunction',names)
                if line.startswith('tellraw @s '):
                    component=json.loads(line[len('tellraw @s '):])
                    self.assertRegex(component['text'],r'[\u4e00-\u9fff]')
                    if 'click_event' in component:
                        self.assertEqual('run_command',component['click_event']['action'])

    def test_nibble_roundtrip_at_world_edges_and_pig_range(self):
        for value,count in [(0,8),(60_000_000,8),(29_999_879,8),(65535,4),(3599,3),(0,3),(4095,3)]:
            encoded=[(value>>(i*4))&15 for i in range(count)]
            decoded=sum(v<<(4*i) for i,v in enumerate(encoded))
            self.assertEqual(value,decoded)
            self.assertTrue(all(0<=v<=15 for v in encoded))

    def test_gpu_writers_touch_only_their_single_pixel_and_reset_clears_stale_data(self):
        self.camera()
        original=np.full((600,800,4),91,dtype='u1')
        image=self.ctx.texture((800,600),4,original.tobytes())
        frame=self.ctx.framebuffer([image]);frame.use()
        writer=self.ctx.vertex_array(self.programs['write'],[])
        for i in range(16):
            self.signal.write(struct.pack('<2i8x',i,i))
            writer.render(vertices=3)
        result=np.frombuffer(image.read(),dtype='u1').reshape(600,800,4)
        self.assertEqual(list(range(0,256,17)),result[0,:16,0].tolist())
        self.assertTrue(np.all(result[1:]==91))
        self.assertTrue(np.all(result[0,16:]==91))
        reset=self.ctx.vertex_array(self.programs['reset'],[]);reset.render(vertices=3)
        result=np.frombuffer(image.read(),dtype='u1').reshape(600,800,4)
        self.assertTrue(np.all(result[0,:120,:3]==0))
        self.assertTrue(np.all(result[0,120:]==91))
        writer.release();reset.release();frame.release();image.release()

    def test_gpu_orientation_wraps_through_north_instead_of_spinning_180_degrees(self):
        self.camera()
        previous=self.ctx.texture((2,1),4,bytes(8))
        signal=self.transport(self.values(yaw=3599))
        old=self.render('orientation',{'In':signal,'Previous':previous},(2,1))
        old_bytes=old.read()
        signal.release();previous.release()
        self.camera(time=.2500139)
        signal=self.transport(self.values(yaw=10))
        smooth=self.render('orientation',{'In':signal,'Previous':old},(2,1))
        b=smooth.read()
        angle=(b[0]*256+b[1])/65535*360
        self.assertTrue(angle>359 or angle<2,angle)
        self.assertNotEqual(old_bytes[:4],b[:4])
        signal.release();old.release();smooth.release()

    def test_gpu_dda_is_seeded_nonuniform_and_disabled_path_is_empty(self):
        self.camera(y=70)
        previous=self.ctx.texture((2,1),4,bytes(8))
        signal=self.transport(self.values(seed=42))
        orientation=self.render('orientation',{'In':signal,'Previous':previous},(2,1))
        output=self.render('raytrace',{'In':signal,'Orientation':orientation},(160,90))
        a=np.frombuffer(output.read(),dtype='u1').copy()
        self.assertGreater(np.std(a.reshape(-1,4)[:,:3]),20)
        output.release();signal.release()
        signal=self.transport(self.values(seed=26456))
        output=self.render('raytrace',{'In':signal,'Orientation':orientation},(160,90))
        b=np.frombuffer(output.read(),dtype='u1').copy()
        self.assertGreater(np.mean(a!=b),.10)
        output.release();signal.release()
        signal=self.transport(self.values(flags=1))
        output=self.render('raytrace',{'In':signal,'Orientation':orientation},(160,90))
        self.assertTrue(all(v==0 for v in output.read()))
        signal.release();output.release();orientation.release();previous.release()


if __name__=='__main__':
    unittest.main(verbosity=2)
