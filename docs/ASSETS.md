# 资源来源与重建

## v0.0.10 桌面无牌面模型

继续复用首套37种许可牌面及原创立体牌体。新增`hidden`模型和物品模型定义，仅将宽面替换为已有深绿背材质，表示未知暗手／盖牌；不注册新玩法牌、不打包测试总览夹具，也不生成牌实体。两份JSON由`tools/Generate-Tile-Models.py`生成，PNG与外部源图未变；含首套在内共117份牌资源重建哈希一致。实际桌面布局、公开信息边界和截图见[桌面展示](TABLETOP_DISPLAY.md)。

## v0.0.9 首套麻将牌

首套牌使用用户提供的[mahjong_graphic](https://github.com/lietxia/mahjong_graphic)许可图案，保留36对PNG／SVG、原许可、上游说明及SHA-256清单。34种普通牌与3种赤五共37外观，白板空白生成；乳白立体牌体、深绿牌背和原版JSON模型为项目原创。图案来源、部分字形来源、JAR内许可、材质尺寸、重建命令和范围见[麻将牌说明](MAHJONG_TILES.md)。以下“原创”表述仅指其对应的早期桌椅／文字原型阶段，不包含本批外部牌面。

## v0.0.8 大桌与两种椅子

大麻将桌由九个各占一格的模型组成，拼成3×3连续绿色桌布与深木外框；只有四个角部件有桌腿。每个部件的局部`part=0..8`和整体`facing`共同决定模型、碰撞及桌心归属。两种椅子均为单格家具，坐面高半格、靠背高一格；空椅子保留木架，使用深灰底、浅色交叉纹坐面与靠背，避免只靠颜色辨别。

新增三张16×16 RGBA纹理由既有`tools/Generate-Textures.ps1`按固定公式生成；原普通桌的三张纹理保持原样。模型、物品定义、掉落表和斧头标签由标准库脚本`tools/Generate-Furniture.py`生成。没有导入外部图片、模型、字体、音效或美术库，资源均为本项目原创。

```powershell
python tools/Generate-Furniture.py
./tools/Generate-Textures.ps1
./tools/Check-Resources.ps1
```

检查器覆盖29份JSON、27个本地资源引用、6张PNG、旧桌四朝向及新家具44组朝向／部件映射、中英文语言键。模型检查不能替代Minecraft实际烘焙、碰撞与放置／掉落检查；游戏证据见[验证记录](VALIDATION.md)。本批先用创造栏／命令获取，未添加配方、坐姿实体或桌面牌具动画；布局和兼容性见[桌椅设计](TABLE_AND_CHAIRS.md)。

## 早期普通桌与界面牌面

v0.0.3 的原型牌面由 `src/client/java/dev/riichicraft/client/TileFaces.java` 直接绘制浅色牌框、数字／花色和字牌文字，赤五有红字与“赤”标记。牌河以 `0万／0筒／0索` 表达赤五，并提供图例与完整牌名提示。没有导入外部麻将牌图片、图标或字体文件；文字使用 Minecraft 自带字体系统。物理牌 ID 不显示在牌面上。

以下为 v0.0.1 普通桌资源的来源和重建方式。

本步骤的麻将桌模型、像素纹理和中英文文本均为 Riichicraft 项目原创，没有导入第三方图片或模型。

JSON 模型字段参考 [Fabric 的 26.2 方块模型文档](https://docs.fabricmc.net/develop/blocks/block-models)。该文档仅作为格式参考，未复制其美术资源。

模型由五个轴对齐长方体构成，单位为一个方块的 1/16：桌板从 `[0,12,0]` 到 `[16,16,16]`；四条腿高 12，横截面为 2×2，分别位于 X/Z 的 `1..3` 和 `13..15`。顶面有木色边框和绿色桌布，北侧边框中央有低调的黄铜色标记。基础模型朝北，方块状态按 `0° / 90° / 180° / 270°` 旋转对应北／东／南／西。碰撞形状由 Java 方块实现与这些坐标保持一致。

三张纹理均为 16×16、不透明 RGBA PNG。`tools/Generate-Textures.ps1` 使用 PowerShell 与 .NET `System.Drawing` 按固定像素公式生成；不需要第三方图片或图像工具。执行下面的命令可以在仓库内重建纹理：

```powershell
./tools/Generate-Textures.ps1
```

使用 PowerShell 7 可运行独立、只读的资源检查，不会启动 Gradle 或重新生成纹理；脚本会从自身位置定位仓库，因此也支持包含空格的项目路径：

```powershell
./tools/Check-Resources.ps1
```

检查范围为 `src/main/resources/` 下的 JSON 与 PNG：解析 JSON；检查本命名空间的模型、材质、物品和方块引用；检查本地模型继承／纹理变量、合法几何范围、麻将桌四朝向映射；比较中英文翻译键；验证 PNG 签名、IHDR 尺寸和实际解码结果。失败时返回非零退出状态。Minecraft 原版命名空间的引用由游戏运行环境验证，本脚本不会读取仓库外的游戏文件。

普通桌阶段当时已运行通过：8 个 JSON、6 个本命名空间资源引用和 3 个 PNG；也验证了从不同工作目录执行，以及缺失模型、纹理变量循环、非法路径的报错。当前家具资源计数见上节。

物品通过 `assets/riichicraft/items/mahjong_table.json` 直接引用方块模型。掉落表使用 `data/riichicraft/loot_table/blocks/`，斧头标签使用 `data/minecraft/tags/block/mineable/`；普通破坏掉落一张桌子，爆炸沿用 `minecraft:survives_explosion` 条件。尚未添加合成配方或游戏牌具。

本轮离线检查已通过：7 个资源／数据 JSON 均可解析；模型引用的三张纹理全部存在；所有长方体坐标在 `0..16` 范围内；掉落表与斧头标签符合上述约定；三张 PNG 均为不透明 16×16，重新运行生成器后 SHA-256 完全一致。已检查放大的纹理预览，绿布、木框和北侧标记可辨。

离线检查之外，后续已运行真实单人客户端及专用服务器连接测试，并保存 [桌子截图](images/basic-table.png)。模型、桌布、木框、四腿和原型提示在截图中可见。自动化与截图检查不能替代用户的长期操作和美术偏好验收；具体证据见 [VALIDATION.md](VALIDATION.md)。
