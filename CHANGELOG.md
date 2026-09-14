# Changelog

All notable changes to GTShaders are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The part after `+` names the Minecraft version a build
targets and does not affect version ordering.

## Unreleased

### Added

- Library effect `synthwave_classic` (Synthwave Classic): the hard-edged synthwave look with a fixed western sun,
  a faster grid and a scan reveal blended through the reveal mask.

## 0.1.0-alpha.1+26.3-rc-2 - 2026-09-13

First public alpha, built against Minecraft 26.3-rc-2. Expect bugs and breaking changes before 0.1.0.

### Added

- In-game shader editor (`Insert`) with live preview through the vanilla post-effect pipeline. Shaders are compiled
  with the game's own ShaderC, with no resource reload and no restart; a failed compile keeps the last working
  version on screen and highlights the error line.
- Post-processing layers with blend modes, strength and a per-layer screen area.
- Core shader editing for 21 vanilla shader kinds through two hooks, `gtVertex` and `gtFragment`, injected into the
  game's own templates.
- Parameters generated from `// @param` annotations: sliders, color pickers and toggles, with groups and
  descriptions, updated in real time.
- Effect library (`Ctrl+L`) with 164 post-processing effects in 16 categories, 25 core shader examples and 9 starter
  templates, all as commented source.
- Resource pack export triggered by `/posteffect` or `end_of_frame`, and "Export and verify by real load" to test the
  pack the vanilla way before sharing it.
- GLSL code editor with syntax highlighting, completion, hover docs, find and replace, and paste clean-up.
- Debug tab: pixel probes, variable probes with exact read-back, undefined-behaviour checks and per-layer GPU timing.
- World-aware inputs: depth and camera helpers, anchors bound to entities, positions or death and hurt events,
  emitters with orientation, weapon trails and an entity outline layer.
- Project snapshots, recent history, dockable panels, a UI scale slider and an optional vector UI font.
- Optional AI generation (`Ctrl+G`) through OpenAI-compatible providers with your own API key. Keys are stored in
  `~/.gtshaders/`, outside `.minecraft`.
- English and Simplified Chinese UI and effect descriptions, following the game language.

### Known limitations

- Only Minecraft 26.3 is supported. Compatibility with Sodium and Iris has not been tested.
- Applying a core shader rebuilds the render pipelines and causes a short hitch.
- Some library effects contain bright flashes and fast flicker; they only play after you add them.
