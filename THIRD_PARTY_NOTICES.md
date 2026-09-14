# Third-Party Notices

GTShaders is released under the MIT License (see [LICENSE](LICENSE)). It adapts or builds on the work listed below.
Projects whose code is adapted keep their copyright and license notices here, as their licenses require.

## Adapted code (MIT License)

### VanillaDI

- Source: <https://github.com/JNNGL/VanillaDI>
- Copyright (c) 2024 JNNGL
- Used in: the data probe decoder (`PROBE_HELPERS` in `src/main/java/mc/GTedd/cn/gtshaders/codegen/GlslCodegen.java`):
  the integer and float decoding and the reserved-pixel layout, kept bit-compatible with VanillaDI's encoder.

### some_of_fx

- Source: <https://github.com/YangMao-Minister/some_of_fx>
- Copyright (c) 2026 Pizuka
- Used in: the hash and value noise functions of the Singularity Bomb effect
  (`src/main/resources/assets/gtshaders/library/field/singularity.fsh`). The emitter effects
  (`library/emitter/fx_*.fsh`) and the `gtEmitter` helpers follow its ideas but are written independently.

### gd656killicon

- Source: <https://github.com/MinecraftGD656/gd656killicon>
- Author: Minecraft_GD656. The project declares the MIT License; its repository has no license file or copyright
  line, so the standard MIT text is reproduced below with the author's name.
- Used in: the animation timing of the kill icon effects (`library/feedback/killtype_icon.fsh`,
  `library/feedback/scrolling_killicon.fsh`). **No images from gd656killicon are included**; the bundled kill icon is
  an original drawing.

### MIT License text

The following notice applies to the adapted portions of each project above, with that project's copyright line:

```
Copyright (c) 2024 JNNGL
Copyright (c) 2026 Pizuka
Copyright (c) Minecraft_GD656

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Acknowledgements

These works inspired features or provided formulas and data formats. No code from them is included.

| Work | Author | What it inspired |
|---|---|---|
| [BladeFlash](https://github.com/YangMao-Minister/BladeFlash) | Zenoxite | Blade trail effects (`gtTrail`, `library/combat/bladeflash.fsh`) |
| [llm-postshader](https://github.com/xpncvr/llm-postshader) | xpncvr | The RGBA8 float bit-packing layout; `gtPackFloat` / `gtUnpackFloat` are an independent implementation that reads and writes the same format as its `encf` / `decf` |
| [SHADERed](https://github.com/dfranx/SHADERed) and SPIRV-VM | dfranx | The debug tab: value probes, undefined-behaviour checks and GPU timing |
| [Minecraft 着色器教程 (Minecraft Shader Tutorial)](https://cr-109.docs.repeater.red/datapack-index/index/附录7.html) | 轩宇1725 (Xuanyu1725) | The ordered dithering, radial blur, tone mapping and code rain starter templates, and the atlas sprite sampling formula |
| [ACES filmic tone mapping curve](https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/) | Krzysztof Narkowicz | The ACES curve fit in the Tone Mapping template (released by the author under CC0 or MIT) |
| [Distance functions](https://iquilez.org/articles/distfunctions/) | Inigo Quilez | Box and segment signed distance formulas used in several effects |
| 碰撞箱透视 (Hitbox X-ray) resource pack | hi_mv | Telling line types apart by vertex color (`corelib/lines_xray.fsh`) |

## Build tooling

- Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`): Apache License 2.0, Copyright Gradle, Inc.

## Minecraft

GTShaders is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft.
Vanilla shader templates are read from the installed game at runtime; no Mojang assets are distributed with GTShaders.
