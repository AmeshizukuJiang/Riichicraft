# Riichicraft

面向 **Minecraft Java 26.2 / Fabric** 的日本麻将模组。

从一张能与朋友打麻将的桌子起步，逐步发展为包含自动麻将桌、完整牌具、NPC、特殊结构、独立维度和主线剧情的模组。

## 当前状态

2026-10-15：已实现 v0.0.1 基础麻将桌，完成构建、服务端 GameTest、单人客户端及本地专用服务器连接验证。当前包含普通桌的注册、四向放置、碰撞、原型状态提示、掉落与原创模型；不包含入座或麻将对局。

当前按用户要求暂停；[接续记录](docs/SESSION_STATUS.md)与[验证记录](docs/VALIDATION.md)已保存。

已确定：

- 名称：Riichicraft；模组 ID 采用 `riichicraft`。
- 目标平台：Minecraft Java 26.2 / Fabric。
- 初期功能目标：三人／四人，东风战／半庄战。
- 交互方式：实体麻将桌＋手牌操作界面，优先保证清楚易用。
- 长期方向：独立维度、特殊结构与主线剧情。

## 规划入口

- [完整规划](docs/PLAN.md)：玩法边界、物品、规则、架构、开发阶段及验收。
- [第一个开发步骤](docs/FIRST_STEP.md)：只实现工程与基础麻将桌的具体范围。

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

首次运行会将固定版本 JDK、Gradle 和依赖下载到本项目。后续通过 `Invoke-Gradle.ps1` 使用项目专用环境，避免使用全局 Gradle 缓存。构建产物为 `build/libs/riichicraft-0.0.1.jar`，带 `-sources` 的文件是源码包。

```powershell
# 自动进入测试世界，验证客户端交互并保存截图
./tools/Invoke-Gradle.ps1 runClientGameTest

# 手动打开开发客户端
./tools/Invoke-Gradle.ps1 runClient
```

开发客户端里可以使用 `/give @s riichicraft:mahjong_table` 获得桌子，也可以在创造模式的功能方块分类中找到它。普通右键显示原型提示；潜行保留原版使用手持物品的行为。当前桌子用于验证模组基础交互。

- [开发流程与工具链](docs/DEVELOPMENT.md)
- [资源来源与重建](docs/ASSETS.md)
- 项目协作与文件访问约束见 [AGENTS.md](AGENTS.md)。
