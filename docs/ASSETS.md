# v0.0.1 麻将桌资源

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

当前已运行通过：8 个 JSON、6 个本命名空间资源引用和 3 个 PNG；也验证了从不同工作目录执行，以及缺失模型、纹理变量循环、非法路径的报错。

物品通过 `assets/riichicraft/items/mahjong_table.json` 直接引用方块模型。掉落表使用 `data/riichicraft/loot_table/blocks/`，斧头标签使用 `data/minecraft/tags/block/mineable/`；普通破坏掉落一张桌子，爆炸沿用 `minecraft:survives_explosion` 条件。尚未添加合成配方或游戏牌具。

本轮离线检查已通过：7 个资源／数据 JSON 均可解析；模型引用的三张纹理全部存在；所有长方体坐标在 `0..16` 范围内；掉落表与斧头标签符合上述约定；三张 PNG 均为不透明 16×16，重新运行生成器后 SHA-256 完全一致。已检查放大的纹理预览，绿布、木框和北侧标记可辨。

离线检查之外，后续已运行真实单人客户端及专用服务器连接测试，并保存 [桌子截图](images/basic-table.png)。模型、桌布、木框、四腿和原型提示在截图中可见。自动化与截图检查不能替代用户的长期操作和美术偏好验收；具体证据见 [VALIDATION.md](VALIDATION.md)。
