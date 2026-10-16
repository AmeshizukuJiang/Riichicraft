# 四座大厅接口调查（Minecraft 26.2 / Fabric）

调查日期：2026-10-16。接口部分来自项目内真实 JAR 的签名与字节码调查。用户随后确认了自动分配座位、关闭界面保留座位，客户端源码已据此实现。构建与实际 Minecraft 集成结果见 [VALIDATION.md](VALIDATION.md)；本文件保留接口依据，便于后续维护。

结论：可以使用普通客户端 `Screen` 加 Fabric 自定义 play payload 实现“自动入座／四座只读显示／准备与取消／离座”。不需要 `AbstractContainerMenu`、原版库存槽或虚构背包物品。服务端保留大厅真实状态，客户端显示服务端发来的快照并发送操作请求。

## 直接核对的本地文件

所有读取均在本项目内，没有读取外部缓存或游戏安装。

| 文件 | 版本／来源 |
| --- | --- |
| `.tools/minecraft-client-26.2.jar` | Mojang 官方 26.2 客户端，未混淆类名；SHA-1 `2dc72797acbc1b63fc16a11c4ac393605f453754` |
| `.tools/minecraft-26.2.json` | 本地保存的官方版本元数据，`downloads.client` 提供客户端 URL、大小与 SHA-1 |
| `.gradle-home/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-networking-api-v1/6.3.4+2989c6a09e/608860809ec66fd65ca764c4157e96030f330537/fabric-networking-api-v1-6.3.4+2989c6a09e.jar` | 当前固定 Fabric API `0.161.0+26.2` 的 networking 模块，SHA-1 与目录名相同 |
| `.tools/java/jdk-25.0.4.1+1/bin/javap.exe` | 项目专用 JDK 的检查工具；使用 `-p` 核对签名、`-p -c` 核对字节码 |

客户端官方来源为 [Mojang client.jar](https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar)。Networking 模块对应 [Fabric Maven 制品](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-networking-api-v1/6.3.4+2989c6a09e/fabric-networking-api-v1-6.3.4+2989c6a09e.jar)。这里直接读取已下载的二进制 JAR，并非把其他 Minecraft 版本的源码当作 26.2 接口。

补充对照：[Fabric 26.2 Networking 文档](https://docs.fabricmc.net/develop/networking)。本文下面的签名和线程结论以项目内 JAR 为准。

## 普通 Screen 与按钮

以下类属于客户端，只能放在 `src/client/java/` 及客户端初始化入口中引用。公共 payload 定义和服务端管理器不得引用它们。

`net.minecraft.client.gui.screens.Screen` 中可直接使用／重写的签名：

```java
protected Screen(Component title);
protected void init();
protected <T extends GuiEventListener & Renderable & NarratableEntry>
    T addRenderableWidget(T widget);
public void extractRenderState(GuiGraphicsExtractor graphics,
                               int mouseX, int mouseY, float partialTick);
public boolean keyPressed(KeyEvent event);
public void onClose();
public void removed();
public boolean isPauseScreen();
public void tick();
protected void rebuildWidgets();
```

完整类型位置分别是 `net.minecraft.client.gui.GuiGraphicsExtractor`、`net.minecraft.client.input.KeyEvent`，其余 widget 接口在 `net.minecraft.client.gui.components` 及其子包。`KeyEvent` 提供 `key()`、`scancode()`、`modifiers()`。

26.2 绘制入口是 `extractRenderState`，不再沿用旧教程的 `render(GuiGraphics, ...)`。调用 `super.extractRenderState(...)` 可提取已注册按钮的显示状态。本轮确认存在的绘制方法包括：

```java
GuiGraphicsExtractor.fill(int left, int top, int right, int bottom, int color);
GuiGraphicsExtractor.text(Font font, Component text, int x, int y, int color);
GuiGraphicsExtractor.text(Font font, Component text, int x, int y,
                          int color, boolean shadow);
GuiGraphicsExtractor.centeredText(Font font, Component text,
                                  int centerX, int y, int color);
```

`Button` 和 `Button.Builder` 的相关签名：

```java
public static Button.Builder builder(Component text, Button.OnPress callback);
public abstract void Button.OnPress.onPress(Button button);
public Button.Builder bounds(int x, int y, int width, int height);
public Button.Builder pos(int x, int y);
public Button.Builder size(int width, int height);
public Button build();
```

`OnPress` 仍接收一个 `Button` 参数，可写成 `button -> sendAction(...)`。`AbstractWidget` 的 `active`、`visible` 是 public 字段，`setMessage(Component)` 可更新按钮文字。按钮启用状态只能帮助界面表达，服务端仍须独立检查请求。

`Screen.isPauseScreen()` 的默认实现返回 `true`。大厅覆盖为 `false`，避免单人游戏暂停集成服务器。本轮确认关闭界面保留座位，`onClose()`／`removed()` 仅关闭服务端视图，显式“离座”按钮才发出离座请求；界面替换也会经过 `removed()`，不应据此移除座位。

## 打开与识别当前界面

26.2 的当前界面保存在 `Minecraft.gui` 中：

```java
public static Minecraft Minecraft.getInstance();
public final Gui Minecraft.gui;
public Screen Gui.screen();
public void Gui.setScreen(Screen screen);
```

普通开关界面应使用 `context.client().gui.setScreen(screen)`；传 `null` 可返回游戏。更新到达时用 `context.client().gui.screen()` 判断是否仍是对应大厅界面，避免迟到快照覆盖玩家后来打开的界面。当前界面打开后玩家维度变化时关闭旧视图，连接断开时清理视图状态。

不要使用旧的 `minecraft.screen` 字段或 `minecraft.setScreen(Screen)`，当前 JAR 没有这些接口。另有 `Minecraft.setScreenAndShow(Screen)`，字节码会在设置界面后立即调用 `renderFrame(false)`，普通网络快照不需要这个强制渲染入口。`Screen.onClose()` 自身也通过 `minecraft.gui.setScreen(null)` 关闭。

## Payload 定义、注册与收发

公共接口：

```java
// net.minecraft.network.protocol.common.custom
interface CustomPacketPayload {
    CustomPacketPayload.Type<? extends CustomPacketPayload> type();
}
// Type 是 record，构造函数接收当前版本的 Identifier。
new CustomPacketPayload.Type<MyPayload>(Identifier id);

// net.minecraft.network.codec
StreamCodec<B, T> StreamCodec.of(StreamEncoder<B, T> encoder,
                                StreamDecoder<B, T> decoder);
StreamCodec<B, T> StreamCodec.ofMember(StreamMemberEncoder<B, T> encoder,
                                      StreamDecoder<B, T> decoder);
// 注意 member encoder 的参数顺序为 payload、buffer。
void StreamMemberEncoder<B, T>.encode(T payload, B buffer);
T StreamDecoder<B, T>.decode(B buffer);
```

`StreamCodec.composite(...)` 也存在。可声明 `StreamCodec<RegistryFriendlyByteBuf, MyPayload>`，组合下节列出的基础 codec 和 record getter。

Fabric 当前实际注册方法是下面的名称，不能使用旧版 `playC2S()`／`playS2C()`：

```java
// net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
static PayloadTypeRegistry<RegistryFriendlyByteBuf> serverboundPlay();
static PayloadTypeRegistry<RegistryFriendlyByteBuf> clientboundPlay();
<T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T>
    register(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec);
```

请求包和快照包的 codec 均在公共初始化中注册，使两种物理端都有编码／解码定义；再分别注册对应接收处理器。公共 payload 的字段不需要客户端类。

```java
// net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
static <T extends CustomPacketPayload> boolean registerGlobalReceiver(
    CustomPacketPayload.Type<T> type, ServerPlayNetworking.PlayPayloadHandler<T> handler);
static void send(ServerPlayer player, CustomPacketPayload payload);

// 服务端 handler 与 context
void receive(T payload, ServerPlayNetworking.Context context);
MinecraftServer context.server();
ServerPlayer context.player();
PacketSender context.responseSender();

// net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
static <T extends CustomPacketPayload> boolean registerGlobalReceiver(
    CustomPacketPayload.Type<T> type, ClientPlayNetworking.PlayPayloadHandler<T> handler);
static void send(CustomPacketPayload payload);

// 客户端 handler 与 context
void receive(T payload, ClientPlayNetworking.Context context);
Minecraft context.client();
LocalPlayer context.player();
PacketSender context.responseSender();
```

`ServerPlayNetworking.canSend(ServerPlayer, CustomPacketPayload.Type<?>)` 和 `ClientPlayNetworking.canSend(CustomPacketPayload.Type<?>)` 均存在。注册接收器要早于实际发包。

## BlockPos、UUID 和集合序列化

下列类型均可在公共源码中引用：

```java
BlockPos.STREAM_CODEC;        // StreamCodec<ByteBuf, BlockPos>
UUIDUtil.STREAM_CODEC;        // StreamCodec<ByteBuf, UUID>
Identifier.STREAM_CODEC;      // StreamCodec<ByteBuf, Identifier>
ByteBufCodecs.VAR_INT;        // StreamCodec<ByteBuf, Integer>
ByteBufCodecs.BOOL;           // StreamCodec<ByteBuf, Boolean>
ByteBufCodecs.stringUtf8(int maximumLength);
ByteBufCodecs.optional(StreamCodec<? super B, V> valueCodec);
ByteBufCodecs.collection(IntFunction<C> factory,
                        StreamCodec<? super B, V> valueCodec, int maximumSize);
ByteBufCodecs.list(int maximumSize); // CodecOperation，可传给 codec.apply(...)
ResourceKey.streamCodec(ResourceKey<? extends Registry<T>> registryKey);
```

`BlockPos`、`UUIDUtil` 位于 `net.minecraft.core`，`Identifier`、`ResourceKey` 位于 `net.minecraft.resources`。例如维度可由 `ResourceKey.streamCodec(Registries.DIMENSION)` 表达；大厅桌子身份在服务端应同时包含维度与不可变方块位置，避免同坐标的跨维度桌子混淆。

手动编码同样可用，`FriendlyByteBuf` 仍保留以下实例方法，`RegistryFriendlyByteBuf` 可继承使用：

```java
BlockPos readBlockPos();
FriendlyByteBuf writeBlockPos(BlockPos pos);
UUID readUUID();
FriendlyByteBuf writeUUID(UUID uuid);
int readVarInt();
FriendlyByteBuf writeVarInt(int value);
boolean readBoolean();
FriendlyByteBuf writeBoolean(boolean value);
String readUtf(int maximumLength);
FriendlyByteBuf writeUtf(String value, int maximumLength);
```

空座可使用 `Optional<UUID>`；四座快照的集合必须有长度上限，并在业务解码／使用层要求所需座位数。玩家展示名称应设字符串长度上限。请求包不能用客户端提供的 UUID 决定操作者身份，应直接使用 `context.player().getUUID()`。

## 接收线程结论

当前版本的 play payload handler 已在相应游戏线程上调用，不应把它们误认为任意 Netty IO 线程。证据来自本地 JAR 字节码：

1. Fabric 的 `AbstractChanneledNetworkAddon.handle(...)` 在调用 `receive(...)` 前检查 `isOnReceiveThread()`；不在正确线程会抛 `RunningOnDifferentThreadException`。
2. `ServerPlayNetworkAddon.isOnReceiveThread()` 检查 `server.packetProcessor().isSameThread()`；`ClientPlayNetworkAddon` 检查 `client.packetProcessor().isSameThread()`。
3. Minecraft 的 `PacketProcessor` 保存构造时传入的 `Thread`，`isSameThread()` 比较它与 `Thread.currentThread()`。
4. `MinecraftServer` 用同一个服务端线程构造其 `PacketProcessor`；`Minecraft` 用 `gameThread` 构造客户端 `PacketProcessor`。

因此服务器 handler 可以在一次调用中检查并修改大厅状态，两个玩家争抢同一座位会由服务端顺序裁决；客户端 handler 可以直接应用快照、更新 widget 或调用 `client.gui.setScreen(...)`。这些 handler 中不要做阻塞文件或网络操作。若将来把处理移到其他线程，再通过 `server.execute(Runnable)`／`client.execute(Runnable)` 回到游戏线程。

## 与交互方案无关的服务端约束

- 桌子右键在服务端验证后发送打开快照；客户端打开普通 Screen。自动入座、准备状态和离座通过请求包交给服务端裁决，界面等待权威快照更新。
- 所有请求以 `context.player()` 为操作者，检查桌子身份、当前维度、已加载区块、桌子是否仍存在、玩家可交互状态及距离。先检查区块已加载，避免恶意远程请求触发加载。
- 当前确认存在 `Player.isWithinBlockInteractionRange(BlockPos, double additionalRange)`；第二个参数是附加容差，不是绝对距离。其字节码使用 `blockInteractionRange() + additionalRange`，并计算眼睛到方块 AABB 的距离。另可使用 `distanceToSqr(Vec3)` 做明确设定的大厅范围判断，具体范围应随交互规则确定。
- `LevelReader.hasChunkAt(BlockPos)`、`Level.getBlockState(BlockPos)`、`Level.dimension()` 均可用。仅已入座玩家可以改自己的准备状态；同一玩家不能同时占多个座位／桌子。按钮禁用不能替代这些服务端校验。
- 服务端断线释放可用 `ServerPlayConnectionEvents.DISCONNECT`；当前回调签名为 `onPlayDisconnect(ServerGamePacketListenerImpl handler, MinecraftServer server)`。
- 快照带有版本／会话身份，避免旧界面请求或迟到快照作用于已替换的同坐标桌子。客户端仅发送服务器发出的 `viewId`、已知 `revision` 和操作意图，不能指定操作者或任意桌子。

## 本轮客户端源码与静态布局

- `src/client/java/dev/riichicraft/client/RiichicraftClient.java` 注册客户端网络与连接事件。
- `LobbyClient.java` 维护当前 `viewId` 和本连接内已关闭视图集合。首次有效快照打开 `LobbyScreen`，同视图更新只接受不落后的 revision；关闭或替换的旧视图不会因迟到快照重新打开。服务器 `LobbyClosedPayload` 只关闭匹配视图，并显示原因。断线／重新加入清理状态。
- `LobbyScreen.java` 使用真实 `Button`，暴露 `public LobbySnapshotPayload snapshot()` 供客户端集成检查。入座、准备／取消和离座均等待服务器回包后更新显示；关闭按钮和 Esc 发出 `CLOSE`，保留座位。所有座位均只读展示，满员准备提示明确写出“配牌将在后续版本加入”。
- 协议引用公共源码中的 `LobbySnapshotPayload`、`LobbyActionPayload`、`LobbyClosedPayload`，不在客户端重复定义规则或状态。
- 布局在 320×240 时使用 304×228 面板、四个 24 像素座位行，底部三枚操作按钮及独立关闭行。状态和反馈各最多两行，玩家长名称根据实际字体宽度截断。此处的布局尺寸属于离线计算，实际字体、鼠标命中与低分辨率截图需要真实客户端核对。

以上记录接口来源和客户端实现方式。各项实际构建、服务端、网络与界面检查的结果及边界统一记录在 `docs/VALIDATION.md`。
