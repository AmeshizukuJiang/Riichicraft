# v0.0.1 验证记录

日期：2026-10-15，Asia/Shanghai。当前已按用户要求暂停，不再启动新验证。

## 已通过

| 类别 | 实际执行结果 |
| --- | --- |
| 资源检查 | `Check-Resources.ps1`：8 个 JSON、6 个本地引用、3 个 PNG、模型边界、纹理变量、四朝向映射、中英文键一致，通过 |
| 工具链 | 项目内 JDK 25.0.4.1 的 Java／javac 版本检查通过；Gradle 分发及 Wrapper 核对官方 SHA-256 |
| 完整构建 | `build` 成功，生成正式 jar 与源码 jar，配置缓存已可保存 |
| 服务端 GameTest | 4 项 required tests 全部通过：本模组真实 BlockItem 四向放置／生存消耗、生存破坏恰好掉落一桌、创造破坏无掉落，以及框架自检 |
| 单人客户端 | `runClientGameTest` 成功：进入实际世界，检查资源／翻译，真实右键；双手空、主手持棒、副手持棒三种情况下每次只收到一次服务端反馈；已保存并检查桌面截图 |
| 专用服务器连接 | 实际 DedicatedServer 绑定 127.0.0.1 临时端口，测试客户端经 localhost TCP 加入；同一资源和交互场景通过 |
| 专用模式窗口配置 | 加入 `java.awt.headless=true` 后再次运行专用连接场景，成功，最终用时 1m 4s |
| 打包内容 | 正式 jar 包含正确的 id、版本、主类及所需资源，未包含测试类；共 37 个 ZIP 条目（含目录） |

产物：`build/libs/riichicraft-0.0.1.jar`，12,606 字节。

SHA-256：`2f5188ca2a52b6b31511224e50f7dd1b50219feb1fadedcf1a061fd1aae1215d`。

## 证据位置

- `.tmp/build.log`：完整构建与独立服务端 GameTest。
- `.tmp/client-test.log`：单人客户端测试。
- `.tmp/dedicated-test.log`：最后一次专用服务器连接测试。
- `docs/images/basic-table.png`：单人场景截图，已查看。
- `build/run/clientGameTest/screenshots/0000_riichicraft-dedicated-table.png`：专用场景截图。

`.tmp`、`build` 与工具缓存不提交 Git；本文件及普通桌截图提交，保留可阅读的验收摘要。

## 实际边界

独立服务端 GameTest 使用服务端进程和服务端类路径，不监听玩家 TCP；专用连接测试使用真实 DedicatedServer 与 TCP 连接，但测试服务器由测试客户端 JVM 内启动。两项分别记录，不能把前者当成客户端加入证明。

尚未进行正式启动器手动安装、发行 jar 在另一台机器上的联机、真人长期操作体验和最终美术验收。当前是基础桌原型，尚无座位、大厅或麻将对局。

测试日志中的系统信息采集和离线开发账号提示不影响上述通过结果；后续若遇到相关功能异常，再针对性处理。

用户在本聊天明确同意 EULA，仅授权本项目本地测试；没有使用或修改用户的真实游戏存档。外部 Gradle 缓存访问问题未获得同意，也未执行该访问；所有主动读取、下载、缓存和写入均在工作文件夹内完成。
