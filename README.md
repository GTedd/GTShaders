# GTShaders

**English** | [简体中文](README.zh-CN.md)

An in-game visual editor for Minecraft shaders. Write a post-processing effect or change one of 21 vanilla core
shaders, drag the parameters and watch the result on your live game view, then export a plain resource pack that
works **without this mod**.

- **Status**: alpha, built against Minecraft 26.3-rc-2; expect bugs and breaking changes before 0.1.0
- **Minecraft**: 26.3 (Java 25), client-side only
- **Loader**: Fabric Loader 0.19.5+ with [Fabric API](https://modrinth.com/mod/fabric-api)
- **License**: [MIT](LICENSE) — third-party credits are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- **Changelog**: [CHANGELOG.md](CHANGELOG.md)

## Features

- **Live preview through the vanilla pipeline.** Your shader is compiled with the game's own ShaderC and loaded into
  the vanilla post-effect chain in memory: no resource reload, no restart. If a change fails to compile, the last
  working version stays on screen and the error line is highlighted.
- **Parameters without UI code.** Annotate a value with `// @param` and the editor builds a slider, color picker or
  toggle for it. Dragging only writes the uniform buffer, so it updates in real time.
- **160+ ready-made effects** in a searchable library (`Ctrl+L`): combat and status, hit feedback,
  transitions, camera shake, magic, sci-fi HUDs, lighting, horror, weather, retro looks, lens effects, color grading
  and glitches. Every effect is commented source you can read and modify.
- **Core shaders, not just post-processing.** Change terrain, entities, sky, items, lightmap and more by writing two
  hook functions, `gtVertex` and `gtFragment`. They are injected into the game's own templates, so layouts, includes
  and define variants stay correct.
- **Export that matches the preview.** One click produces a resource pack triggered by
  `/posteffect add @s gtshaders:<id>` or always on via `end_of_frame`. "Export and verify by real load" installs it
  and loads it the vanilla way so you can confirm it works before sharing.
- **Code editor** with highlighting, completion, hover docs, find/replace and paste clean-up.
- **Debug tab**: pixel probes, variable probes with exact read-back, undefined-behaviour checks and per-layer GPU timing.
- **Snapshots** of every version of a project, dockable panels, UI scaling.
- **World-aware effects**: anchors tied to entities, positions or events (death, hurt), emitters with orientation,
  weapon trails and an entity outline layer. These need the mod at runtime and fall back gracefully in exported packs.
- **Optional AI generation** with your own API key (see below).
- **English and Simplified Chinese**, following the game language. Other languages can be added in
  `config/gtshaders/lang/`.

## Getting started

1. Install Fabric Loader and Fabric API, then put the GTShaders jar into `mods/`.
2. Join any world (post-processing runs on the rendered world, so the title screen has nothing to process).
3. Press **Insert** to open the editor and **Ctrl+L** to open the effect library. Pick an effect and press
   "Add to project"; its parameters appear in the floating panel.

Nothing is applied until you add or enable a layer. To write your own effect, edit the source of a layer and tick
"Enabled":

```glsl
// Soft Tint / Soft Tint
// @param name=Strength type=float min=0 max=1 default=0.5 zh_cn=强度 en_us=Strength
// @param name=Tint type=color3 default=#99CCFF zh_cn=颜色 en_us=Tint

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    fragColor = vec4(mix(col, col * Tint, Strength), 1.0);
}
```

## Controls

| Key | Action |
|---|---|
| `Insert` | Open or close the editor |
| `Delete` | Toggle live preview |
| `Home` | Binding mode: attach effects to things in the world with the crosshair |
| `B` | Fire the selected anchor at the crosshair |
| `PageUp` | Make the entity under the crosshair glow locally (for previewing outline effects) |
| `Ctrl+L` | Effect library |
| `Ctrl+G` | AI generation |
| `Ctrl+S` / `Ctrl+K` | Save project / commit a snapshot |
| `Ctrl+Enter` | Compile and apply now |
| `Ctrl+E` | Variable probe at the caret |

Global keys can be rebound in Options → Controls.

## Exporting

Choose a trigger in the Export tab and press "Export Pack". Packs are written to `config/gtshaders/export/`.

| Trigger | Usage | Notes |
|---|---|---|
| Command | `/posteffect add @s gtshaders:<id>` | Per player and per moment; needs permission level 2 |
| `end_of_frame` | Always on while the pack is enabled | Cannot be turned off by command |

Inputs that only the mod can provide (world anchors, emitters, weapon trails, `GTDeltaTime`, `GTFrame`,
`GTPlaying`) are zero in a plain resource pack. The export panel and the pack's `README.txt` list which ones your
project uses.

## AI generation (optional, bring your own key)

`Ctrl+G` turns a one-line description into an effect. The result is compiled on your machine and compile errors
are sent back to the model for up to three repair rounds.

- No key is included and nothing is sent until you configure a provider and press Generate.
- Works with OpenAI-compatible APIs: DeepSeek (default preset), OpenAI, OpenRouter, Zhipu GLM, Moonshot, Alibaba
  Cloud Bailian, SiliconFlow, or local Ollama / LM Studio.
- Sent data: your prompt, the generated shader and its compile errors, only to the provider you choose.
- The key is stored in `~/.gtshaders/credentials.json`, outside `.minecraft`, so modpack exports don't pick it up.

## Files

| Path | Content |
|---|---|
| `config/gtshaders/` | Projects, snapshots, layout, recent history |
| `config/gtshaders/export/` | Exported resource packs |
| `config/gtshaders/lang/` | Extra or overriding UI languages (`<code>.json`) |
| `config/gtshaders/font/` | Optional `.ttf` / `.otf` fonts for the vector UI font |
| `config/gtshaders/vanilla/<version>/` | Vanilla shaders of another version, to export core shaders for it |
| `~/.gtshaders/credentials.json` | AI provider keys |

## Compatibility and limitations

- Only Minecraft 26.3 is supported; its shader format differs from 26.2.
- Compatibility with Sodium and Iris has not been tested yet.
- Applying a core shader rebuilds the render pipelines, so expect a short hitch. Only one layer per core shader kind
  can be active, and core shader parameters are compiled as constants.
- Some library effects contain bright flashes and fast flicker. They only play after you add them.

## Building from source

Requires JDK 25. Gradle is fetched by the wrapper.

```bash
./gradlew build        # jar in build/libs/, runs unit tests and compiles the whole effect library with ShaderC
./gradlew runClient    # development client
```

`checkShaderLib` (part of `build`) compiles every library effect with the game's ShaderC. To also verify core shader
examples against the real vanilla templates, extract `assets/minecraft/shaders/` from the matching `client.jar` into
`docs/vanilla/<minecraft_version>/shaders/` (this folder is git-ignored; Mojang's assets must not be committed).

## License and credits

GTShaders is released under the [MIT License](LICENSE). Some effects and techniques build on other people's work;
see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for sources, licenses and acknowledgements.

GTShaders is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft.
