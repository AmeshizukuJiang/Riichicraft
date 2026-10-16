# 独立规则核心：牌形基础

开发版本：`0.0.4-dev`。本批只完成牌形和真实可和的结构等待，未接入 Minecraft；不是完整四麻规则发布。

用户已确认以雀魂普通四麻为规则基线，允许一炮三响。来源、已修正差异及后续待核实项见 [FOUR_PLAYER_RULES.md](FOUR_PLAYER_RULES.md)。

## 当前代码边界

| 文件 | 职责 |
| --- | --- |
| `Meld` | 已声明吃、碰、开放杠、暗杠；使用唯一物理牌 ID，验证牌组结构与防御拷贝 |
| `HandInput` | 手牌及声明组；验证物理牌不重复、数量与杠数、牌种上限，区分 13／14 张结构状态及门清 |
| `HandDecomposition` | 使用牌种表示标准形的雀头和四组面子、七个对子或国士重复牌；固定副露不会被拆开重组 |
| `HandAnalyzer` | 返回所有不同的标准分解及七对子／国士解释；对 13 张结构状态返回可补的牌种集合 |

文件位于 `src/main/java/dev/riichicraft/rules/`。仅复用纯 Java 的 `game/TileCatalog`，不引用 Minecraft、Fabric 或第三方规则库。

输入牌 ID 为 0～135，牌种为 `ID / 4`；赤五的结构等同普通五，保留原物理 ID 供未来计分。声明杠占一组面子，完整手实际牌数为 14＋杠数；即使暗杠保持门清，也不再允许七对子或国士。

`HandInput.isComplete()` 只表示 14 张结构数量，`isWaiting()` 只表示 13 张结构数量，均不是和牌／听牌合法性结论。真正结构完成由 `HandAnalyzer.analyze()` 的非空结果表示。

`waits()` 不考虑公开牌河、牌山中还剩几枚、役或振听；全手（含声明组）已经持有四枚的牌种不会被返回。雀魂的流局形式听牌可能有专门边界，后续须独立处理，不能拿这个接口直接作为流局听牌裁决。

当前分解尚不绑定具体和牌张及两面／边张等和牌位置，也不记录加杠前后的动作历史。后续计分需要在完整分解上枚举和牌张位置，并结合独立的和牌上下文；不要仅取第一个分解。

## 独立验证方式

使用项目内固定 JDK，直接编译和运行，不启动 Gradle、Fabric 或 Minecraft：

```powershell
./tools/Test-Rules.ps1
```

脚本只编译 `rules/`、`TileCatalog.java` 与 `src/rulesTest/java/`，使用每次新建的空类路径；产物只写入忽略的 `.tmp/rules-tests-*/`。所有断言显式失败即非零退出，不依赖全局测试框架。

也可通过项目构建执行：

```powershell
./tools/Invoke-Gradle.ps1 rulesTest
./tools/Invoke-Gradle.ps1 build
```

`rulesCore`／`rulesTest` 为独立 source set，测试进程没有 Minecraft 或 Fabric 依赖；`check` 包含此测试。正式模组仍由 `main`／`client` 打包，测试类不随模组发布。

已执行的次数、样本数和结果以 [VALIDATION.md](VALIDATION.md) 为准；新增测试入口本身不等于已通过。

## 后续顺序

在牌形层通过独立对照后，再分批加入：和牌张解释、役与符／支付、振听与响应／杠状态机、多人和与流局／整场轮转。未取得精确依据的雀魂边界先保留证据缺口，进入对应实现前核实；不将旧自定义建议充当默认行为。

当前 v0.0.3 的联网摸打、暂停及重连流程未接入这些新接口，也没有新增和牌按钮或自动胜负结算。
