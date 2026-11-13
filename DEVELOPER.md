# 开发者文档

女仆建筑（Maid Construction）—— 一个 Create 风格的结构蓝图模组：录制结构、投影到任意位置、由车万女仆（TLM）的女仆代为施工。

本文档面向两类读者：接手维护的人类开发者，以及需要在没有上下文的情况下定位和修改代码的 AI。因此除了"是什么"，更强调**为什么这样做**和**改哪里会踩坑**。

---

## 目录

- [1. 项目概览](#1-项目概览)
- [2. 快速上手](#2-快速上手)
- [3. 架构总览](#3-架构总览)
- [4. 核心数据模型](#4-核心数据模型)
- [5. 四条主链路](#5-四条主链路)
- [6. 扩展点指南](#6-扩展点指南)
- [7. 关键陷阱与设计决策](#7-关键陷阱与设计决策)
- [8. 排查手册](#8-排查手册)
- [9. 构建与发布](#9-构建与发布)
- [10. 给 AI 的特别提示](#10-给-ai-的特别提示)

---

## 1. 项目概览

| 项 | 值 |
|---|---|
| 模组 ID | `maidconstruction`（2.0.0 起；原来叫 `blueprint`，改名原因见 [§7.21](#721-modid-别用别人已经用掉的名字)） |
| 显示名 | 女仆建筑 |
| 当前版本 | `2.1.0`（`gradle.properties` 的 `mod_version`） |
| jar 产物 | `MaidConstruction-<version>.jar`（由 `mod_name` 去空格得到） |
| MC / Forge | 1.20.1 / 47.1.3 |
| 映射 | `official` |
| 软依赖 | 车万女仆 `1.5.2-forge+mc1.20.1`、AE2 `15.4.10` |
| 源码包 | `com.example.blueprint`（**包名保留旧词**，它只是路径、与 modid 无关；2.1.0 时共 125 个 Java 文件） |

### 它做什么

1. **录制**：手持蓝图框选两个角点，服务端扫描区域并存成一个结构。
2. **投影**：手持蓝图移动时，结构以半透明"幽灵方块"显示，可旋转、可锚定到具体位置。
3. **施工**：把蓝图交给女仆（或放进她的背包/饰品栏），女仆自动取料并逐块建造。
4. **取料**：从附近的普通容器、绑定书指定的远程容器，或 AE2 的 ME 网络取材料。

---

## 2. 快速上手

```bash
# 构建（首次会反编译 MC，约需数分钟）。Windows 上先让 JAVA_HOME 指向 JDK 17：
#   set JAVA_HOME=E:\java\jdk-17.0.14.7-hotspot
./gradlew build

# 启动客户端（TLM 已在 runtime 里，女仆功能开箱可测；AE2 见下）
./gradlew runClient

# 启动服务端
./gradlew runServer
```

**产物**：`build/libs/MaidConstruction-<version>.jar`

### 开发环境的两个坑

**一、AE2 只有 `compileOnly`，没有 `runtimeOnly`。**

这是刻意的设计（见 [§7.2](#72-软依赖的隔离方式)），代价是**开发环境默认跑不了 ME 取料**。要测 AE2 相关功能，临时在 `build.gradle` 的 deps 里加一行：

```groovy
runtimeOnly fg.deobf("maven.modrinth:ae2:${ae2_version}")
```

测完记得删掉 —— 否则这个模组就变成硬依赖了。

**二、TLM 是 `compileOnly` + `runtimeOnly`**，所以女仆相关功能开箱即可测。

---

## 3. 架构总览

### 3.1 包划分

`com.example.blueprint` 下按"谁认识谁"分层：

```
blueprint/
├── BlueprintMod          入口：注册与事件总线
├── registry/             注册表包装（方块、物品、方块实体、创造栏）
├── schematic/            结构数据：Schematic（不可变）、SchematicStorage（存档内按 UUID 存）、编解码
├── build/                建造的纯逻辑：BuildSession 状态机、站位搜索、阶段策略（**不碰 MC 运行时**，可单测）
├── server/               服务端账本：CommandPostAssignments（女仆→指挥台）、CommandPostBindings（我绑哪台）、
│                         MaidOwnership（主人→女仆）、PlayerData（跨死亡的玩家数据）、Advancements
├── network/              ModNetwork + packet/（C2S 请求、S2C 同步）
├── item/                 蓝图、空白蓝图、绑定书、说明书、蓝图终端、配方序列化
├── block/                指挥台（方块 + 方块实体）
├── client/               界面与渲染：gui/（各 Screen）、SchematicPreview、ProjectionRenderer、
│                         BlueprintLayerView、ClientSchematicCache、ClientPostBinding、BuildProgressHud
├── integration/maid/     车万女仆联动：BlueprintBuildController（施工主循环）、任务/学习/工业模式
└── integration/ae2/      AE2 联动：无线终端、ME 取料、反射隔离（Ae2Compat）
```

### 3.2 依赖方向（重要）

`build/` 不依赖任何 Minecraft 运行时；`client/` 只被客户端加载；`integration/*` 只在对应模组存在时才初始化
（由反射与 `Ae2Compat` 把关）。违反任何一条，都会让**没装 AE2 / 没装女仆**的玩家崩在启动阶段——
这是本项目最容易踩的坑（见 §7.2）。

## 4. 核心数据模型

### 4.1 `Schematic` —— 结构数据

存储方式和原版 structure 一致：

```
palette: List<BlockState>          去重后的方块状态表
blocks:  int[volume]               每个位置在 palette 里的下标
size:    Vec3i                     宽 × 高 × 长
blockEntities: Map<Integer, CompoundTag>   位置下标 → 方块实体 NBT（已剔除坐标字段）
```

**索引公式**：`(y * size.getZ() + z) * size.getX() + x`

**上限**：`MAX_SIDE = 128`、`MAX_VOLUME = 262144`。超限时抛 `IllegalStateException`，**消息本身是翻译键**（`schematic.side_too_large` 等），调用方直接 `Component.translatable(e.getMessage())`。

**关键方法**：

| 方法 | 说明 |
|---|---|
| `capture(Level, BlockPos a, BlockPos b)` | 扫描世界生成结构 |
| `rotate(Rotation)` / `mirror(Mirror)` | 返回变换后的**新实例**（`NONE` 返回自身） |
| `transformState(BlockState, Rotation, Mirror)` | 私有，见 §7.3 |
| `rotateDirection(Direction, Rotation)` / `mirrorDirection(Direction, Mirror)` | **公开的**方向工具，多处共用 |
| `withBlock(x, y, z, state, wholeKind)` | 图纸编辑用：改一格 / 改一类，返回新实例 |
| `stateAt / blockEntityAt / inBounds / entries / countBlocks` | 访问 |
| `encode / decode` | 字节（文件与网络共用）；`write / read` 是 NBT 层 |
| `stripContainerItems(CompoundTag)` | 剔掉方块实体里的物品库存，**放置时**调，见下 |

**三个刻意的选择**：

- **不用 `NbtUtils` 读写方块状态**。1.20.1 的 `NbtUtils.readBlockState` 需要 `HolderGetter<Block>`，而蓝图读写常发生在拿不到世界 registry 的场合。自己实现格式反而可控。
- **`entries()` 会为每个方块新建 `BlockPos` 和 `BlockEntry`**。大结构下别在渲染循环里调它 —— `ProjectionRenderer.rebuild` 就是为此改用下标遍历 + 复用 `MutableBlockPos`。
- **录制照实录、放置时才剔库存**（`capture` 一个键都不删，`BuildSession.step` 调 `stripContainerItems`）。
  录的时候剔掉，材料判定就没法知道"这台机器要什么"（AE2 线缆要的是贴在面上的部件）；
  放的时候不剔，一台装好样板的供应器放下就多出一套样板——那是凭空复制。
  剔的判据是"已知键名 + 物品栈形状兜底"，并且要往 `ForgeCaps` 这类子标签里钻。

### 4.2 `SchematicStorage` —— 存档内的结构库

- 类型：`SavedData`，存在**主世界**（所以跨维度的蓝图也能解析）。
- 存档名：`maidconstruction_schematics`（`MOD_ID + "_schematics"`，2.0.0 跟着改名）。
- 映射：`Map<UUID, Schematic>`。
- 另有一份 `completed: Set<String>`：**机械动力蓝图的完工标记**。那张图我们只读不改，
  完工只能按"图的内容 + 锚点"的指纹记在自己这儿（见 `MaidBlueprint`）。
- **蓝图物品只存 UUID**，结构本体不入物品 NBT —— 否则一本蓝图会撑爆物品栏同步。

```java
static SchematicStorage get(ServerLevel level)   // 从主世界取/建
UUID put(Schematic schematic)                    // 存入并标脏，返回新 UUID
@Nullable Schematic get(UUID id)
boolean remove(UUID id)
boolean isCompleted(String signature) / void setCompleted(String signature, boolean value)
```

**注意**：`BlueprintItem.clearSchematic` **不删**存档里的结构。因为蓝图物品可以被复制（创造模式、`/give`），其他副本可能仍指向同一份数据，贸然删除会让它们失效。

### 4.3 `BlueprintItem` 的 NBT 字段

| 键 | 类型 | 含义 |
|---|---|---|
| `Schematic` | UUID | 指向 `SchematicStorage` 里的结构 |
| `Size` | int[3] | 结构尺寸（客户端投影要用，避免等数据包） |
| `Name` | String | 蓝图名（也是导出文件名） |
| `Pos1` | int[3] | 已选的第一个角点 |
| `Anchor` | int[3] | 投影锚定位置 |
| `Rotation` | int | 旋转的 `ordinal` |
| `Mirror` | int | 翻面的 `ordinal`（2.1.0 起；缺省按 `NONE` 读） |
| `Completed` | boolean | 是否已建完 |
| `MaidBuild` | boolean | 是否允许女仆施工（默认允许） |

`Completed` 和 `MaidBuild` 存在物品 NBT 而不是内存里 —— 存档重载、女仆换班之后仍然有效，不会反复播报"施工完毕"。

### 4.4 `BuildSession` —— 增量建造状态机

内部状态：`order`（待放置列表）、`cursor`、`deferred`（支撑未就位的推迟列表）、`pass`、`finished`、
`total` / `built`（进度条的分母与分子）。

**关键方法**：

| 方法 | 用途 |
|---|---|
| `static plan(Schematic)` | 排序：`y` → 支撑优先级 → `z` → `x` |
| `static bill(Schematic)` | **整座结构**从零建起需要哪些材料 |
| `remainingBill(level, origin)` | **从现在这个进度**还差哪些材料（跳过已建成的方块） |
| `step(level, origin, source, maxBlocks, salvage, selfBox)` | 实际放置，返回 `StepResult` |
| `peekNextTarget(level, origin)` | 下一个目标坐标（女仆据此决定走位） |
| `total()` / `done()` | 进度条的分母与分子（`done` 是**数世界**数出来的，不是拿游标倒算） |
| `static clearAt(level, pos, salvage)` | 清掉一格并把原有方块交给 `Salvage` 回收 |

`step` 的后两个参数：`salvage` 是被顶掉方块的去处（`null` 就照旧凭空消失，只有"没人来收"的场合才这么传）；
`selfBox` 是施工者自己的碰撞箱，落在她身上的格子这一趟跳过并记进 `selfBlocked`。

**进度为什么数世界**：一趟结束时会 `order = deferred`（清单换成"这一趟没放成的那些"），
拿"游标之后还剩多少"倒算出来的已建数会忽高忽低，玩家看到的就是进度条来来回回。
分母 `total` 在会话开始时定死，分子是会话建立时**点一遍工地**得到的（所以会话会被定期重建，好让它跟着工地刷新）。
会话重建时靠构造里的 `fastForward` 把游标推到第一块没到位的方块上，否则进度会先掉回 0 再涨回来。

**摆放顺序**：先放能独立存在的完整方块（`canOcclude()` 为真），再放半砖、火把这类依附物。第一轮放不下的进入 `deferred`，最多重试 `MAX_PASS = 3` 轮。

**幂等性**：`step` 会跳过已经是目标状态的方块。所以女仆中途被打断、存档重载后重来，都不会破坏已建成的部分。

**`bill` 与 `remainingBill` 的区别是被反复踩过的坑**，详见 [§7.5](#75-取料的三层数量账)。

---

## 5. 四条主链路

### 5.1 录制链路

```
玩家右键方块（选点1）→ 写入物品 NBT 的 Pos1
玩家右键方块（选点2）→ C2SCapturePacket
    ↓
服务端 Schematic.capture(level, pos1, pos2)
    ├─ 逐格读 BlockState + BlockEntity.saveWithoutMetadata()
    └─ 抛 IllegalStateException 时把消息当翻译键回报
    ↓
SchematicStorage.put(schematic) → 得到 UUID
BlueprintItem.setSchematic(stack, id, size, name)
    ↓
S2CSchematicDataPacket（完整结构 NBT + id + 名字）
    ↓
客户端 ClientSchematicCache.put + ClientBlueprintBinder.bind
    ↓
BlueprintScreenOpener.schematicArrived(id) → 正开着的界面当场刷新
```

**`ClientBlueprintBinder` 为什么存在**：不走服务端物品 NBT 同步。那条路要经过容器槽位广播，慢半拍会让面板一直显示旧结构、得关掉重开才对。收到包就当场绑定，界面立刻正确。

**界面刷新走通知，不走轮询**（1.7.1）。面板不该每帧去读物品 NBT 判断"结构到了没有"：
那一次要读复合标签、现造一个 `UUID`，面板开着时每秒六十次纯属白做；而"数据什么时候到"
只有服务端知道，到了叫一声最准。所以绑完就调 `BlueprintScreenOpener.schematicArrived`，
面板据此点亮「建筑清单」按钮、把状态行从"正在导入…"换成结果（导入那趟另留 10 秒超时兜底）。
两个客户端界面的分工也是这么定的：**清单**收到通知只置一个 `stale` 标记、下一帧重取；
**面板**在 `init()` 里算一次按钮状态，之后只认这条通知。

**面板开着时不暂停世界**（`isPauseScreen` 返回 false，1.7.1 之前漏了）。除了"女仆要干活"
这条一贯的理由，面板还多一条硬的：**导入要服务端来回一趟**（请求 → 读文件 → 回包）。
暂停世界等于把服务端停在那儿，回包只能等玩家关掉面板才到，表现是"点了导入没反应、
关掉重开才看见"——这个现象当初就是被当成"界面不刷新"在查的。

### 5.2 投影链路

```
ProjectionRenderer.onRenderLevel（AFTER_PARTICLES 阶段）
    ├─ 手持蓝图但只选了 Pos1（还没有结构）→ 画选区线框，到此为止
    ├─ projectionSource() 四个来源取第一个可用的：
    │      正在摆的投影（定位态）> 自己手持 > 最近一台指挥台托管 > 附近女仆手持
    ├─ ClientSchematicCache.get(id, rotation, mirror)
    │      本地缺失 → 发 C2SRequestSchematicPacket（2 秒节流）
    ├─ origin = 锚点 或 视线所指位置（没定过锚点的图）
    ├─ rebuild()   重算待渲染列表 PENDING（400ms 间隔 / id 变 / origin 变 / 结构实例变）
    └─ 逐块渲染半透明幽灵方块
```

指挥台托管与女仆手持那两条都**取最近的一处**——同时叠两张半透明的图，谁也看不清。
"附近女仆"那一段整段关在 `MaidClientBridge` 后面：没装 TLM 时 `projectionSource` 自己的字节码里
不许出现 `EntityMaid`（§3、§7.2）。

**面剔除**：邻居在结构内部且 `canOcclude()` 为真时跳过该面，避免半透明叠加和大量无用绘制。

**渲染数量上限** `MAX_RENDER_BLOCKS = 20000`。

**画不出模型的方块**：走 `BlockMaterials` 那套之外的兜底 —— 见 §5.5。

### 5.3 建造链路

```
MaidBuildTickHandler（LevelTickEvent.END，ServerLevel）
    └─ 遍历维度内所有已加载实体，找任务是 BlueprintBuildTask.UID 的女仆
        └─ BlueprintBuildController.tick(level, maid)

BlueprintBuildController 状态机：
    MOVE_TO_SPOT   走到站位（离结构边界至少 STAND_MARGIN=2 格，即中间空出一格；
                   先从她脚下往外找，找不到退到结构外圈那一圈）
    BUILD          调 session.step(...)，速度按好感度算（见 §7.6）
                      ├─ placed > 0    → 挥手 + 音效
                      ├─ finished      → 收工，走还料流程
                      └─ 都没发生       → 认为缺料，转 FETCH
    FETCH          找来源 → 走过去 → 取料 → 回 BUILD（歇 FETCH_COOLDOWN = 1 tick）
```

**为什么绕开 TLM 的 Brain 调度**：`BlueprintBuildTask.createBrainTasks()` 刻意返回 `List.of()`。施工由服务端 tick 主动驱动，行为可控（出错能提示玩家），且不要求主人在附近。区块卸载即自动停止。

**状态机重入**：控制器按女仆 **entity id** 缓存在 `MaidBuildTickHandler` 的 Map 里。但**开工前的待命状态记在女仆的持久数据**（`BlueprintHomeModeBefore`）而不是控制器字段 —— 区块重载会换一个新的控制器实例，记在字段里会丢。

### 5.4 取料链路（三层数量账）

这是最容易改错的地方，单独展开见 [§7.5](#75-取料的三层数量账)。

```
1. pendingBill = session.remainingBill(level, anchor)
        ↓  整座结构还缺什么（跳过已建成的方块）
2. shortfall  = ItemProvider.missingAmounts(backpack, pendingBill)
        ↓  再扣掉背包已有的 —— 这才是"真正要去拿的"
3. 用 shortfall 找来源（findProvider）
        ↓
   provider.transferInto(backpack, pendingBill, 空位数)
        ↓  内部会再算一次 missingAmounts，结果与 shortfall 一致
```

### 5.5 渲染兜底：画不出模型的方块

`ProjectionRenderer` 与界面里的预览（`SchematicPreview`，以及蓝图面板自己那段 `drawPreview`）渲染方块时
传的是 `ModelData.EMPTY`（那些位置还没有方块实体）。

**多数方块无所谓，但 AE2 的 ME 线缆不是**：`CableBusBakedModel.getQuads()` 第一件事就是 `ModelData.get(...)` 取渲染状态，取不到直接 `Collections.emptyList()` —— **一个面都画不出来**。

所以这几处都做了兜底：记录画不出任何面的方块，循环结束后统一补轮廓。

- 普通方块：整格线框
- AE2 线缆：交给 `CableBusOutline`，画"芯 + 连接臂 + 部件标记"，按部件类型上色

**`CableBusOutline` 靠注册名 `ae2:cable_bus` 识别，不引用 AE2 类型** —— 没装 AE2 时它就是个空壳。

---

## 6. 扩展点指南

### 6.1 接入一种新的存储来源

**适用场景**：支持某个模组的仓库、无线终端、末影箱网络等。

**步骤**：

1. 在 `build` 包下实现 `ItemProvider`：

```java
public class MyProvider implements ItemProvider {
    @Override public BlockPos interactPos() { ... }        // 女仆走到哪
    @Override public boolean hasAny(Map<Item,Integer> bill) { ... }   // 便宜地判断有没有
    @Override public int transferInto(IItemHandler dst, Map<Item,Integer> bill, int maxKinds) { ... }
    @Override public boolean requiresTravel() { ... }      // 可选：无线来源返回 false（不许她跑过去）
    @Override public int acceptInto(IItemHandler src, Map<Item,Integer> filter) { ... }  // 可选（还料）
    @Override public int deposit(ItemStack stack) { ... }  // 可选（收下拆出来的杂物，不认清单）
}
```

2. 在集成包里加一个 `Compat` 门面（不引用对方类型），像 `Ae2Compat` 那样用 `ModList.get().isLoaded(...)` 把关。

3. 在 `BlueprintBuildController.findProvider` 里挂上去。

**`transferInto` 的契约**：

- `bill` 传入的是**需求**，不是缺口 —— 实现内部应当调 `ItemProvider.missingAmounts(dst, bill)` 得到真实缺口。
- `maxKinds` 是本次最多搬几种（调用方传的是背包空位数）。
- **绝不能吞物品**：塞不进 `dst` 的东西要原样还回去。

**`Ae2ItemProvider` 是最复杂的参考实现**，因为它面对的 ME 网络只能"模拟提取"，且提取出来就不可逆 —— 必须先确认容量再真提。

### 6.2 让某个方块的材料计算正确

**适用场景**：方块的 `Block.asItem()` 返回的东西不是玩家实际放上去的那个物品。

**典型案例**：`ae2:cable_bus` 只是个容器方块，玩家实际放的是贴在各面的**部件**（线缆本体、终端、存储总线）。它的 `asItem()` 返回一个游戏里拿不到的方块物品 —— 于是女仆抱着错误的物品名去箱子里找，箱子里明明有也视而不见。

**步骤**：

1. 实现 `BlockMaterials`：

```java
public class MyMaterialResolver implements BlockMaterials {
    @Override
    @Nullable
    public List<Item> resolve(BlockState state, @Nullable CompoundTag blockEntityTag) {
        if (!(state.getBlock() instanceof MyBlock)) {
            return null;              // 不认领 → 交给下一个
        }
        List<Item> items = new ArrayList<>();
        // 从 blockEntityTag 里解析出真正的物品
        return items;                 // 空列表 = 不需要材料
    }
}
```

2. 在模组初始化时注册（参考 `BlueprintMod` 里 `Ae2Compat.registerMaterialResolver()`）。

**返回值语义**：`null` = 不认领；空列表 = 认领但不需要材料；非空 = 需要这些物品（**可以重复**，表示同种物品要多份）。

### 6.3 让某个方块在旋转/翻面时朝向正确

**适用场景**：方块把自己或子部件的朝向存在方块实体 NBT 里。

**背景**：`Schematic.rotate` / `Schematic.mirror` 转 `BlockState` 是通用的（见 §7.3），但 **NBT 是无 schema 的任意树**，引擎不知道哪个字段是朝向。

**步骤**：

1. 实现 `BlockEntityRotation`（旋转与翻面一次收齐，顺序是**先翻面、后旋转**）：

```java
public class MyRotation implements BlockEntityRotation {
    @Override
    @Nullable
    public CompoundTag transform(BlockState state, CompoundTag tag, Rotation rotation, Mirror mirror) {
        if (!(state.getBlock() instanceof MyBlock)) {
            return null;
        }
        CompoundTag result = tag.copy();       // 不要就地改传入的
        // 用 Schematic.mirrorDirection(...) 再 rotateDirection(...)，搬运对应的键/字段
        return result;
    }
}
```

2. 在模组初始化时注册（参考 `Ae2Compat.registerBlockEntityRotation()`）。

**三个必须注意的点**：

- **不要就地修改传入的 `tag`** —— 同一个结构可能被反复变换。
- **不要边搬边查**。如果要把 `north` 的内容搬到 `east`，必须先把"搬运计划"整个算出来，再分两批执行（先全摘除、再全写入）。否则刚搬过去的键会被后面的迭代当成源再处理一遍，**结果多转一格**。
- 用 `Schematic.mirrorDirection` / `rotateDirection`：`rotateDirection` 已经处理了 `UP`/`DOWN`（`Direction.getClockWise()` 对它们会抛异常），两者的**先后必须与坐标一致**（先镜像再旋转）。

### 6.4 加一个网络包

1. 在 `network/packet` 下新建类，提供静态的 `encode` / `decode` / `handle`。
2. 在 `ModNetwork.register()` 里**追加**注册（用自增 id）。

**注意事项**：

- 所有 `handle` 都要 `context.enqueueWork(...)` 切主线程 + `context.setPacketHandled(true)`。
- C2S 侧先判 `context.getSender() == null` 再往下走。
- **注册顺序即协议**。改动顺序会让新旧客户端无法互通 —— 此时应该升 `PROTOCOL_VERSION`。
- 传大数据用 `writeByteArray` 而不是字符串（字符串有 32K 上限，会被静默截断）。

### 6.5 加一个新物品

在 `registry/ModItems.java` 里加 `DeferredRegister` 条目，然后：

- 模型放 `assets/maidconstruction/models/item/<name>.json`
- 中英文案都要加（`assets/maidconstruction/lang/zh_cn.json` 和 `en_us.json`）
- 配方放 `data/maidconstruction/recipes/<name>.json`
- 给创造模式用的物品，加进 `ModCreativeTabs` 的 `displayItems` 回调（模组独占一栏，不要塞原版标签页）
- **涉及软依赖模组物品的配方必须用 `forge:conditional` 包住**，否则没装那个模组的整合包会加载失败

**创造物品栏的注意点**：`ModCreativeTabs.MAIN` 的 `displayItems` 里，`ModItems` 的注册对象要 `.get()` 之后再 `accept`（`Output` 只收 `ItemLike`/`ItemStack`，不收 `RegistryObject`）。软依赖模组的物品**必须先在 `Ae2Compat.isLoaded()` 里包一层**再取 `.get()` —— 触碰 `Ae2TerminalRegistry` 的静态字段会初始化整个类，没装 AE2 时那是个 `NoClassDefFoundError`。标签页标题走 `itemGroup.maidconstruction.main` 这个翻译键。

---

## 7. 关键陷阱与设计决策

这一节是文档里最重要的部分。下面每一条都是实际踩过的坑，动这些代码之前先读一遍。

### 7.1 原版交互是「方块优先」

方块的 `use()` 先于物品的 `useOn()` 执行，方块返回 `CONSUME`/`SUCCESS` 时物品那个根本不会被调用——所以手持绑定书右键容器只会打开容器，手持空白蓝图点容器选不了角点。

**解法**：覆写 `Item#onItemUseFirst(ItemStack, UseOnContext)`。Forge 这个钩子跑在方块处理**之前**，返回非 `PASS` 就能完整接管这次右键。`BlueprintItem` 与 `BindingBookItem` 都这么做；`BindingBookEvents` 里那个 `setUseBlock(DENY)` 只是自己抢不到时的兜底。

### 7.2 软依赖的隔离方式

**TLM**：类上标 `@LittleMaidExtension`，只有 TLM 存在时才被反射实例化；事件注册（`MaidBuildTickHandler`）放在 `addMaidTask` 回调里，用静态标志防重复。

**AE2**：三重隔离——① 代码全部关在 `integration.ae2` 包；② `Ae2Compat` 本身不引用任何 AE2 类型，只用 `ModList.get().isLoaded("ae2")` 判断（JVM 是懒加载的：方法体里提到的类要执行到那一行才解析，没装 AE2 就永远不会去解析 `Ae2ItemProvider`）；③ `build.gradle` 只给 `compileOnly`，不加 `runtimeOnly`。

代价是开发环境跑不了 AE2 功能，要测需临时加 `runtimeOnly`。同思路的延伸：`CableBusOutline` 用注册名 `ae2:cable_bus` 认线缆，也就不必引用 AE2 类型。

### 7.3 换朝向的两种失效（旋转与镜像都适用）

**失效一：方块状态没转。** 原版 `Block.rotate` 的默认实现**直接返回原状态**，只有主动覆写过的方块（楼梯、箱子这类）才会真的转朝向；AE2 的机器（`DriveBlock` 等继承自己的 `AEBaseEntityBlock`）没覆写，`facing` 纹丝不动。
**解法**：`Schematic.transformState` 在方块自己转完之后，按**原始状态的值**把所有 `DirectionProperty` 重设一遍。用的是原值，所以不会"转两次"。

**失效二：NBT 里的朝向。** 有些方块的朝向压根不在 BlockState 里：AE2 线缆把"部件挂在哪个面"记在 NBT 的**键名**上（`north`、`east`，每个部件一个键），`CableBusContainer.readFromNBT` 的方向完全由键名决定，**没有别的通道**，想让部件跟着走只能搬这些键——这由 `Ae2BlockEntityRotation` 负责。**引擎帮不上忙**：BlockState 有 `Property` 系统、能按 `Rotation` 通用变换，而 NBT 是没有 schema 的任意树，看到 `north: {...}` 并不知道那是朝向、物品名还是自定义标签；原版 `StructureTemplate`（结构方块）同样只处理 BlockState。

**镜像走的是同一套。** 翻面与旋转是"换朝向"的两半，`Schematic.transform` 里**先翻面、后旋转**（与原版结构方块、机械动力那份换算一致；反了就是另一个结构），调用口径是 `base.mirror(m).rotate(r)`。上面两条失效在翻面时同样成立，所以 `BlockEntityRotation#transform` 一次收旋转与翻面两个参数——各家的 NBT 搬运只写一遍，顺序不会两边分叉。

**坐标与方向必须同序。** 坐标是先镜像再旋转，那么 `mirrorDirection` 与 `rotateDirection` 的组合也得是这个顺序（见 `BlockEntityRotation` 的实现与 `CableBusOutline` 的部件补偿），否则方块位置转了 90°、朝向却按另一种顺序翻，落点对不上。

### 7.4 缓存与结构的一致性

`ProjectionRenderer` 用静态 `PENDING` 列表缓存"待渲染方块"，坐标是**结构内的相对坐标**，因此有四个失效条件：

```java
now - lastScan > RESCAN_INTERVAL_MS      // 定时刷新
|| !Objects.equals(id, cachedId)          // 换了蓝图
|| !Objects.equals(origin, cachedOrigin)  // 锚点移动
|| schematic != cachedSchematic           // ★ 结构实例变了
```

最后一条是崩溃修复留下的：换朝向时 `ClientSchematicCache.get(id, rotation, mirror)` 会返回一个新副本（旋转还会宽长互换），而 `id` 与 `origin` 都没变——沿用旧坐标去访问新结构就是 `ArrayIndexOutOfBoundsException`。渲染线程上抛异常会**直接退出游戏**（日志末尾是 `Unreported exception` 紧跟 `Stopping server`）。

**防御**：`CableBusOutline.outlinesOf` 开头也做了 `inBounds` 检查。调用方来自渲染循环，坐标未必和传进来的结构对得上——宁可少画，不能崩。

### 7.5 取料的三层数量账

这三个概念很容易混，改代码时务必分清：

| 名字 | 含义 | 用在哪 |
|---|---|---|
| `bill` | **整座结构**从零建起需要多少 | 还料时判断"哪些是这次工程带来的" |
| `pendingBill` | 跳过已建成的方块后，**还缺多少** | 传给 `transferInto`（内部再减背包） |
| `shortfall` | 再扣掉**背包已有**，真正要去拿的 | 判断"值不值得跑一趟" |

两个踩过的坑：用 `bill` 取料 → 每缺一次料就把整座建筑的量搬一遍（解法：`remainingBill`）；用 `pendingBill` 判来源 → 容器里只有"背包早就备齐的那几种"时 `hasAny` 也通过，她白跑一趟还报"背包满了"（解法：`shortfall`）。

取料上限也别写死：原来是 `MAX_PULL_SLOTS = 8`，用超过 8 种材料的建筑永远凑不齐、装了背包升级也不多拿；现在传的是 `countEmptySlots(backpack)`。

### 7.6 数值与单位约定

**所有距离比较都用平方距离**（`distanceToSqr` / `distSqr`），省掉多余的 `sqrt`；常量命名也跟着是 `XXX_SQR`。

| 常量 | 值 | 含义 |
|---|---|---|
| `CONTAINER_SEARCH_RADIUS` / `_HEIGHT` | 10 / 4 | 就近取料的搜索范围 |
| `ARRIVE_DISTANCE_SQR` | 12.25 | 到站判定（水平）：**故意宽松**，寻路常在两三格外就停下，卡太死会让她每 tick 在"没到岗"与"离岗"之间来回踢 |
| `ARRIVE_DY` | 2.5 | 到站的垂直容差 |
| `STAND_MARGIN` | 2 | 站位离结构边界至少几格（2 = 中间空出一格，见 `StandSpotSearch.clearOf`） |
| `LEAVE_SPOT_DISTANCE_SQR` | 64 | 超过就重新走回站位 |
| `CONTAINER_DISTANCE_SQR` | 9 | 走到容器 3 格内就开箱取料 |
| `ABORT_DISTANCE_SQR` | 256 | 超过就不去了，就地取料 |
| `MOVE_PATIENCE_TICKS` / `MOVE_TIMEOUT` | 60 / 120 | 走位判定：连续 60 tick **没靠近**就算卡住（不是"花了多久"）；每段路另设 120 tick 总超时 |
| `STEP_ASIDE_RETRY` / `MAX_ESCAPE_TRIES` | 200 / 3 | 请她让开的重试间隔，以及最多换几个落脚点 |
| `BASE_PLACES_PER_SECOND` | 5 | 放置速度基准（原来的 `PLACE_COOLDOWN = 4`） |
| `FAVORABILITY_PER_EXTRA_PLACE` | 5 | 好感度每多 5 点，每秒多放 1 块 |
| `MAX_PLACES_PER_TICK` | 20 | 放置上限（400 块/秒），只是防呆线 |
| `FETCH_COOLDOWN` | 1 | 搬完料回到站位后歇几 tick 再动手（原来 20，放置提速后同比例压到 1） |
| `NO_SOURCE_COOLDOWN` | 100 | 找不到材料后的冷却 |
| `RETURN_TIMEOUT` | 600 | 还料流程的超时兜底 |
| `PROGRESS_INTERVAL` | 10 | 施工进度推送间隔（约半秒） |
| `MESSAGE_COOLDOWN_MS` | 30000 | 同一类提示的最小间隔 |
| `MAX_REPORTED_MATERIALS` | 5 | 缺料提示最多列几种 |

**放置速度不要再写成"冷却几 tick"**：冷却只能是整数 tick，速度会被锁死在 20/1、20/2、20/3 这几档上，
"好感度每 5 点加一块"根本落不下去。现在是**攒份额**（`takePlaceBudget`）：每秒 N 块折成本 tick 的份额，
攒够一块才放，7 块/秒、13 块/秒都准。

### 7.7 提示信息的几个约定

- **面向玩家的文本一律走翻译键**（`Component.translatable`），不硬编码；`Schematic` 抛的 `IllegalStateException` 消息本身就是翻译键，上层直接 `Component.translatable(e.getMessage())`。
- **物品名用 `Component` 传递，不预先 `.getString()`**，否则会在服务端固定成某种语言。
- **缺料提示不走 `notify`**：那条路径有 30 秒冷却、会被其他消息挤掉；同一批缺料只播报一次（比较 `shortfall` 内容）。
- **`lastReportedShortfall` 要在确认附近有玩家之后才设**，否则她在远处缺料时先被标记成"说过了"，玩家走过去反而听不到。

### 7.8 幂等性

多处刻意做成幂等，改动时别破坏：

- `BuildSession.step` 跳过已是目标状态的方块。
- `ClientBlueprintBinder.bind` 只在 id 不同时才写入。
- `Schematic.rotate(NONE)` / `Schematic.mirror(NONE)` 直接返回自身；`BlockEntityRotationResolver.transform` 在两个参数都是 NONE 时也直接返回，不产生复制。
- `ClientSchematicCache.get(id, NONE, NONE)` 返回原始实例。
- `MaidTerminalBlockEntity.ensureNodeCreated()` 可重复调用。

### 7.9 几个"看着奇怪但别改"的地方

| 位置 | 说明 |
|---|---|
| `MaidTerminalBlockEntity` 待机功耗 `0.0` | **与 AE2 官方终端一致**，不是随手填的 |
| `MaidItemSource.getBackpack()` 用 `getMaidInv()` | 不用 `ITEM_HANDLER` 能力——那个是含主手/副手/盔甲的组合视图，往里面塞材料会让建筑方块跑到女仆装备栏里 |
| `MaidTerminalBlock.use` 覆写的是 `@Deprecated` 方法 | 1.20.1 没提供替代重载，覆写它仍是注册右键行为的标准做法 |
| `BlueprintBuildTask.createBrainTasks` 返回空列表 | 施工不走 Brain 调度，见 §5.3 |
| `MaidIndustryTask.createBrainTasks` 返回空列表 | 做单同样走服务端 tick，而且**干活时不能跟人**：跟随和取料共用一套导航，见 §7.16 |
| 学习池界面的提示自己画（`MaidStudyScreen.flashAt`） | 开着界面时游戏那一层整个不画，`displayClientMessage` 发出去没人看得见，见 §7.17 |
| `ClientSchematicCache` 不标 `@OnlyIn` | 它不引用客户端专属类型，保持中立可避免服务端收包时触发意外的类加载 |
| `ItemProvider` 不用 `IItemHandler` | ME 网络这类存储根本没有槽位概念，硬套槽位接口会写出一堆假实现 |

### 7.10 创造女仆接口：一个方块，两个存储出口，别搞混

`CreativeMaidInterfaceBlockEntity` 对外的两个存储出口**故意不一样**：

| 出口 | 返回什么 | 谁在用 | 为什么 |
|---|---|---|---|
| Forge 能力 `Capabilities.STORAGE` | **永远**是 `maidStorage`（`InfiniteItemStorage.forMaid()`） | 女仆取料与还料（`Ae2ItemProvider`） | 女仆要的保证是"什么材料都拿得到"，这个保证不该取决于方块有没有接网络、有没有通电、AE2 的挂载跑完没有 |
| 挂到网格的 `mountInventories` | `gridStorage`（`InfiniteItemStorage.forGrid()`） | 整张 ME 网络 | 让任意终端都能取到所有物品 |
| `ITerminalHost.getInventory()` | 接上网络就是**网络库存**，否则是 `maidStorage` | AE2 终端界面 | 上了网就该看到全网（已含挂上去的创造库存）；没上网也不能是空的 |

两份 `InfiniteItemStorage` 的差别只在收不收东西：`forMaid()` **收下**（等于销毁）——女仆建完房要把剩料还回来，还料就是"从背包取出往来源里塞"，收下比让她抱着一堆材料干净；`forGrid()` **拒收**——AE2 的网络库存写东西时按优先级逐个问下来，挂上去的这份一旦答"我全要"，玩家往任意终端里放的东西就会被静默销毁。**别再合并回一个实例**：前者是我们主动清场，后者是物品蒸发。

**为什么不能统一成一个出口**：能力若也返回网络库存，会出现一个窗口期——节点已就绪但 `mountInventories` 还没跑，女仆这时来取料会看到"没有材料"，白跑一趟再等 100 tick 冷却；反过来，`getInventory` 只返回自己的库存，联网时右上角的搜索框就搜不到网络里别的东西了。

**挂进网络的机制**：方块实体实现 `IStorageProvider`，并在构造函数里 `mainNode.addService(IStorageProvider.class, this)`——**这一句必须在节点 `create` 之前**（它是"这个节点能对外提供什么服务"的登记，等网格建起来再补登记就挂不上去了）；`mountInventories` 里 `mounts.mount(storage)`。申报数量用 `Integer.MAX_VALUE`（与 AE2 自己的创造存储一致），别改 `Long.MAX_VALUE`：网络库存是若干来源相加的，几张创造接口同处一网会加溢出，而且那个数字会原样显示在终端里。

**贴图是自己画的**：`ae2:part/terminal` 是**部件**贴图，16×16 里只有 44 个不透明像素（一个 12×12 的空心边框、连屏幕都没有），当整方块贴图（`cube_all`）用只会渲染出一个透空的深灰框。**"发光"要靠模型面级全亮**，不是 `lightLevel` 单独能做到的：

```json
"forge_data": { "block_light": 15, "sky_light": 15 }
```

这是 Forge 的 `ForgeFaceData`（1.20.1 有效，AE2 自己的终端屏幕也这么写）。只设 `lightLevel` 的话，方块能照亮周围，但它自己的六个面仍按环境光渲染，暗处看着是块灰砖。

### 7.11 无线女仆终端：只覆写取电、跨维度耗电与断开，其余照抄官方

**必须继承 `WirelessTerminalItem`，不能自己写一个物品**：`WirelessTerminalMenuHost` 的构造函数里写死了 `instanceof WirelessTerminalItem` 检查，不满足直接抛 `IllegalArgumentException`。继承过来之后，面板、升级槽、"在物品栏里直接打开"的入口全是现成的。

| 覆写 | 作用 |
|---|---|
| `use` | Shift + 右键空气断开链接，其余交给父类去开面板 |
| `getAECurrentPower` | 插卡后对外声明满电（见下"供电路径"） |
| `hasPower` / `usePower` | 插卡后从网络取电 |
| `getMenuHost` | **只为压住跨维度耗电**，且只在插卡时用自己的宿主；判定逻辑全留在官方那边 |

**其余一律不覆写。** 这是踩完坑的结论：早先为了让"没链接也能开面板"，把 `getLinkedGrid` / `checkPreconditions` / `getMenuHost` 三处都换成了自己那套，结果整台终端**右键没反应、而且没有任何提示**。三处各自的理由：

- **`getLinkedGrid`：提示就写在它里面。** 官方实现的分支是"没链接 → 提示 `DeviceNotLinked`"、"链接的维度/方块找不到 → 提示 `LinkedNetworkNotFound`"。换成自己的解析等于把这些提示全吞掉——玩家右键之后什么都没发生。**要加自己的判定，就加在它返回 null 之后**，别在它前面截断。
- **`checkPreconditions`：能不能开面板归官方。** 它负责"没链接 → 不吭声（提示已由 `getLinkedGrid` 发过）"与"没电 → 提示 `DeviceNotPowered`"。覆写任何一个分支都会让对应提示消失。
- **`getMenuHost`：官方宿主并没有射程上限。** `WirelessTerminalMenuHost.rangeCheck()` 只做两件事——`targetGrid` 是否为 null、网格里还找不找得到一台 `WirelessAccessPointBlockEntity`；它把最近那台存进 `myWap`、把距离算出来给耗电速率用，**没有任何"超出射程就拒绝"的比较**。AE2 的无线终端本来就不限距离，真正的限制是"目标区块得加载着"。当初以为"官方宿主会按射程关面板"才换掉它，反而把官方的耗电与失效逻辑一起换掉了。

**唯一的例外：跨维度耗电必须压住，这只能靠换宿主。** 所以后来还是加回了 `getMenuHost`，但它只做一件事——覆写 `setPowerDrainPerTick(double)`（该方法在 `ItemMenuHost` 里是 `protected`，跨包覆写没问题），而且**只在插了女仆绑定卡时**才用自己的宿主。起因是 AE2 自己算不出跨维度的距离：`WirelessTerminalMenuHost.getWapSqDistance` 在"访问点跟玩家不同维度"和"访问点没在工作"两种情况下**直接返回 `Double.MAX_VALUE`**，于是 `currentDistanceFromGrid ≈ 1.3e154`，再乘 `wirelessTerminalDrainMultiplier` 就是**一 tick 把整张网络抽干**。取值直接照抄 AE2WTLib 的量子桥卡：**22.5 AE/tick**（它补的是同一个洞，做法也一样——判定过不去就不接受 AE2 给的速率）。**这个宿主只覆写这一个方法**，`rangeCheck()` / `onBroadcastChanges()` 一律不碰。

**`onBroadcastChanges` 的极性（万一以后真要覆写）**：它返回的是"菜单还算有效吗"，`AEBaseMenu.broadcastChanges` 里是 `if (!host.onBroadcastChanges(this)) setValidMenu(false);`——**`true` 才是继续开着**，返回 `false` 是当场关掉（表现："面板一闪而过"）。跟 `rangeCheck()`、`ItemProvider.requiresTravel()` 那种"返回 true 表示有问题"的直觉正好相反。

**`onItemUseFirst` / `useOn` 都不要碰，方块上的右键一律让给方块。** 这里踩过一次：为了让"对着方块右键也能开面板"而覆写了 `onItemUseFirst`，结果这台终端**再也放不进 AE2 的充能器**，也放不进无线访问点去链接。原因很实在——充能器**没有界面**（整个 AE2 里没有 `ChargerMenu`），它只有一个 `ChargerBlock.onActivated(...)`，右键把物品收进去是唯一入口；无线访问点自己开面板（`MenuOpener.open(WirelessAccessPointMenu.TYPE, …)`），只在玩家潜行时让位。结论：面板只从**右键空气**进，方块上的右键一律让给方块。想验证"是不是物品抢了右键"，把 `onItemUseFirst` 注释掉、空手右键同一个方块对比即可。

**链接必须走 AE2 原生那套，而且要记得登记。** 链接是把终端放进 **ME 无线访问点**的槽位完成的，槽位按 `RestrictedInputSlot$PlacableItemType.GRID_LINKABLE_ITEM` 放行，它查的是 `GridLinkables.get(item)`——**每个物品都要显式登记处理器**：

```java
GridLinkables.register(WIRELESS_MAID_TERMINAL.get(), WirelessTerminalItem.LINKABLE_HANDLER);
```

直接用官方那个处理器就行（`canLink` 是 `instanceof WirelessTerminalItem`，我们的终端本来就是子类；`link`/`unlink` 读写的也是官方那套 NBT 键）。**没登记的表现是"终端根本放不进访问点"**，同样没有任何提示。它与升级卡关联一起放在 `registerItemHooks()`（`commonSetup` 里调）。

**供电路径要看仔细。** 链路是 `ItemMenuHost.drainPower() → WirelessTerminalMenuHost.extractAEPower() → WirelessTerminalItem.usePower(...)`，而 `extractAEPower` 先 `Math.min(amount, getAECurrentPower(stack))` 夹了一次上限——所以"插卡后从网络取电"必须**同时**覆写 `hasPower`/`usePower`/`getAECurrentPower`。两个容易写错的地方：

- **`hasPower` 不能无条件返回 true。** 官方只是先问它，得到 true 就照常 `drainPower()`，扣不到照样把菜单判为失效——表现是**面板一闪而过**。插卡时拿网络 SIMULATE 一次如实回答，没电就会走到官方那条"设备未通电"的提示上。
- **`usePower` 必须"先 SIMULATE 再 MODULATE"。** `extractAEPower` 是能抽多少抽多少，抽不满时我们会转去用内置电池，而**那半截已经先从网络里扣掉了**——等于凭空烧掉。先用 `Actionable.SIMULATE` 确认能给够，再真扣。

**升级槽能不能插一张卡，不看物品类型。** AE2 的过滤器只有一行 `getInstalledUpgrades(item) < getMaxInstalled(item)`，而后者的值来自 `Upgrades.add(卡, 机器, 张数)` 的登记——**没登记就是 0，卡会被默默拒绝**，也没有任何提示。`Upgrades.add` 的第二个参数是机器（map 的键取的是 `Association.upgradeCard()`，即第一个参数），登记必须放在**物品注册完成之后**（本项目在 `commonSetup`）。

**无线来源不能让女仆走动。** `ItemProvider.requiresTravel()` 默认 `true`，无线终端返回 `false`，控制器的 `tickFetch` / `tickReturn` 据此跳过寻路与开箱动画；否则她会照着绑定坐标一路跑过去——那坐标可能在地图另一头，甚至在别的维度。

**"没电就不给开面板"这条原生规则保留，代价是刚做出来要先去充一次电。** `AEBasePoweredItem.getAECurrentPower` 读 NBT 里的 `internalCurrentPower`，**没有 NBT 就是 0**，所以合成出来的终端与官方无线终端一样是空的：右键提示「设备未通电」，在充能器里充一次就能开面板插卡。别为了跳过这一步去覆写 `checkPreconditions`（那正是让所有提示一起消失的原因）；真要跳过，正确做法是给合成产物直接充满（覆写 `onCraftedBy`）。**充能器本身没问题**，别去改物品的可充能性：`ChargerInvFilter.allowInsert` 的判据是 `Platform.isChargeable(stack)`，继承 `AEBasePoweredItem` 就自动满足；它显示"供能不足"是**它自己没接电**。

**跨维度有一条解不开的限制。** `resolveGrid` 会去对应的 `ServerLevel` 找节点宿主，但**区块没加载就拿不到网格**。绑定卡放开的是"距离"与"维度"两条，放开不了"区块加载"——ME 网络只存在于已加载的区块里，AE2 自己也做不到隔空访问。要长时间远程取料，得靠区块加载器撑着那边。

### 7.12 学习池：展示按产物，存储按配方

**这两件事必须分开。** 早先那版只存产物，理由是"配方现场去配方表反查就行"——那在"一样产物只有一个配方"时成立，一旦**一样产物有好几个配方**就塌了：反查拿到哪个取决于**配方表的顺序**，不是主人的意愿；而且"她到底见过哪一种做法"根本没记下来，主人想指定也没处可指。现在的结构是：

```java
Learned(ItemStack product, List<Recipe> recipes, int selected)   // selected = 主人点名的那条做法
Recipe(@Nullable ResourceLocation id, List<ItemStack> grid)      // 配方身份 + 演示时那 3×3 的摆法
```

**每个配方存两样，不是冗余**：`id` 是数据包写的身份（`minecraft:torch`），按它去重、也按它反查原版配方；`grid` 是她**亲眼看见的摆法**——数据包被换掉、配方被删掉之后 `id` 就查不出东西了，摆法还能读、还能显示给主人看。少存任何一样都会缺一块。

**去重按 id，没有 id 才按摆法**（`hasRecipe`）：同一个配方换个材料再演示一遍不该算第二条（原版自己就常常让 `Ingredient` 吃 tag，橡木换云杉还是同一个 `minecraft:stick`）；但真换个做法（配方表里有两条不同记录）就该是新的一条，排在后面。

**做法是"选"出来的，不是"排"出来的。** `Learned.selected` 记一个下标，`select` 只改它，列表顺序（学会的先后）稳定不动，界面把选中那条高亮。早先那版是 `promote` 把选中的挪到最前、拿"第 0 个"当优先——看着省了一个字段，代价是每次改选择都重排列表：顺序一直在动，主人反而记不住自己选的是哪条，"取消优先"也没有对应操作。（没有做法、或下标越界时 `chosenIndex()` 退回 0，所以"照第 1 条做"这个默认是稳的。）

**"能读到配方"这件事全靠事件时机。** `ItemCraftedEvent` 是在 `ResultSlot#onTake` 的**第一步**（`checkTakeAchievements`）发出来的，之后才轮到"按 `getRemainingItemsFor` 逐格扣减材料"，所以处理器里读到的合成格**还是满的**：摆法与配方都必须**当场**取，等到下一 tick 就只剩产物了。这也是"演示一次"能记住做法的根本原因。

**"这次用的是哪个配方"要去问那次合成自己，不要猜界面类型。** 事件本来就带着合成容器（`getInventory()`），原版工作台与背包给的正是 `CraftingContainer`，连"配方几乘几"都顺带有了。早先拿 `player.containerMenu` 判断是 `CraftingMenu` 还是 `InventoryMenu` 再自己拼容器，是绕远路——**谁合成的，容器就在谁手里**。

**AE2 的合成终端是这条路上的例外。** 它发事件时传的是 `craftingGrid.toContainer()`（`InternalInventory` 包装出来的适配器），**不是 `CraftingContainer`**，所以"把事件里那个容器当合成格读"在它这里什么也读不到，池子里只落一条没有配方的记录（界面显示"认不出的配方"）。它留的口子是 `CraftingTermMenu#getCurrentRecipe()`（公开，**无线合成终端与便携合成终端都是它的子类**）。从它拿到的配方要用**配方自己的材料表**摊展示摆法，**别去读它内部网格的顺序**（那是它三乘三槽位的顺序，未必等于配方行宽；有序配方按 `ShapedRecipe#getWidth()` 摆，无序的排一行）。跨模组的这种调用一律走 `Ae2Compat` 那层（类本身不引用 AE2 类型，确认加载了才碰 `Ae2CraftingCapture`）。

**还有一条兜底：按产物反查，且只认唯一一条匹配。** 多条匹配时那正是"一个产物有好几种做法"本身——该由主人自己挑，不是我们随便选一条塞进池子。

**认不出配方就只记产物，别塞假配方。** "没有 id、摆法全空"的记录在界面上是一行"认不出的配方"，占着位置还挡着主人重演示一遍；只记产物的话界面会老实说"只见过产物，没见过做法（再演示一次她就记住了）"。

**取摆法要分工作台与背包两种容器。** 工作台 3×3 的 `craftSlots` **没有公开 getter**，只能按槽位号读 `slots` 1~9（0 号是产物格）；背包 2×2 的 `InventoryMenu#getCraftSlots()` 是公开的。反查配方时要把这两种容器**原样**递给 `getRecipeFor`——容器大小本身参与匹配（2×2 的配方在 3×3 里未必匹配得上）。另外 1.20.1 **没有 `RecipeHolder`**（那是 1.20.2 的包装），配方 id 就在 `Recipe#getId()` 上。

**`registerTaskData` 漏了，是不报错的那种坏。** `TaskDataKey` 只是"一把钥匙"，必须在这个回调里 `register(KEY)` 交给 TLM 登记，`maid.getData(key)` 才查得到。漏掉的表现是：数据**照样写、照样进存档**，但读回来永远是 null（"学过了全不记得"），日志里一个字都没有。凡是新增 `TaskDataKey`，先看 `MaidExtension.registerTaskData` 有没有登记。

**界面入口：`InteractMaidEvent` 的 `post` 返回值就是"要不要跳过它自己的女仆界面"。** 女仆那边的顺序是"post 事件 → 手里物品的 `interactLivingEntity` → `openMaidGui`"，而 `post()` 返回的是"事件被取消了"，所以**取消它 = 直接 SUCCESS 收场**。触发条件是"**蹲下 + 手里拿着木棍**"（`MaidStudyInteractHandler.TRIGGER_ITEM`）：原来用"蹲下 + 空手"，那个手势被 TLM 自己占了（空手蹲下右键 = 亲亲女仆），两边抢同一个动作的结果是玩家想开学习池却亲了她一口；木棍没人拿它跟人互动，不会撞车，普通右键与拿别的物品右键仍归 TLM。事件两端都会走一遍（右键本来就有客户端预测），所以界面在客户端开就行，不需要额外发包。

**界面不用请求数据。** 池子是 `TaskDataKey`，TLM 的 `TASK_DATA_SYNC` 已经把女仆身上那份同步到客户端了，界面直接 `MaidStudyPool.known(maid)` 就行；只有**换做法**要发 C2S 包，服务端 `setAndSyncData` 之后新选择会顺着同一条同步链路推回来。

### 7.13 学习池下单：单子只记产物，配方每次现查

**订单里不存配方，只存"做什么、还剩几个"**，配方每次从她的学习池里取那条**选中的**。理由跟池子那边一致：主人在界面上换一次做法就该立刻作用于还没做完的单；下单时抄一份配方，等于多出一份要同步的旧数据，两份一旦不一致谁也说不清该照哪份做。

**取料那套是借来的，但清单得传进去。** 就近容器 → 无线终端 → 绑定书仓库这个顺序，以及"哪些方块算容器""无线终端怎么认""要不要扫饰品栏"那些细节，施工那边都磨过了，重写必然走样。所以把 `findNearbyContainer` 与 `hasWantedItem` 改成静态、并且**把清单当参数传**（原来读的是实例上的 `shortfall`）——手搓要的料跟施工那份完全是两回事。只有绑定书那一支自己写：那边那支会顺带播报"隔着维度""仓库空了"，是施工口径的话术。

**"探针清单"与"真清单"是两张不同的单。** 吃 tag 的材料（"任意木板"）在清单里没法表达"任意"，所以分两步：

```
探针清单：每个材料位的**所有候选**都列上 → 用来找"哪个来源值得跑一趟"
真清单：  每个材料位挑一件**真拿得到**的 → 用来搬料与扣料
          （先看她背包有没有，再拿 hasAny(单件) 逐个候选问来源）
```

少了探针那一张，箱子里明明有云杉木板，她也会认准材料表里排第一的橡木，然后卡在"缺材料"。

**手搓用一个自己的合成格，不借 `TransientCraftingContainer`。** 后者必须挂一个 `AbstractContainerMenu`，每次 `setItem` 都回调那个菜单的 `slotsChanged`；我们只是替她"空手摆一遍"，没有菜单可挂，传 null 会在 `setItem` 时炸，造个假菜单则是把假状态塞进真流程。`CraftingGrid` 就是个 3×3 + 空实现的 `setChanged`。

**先摆格、让配方认一遍，再扣料。** 顺序是"按她手上真有的东西摆 → `recipe.matches` → 确认每种都够 → 才 `consume` → `assemble` → `getRemainingItems`"，这样就没有"扣了料才发现失败"的回滚路径要写（施工那边同一条规矩：先确认全都够再动手）。摆法本身来自**配方自己写的材料表**（`StudyRecipeCapture.layout`，与记录"她看过什么"共用一份），不读任何一家模组内部网格的顺序。

**一步一 tick。** 认配方 → 找来源 → 赶路 → 搬料 → 手搓，每八 tick 只推进一步：赶路要时间、一趟也可能搬不完，全塞进一个 tick 里就只能成功一次。

**缺料只提醒一次**（写在 `Order.warned` 上：她每隔几秒就重试一遍，照实播报会把聊天栏刷满）。做不了的单（池子里没记下做法、或那条配方已被数据包删掉）直接撤掉并说明——留着只会把后面的单永远堵住。

**"跟着主人"和"走去取料"用的是同一套导航，只能让一个说话。** 学习模式的 tick 驱动**每 tick**都 `moveTo(主人)`，而取料要 `moveTo(仓库)`；两套各写各的，她的导航目标就被每 tick 顶回去一次，**表现是站在原地不动、永远到不了仓库**。附近的箱子几步就到，所以这个坑**只在远程仓库上才露头**。规则：**手上有单就不跟人**（她正在干活）。同一只女仆身上挂多个 tick 驱动时，凡是会发导航指令的都要写明让位条件——这类冲突不会报错，只会表现为"她傻了"。

**分得清"仓库里没有"与"背包塞不下"。** 搬运回来是 0 不等于来源里没有：先用 `provider.hasAny(shortfall)` 问一句，再决定说哪句提示；写错提示会让主人顺着错的线索去翻箱子，而问题其实在她背包里（施工那边同一个讲究，见 `pullFromProvider`）。

**"做完了来告诉我"是临时状态，不进存档。** 只存在内存里的 `REPORTS`（值是"做好了的那几样"）：跑过去说一声这件事过期就没意义，存档里留个"还没汇报"的尾巴，下次进游戏她突然跑来报一句反而怪。开口的条件三条缺一不可：**手头没别的活**（他问的是"做完了吗"，不是"做到哪了"）、**主人得在身边**（不在就走过去，走到 4 格内才开口——聊天栏里飘一句和在眼前被拍一下，感觉完全不同）、**没等太久**（约五分钟作废，不留一笔永远报不掉的账）。**零件单不报**：那是她自己给自己排的活。

**嵌套合成用"插队"，不用递归。** 缺的零件她要是自己会做，就往**队首插一张"先做这个零件"的单**：

```java
tryNest(shortfall):
    对每一样缺的材料：
        她会做吗（她池子里有配方）？不会 → 看下一样
        会   → insertFirst(零件, ceil(缺的量 / 一次出几个), depth + 1)  ← 插到最前面
```

递归得自己管调用栈、超产与失败回滚；插队只用那一份顺序表，而且**主人看得见"她接下来要做什么"**（排队行会直接把零件显示出来）。零件做完，原来那张单自然又有料了；零件自己也缺料，它那一步会再插一层。

**挡循环配方靠"来路"，不靠层数上限。** 一开始写的是"最多套三层"，那是**把错的尺子**：AE2 的处理器那类东西正常就套四五层，按层数卡会把正当配方一起卡死；真正无解的只有**绕回自己**。所以每张单都带着自己的**来路**（从根单到上一层那一串产物），插零件时只要求"这个零件不出现在它自己的来路上"：

```java
insertFirst(零件, 数量, 来路):
    零件 ∈ 来路        → 拒绝（循环）
    队列里已有这号产物 → 拒绝（等她做完那张就有了）
    队满               → 拒绝
    否则插到队首，来路 = 原单的来路 + 原单自己
```

这样正当的深套一路放行、循环的当场断掉，而且**不用维护任何魔法数字**。代价是来路要跟着单一起存（`Order.lineage`，NBT 里一串物品）；它有界——链条上不会出现重复产物，长度天然受"不同产物个数"限制。

**"用途"视图查的是她自己的池子，不是配方表。** 右键产物列出**她会做的、用到这个产物的东西**（`MaidStudyScreen#learnedUsesOf`：遍历池子，看哪样产物的配方摆法里含它），点一条就跳过去。最初的实现扫配方表（`ingredient.test(产物)`），后来改成反查池子：一是扫全表**必须自己设上限**，木板这类几百条只能砍到 32 条，主人看着就是"缺失特别严重"，而池子本来就有界、可以不砍条数；二是扫全表列出来的**绝大多数她根本不会做**，点过去只撞上"她不会做"，对"接下来让她做什么"没有帮助，反查池子则每一条都点得过去、都能直接下单。代价是她还没学过用它做的东西时这一页是空的——这是**口径本身的取舍，不是丢数据**；真要"全世界还有哪些做法"，那是接 JEI 那条路。

### 7.14 录入配方必须先复制：合成格里取到的是活引用

`ItemCraftedEvent` 是在 `ResultSlot#onTake` 的第一步发出来的，所以事件里读合成格时格子还是满的——**读的时机没问题，坑在"存"**：

```java
slots[index] = container.getItem(row * width + col);   // ← 存的是那件物品本身
```

`container.getItem()` 返回的是合成格里**那件 ItemStack 自己**，不是副本；而主人的下一步操作就是"逐格扣减材料"，于是我们刚记下来的摆法跟着一起被扣空。表现（很容易被误判成"数据没存上"）：刚学会的做法摆法那一片过后全空、只剩配方 id；id 也没反查到的话 `MaidStudyPool.isEmpty()` 判定为"什么都没看出来"，**整条做法等于压根没记上**；而且它**只在"每格正好放 1 个"时才丢干净**（配方书自动填充、或材料正好够），每格有剩料时反而侥幸留着——同一个 bug 看起来时好时坏，特别难查。

**规矩：凡是从别人的容器里取出来的 ItemStack，要存进自己的数据结构就先 `copyWithCount(1)`。** 它之所以藏得住，是因为 `StudyRecipeCapture#layout` 一开始就复制了（`chosen.copyWithCount(1)`），所以走 AE2 合成终端与"按产物反查"兜底的配方从来没有这个问题，只有"工作台 / 背包格子手搓"这一条路会丢——**同一个概念两条写入路径、写法不一致**，正是这类 bug 的温床。

### 7.15 开界面要判逻辑端：`DistExecutor` 只认物理端

```java
DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MaidStudyScreenOpener.open(maid.getId()));
```

看着"已经限定客户端了"，其实**只挡住了专用服务端**：`DistExecutor` 判断的是**物理端**，而**单人游戏的物理端就是 `CLIENT`**。像 `InteractMaidEvent`（以及 Forge 的 `PlayerInteractEvent`）这类事件**两端都会走一遍**——客户端线程一遍、集成服务端线程一遍——于是服务端线程上那一份也会执行，跑去调 `Minecraft.getInstance().setScreen()`，撞上 `RenderSystem` 的线程断言（日志里是 `Rendersystem called from wrong thread`）。所以**"物理端是客户端" ≠ "可以碰客户端 API"**，还差一个"当前线程是不是客户端线程"。

正确写法是分成两件事：**取消事件**这类逻辑两端都要做（服务端不取消，TLM 照样开它自己的界面）；**开界面**只能在这一份是**逻辑客户端**时做：

```java
event.setCanceled(true);                  // 两端都要
if (!player.level().isClientSide()) {     // 逻辑端：服务端线程那份到此为止
    return;
}
DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MaidStudyScreenOpener.open(maid.getId()));
```

外层那个 `DistExecutor` 仍然要留着：它保证在**专用服务端**上不会去加载 `client` 包里的类（否则 `NoClassDefFoundError`）。两层各管一件事，不能互相替代。

### 7.16 工业模式：下单即上工，做完把模式还回去

`MaidIndustryTask` 是照学习池的单子干活的工作模式，骨架与「蓝图施工」一致（`createBrainTasks` 返回空列表 + 服务端 tick 驱动），差在三点：

- **下单自动切换**：`employ` 先记下她**原来**的模式（`RETURN_TO`，只在内存里），再切到工业模式；已经在工业模式时什么都不做——否则连着下几单会把"原来是什么模式"覆盖成工业模式本身。
- **待做清单空了才还回去**：`MaidCraftTickHandler.onLevelTick` 里先让她把"做好了"那句说完（`REPORTS` 里还有她的账就再等一拍），**说完了才 `release`**——顺序反了的话，一还回去她就不归那段代码管了，那句话永远没机会说。
- **只在工作时间干活**：判据用 TLM 自己的作息（`Activity.WORK.equals(maid.getScheduleDetail())`），**别自己按 `dayTime` 算时段**——那等于把 TLM 的三张作息表在本模组里抄一遍，它一改我们就错。查不出来时**放行**：宁可她在休息时段多干一点，也好过"下了单她一动不动还不报错"。

### 7.17 界面里的反馈必须在界面里画

**开着任何 GUI 时，游戏那一层（HUD、聊天栏、动作栏）整个不画**，所以：

```java
player.displayClientMessage(component, true);   // 动作栏：界面开着时看不见
owner.sendSystemMessage(component);              // 聊天栏：同上
```

**在界面里点出来的反馈等于没发。** 学习池界面的做法是自己在界面上飘一句（`MaidStudyScreen.flashAt` / `drawFlash`），并且把这句话占的方块记下来（`flashOverlaps`），让跟它重叠的悬停提示让开——她说的话优先级最高。同理，界面里的操作**不再由服务端回话**：换做法、忘掉、下单失败都在客户端就地反馈；下单上限也在客户端先算一遍（`canFitInQueue`），**规矩必须与服务端 `MaidCraftOrder.order` 一模一样**，否则会出现"界面说能下、服务端不收"这种最难查的错位。

### 7.18 按路径缓存本地文件时，别忘了"同名覆盖"

图纸库、以及它上面那些缩略图缓存，一度都只用**路径**当键：重扫时路径没变就沿用旧条目。而图纸的惯例恰恰是**同名覆盖**——改一张图再导出去、从同伴那儿收来一个同名的新版——于是列表里一直是上一版的结构与缩略图，看着像"没刷新"，其实缓存压根没去问文件变没变。

规矩：**凡是缓存"游戏外面能被替换的文件"，键里必须带上文件本身的标记**（大小 + 修改时间，或内容校验和）。这里的落点：

- `BlueprintLibrary.Entry` 建条目时记下 `Files.size` 与 `getLastModifiedTime`，重扫时对不上就重建条目（`changedOnDisk`）；属性读不出来时**当作变了**——宁可多读一遍，也不能拿不准时继续用旧结构。
- 缩略图那类缓存用 `Entry.cacheKey()`（路径 + 条目序号），而不是路径：序号一定会随重建而变，属性读不出来时那两个数却不会。
- 目录**每五秒自动重扫**（`RESCAN_INTERVAL_MS`）这条本来就有，界面还得真去取它的结果（`BlueprintLibrary.entries()`）——只重扫、不接上，等于没有。

### 7.19 录制态：不借旁观者任何本事，只借"接管按键"这一件事

录制态**不给玩家任何能力**——不是"给得少"，是一样都不给。曾经试过两样，都删了：

- **能飞**（`abilities.mayfly / flying`）：那等于给生存玩家一个免费飞行，太容易被拿来钻空子，这是删掉的直接原因。
- **能穿墙**（`noPhysics`）：1.20.1 里它不是同步字段——服务端置了客户端根本不知道，玩家的本地预测照样在墙前停下，看着就是"穿不过去"；而让客户端自己每刻按着它，只是把"客户端说了算"从一件事挪到另一件事上。**做不到的承诺不如不给。**

所以录制态剩下的只有两样：**把原版那两个键在录制期间摘掉**（见下面那段，这才是它存在的真正理由），以及屏幕角上那块提示。大建筑上不去就先搭路——这是"不给能力"的代价，也是刻意的：一个能框住整座山的工具，同时也是一个能飞遍全图的工具，**同一个入口不该同时是捷径**。

**当年给出去的能力怎么收尾**：1.8.0 那版会把"进录制态之前长什么样"写进 `player.getPersistentData()`（`BlueprintRecordSaved` / `BlueprintRecordSavedData`），飞行也真的开出去了——能力跟着玩家存档走，在录制态里直接退游戏的人，下次登录会带着 `mayfly` 进来。删掉能力之后这件事交给 `RecordModeCleanup` 在**登录**时收尾：存档里还有那份记录就还原、清掉。**过了 1.8.x 不再有这种存档，连那个类一起删。**

按键一侧的规矩：只有**世界里没开着任何界面**时才抢键。左键归那个**可取消**的 `InputEvent.MouseButton.Pre`（录制时贴着建筑看，很容易顺手挖掉一块）；`E`（开背包）与 `Q`（丢东西）则**不能**靠事件取消——`InputEvent.Key` **不可取消**，对它调 `setCanceled()` 会抛 `UnsupportedOperationException`，而且那一抛会把整段处理打断，按键白按、日志里还多一条 ERROR（这个坑踩过一次）。也**不能**靠"抢在原版读按键之前把 click 收掉"：在 `TickEvent.ClientTickEvent` 的 `START` 相位 `consumeClick()` 试过，玩家按 Q 照样把东西丢出去——原版读按键比任何一个 tick 阶段都早。**确定生效的做法是把这两个键在录制期间摘掉**（`KeyMapping.setKey(InputConstants.UNKNOWN)`，退出、取消、断线时原样还回去）：键都没绑，原版那句 `consumeClick()` 永远是 false。摘的是**客户端**的控制设置（`KeyMapping` 本来就是客户端状态），代价只有"这中间崩了要自己去控制里绑回来"这一点。方向键微调按"起点 / 终点"分工（起点 = 先点的那一角，终点 = 后点的）：`←` 终点 X+1、`→` 终点 Z+1、`↑` 终点 Y+1、`↓` 起点 Y−1，`Ctrl` 把这一下从终点换成起点（`Ctrl+←` 起点 X+1、`Ctrl+→` 起点 Z+1、`Ctrl+↑` 起点 Y+1、`Ctrl+↓` 终点 Y−1），Shift 一律反向。高度之所以分成上下界两条键（↑ 抬终点、↓ 压起点），是因为框的顶与底最常分开动；水平方向一根轴一个键就够。三根轴各给各的键，不让方向键去猜"你想调哪根"，猜错了反而要点两次才知道偏了。只点了一个角时，方向键照样能动它。E 那一下还留了兜底：万一还是把背包开了，就关掉背包并按"完成"处理，绝不让玩家按了 E 只看到背包、不知道录没录成。

**录下来的结构落在哪**：**直接就是 `maidconstruction/blueprints` 目录里的文件**——`C2SCaptureToFilePacket` → 服务端 `Schematic.capture` → `S2CSchematicFilePacket` → 客户端 `BlueprintTransfer.writeToFile` + `BlueprintLibrary.refresh`。蓝图物品在这条路上只是"把某一份取到手上"的容器，录制不经过它：让录制先写进手上一张纸、再让玩家去导出，等于凭空多一步，还要占住他手上的格子。

**扫描与落盘为什么不在同一端**：扫描只有服务端做得准（客户端的方块实体 NBT 常常是残的），而文件只能写在**玩家自己那台机器**的游戏目录里——所以数据要在网络上来回一趟。中间那段字节只有一种写法（`Schematic.encode` / `decode`，文件与网络共用），别再在客户端解一遍再编一遍。

### 7.20 让玩家"先腾出手"是一种设计债

图纸详情页的「取到手上」原本要求手里先有一张空白蓝图，服务端也只认 `findHeld`（主手 → 副手）。
于是他点这个按钮之前得：翻背包找到空白蓝图 → 换到手上 → 回来再点一次——而这一整套动作
只是把物品从背包挪到手上，那是代码该做的事。改法见 `BlueprintItem.findBlankToHand`：

- 按 **主手 → 副手 → 快捷栏 → 主背包** 找第一张空白的，不在手上就**与主手对调**：
  换下来的东西只是换个格子，一样在他身上，不会丢。
- **先解析、再动背包**：读文件 / 解压 / 解析失败时就该直接返回，不能已经把玩家的东西换了一遍。
- 客户端那半只做**只读预检**（`BlueprintItem.hasBlank`）：物品栏在客户端是同步来的镜像，
  在那儿做对调会和服务端对不上。

规矩：**凡是"把数据装进物品"的动作，容器就在他背包里，不要在手上找。**要"拿出来用"的
（交给女仆、放到指挥台），顺手换到手上；只是查看的，就地写进去就行。

### 7.21 modid 别用别人已经用掉的名字

`blueprint` 曾经是我们的 modid，直到装 1.20.1 的 **Team Abnormals Blueprint**（一个库模组，7.x）
时撞了车——Forge 不允许两个 modid 相同，结果是**两台里只能加载一台**。**该让的一直是后来者**：
那种库模组有一堆模组依赖，动不了；我们改一个字符串就完事。→ 现在叫 `maidconstruction`。

选 modid 时先在 CurseForge / Modrinth 上搜一遍那个词：**越通用的词越危险**（`blueprint`、
`framework`、`library`……）。真撞了要改的是这几处（1.8.3 → `maidconstruction` 就是这么改的）：

- `gradle.properties` 的 `mod_id`（`mods.toml` 里的 `${mod_id}` 跟着变，`build.gradle` 的
  `mods { "${mod_id}" { ... } }` 与 `enabledGameTestNamespaces` 也用它）；
- `BlueprintMod.MOD_ID`（注册表项、包通道 `newSimpleChannel(new ResourceLocation(MOD_ID, ...))`
  都由它拼出来）；
- 资源目录 `assets/<id>`、`data/<id>`（`git mv`），以及里面所有 `"<id>:xxx"` 引用：模型与方块状态、
  配方、掉落表、进度、标签；
- **翻译键里带命名空间的那几类**（`item.`/`block.`/`itemGroup.`/`task.`/`advancements.`/`command.`……）：
  它们和注册名同源——漏改的表现是"物品名显示成一行原始键"，比不认识新名字难查得多。

**跟着改名、但不是命名空间的那几样**（2.0.0 一起改了，理由是同一个：名字里留着旧名只会让人更糊涂）：

| 旧 | 新 | 玩家要做什么 |
|---|---|---|
| `blueprints/`（游戏目录） | `maidconstruction/blueprints/` | 不用——第一次访问自动搬 |
| `.blueprint`（图纸后缀） | `.maidbp` | 不用——搬家时顺手改；老后缀列表里也认 |
| `/blueprint`（命令） | `/maidconstruction` | 换个词敲 |
| `gui.blueprint.*`（语言键） | `gui.maidconstruction.*` | 不用 |
| `config/blueprint-common.toml` | `config/maidconstruction-common.toml` | **要**：旧的那份不再读，开关得重设一遍 |

**别跟着改的两样**（改了会直接伤到人）：

- 玩家持久数据里的键（`CommandPostBindings` 那两个 `maidconstruction:bound_post_*`）——它不是注册名，
  改了等于让每个人丢掉"我绑的是哪一台"（而旧那台可能远在未加载区块里，没法就地补回来）；
- Java 包名与类名（`com.example.blueprint`）——只是路径，对加载没有影响，改了白改。

**改名的实操顺序**（照它走不容易漏）：`gradle.properties` 与 `MOD_ID` → 资源目录 `git mv` →
全仓替换 `"<旧id>:` 与 `` "<前缀>.<旧id>." `` → 文案与文档 → 编译 + `jar tf` 里确认命名空间 →
提交（顺手记进 CHANGELOG 的"破坏性更新"）。**替换一定要带上下文**（引号、斜杠、前缀），别裸替一个词：
`blueprint` 满仓都是（类名、概念名、贴图路径）——我第一遍就是裸替了 `/blueprint`，
把 `maidconstruction:item/blueprint` 这种贴图路径一起换掉了。

**代价要提前说清**：命名空间一变，**旧世界里的物品与方块会变成"未知物品"**；这一版还顺带改了目录、
后缀、语言键、命令名与配置文件名（上表）。这类改动必须配一句"升级请新开世界"，而且要挑版本发
（见 CHANGELOG 2.0.0）。



### 7.22 分层查看：过滤放在"拿到方块清单之后、渲染之前"

蓝图预览能一层层看（`client/BlueprintLayerView`）。它只做一件事：**按 Y 过滤方块清单**，筛完交给
`SchematicPreview`——三处预览（面板、详情页、缩略图）画的是同一份东西，口径必须只有一处。

- **先按层筛、再抽稀**：反过来会把某层剩下的几块也抽没，看着像"这层是空的"；
- **抽稀步长按筛完的数量算**：拿整栋的 `countBlocks()` 算，只看一层时会被抽成几个孤块；
- **别把控件摆进预览框**：那幅 3D 在 `super.render` 之后画，会压住控件——所以分层用拖动条
  （`drawBar` / `overBar`：底＝第 1 层、顶＝整栋），右键切"只看这一层"；
- **模式只归右键管**，拖条只动层号：拖一下就把模式掰回累积，玩家会以为右键没生效；
- 拖条与"拖动转视角"共用同一块预览：按下时**先判拖条**（`draggingLayer`），否则一拖两动。

### 7.23 玩家持久数据的根节点不跨死亡

`player.getPersistentData()` 的**根节点**会进存档、重登也在，但**死一次就没了**：Forge 在
`ServerPlayer#restoreFrom` 里只复制 `PlayerPersisted` 这一层子标签。所以"只该记一次"的标记
（说明书发过没有、绑的是哪台指挥台）都必须写在那一层：

- 读：`root.getCompound(Player.PERSISTED_NBT_TAG)`，**改完必须 `root.put(...)` 回去**——
  键不存在时 `getCompound` 返回的是一个没挂上去的新标签，直接写等于写进空气；
- 升级：老版本写在根节点上的键，用 `PlayerData.adoptLegacyRootKeys` 在读取时就地搬进那一层，
  再删掉根上那份（不这么做，老玩家的绑定会凭空消失）；
- 封装在 `server/PlayerData`（`persisted` / `adoptLegacyRootKeys`），别各处自己拼。

**这个坑的特点是"重登测不出来"**：只有死亡 → 重生才会复现，所以验证得真 `/kill` 一次。

## 8. 排查手册

### 8.1 女仆不干活

先看三处，顺序别反：**她手上有没有图** → **模式是不是"建造"** → **指挥台有没有下令开工**。

- 指派了但不动：多半指派落到了**没有投影的那一台**指挥台（见 §12.1）；
- 有图有投影仍不动：确认 `started`——准备不等于施工，口令按下之后她才动；
- 都不对：看日志里 `BlueprintBuildController` 的状态迁移，是不是卡在 `WAIT_MATERIAL`（缺料）。

### 8.2 女仆取不到材料

取料是**三层数量账**（§5.4）：身边容器 → 绑定书 → AE2。缺料的常见原因不是"没有"，而是**配方对不上**：
容器里有原木、而这一步要木板，那就得有合成途径，或者改用算好的材料（§6.2）。

### 8.3 渲染相关

投影与预览里画不出模型的方块（线缆那类靠方块实体喂数据的）会退化成线框，这是有意的（§5.5）。
新方块"透明 / 穿模"先查两件事：模型是不是非整块（要不要 `noOcclusion()`）、面与相邻方块贴得是不是太近
（深度精度打架）。

### 8.4 编译 / 运行时错误

- `UnsupportedOperationException` 出现在集合操作里：拿 `List.of` / `List.copyOf` 存了之后又去改它。
  规矩：要么存可变副本，要么只走"整份替换"（2.1.1 的崩服就是这个）。
- `zip END header not found` / `Could not add library: <缓存 jar>`：Gradle 缓存里有半截文件（下载被打断）。
  `gradlew --stop` → 删日志点名那个 jar → 重跑。
- 没装 AE2 却崩在启动：`integration.ae2` 的类被主代码直接引用了，必须走 `Ae2Compat` 把关（§7.2）。

### 8.5 验证第三方 API 的正确姿势

别凭印象写。用 `javap` 或直接看 `~/.gradle/caches/forge_gradle/...` 里的 jar 核签名；Forge 的
`patches/*.java.patch` 也在同一处，能确认注入点在不在。

## 9. 构建与发布

- 构建：`JAVA_HOME` 指到 **JDK 17**，`gradlew build` → `build/libs/MaidConstruction-<版本>.jar`。
  产物里**不能有第三方类**（AE2 / TLM / Create 一律 `compileOnly`）——审核会看这个。
- 版本：`gradle.properties` 的 `mod_version`，语义化版本；破坏性改动（改 modid、存档格式）走主版本。
- 发布：`CHANGELOG.md` 顶部加 `## [x.y.z] - YYYY-MM-DD` → 提交推送 → 打标签 `vX.Y.Z` 推上去。
  CI 会构建 → 从 CHANGELOG 抽该段当发布说明 → 建 GitHub Release → **自动上传 CurseForge**。
  - 标题必须**严格**以 `## [x.y.z]` 开头：抽段的 `awk` 是前缀匹配，多一个空格就抽不到，会退化成兜底文案；
  - CF 那步用仓库 Secrets `CF_API_KEY` / `CF_PROJECT_ID`；它 `continue-on-error`，失败只留红叉，
    但**别把红叉当没发生**；
  - `mod_authors` 别写成邮箱——会被解析成"名字 <邮箱>"那套格式，标红。

## 10. 给 AI 的特别提示

动手前先读 `CHANGELOG` 最近三段与本文 §7：这个项目一半的坑都写在那儿，重复踩一遍不值得。

最容易改错的几条：

- `client/` 里的类**不能**被主代码直接引用（专用服务端会 `NoClassDefFoundError`），一律 `DistExecutor` 包一层；
- 判定"是不是我的指挥台"只走 `canUse`，别看方块实体上那个 `owner`（它会过期，见 §12.1）；
- 玩家持久数据写 `PlayerData.persisted`，**别写根节点**（根节点不跨死亡，见 §7.23）；
- 会被增删的集合别用 `List.of` / `List.copyOf` 存（见 2.1.1 崩服）；
- 改完 `gradlew build`（含单测），**别中途取消**——取消会在缓存里留半截 jar。

验证现状：绝大多数改动只到"编译 + 单测"这一层，实机行为要用户配合验；别把"编译过了"说成"验过了"。

## 11. 待办

### 还开着

- **展示物料**：CurseForge / MCMOD 用的**游戏内截图**还没做（分层查看那张最适合放第一张）。
- **`paused` 是空壳**：`setPaused` 全仓没有调用方——要么补一个暂停按钮，要么把这个字段连同"已暂停"
  那几条文案一起删掉（倾向删，省得后人以为能用）。
- **手册里一句待核**：它写着"附近还有别的女仆在建会多写一位"，但 `BuildProgressHud` / `MaidBuildProgress`
  里没找到对应逻辑——要么补实现，要么把那句删掉。

### 明确不做（设计取舍）

- 不做"缩放 / 裁切结构"：旋转与镜像够用，再多几种变换只会让"这份图到底是什么"变含糊。
- 不做云端同步：图纸就是本地文件，拷给别人即可；加服务器反而多一套权限与版本问题。
- 不做"区块没加载也远程施工"：那台指挥台的区块没加载就什么都不会发生——不替玩家偷偷加载别人家的区块。

### 候选方向（待讨论）

- **按层统计材料**：材料清单现在按整栋算，能不能按"只看这一层"统计（同一份过滤换个出口）。
- **进度合并**：多只女仆同工地时把进度合成一条。
- **1.12.2 下探**：TLM 在 1.12.2 有官方版，但渲染与网络那套要重写，成本另算。

## 12. 蓝图指挥台：托管投影与远程下单（已实现）

一台方块，三件事：**托管一份图纸的投影**、**收下一批女仆**、**接受开工/停下的口令**。
完整推演（为什么是方块不是物品、为什么不用 Menu、四步实现顺序）在 `git log --follow -p -- DEVELOPER.md`
里翻得到，这里只留现在生效的规矩。

### 12.1 状态与所有权

- 状态挂方块实体：`owner`、`schematicId` + `anchor` + `rotation`/`mirror`、`maids`、`started`、`completed`。
- **一台只属于一个绑定玩家**（可解绑换人）；**一个玩家也只能绑一台**：绑第二台自动解绑第一台。
  "现在绑的是哪一台"另记一份、**按玩家**存在持久数据里（`server/CommandPostBindings`）——
  换绑时旧那台多半在未加载区块，够不着它的方块实体。
- 判定一律走 `canUse` = `owner 是他` **且** 记录里他绑的就是这一台。方块实体上那个 `owner` 只用于显示
  与拦别人，它是**会过期的**（旧那台没加载时清不掉）。
- 客户端同一套规矩：`CommandPostProjections.update` 看到某台刚变成"是他的"，就把别处同名的 owner 划掉，
  `nearestMine` 于是不会再挑中旧那台（漏掉这条的表现是"指派了女仆没反应"）。
- **"我绑的是哪一台"要推给客户端**（`S2CPostBindingPacket`）：登录一次 + 每次绑定/换绑/解绑之后。
  客户端自己那张表只记**加载过的**指挥台，走远了就查不到。

### 12.2 投影与口令：不看距离、不看维度

判定是"那台的**区块加载着** + 你是它的绑定主人"。目标由服务端按**你现在绑定的那一台**解析
（`CommandPostBindings.current`），包里的坐标只当提示——所以包体与协议号都没动。
（原来是 64 格射程，还写成 `64.0D`＝8 格²，离远一点就被静默丢掉。）

### 12.3 女仆怎么知道该建哪处

优先级：**手上那张图** > **指挥台指派** > 没有。指派走 `server/CommandPostAssignments`（女仆→指挥台，带维度）。
把蓝图交给女仆时会把那张图**收走**，免得"她手上那张"与"指挥台那份"各说各的。
