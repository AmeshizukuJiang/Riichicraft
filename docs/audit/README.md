# 审计执行证据与最小复现

日期：2026-10-19。生产源码基线 `0b5618e`／`0.0.16-dev`。主结论见[代码审计](../CODE_AUDIT.md)。本目录的 Java 文件为主动暴露缺陷的复现，**未加入默认测试注册，不打进模组**；在未修复版本上失败是预期结果，不能与基线回归失败混为一谈。

## 本次实际执行

| 检查 | 结果与证据 |
| --- | --- |
| 未改动源码的 `build sourcesJar` | **1分59秒成功**；27套规则／5,661,287断言；88 required GameTest／19.51秒通过。日志 `.tmp/audit-baseline-build.log` |
| 只读资源检查 | **115 JSON／105本地引用／45 PNG通过**，包含44组家具朝向／部件映射、纹理变量及双语键 |
| 未改动源码的完整 TCP 基线 | **2分13秒失败**，在恢复后推进一手已荒牌，却继续点隐藏的终止按钮。日志 `.tmp/audit-baseline-client.log:1108`；该次原生截图实际显示荒牌与返回大厅。归因为 T1 测试轨迹假设，不当成生产终止／保存失败 |
| A1 独立核心定向复现 | 使用项目JDK编译真实108墙轨迹，第三次拔北已接受，守恒通过，归档恢复成功；随后本人视图 `KanActions.java:96` 抛异常。根代理再次独立运行，退出1。日志 `.tmp/audit-core-reproduction-root.log` |
| A2 原生服务端定向复现 | 临时注册3项检查，每项覆盖三麻／四麻；**原88项仍通过，新增3项全部按预期失败**，91项总18.22秒。六分支均 `released=false, replacement accepted=false`。日志 `.tmp/audit-server-reproductions.log:780` |
| A3／A4 客户端定向复现 | 真实TCP坐席 R→V→Esc 检查失败；实际客户端帧函数检查也失败：旧牌 `x=-17.65,z=20.5`，飞牌起点 `x=6.01,z=20.5`。两项均执行并收集后统一抛断言，**1分54秒预期失败**。日志 `.tmp/audit-client-reproductions.log:837` |
| T1 测试修正后的完整 TCP | **2分35秒成功**，包括后续三麻、家具、坐席及正常世界重开。日志 `.tmp/audit-client-after-test-fix.log`。生产代码与协议未改变；只修复测试终局分支，并实际读取磁盘归档核对清理 |
| 收尾检查 | **35份Markdown／396本地链接／11源码行锚点、45役双语名、Git空白检查通过**。生产源码／资源和测试入口无差异；临时审计class已移除，Gradle已停止 |

首次基线及预期失败日志均保留在项目忽略目录；临时注册、插入调用和测试源文件在 `finally` 中恢复／移除。提交中的复现代码仅在 `docs/audit/repro`，默认构建不会执行它们。没有把故障注入存档日志、离线账号401或 Windows系统信息警告当成新产品故障。

测试使用一个实际 TCP 客户端及原生内存连接夹具；世界恢复为同 JVM 内正常关闭后启动另一个原生专用服务器实例。不是四个真人客户端、硬崩溃、整机重启或长期主观手感验收。

## A1：独立规则复现

文件：[RiichiNorthExhaustion.java](repro/RiichiNorthExhaustion.java)。没有反射、私有状态注入或伪造立直标志，三次拔北均走公开规则 API；该复现刻意在有问题的查询处失败。

在项目内完成正常编译后，用本项目 JDK 将此文件编译到忽略的 `.tmp/audit/core-classes`，类路径为 `build/classes/java/rulesCore`；主类为 `dev.riichicraft.rules.RiichiNorthExhaustion`。运行类路径包含这两个目录。Java `user.home`、临时目录仍须按项目工具入口限定在仓库内。期望错误摘录：

```text
before extraction 1: offered=[121]
accepted extraction 1: held norths=[121]
before extraction 2: offered=[122]
accepted extraction 2: held norths=[121, 122]
before extraction 3: offered=[123]
accepted extraction 3: held norths=[121, 122, 123]
restored accepted state, public view=DRAWN_TURN
IllegalArgumentException: An established riichi hand must have a preceding structural wait
    KanActions.options(KanActions.java:96)
    ThreePlayerRound.turnOptions(ThreePlayerRound.java:259)
    ThreePlayerRound.view(ThreePlayerRound.java:135)
```

修复时应将该轨迹转为正常通过的正式回归，并继续驱动摸切／流局／恢复；本文件当前不是这样的成功回归。

## A2：真实服务端座位转换

文件：[AuditServerGameTests.java](repro/AuditServerGameTests.java)。临时复制到 `src/gametest/java/dev/riichicraft/server/`，在测试模组 `fabric-gametest` 入口临时加入 `dev.riichicraft.server.AuditServerGameTests` 后运行 `runGameTest`；备份并恢复原入口与临时源，禁止修改正式模组入口。

三个测试实际建立大桌、普通／空椅、三／四名原生连接，先准备开局，再进行断线或离椅，最后尝试新人在原椅补位。断线玩家确实从原生 PlayerList 移除；终局通过真实摸切和放弃响应自然到达，没有注入终局字段。

```text
abortReleasesParticipantWhoDisconnectedDuringPlay
returnLobbyReleasesParticipantWhoDisconnectedAfterResult
abortReleasesParticipantWhoLeftChairDuringPlay

每项相同失败：
[3p: released=false, replacement accepted=false,
 4p: released=false, replacement accepted=false]
```

修复应使六个分支均通过，另保护仍在椅上的成员、旧桌界面关闭和重复请求的行为。

## A3／A4：客户端键盘与帧来源

文件：[AuditClientChecks.java](repro/AuditClientChecks.java)。临时复制到测试客户端包，不加入生产客户端源码。

调用点是 `ImmersiveClientScenario.run` 原有“Reopening controls preserves the normal chair view”断言之后、下一次按 V 之前。先在客户端线程调用 `verifySelectedDiscardOrigin()`，再在测试线程调用 `verifyEscapeFromHiddenOverhead(context)`。本次收集两个 `AssertionError` 的文本后统一失败，避免首个预期失败遮住另一项；真实输入检查的 `finally` 会恢复原坐席／正常镜头操作层。

```text
AUDIT: selected discard must begin at its actual previous private-hand position
old=Pose[x=-17.65, y=1.18875, z=20.5, yaw=0.0, pitch=0.0, scale=0.85]
flying=Pose[x=6.01, y=1.18875, z=20.5, yaw=0.0, pitch=0.0, scale=0.85]
AUDIT: Esc must exit overhead even when TableScreen is hidden
```

帧检查只比较 x/z，不混淆私有牌尺度、竖放高度和新摸牌间距。它是明确构造的客户端布局输入；Esc 是实际坐席和实际客户端按键。原摸牌闪现修复、两种姿态和迟到消息也仍需在未来修复中保持原有回归。

## 默认构建与正式产物

本轮没有修改 `src/main`、`src/client`、生产资源、依赖或版本。`PersistenceClientScenario` 的 T1 修正属于测试模组，不进入正式 JAR。生产 JAR SHA-256 仍为：

```text
2dc248499ec8e60851dc4d73b0e91c63e9db8657f6f3ceef596b1f0e1e03d7a5
```

没有新开发版本标签或发布包；以上四项生产缺陷在当前包中仍未修复。远程同步与最终检查结果见[接续记录](../SESSION_STATUS.md)。
