# Riichicraft

面向 **Minecraft Java 26.2 / Fabric** 的日本麻将模组。

从一张能与朋友打麻将的桌子起步，逐步发展为包含自动麻将桌、完整牌具、NPC、特殊结构、独立维度和主线剧情的模组。

## 当前状态

当前版本为 **`v0.0.3` 四人摸打原型**：136 张牌含三赤五、东位开局、私人手牌、四家牌河、选牌后确认出牌和自动摸牌已实现。

参与者掉线、死亡、跨维度或离桌太远会暂停并保留牌局；四人回桌后各自确认继续。关闭界面保留牌局，耗尽或二次确认终止后回大厅。破坏桌子、卸载区块或停服会终止临时牌局，本版不保存或恢复对局。当前不包含吃碰杠、立直、和牌或计分。详情见[摸打步骤](docs/DRAW_DISCARD.md)。

33 项服务端测试、真实客户端经 TCP 连接专用服务器的完整摸打与运行中断线重连检查已通过。接续位置见[接续记录](docs/SESSION_STATUS.md)，证据及验证边界见[验证记录](docs/VALIDATION.md)。

已确定：

- 名称：Riichicraft；模组 ID 采用 `riichicraft`。
- 目标平台：Minecraft Java 26.2 / Fabric。
- 初期功能目标：三人／四人，东风战／半庄战。
- 交互方式：实体麻将桌＋手牌操作界面，优先保证清楚易用。
- 长期方向：独立维度、特殊结构与主线剧情。

## 规划入口

- [完整规划](docs/PLAN.md)：玩法边界、物品、规则、架构、开发阶段及验收。
- [第一个开发步骤](docs/FIRST_STEP.md)：只实现工程与基础麻将桌的具体范围。
- [入座大厅设计](docs/LOBBY.md)：v0.0.2 已确认的交互与验证目标。
- [四人摸打原型](docs/DRAW_DISCARD.md)：操作流程、已确认生命周期与实现边界。

推荐顺序：

**基础桌 → 入座大厅 → 摸打原型 → 四人单局 → 四人东风 → 四人半庄 → 三麻两种赛制 → 首个玩法发布 → 自动桌与生存体验 → NPC 与结构 → 维度与剧情。**

每一阶段分别记录代码验证、Minecraft 实测与玩家体验结果。

## 开发与构建

Windows / PowerShell 7：

```powershell
./tools/Initialize-Dev.ps1
./tools/Check-Resources.ps1
./tools/Invoke-Gradle.ps1 build
```

首次运行会将固定版本 JDK、Gradle 和依赖下载到本项目。后续通过 `Invoke-Gradle.ps1` 使用项目专用环境，避免使用全局 Gradle 缓存。模组产物为 `build/libs/riichicraft-0.0.3.jar`，带 `-sources` 的文件是源码包。客户端与服务器都需安装同一版本的 Riichicraft 及兼容的 Fabric API；不同版本的网络协议不兼容。

```powershell
# 自动进入测试世界，验证客户端交互并保存截图
./tools/Invoke-Gradle.ps1 runClientGameTest

# 手动打开开发客户端
./tools/Invoke-Gradle.ps1 runClient
```

开发客户端里可以使用 `/give @s riichicraft:mahjong_table` 获得桌子，也可以在创造模式的功能方块分类中找到它。普通右键打开大厅，再点击“自动入座”；四人全部准备后，由东位开始摸打。潜行保留原版使用手持物品的行为。同一玩家只能占一桌，暂不提供 NPC 补位、坐姿动画和生存配方。大厅与牌局均为临时状态，桌子破坏、区块卸载或服务器停止时清理。

![四人摸打界面；其中三席为原生服务端测试玩家](docs/images/draw-discard.png)

- [开发流程与工具链](docs/DEVELOPMENT.md)
- [资源来源与重建](docs/ASSETS.md)
- 项目协作与文件访问约束见 [AGENTS.md](AGENTS.md)。
