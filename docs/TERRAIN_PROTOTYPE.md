# 0.1 独立地形与群系样板

目标：Minecraft Java 26.2 / Fabric；版本`0.1.0-dev`。本次只验证独立维度、三群系和地形生成，不固定最终文化主题。

## 实现与进入方式

在安装同版模组的新测试世界中开启作弊／获得OP2，使用`/riichicraft terrain enter`进入，`/riichicraft terrain leave`离开。F3维度为`riichicraft:terrain_test`；三个群系是`riichicraft:test_region_a`、`riichicraft:test_region_b`、`riichicraft:test_region_c`，可用原版群系查询识别。

26.2简化F3默认可能隐藏群系行，可按`F3+F6`在原版调试配置中启用群系项；模组保留玩家的原版调试设置，测试只临时调整项目运行目录的配置。

维度使用原版`minecraft:noise`生成器与`minecraft:multi_noise`群系来源。所有固体着色发生在正常SURFACE生成阶段，没有运行时逐块替换器。主世界生成配置没有覆盖。

进入时搜索正常生成地表的安全落脚点，不改变玩家模式；乘坐中拒绝传送。离开优先回本次服务器运行内记录的原位置／朝向；原位置失去支撑时改找附近地表。服务器重开后若已无内存返回点，则回主世界出生点附近。没有新增玩家归档或麻将协议。

## 参数在哪里

| 要调整的内容 | 文件 | 调整方法 |
| --- | --- | --- |
| 维度、群系来源及区域比例 | [dimension/terrain_test.json](../src/main/resources/data/riichicraft/dimension/terrain_test.json) | 三个temperature区间目前以−0.16与0.16分界；扩大某区间通常增加该群系面积，有限窗口不会精确等比例 |
| 区域尺度与碎片 | [noise/terrain_test/regions.json](../src/main/resources/data/riichicraft/worldgen/noise/terrain_test/regions.json)、[density regions](../src/main/resources/data/riichicraft/worldgen/density_function/terrain_test/regions.json) | 主octave目前−9，减1约扩大一倍；或将xz_scale减半。高频振幅保持小，减少小斑块 |
| 平地、丘陵与高地高度 | [height.json](../src/main/resources/data/riichicraft/worldgen/density_function/terrain_test/height.json) | 第一组spline控制宏观高度，当前控制点约76～188；第二组spline控制细节幅度约3～18。修改value及相邻derivative以保留连续过渡 |
| 起伏的水平尺度 | [landform.json](../src/main/resources/data/riichicraft/worldgen/noise/terrain_test/landform.json)、[detail.json](../src/main/resources/data/riichicraft/worldgen/noise/terrain_test/detail.json) | 大尺度起伏octave−9，小尺度细节−6；提高频率会让坡更密、更陡 |
| 固体／空气与高度范围 | [base_density.json](../src/main/resources/data/riichicraft/worldgen/density_function/terrain_test/base_density.json)、[noise_settings](../src/main/resources/data/riichicraft/worldgen/noise_settings/terrain_test.json) | 基础密度为(H−Y)/16，原版插值后填充；当前minY0、高256、海平面0、无洞穴／含水层／矿脉 |
| 颜色映射 | [noise_settings的surface_rule](../src/main/resources/data/riichicraft/worldgen/noise_settings/terrain_test.json) | A青柠混凝土、B橙色混凝土、C淡蓝混凝土；地表与主要固体同色，底层单层基岩 |
| 各群系未来内容 | [A](../src/main/resources/data/riichicraft/worldgen/biome/test_region_a.json)、[B](../src/main/resources/data/riichicraft/worldgen/biome/test_region_b.json)、[C](../src/main/resources/data/riichicraft/worldgen/biome/test_region_c.json) | 目前carvers／features／spawners均为空；未来内容按原版配置在这里扩展，不耦合麻将服务 |
| 维度天空与边界 | [dimension_type](../src/main/resources/data/riichicraft/dimension_type/terrain_test.json) | 独立高度、天空、时钟配置，当前保持中性日光环境 |

群系噪声与地形噪声分离：颜色改变不直接改变高度，边界不会由两块不同高度地形硬拼接。之后若要让特定区域更高，应先设计连续的噪声关系。三个中性名字与三种原版色块都是占位，不是最终主题或材质。

原版模糊群系采样会让交界数格内的颜色略有交错，甚至同一竖柱不同高度略变色。核对实体颜色应查询同一BlockPos的实际群系，而非直接用4格分辨率的原始群系格代替。

调整噪声ID或参数会改变未来生成；已生成区块保留原内容。参数实验使用新测试世界，本阶段不增加历史测试版迁移逻辑。

若改变当前256格生成上限，须一起核对`dimension_type`的min_y／height／logical_height、`noise_settings.noise`的边界、`preliminary_surface_level`搜索上限，以及`base_density`的Y梯度范围和终值，维持(H−Y)/16关系；同时让高度spline及细节给天空留下余量。只调山高且仍处于现有边界内时，主要修改height spline即可。参数快照与资源SHA见[parameters.json](terrain/parameters.json)。

Java入口仅在`dev.riichicraft.worldgen`维护维度标识和测试传送，生成内容留在原版数据目录。未来新增群系内容可沿biome／feature／结构各自的数据职责扩展；麻将服务不读取地形配置，本轮也没有为未来内容添加空框架或新依赖。

## 验证结果与证据

固定种子`20261020`、`42`、`-7046029254386353131`均使用相同参数。记录已归档为[真实测量与预测报告](terrain/validation.json)和[参数／资源校验快照](terrain/parameters.json)，完整执行边界见[验证记录](VALIDATION.md)。最终`terrain-release.log`真实客户端与专用服务器测试**4分25秒成功**，包含原生F3群系条目的输出断言与补拍；默认麻将TCP回归也已2分39秒通过；Git节点与同步结果见[接续记录](SESSION_STATUS.md)。

群系预测覆盖坐标−2048～2048、步长32的129×129网格；高度预测独立使用步长128的33×33网格。两者都不是实际生成方块。真实FULL测量包含固定原点16区块，另按固定规则选择各群系、交界与预测低／高点；同种子A正常保存重开后继续生成2个预先不存在的远方区块，独立B按反序请求，比完整方块及保存群系哈希。

| 种子 | 预测A／B／C占比 | 预测高度P10／中位／P90（首个空气格Y） | 实际最高固体Y范围 | A重开／B反序比较的FULL区块 | 实测区块边界最大邻格高差 |
| --- | --- | --- | --- | --- | --- |
| 20261020 | 29.22%／44.84%／25.94% | 85／101／140 | 76～187 | 31（原29＋新2） | 1格 |
| 42 | 36.31%／41.43%／22.26% | 84／103／143 | 75～183 | 30（原28＋新2） | 1格 |
| −7046029254386353131 | 32.95%／40.74%／26.31% | 84／102／138 | 76～182 | 31（原29＋新2） | 1格 |

三个种子均形成连片区域，B目前占比最高；占比是固定调查网格的结果，不能当作全无限世界的精确比例。粗高度调查中约39%～42%的相邻采样点平均坡度不超过0.05，表明平缓地区与起伏并存；这是128格跨度的预测，不等于逐格适建面积。真实样本逐格检查固体／空气、无流体、同BlockPos群系与颜色相符，内部及边界邻格高差均未超过1格。针对性FULL样本的群系面积有明显选点偏差，不用于推算区域比例。

实际TCP验证还覆盖生存模式往返保留模式、安全支撑及碰撞／流体检查，重复进入拒绝、正常停服后恢复维度、丢失内存返回点时回主世界出生点。没有把旁观拍照当作生存落点验收。

## 固定样本图片

共归档36张图：每个种子10张真实游戏截图与2张预测图。交界和低／高地的位置由固定调查规则决定；没有按美观重新挑种子或地点。真实拍摄统一正午、晴天、关闭云层；原点俯视在地表上方40格、交界48格，采用有限测试视距。坐标、相机方向与完整截图路径见报告。

| 种子 | 群系预测／高度预测（非游戏截图） | 真实交界俯视 | 真实低地／高地 | 原生F3画面 |
| --- | --- | --- | --- | --- |
| 20261020 | [区域](images/terrain/seed-0-biome-prediction.png)／[高度](images/terrain/seed-0-height-prediction.png) | [交界](images/terrain/seed-0-boundary-overhead.png) | [低地](images/terrain/seed-0-lowland-ground.png)／[高地](images/terrain/seed-0-highland-ground.png) | [F3](images/terrain/seed-0-debug-biome.png) |
| 42 | [区域](images/terrain/seed-1-biome-prediction.png)／[高度](images/terrain/seed-1-height-prediction.png) | [交界](images/terrain/seed-1-boundary-overhead.png) | [低地](images/terrain/seed-1-lowland-ground.png)／[高地](images/terrain/seed-1-highland-ground.png) | [F3](images/terrain/seed-1-debug-biome.png) |
| −7046029254386353131 | [区域](images/terrain/seed-2-biome-prediction.png)／[高度](images/terrain/seed-2-height-prediction.png) | [交界](images/terrain/seed-2-boundary-overhead.png) | [低地](images/terrain/seed-2-lowland-ground.png)／[高地](images/terrain/seed-2-highland-ground.png) | [F3](images/terrain/seed-2-debug-biome.png) |

原版26.2默认简化F3不包含群系行。最终补拍通过原生`debugEntries.setStatus(BIOME, IN_OVERLAY)`启用条目，并检查原生DebugEntryBiome输出中的真实ID；此设置只保存到项目测试运行目录的`debug-profile.json`，拍摄后还原原条目状态，不修改全局配置。最终三张F3均输出`Biome: riichicraft:test_region_c`，已查看确认清晰可见；拍摄时位于最后的C区，A／B身份另由对应实机位置的客户端查询及原版群系命令核对。

## 有限探索与当前边界

另在每个种子的全新区域，从(8192,8192)开始，按+X方向每次32格移动、共16段，以普通玩家区块加载流程观察下载与渲染；该窗口不执行群系预测或逐列哈希诊断。以下为最终`terrain-release.log`与归档JSON对应这一轮的本机数据，含同步、网络和客户端渲染等待：

| 种子 | 首次冷加载 | 16段中最长等待 | 最长服务端tick执行 | 超过2秒tick／分段 |
| --- | --- | --- | --- | --- |
| 20261020 | 6.14秒 | 1.29秒 | 1.56秒 | 0／0 |
| 42 | 6.35秒 | 1.40秒 | 1.38秒 | 0／0 |
| −7046029254386353131 | 4.72秒 | 1.25秒 | 1.43秒 | 0／0 |

这组有限窗口未见超过2秒的服务端tick，首次进入仍有约4.72～6.35秒加载。服务端tick执行时间不含全部异步生成工作；分段时间也不是纯生成耗时，不能换算为持续TPS、FPS或每秒区块吞吐。此前启动／重诊断阶段有2～6秒落后告警，不能全部归因于生成。本轮没有真人多人长期探索、极高速飞行、第三方模组组合、硬崩溃或整机重启验收。

本原型为无水、无洞穴的连续高度场，尚未测试洞穴、河流、岛屿或正式生态与主题。当前重点是区域分布、连续地形和可调职责；自动断言与有效截图仍不能替代玩家对风格、尺度和探索手感的评价。测试工具、缓存与世界均在项目忽略目录中。

## 格式依据

具体JSON与API以本项目缓存的Minecraft 26.2原版JAR、`NoiseRouter`、`SurfaceSystem`和相关codec为准；官方参考：[Fabric动态注册表](https://docs.fabricmc.net/develop/registries/dynamic-registry)、[Minecraft Java 26.2发布说明](https://feedback.minecraft.net/hc/en-us/articles/46690753273997-Minecraft-Java-Edition-26-2)。本轮不引入第三方生成器或依赖。
