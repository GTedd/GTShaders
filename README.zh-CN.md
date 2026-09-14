# GTShaders

[English](README.md) | **简体中文**

游戏内的 Minecraft 着色器可视化编辑器。写一个后处理效果，或改 21 种原版核心着色器中的任意一种，
拖动参数、在实时游戏画面上看效果，最后导出成**不需要本模组**也能使用的普通资源包。

- **状态**：alpha 开发版，基于 Minecraft 26.3-rc-2 构建；0.1.0 正式版之前可能有 bug 和不兼容改动
- **Minecraft**：26.3（Java 25），仅客户端
- **加载器**：Fabric Loader 0.19.5+，需要 [Fabric API](https://modrinth.com/mod/fabric-api)
- **许可证**：[MIT](LICENSE)；第三方来源与鸣谢见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- **更新日志**：[CHANGELOG.md](CHANGELOG.md)（英文）

## 功能

- **走原版管线的实时预览**：着色器用游戏自带的 ShaderC 编译，在内存里挂进原版后处理链，不重载资源、不用重启。
  改到一半编译不过时，画面停在上一个能用的版本，错误行在编辑器里标红。
- **不写界面代码的参数**：用 `// @param` 注解一个值，编辑器自动生成滑块、取色器或开关；拖动只写 uniform，实时生效。
- **160 多个现成效果**：可搜索的效果库（`Ctrl+L`），涵盖战斗与状态、命中反馈、转场、震屏、魔法、科幻界面、
  光影、恐怖、天气、复古风格、镜头、调色与故障等，每个都是带注释、可读可改的源码。
- **不只是后处理**：写 `gtVertex` 与 `gtFragment` 两个钩子函数，就能改地形、实体、天空、物品、光照贴图等核心着色器。
  钩子注入到游戏自己的模板里，布局、include 和各种 define 变体都保持正确。
- **导出与预览一致**：一键导出资源包，用 `/posteffect add @s gtshaders:<id>` 触发，或通过 `end_of_frame` 常驻。
  「导出并真实加载验证」会按原版方式装载一次，分享之前先确认能用。
- **代码编辑器**：语法高亮、补全、悬停说明、查找替换、粘贴时自动清洗全角符号。
- **调试页**：点画面取样、变量探针（按位精确回读）、未定义行为检查、每层 GPU 耗时。
- **版本快照**、可停靠的面板、界面缩放。
- **锚定到世界的效果**：绑定实体、坐标或事件（死亡、受伤）的锚点，带朝向的载体，武器轨迹，实体轮廓层。
  这些需要装着模组才有数据，导出成纯资源包后会自动退化。
- **可选的 AI 生成**，使用你自己的 API Key（见下文）。
- **中文与英文界面**，跟随游戏语言切换；其他语言可以放进 `config/gtshaders/lang/`。

## 快速开始

1. 安装 Fabric Loader 与 Fabric API，把 GTShaders 的 jar 放进 `mods/`。
2. 进入任意世界（后处理作用在渲染好的世界画面上，标题界面没有可处理的画面）。
3. 按 **Insert** 打开编辑器，**Ctrl+L** 打开效果库，选一个效果点「添加到工程」，浮动面板里就是它的全部参数。

添加或启用效果层之前，画面不会有任何变化。想自己写，就在一层的源码里写好并勾选「启用」：

```glsl
// 柔和染色 / Soft Tint
// @param name=Strength type=float min=0 max=1 default=0.5 zh_cn=强度 en_us=Strength
// @param name=Tint type=color3 default=#99CCFF zh_cn=颜色 en_us=Tint

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    fragColor = vec4(mix(col, col * Tint, Strength), 1.0);
}
```

## 按键

| 按键 | 作用 |
|---|---|
| `Insert` | 打开 / 关闭编辑器 |
| `Delete` | 开关实时预览 |
| `Home` | 绑定模式：用准星把效果绑到世界里的东西上 |
| `B` | 在准星处触发选中的锚点 |
| `PageUp` | 让准星指着的实体在本地发光（预览轮廓效果用） |
| `Ctrl+L` | 效果库 |
| `Ctrl+G` | AI 生成 |
| `Ctrl+S` / `Ctrl+K` | 保存工程 / 提交快照 |
| `Ctrl+Enter` | 立即编译并应用 |
| `Ctrl+E` | 在光标处开变量探针 |

全局按键可在「选项 → 控制」中修改。

## 导出

在「导出」页选好触发方式后点「导出资源包」，产物在 `config/gtshaders/export/`。

| 触发方式 | 用法 | 说明 |
|---|---|---|
| 指令 | `/posteffect add @s gtshaders:<id>` | 可按玩家、按时机控制；需要权限等级 2 |
| `end_of_frame` | 启用资源包即常驻 | 无法用指令关闭 |

只有模组才能提供的输入（世界锚点、载体、武器轨迹、`GTDeltaTime`、`GTFrame`、`GTPlaying`）在纯资源包里恒为 0。
导出面板和包里的 `README.txt` 会列出工程用到了哪些。

## AI 生成（可选，自备 Key）

`Ctrl+G` 把一句描述变成一个效果。生成结果在本机编译，编译错误会自动回传给模型修正，最多三轮。

- 不内置任何 Key；配置好服务商并点「生成」之前，不会发出任何请求。
- 支持 OpenAI 兼容接口：DeepSeek（默认预设）、OpenAI、OpenRouter、智谱 GLM、Kimi 月之暗面、阿里百炼、硅基流动，
  以及本机的 Ollama / LM Studio。
- 发送的内容：你的描述、生成的着色器与编译错误，只发给你选择的服务商。
- Key 保存在 `~/.gtshaders/credentials.json`，在 `.minecraft` 之外，导出整合包时不会被带走。

## 文件位置

| 路径 | 内容 |
|---|---|
| `config/gtshaders/` | 工程、快照、界面布局、最近使用 |
| `config/gtshaders/export/` | 导出的资源包 |
| `config/gtshaders/lang/` | 新增或覆盖界面语言（`<语言代码>.json`） |
| `config/gtshaders/font/` | 可选的 `.ttf` / `.otf` 字体，用于矢量界面字体 |
| `config/gtshaders/vanilla/<版本>/` | 其他版本的原版着色器，用来给那个版本导出核心着色器 |
| `~/.gtshaders/credentials.json` | AI 服务商的 Key |

## 兼容性与限制

- 只支持 Minecraft 26.3，它的着色器格式与 26.2 不同。
- 尚未测试与 Sodium、Iris 的兼容性。
- 应用核心着色器会重建渲染管线，会有一下短暂卡顿；每种核心着色器只能有一层生效，参数编译为常量。
- 部分库效果包含强烈闪光与快速闪烁，只在你添加后才会播放。

## 从源码构建

需要 JDK 25，Gradle 由 wrapper 自动获取。

```bash
./gradlew build        # 产物在 build/libs/；会跑单元测试，并用 ShaderC 编译整个效果库
./gradlew runClient    # 开发客户端
```

`build` 中的 `checkShaderLib` 会用游戏自带的 ShaderC 编译所有库效果。如果还想用真实原版模板校验核心着色器示例，
把对应版本 `client.jar` 里的 `assets/minecraft/shaders/` 解压到 `docs/vanilla/<minecraft_version>/shaders/`
（该目录已被 git 忽略，Mojang 的资产不能提交）。

## 许可证与鸣谢

GTShaders 以 [MIT 许可证](LICENSE) 发布。部分效果与技术参考或改编自他人的作品，
来源、许可证与鸣谢见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

GTShaders 不是 Minecraft 官方产品，未经 Mojang 或 Microsoft 认可，也与其无关。
