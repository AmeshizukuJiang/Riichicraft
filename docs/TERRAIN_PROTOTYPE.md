# 0.1 独立地形与群系样板

目标：Minecraft Java 26.2 / Fabric；版本`0.1.0-dev`。本次只验证独立维度、三群系和地形生成，不固定最终文化主题。

## 实现与进入方式

在安装同版模组的新测试世界中开启作弊／获得OP2，使用`/riichicraft terrain enter`进入，`/riichicraft terrain leave`离开。F3维度为`riichicraft:terrain_test`；三个群系是`riichicraft:test_region_a`、`riichicraft:test_region_b`、`riichicraft:test_region_c`，可用原版群系查询识别。

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

## 验证方法与当前进度

固定种子：`20261020`、`42`、`-7046029254386353131`。预测调查覆盖4096×4096格；群系分布、地形形状预测和实际FULL区块测量分别记录。实际区块包含固定原点网格与按固定规则选出的各群系、交界和低高地，不能将定向样本的面积比例冒充整片世界比例。

同种子A世界按正序生成，正常保存停服重开后比旧块并生成事先不存在的远方区块；独立B世界按逆序请求，比较实际方块／保存群系的哈希。真实客户端命令往返、俯视、地面和F3截图另行验证。

本次实现及验证尚在进行，最终执行结果、统计和截图完成后补充至本页与[验证记录](VALIDATION.md)。测试工具、世界和原始日志仅在项目忽略目录内。预测图不是游戏截图，自动断言不是人工长期探索验收。

## 格式依据

具体JSON与API以本项目缓存的Minecraft 26.2原版JAR、`NoiseRouter`、`SurfaceSystem`和相关codec为准；官方参考：[Fabric动态注册表](https://docs.fabricmc.net/develop/registries/dynamic-registry)、[Minecraft Java 26.2发布说明](https://feedback.minecraft.net/hc/en-us/articles/46690753273997-Minecraft-Java-Edition-26-2)。本轮不引入第三方生成器或依赖。
