# Riichicraft

面向 **Minecraft Java 26.2 / Fabric** 的日本麻将模组。

从一张能与朋友打麻将的桌子起步，逐步发展为包含自动麻将桌、完整牌具、NPC、特殊结构、独立维度和主线剧情的模组。

## 当前状态

上一个完整验证的版本 **`0.0.4-dev` 已接入四人单局**（`codex/four-player-rules`，`7c49a93`）：136 张牌含三赤五，私人手牌与公开牌河、吃碰杠、立直、自摸／荣和、流局及点数结算均可通过麻将桌界面操作。行动、牌河和结果支持分页，组合选择保留赤牌差异，食替与立直摸切由服务端限制。操作见[四人单局](docs/SINGLE_ROUND.md)。

当前分支 `codex/four-player-matches` 正在开发 `0.0.5-dev` 东风／半庄。排名与终算已单独验证，整场推进仍等待三项规则裁定，工作区尚不能作为可用整场版本构建或发布。具体进度见[整场开发记录](docs/MATCHES.md)。以下游戏说明对应上一个已验证的四人单局。

每局四家各 25,000 点，东场、东位为庄。和牌或流局后保留结算与牌河，任一参与者点击“返回大厅”带全桌返回并取消准备；重新准备开始的是全新 25,000 点单局，暂不自动连庄／轮庄。当前没有东风／半庄整场、三麻或 NPC 补位。

规则以雀魂普通四麻为基线，允许一炮三响，并如实标明用户直接裁定的边界，包括无役结构完成牌参与振听，以及流局满贯须门清（暗杠允许）。标准形／七对子／国士、役番符、宝牌、振听、抢杠与翻宝、流局及包牌均由独立核心裁决，服务端只发送本人私手和公开信息。详情见[四麻规则表](docs/FOUR_PLAYER_RULES.md)与[独立单局核心](docs/SINGLE_ROUND_CORE.md)。

活动局掉线、死亡、跨维度或离桌过远会暂停保留状态，四人回桌确认后继续。关闭界面可重开；终局掉线不撤销已结算点数。破桌、卸载区块或停服清理临时牌局，当前没有跨重启恢复或长期点数账户。

独立规则测试、Minecraft 服务端测试和真实客户端经 TCP 连接专用服务器均已通过，包括真实鼠标碰牌、立即弃牌、结算重开和终局重连；证据与产物见[验证记录](docs/VALIDATION.md)。TCP 配置为**一个真实客户端＋三名原生服务端内存夹具**，不代表四真人验收。人工视觉与长期操作手感另行验证；接续位置见[SESSION_STATUS.md](docs/SESSION_STATUS.md)。

![真实客户端的碰牌选择界面](docs/images/single-round-call.png)

## 规划入口

- [完整规划](docs/PLAN.md)：玩法边界、物品、规则、架构、开发阶段及验收。
- [第一个开发步骤](docs/FIRST_STEP.md)：只实现工程与基础麻将桌的具体范围。
- [入座大厅设计](docs/LOBBY.md)：v0.0.2 已确认的交互与验证目标。
- [四人摸打原型](docs/DRAW_DISCARD.md)：v0.0.3 历史范围与活动局暂停方案。
- [四人单局](docs/SINGLE_ROUND.md)：当前游戏操作、分页菜单、结果保留、生命周期与验证边界。
- [四人整场开发](docs/MATCHES.md)：已确认的东风／半庄交互、待决边界与本次验证进度。
- [四麻规则基线](docs/FOUR_PLAYER_RULES.md)：雀魂官方来源、用户裁定与整场待核实边界。
- [独立规则核心](docs/RULES_CORE.md)：牌形、计分、振听与各组件的代码契约和验证索引。
- [独立单局引擎](docs/SINGLE_ROUND_CORE.md)：阶段、身份、稳定响应窗、私有视图与守恒。
- [振听与和牌检查](docs/FURITEN.md)：历史状态、无役见逃及指定机会裁决。
- [弃牌响应窗口](docs/DISCARD_RESPONSES.md)：三家收集、动作优先级、多人荣和与重放边界。
- [鸣牌与牌山](docs/CALL_ACTIONS.md)：吃碰明杠的物理选项、食替、手牌执行与活牌／王牌守恒。
- [立直与账本](docs/RIICHI_AND_LEDGER.md)：宣言成立、一发、准确弃牌与小局和牌支付、供托守恒和组件包牌。
- [杠流程](docs/KAN_FLOW.md)：暗杠／加杠选择、抢杠响应与见逃、翻宝、第四杠和组合验证。

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

首次运行会将固定版本 JDK、Gradle 和依赖下载到本项目。后续通过 `Invoke-Gradle.ps1` 使用项目专用环境，避免使用全局 Gradle 缓存。上一个已验证分支的产物为 `build/libs/riichicraft-0.0.4-dev.jar`；当前 `0.0.5-dev` 尚未完成构建。带 `-sources` 的文件是源码包；v0.0.3 检查点构建对应 `riichicraft-0.0.3.jar`。客户端与服务器都需安装同一版本的 Riichicraft 及兼容的 Fabric API；不同版本的网络协议不兼容。

只验证独立规则，不启动游戏或 Gradle：

```powershell
./tools/Test-Rules.ps1
```

```powershell
# 自动进入测试世界，验证客户端交互并保存截图
./tools/Invoke-Gradle.ps1 runClientGameTest

# 手动打开开发客户端
./tools/Invoke-Gradle.ps1 runClient
```

开发客户端里可以使用 `/give @s riichicraft:mahjong_table` 获得桌子，也可在创造模式功能方块分类中找到。普通右键打开大厅、自动入座并准备；四人准备后由东位“开始本局”。潜行保留原版使用手持物品的行为，同一玩家只能占一桌。具体鸣牌、立直与结算操作见[四人单局](docs/SINGLE_ROUND.md)。

- [开发流程与工具链](docs/DEVELOPMENT.md)
- [资源来源与重建](docs/ASSETS.md)
- 项目协作与文件访问约束见 [AGENTS.md](AGENTS.md)。
