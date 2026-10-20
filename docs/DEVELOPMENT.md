# 开发流程

## 工作边界

文件访问遵守根目录 `AGENTS.md`：访问工作文件夹外的文件，先说明具体用途并获得用户同意。项目专用工具、缓存和测试世界保存在仓库内的忽略目录，避免依赖其他工程或玩家存档。

| 目录 | 用途 | 提交 Git |
| --- | --- | --- |
| `src/main` | 通用／服务端代码、游戏资源 | 是 |
| `src/client` | 大厅／手牌界面、客户端网络与视图生命周期 | 是 |
| `src/gametest` | 独立测试模组与测试代码 | 是；不打进正式模组 |
| `src/rulesTest` | 纯 Java 规则测试，不依赖游戏或第三方测试库 | 是；不打进正式模组 |
| `tools` | 初始化、构建入口和资源检查 | 是 |
| `.tools` | JDK、初始化下载、Java 专用 home | 否 |
| `.gradle-home`、`.gradle` | Gradle 全部构建缓存 | 否 |
| `.tmp` | 临时文件 | 否 |
| `build`、`run` | 产物、报告、开发／测试世界与日志 | 否 |

## 独立地形生成边界

`src/main/java/dev/riichicraft/worldgen`只维护测试维度标识与管理员往返命令；麻将规则、网络与牌局归档不依赖它。`src/main/resources/data/riichicraft/dimension`定义维度和群系来源，`dimension_type`定义高度／天空边界，`worldgen/noise`与`density_function`定义连续区域和地形，`worldgen/noise_settings`定义填充及按群系着色，`worldgen/biome`定义三个真实群系。目前群系的features／carvers／spawners为空，无运行时逐块替换器、主世界覆盖或新增生成依赖。调参入口见[地形样板](TERRAIN_PROTOTYPE.md)。

## 固定工具链

| 组件 | 当前版本 |
| --- | --- |
| Minecraft Java | 26.2 |
| Java | Microsoft OpenJDK 25.0.4.1+1，Windows x64 |
| Gradle Wrapper | 9.7.1 |
| Fabric Loom | 1.18.4 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.161.0+26.2 |
| Java 包 | `dev.riichicraft` |
| 模组 ID | `riichicraft` |

依赖版本固定在 `gradle.properties`；不使用动态 `latest` 或 Loom SNAPSHOT。Java 初始化下载及 Gradle 分发均核对 SHA-256。Gradle 禁止自动发现／下载外部 JDK。

`tools/Invoke-Gradle.ps1` 只搜索 `.tools/java` 中的 JDK，在该次调用中设置 `JAVA_HOME`、`GRADLE_USER_HOME`、临时目录与 Java `user.home`。它不修改系统环境变量或全局配置。网络下载依赖属于初始化和首次构建的正常步骤。

## 常用命令

在仓库根目录使用 PowerShell 7：

```powershell
./tools/Initialize-Dev.ps1
./tools/Check-Resources.ps1
./tools/Invoke-Gradle.ps1 build
./tools/Invoke-Gradle.ps1 runClientGameTest
./tools/Invoke-Gradle.ps1 runClient
```

规则基础可单独运行，不启动游戏或 Gradle：`./tools/Test-Rules.ps1`。也可用 `./tools/Invoke-Gradle.ps1 rulesTest`；`rulesCore` 编译类路径为空，测试进程仅使用纯规则和测试的输出目录。`check` 自动包含规则验证，具体范围见 [RULES_CORE.md](RULES_CORE.md)。

2026-10-20用户明确：开发阶段不以兼容历史测试版存档为维护目标，不新增旧版迁移分支。验证重点为当前版本的规则、状态约束、保存恢复及错误处理。现有固定v16资源保留作确定性回归输入，来源见[历史回归样本](LEGACY_ARCHIVES.md)；未来若格式或规则变化，可按实际测试价值调整，不为保持这些旧字节增加生产兼容代码。

从`0.0.18-dev`起，新旧客户端界面共用`GamePresentation`的结算摘要与文字转换；布局和合法动作选择仍由原界面管理。真实客户端启动时先运行48组原v17显示样本／11组动作标签对照，再执行完整游戏场景。基准由原class字节校验后采集，默认测试只读取，不重新生成；范围见[共用展示](SHARED_PRESENTATION.md)。

从`0.0.19-dev`起，`GameSnapshotProjector`只转换核心已过滤的View与服务端元数据；`LobbyService`继续管理身份、在场、动作、暂停／等待、保存及里宝公开。GameTest复用原规则历史资源，并将19个实际核心情形的416份编码与固定原v18封包逐字节对照。普通测试只读，手动采集开关默认关闭且要求原JAR／服务class字节匹配；新服务无法重新生成旧预期。边界与命令见[服务端快照转换](SERVER_PROJECTION.md)，测试资源和采集器不进入正式包。

从`0.0.20-dev`起，`TablePersistenceCoordinator`集中保存路径、待保存标记、失败／损坏与脱离运行态状态，执行全量名册校验及检查点写入。`LobbyService`保留当前核心记录构造、运行对象安装及玩家通知。协调器按需调用记录提供者，不缓存失败时的旧快照；没有旧版迁移器或新依赖。详见[存档协调](PERSISTENCE_COORDINATION.md)。

`build` 包括配置好的服务端 GameTest；`runClientGameTest` 另行启动实际 Minecraft 客户端，运行后自动关闭并输出截图。游戏测试是否通过以实际报告为准，配置测试入口本身不代表已验证。

`src/main/java/dev/riichicraft/rules` 是不依赖 Minecraft 的四人单局核心；`game/DrawDiscardGame` 保留为旧摸打原型的独立回归对象，活动桌不再用它裁决。`LobbyService` 管理桌子、连接权限和临时生命周期，分别维护外层界面修订与核心动作修订；普通意图严格匹配界面修订，三家响应使用稳定小局／窗口号，不因另一家先答而失效。网络只携带接收者有权查看的数据，所有嵌套集合在分配前检查长度。测试模组与正式模组分开，正式 jar 不包含测试类。客户端与服务器需使用同一 Riichicraft 版本，当前未实现跨协议版本兼容。

若 Fabric 主源下载超时，可以在该次调用中选用 Fabric 官方安装器使用的备用下载源：

```powershell
./tools/Invoke-Gradle.ps1 '-Ploom_fabric_repository=https://maven2.fabricmc.net/' build
```

PowerShell 中包含 URL 的 `-P` 参数需要整段加引号。此选项同时影响插件源与 Loom 的 Fabric 依赖源；仓库默认仍使用官方主源。备用源适合故障时临时使用，不改变固定依赖版本。首次环境准备中如需人工补齐缓存，先核对官方校验值，再放进本项目缓存，不使用其他工程或全局目录。

后续手动使用专用开发服务器时执行 `runServer`；如引擎提示需要同意 Minecraft EULA，应由用户确认后设置项目内相应配置。项目初始化脚本不会修改用户的游戏安装或默认存档。

2026-10-15 用户已明确同意 Minecraft EULA，授权用于本项目的本地测试。专用服务器自动连接验证命令：

```powershell
./tools/Invoke-Gradle.ps1 '-Ploom_fabric_repository=https://maven2.fabricmc.net/' '-Priichicraft_accept_eula=true' '-Pverify_dedicated_server=true' runClientGameTest
```

地形样板使用独立的可选测试入口：

```powershell
./tools/Invoke-Gradle.ps1 '-Priichicraft_accept_eula=true' '-Pverify_dedicated_server=true' '-Priichicraft_terrain_test=true' runClientGameTest
```

`riichicraft_terrain_test`默认关闭。启用后按三个固定种子创建项目内独立测试世界`riichicraft-terrain-seed-{0,1,2}-{a,b}`，A正常保存重开，B逆序请求区块；这组固定测试目录会重建，不得改成玩家存档。调查和测量报告保存在`.tmp/terrain-prototype`，真实客户端截图保存在客户端测试截图目录；预测图与实际截图分别记录。新旧区块、群系与方块对应、命令往返和重载由真实专用服务器验证，是否整套通过以[地形说明](TERRAIN_PROTOTYPE.md)及[验证记录](VALIDATION.md)为准。

原版26.2的`GameTestServer`把FLAT预设与空LEVEL_STEM注册表合并，忽略额外数据包维度。因此原生GameTest只验证真实资源的`LevelStem.CODEC`解析、动态群系及生成配置；它不证明维度已实际加载。正常创建世界与专用服务器会合并数据包维度，地形实测采用该正常启动路径，不覆盖原版预设或伪造ServerLevel。

EULA 选项默认为 false，不把当前用户的同意写成所有使用者的默认选择。麻将专用测试仅监听 `127.0.0.1`，使用临时端口及项目内的 `riichicraft-dedicated-test-world`；测试会重建该测试世界。地形测试采用上列独立世界及相同本机监听边界。专用模式使用 AWT headless 避免额外弹出 Swing 服务器面板，Minecraft 的 GLFW 测试客户端仍正常运行。

单局场景使用一名真实客户端和三名原生 `ServerPlayer`／`EmbeddedChannel` 夹具。专用测试将最大玩家数设为 8，以允许夹具仍在时真实客户端断线重连；不代表八个真实客户端已经验收。场景验证合法阶段驱动、私有手牌、真实鼠标弃牌、结算保留／重开／返回，以及活动局暂停恢复和终局 TCP 重连；具体已执行证据见 [VALIDATION.md](VALIDATION.md)。每次客户端测试可能重建截图目录，保留证据时应先复制到 `docs/images`。构建日志、测试世界和临时报告保留在忽略目录。

## 每一步的交付流程

1. 检查 Git 状态，选定本次小步骤及对应验收。
2. 方向需要变更时先沟通；范围内实现细节自行完成。
3. 修改代码和资源，执行资源检查与构建。
4. 运行相关游戏内自动测试；只有必要且无法自动完成的实机验证才请用户参与。
5. 检查差异，更新状态、实际证据和待验收事项，创建本地提交。

开发分支使用 `codex/` 前缀。用户在 2026-10-16 已授权合适节点直接 commit／push／tag；先核对目标 GitHub 仓库，仅推送验证过的提交，不强制推送或覆盖已有标签。当前本地提交使用 `Codex <codex@openai.com>` 作为代理身份；不冒用用户身份、不修改全局 Git 配置。

## 来源与许可状态

- 工程构建方式参考 [Fabric 官方 26.2 示例工程](https://github.com/FabricMC/fabric-example-mod/tree/26.2)，本次核对的提交为 `1ce1c77a77ddbf7587e0d171ea369051668a67a6`。
- Wrapper 文件来自上述官方示例；脚本保留原有 Apache-2.0 版权与许可说明。
- JDK 下载来自 [Microsoft OpenJDK 官方下载页](https://learn.microsoft.com/en-us/java/openjdk/download)，仅存于本地忽略目录，不随模组发布。
- 游戏测试配置参考 [Fabric 26.2 自动测试指南](https://docs.fabricmc.net/develop/automatic-testing)。
- 模组原创内容的公开发布许可证尚未选择。发布前再与用户确定；当前不作开源许可承诺。
