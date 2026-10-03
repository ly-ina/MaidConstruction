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
| 模组 ID | `blueprint` |
| 显示名 | 女仆建筑 |
| jar 产物 | `MaidConstruction-<version>.jar`（由 `mod_name` 去空格得到） |
| MC / Forge | 1.20.1 / 47.1.3 |
| 映射 | `official` |
| 软依赖 | 车万女仆 `1.5.2-forge+mc1.20.1`、AE2 `15.4.10` |
| 源码包 | `com.example.blueprint`，共 45 个类 |

### 它做什么

1. **录制**：手持蓝图框选两个角点，服务端扫描区域并存成一个结构。
2. **投影**：手持蓝图移动时，结构以半透明"幽灵方块"显示，可旋转、可锚定到具体位置。
3. **施工**：把蓝图交给女仆（或放进她的背包/饰品栏），女仆自动取料并逐块建造。
4. **取料**：从附近的普通容器、绑定书指定的远程容器，或 AE2 的 ME 网络取材料。

---

## 2. 快速上手

```bash
# 构建（首次会反编译 MC，约需数分钟）
./gradlew build

# 启动客户端（需要把 TLM 放进 runtime 才能测女仆功能）
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

### 包划分

```
com.example.blueprint
├── BlueprintMod            主类：物品注册、AE2 条件注册、网络注册
├── build/                  ★ 建造核心，与具体模组解耦
│   ├── BuildSession        一次建造的增量状态机
│   ├── ItemProvider        材料来源接口 + 注册实现
│   ├── ItemSource          女仆背包视角的材料接口
│   ├── BlockMaterials      ← 扩展点：方块需要什么材料
│   ├── BlockMaterialResolver
│   ├── BlockEntityRotation ← 扩展点：旋转方块实体 NBT
│   └── BlockEntityRotationResolver
├── schematic/
│   ├── Schematic           结构数据（palette + 索引数组）
│   └── SchematicStorage    存档内的结构库（SavedData）
├── item/
│   ├── BlueprintItem       蓝图：选点、锚定、旋转、工具提示
│   ├── BindingBookItem     绑定书：指定远程取料点
│   └── BindingBookEvents   让绑定书抢到方块右键（兜底）
├── client/                 ★ 全部标 @OnlyIn(CLIENT)
│   ├── ProjectionRenderer  世界内的 3D 投影
│   ├── CableBusOutline     AE2 线缆的示意轮廓
│   ├── BoundBlockHighlighter  绑定书目标高亮（可穿透方块）
│   ├── ClientSchematicCache   结构缓存（LRU 24）
│   ├── ClientBlueprintBinder  收到数据后当场绑定到手持物品
│   ├── BlueprintTransfer   导入导出的文件读写
│   ├── BlueprintScreenOpener   打开面板 + 结构数据到达的通知出口
│   └── gui/
│       ├── BlueprintScreen     蓝图面板
│       └── BlueprintMaterialsScreen 建筑清单（按材料汇总）
├── network/
│   ├── ModNetwork          频道 + 11 个包的注册
│   └── packet/             10 个 C2S + 1 个 S2C
├── registry/
│   ├── ModItems            物品注册：blueprint、binding_book
│   └── ModCreativeTabs     模组专属创造物品栏（注册名为 main）
└── integration/
    ├── maid/               TLM 联动
    │   ├── MaidExtension        @LittleMaidExtension 入口
    │   ├── BlueprintBuildTask   女仆的"蓝图施工"工作模式
    │   ├── MaidStudyTask        "学习模式"：跟在主人身边记录演示
    │   ├── MaidIndustryTask     "工业模式"：照学习池里的单开工（见 §7.16）
    │   ├── MaidSpeech           她开口的格式统一在「名字：……」
    │   ├── MaidBuildTickHandler 服务端 tick 驱动器
    │   ├── BlueprintBuildController ★ 施工状态机（最大文件）
    │   ├── MaidItemSource       女仆背包的 ItemSource
    │   └── BindingBookBauble    空饰品，仅为让饰品栏接受绑定书
    └── ae2/                ★ AE2 联动，整包不得在 AE2 缺位时被触碰
        ├── Ae2Compat            安全入口（不引用 AE2 类型）
        ├── Ae2ItemProvider      ME 网络取料/还料
        ├── Ae2MaterialResolver  线缆材料从部件 NBT 反推
        ├── Ae2BlockEntityRotation 线缆部件朝向
        ├── Ae2TerminalRegistry  女仆终端 / 创造女仆接口的方块、物品、BE
        ├── MaidTerminalBlock
        ├── MaidTerminalBlockEntity
        ├── InfiniteItemStorage            ★ 取之不尽的 ME 存储
        ├── CreativeMaidInterfaceBlock     ★ 创造女仆接口（见 §7.10）
        ├── CreativeMaidInterfaceBlockEntity
        ├── Ae2StorageTransfer            ME 存储搬运动作（方块来源与无线来源共用）
        ├── WirelessMaidLink              无线终端的绑定/解析/距离/取电
        ├── WirelessMaidTerminalItem      ★ 无线女仆终端（见 §7.11）
        └── Ae2WirelessProvider           女仆用的无线取料源
```

### 依赖方向（重要）

```
        integration.maid ──┐
                          ├──→ build / schematic / item   （核心）
        integration.ae2 ───┘

        client ──────────────→ build / schematic / item
```

**核心包（`build` / `schematic` / `item`）绝不 import 任何模组类型**。反向依赖靠两种机制实现：

- **注册链**：核心定义接口（`BlockMaterials`、`BlockEntityRotation`），集成包实现并注册。
- **安全入口**：`Ae2Compat` 本身不引用 AE2 类型，由它在中转时判断模组是否存在。

违反这条会导致**没装对应模组的玩家无法启动游戏**。

---

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
| `rotate(Rotation)` | 返回旋转后的**新实例**（`NONE` 返回自身） |
| `transformState(BlockState, Rotation, Mirror)` | 私有，见 §7.3 |
| `rotateDirection(Direction, Rotation)` | **公开的**方向旋转工具，多处共用 |
| `stateAt / blockEntityAt / inBounds / entries` | 访问 |
| `write / read` | NBT 序列化 |

**两个刻意的选择**：

- **不用 `NbtUtils` 读写方块状态**。1.20.1 的 `NbtUtils.readBlockState` 需要 `HolderGetter<Block>`，而蓝图读写常发生在拿不到世界 registry 的场合。自己实现格式反而可控。
- **`entries()` 会为每个方块新建 `BlockPos` 和 `BlockEntry`**。大结构下别在渲染循环里调它 —— `ProjectionRenderer.rebuild` 就是为此改用下标遍历 + 复用 `MutableBlockPos`。

### 4.2 `SchematicStorage` —— 存档内的结构库

- 类型：`SavedData`，存在**主世界**（所以跨维度的蓝图也能解析）。
- 存档名：`blueprint_schematics`。
- 映射：`Map<UUID, Schematic>`。
- **蓝图物品只存 UUID**，结构本体不入物品 NBT —— 否则一本蓝图会撑爆物品栏同步。

```java
static SchematicStorage get(ServerLevel level)   // 从主世界取/建
UUID put(Schematic schematic)                    // 存入并标脏，返回新 UUID
@Nullable Schematic get(UUID id)
boolean remove(UUID id)
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
| `Rotation` | int | 朝向的 `ordinal` |
| `Completed` | boolean | 是否已建完 |
| `MaidBuild` | boolean | 是否允许女仆施工（默认允许） |

`Completed` 和 `MaidBuild` 存在物品 NBT 而不是内存里 —— 存档重载、女仆换班之后仍然有效，不会反复播报"施工完毕"。

### 4.4 `BuildSession` —— 增量建造状态机

内部状态：`order`（待放置列表）、`cursor`、`deferred`（支撑未就位的推迟列表）、`pass`、`finished`。

**四个方法**：

| 方法 | 用途 |
|---|---|
| `static plan(Schematic)` | 排序：`y` → 支撑优先级 → `z` → `x` |
| `static bill(Schematic)` | **整座结构**从零建起需要哪些材料 |
| `remainingBill(level, origin)` | **从现在这个进度**还差哪些材料（跳过已建成的方块） |
| `step(level, origin, source, maxBlocks)` | 实际放置，返回 `StepResult` |
| `peekNextTarget(level, origin)` | 下一个目标坐标（女仆据此决定走位） |

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
    ├─ BlueprintItem.findHeld(player)    手持蓝图才画
    ├─ 没录制但选了 Pos1 → 画选区线框
    ├─ ClientSchematicCache.get(id, rotation)
    │      本地缺失 → 发 C2SRequestSchematicPacket（2 秒节流）
    ├─ origin = 锚点 或 视线所指位置
    ├─ rebuild()   重算待渲染列表 PENDING（400ms 间隔 / id 变 / origin 变 / 结构实例变）
    └─ 逐块渲染半透明幽灵方块
```

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
    BUILD          调 session.step(...)
                      ├─ placed > 0    → 挥手 + 音效，等 PLACE_COOLDOWN
                      ├─ finished      → 收工，走还料流程
                      └─ 都没发生       → 认为缺料，转 FETCH
    FETCH          找来源 → 走过去 → 取料 → 回 BUILD
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

`ProjectionRenderer` 和 `BlueprintScreen` 渲染方块时传的是 `ModelData.EMPTY`（投影那个位置还没有方块实体）。

**多数方块无所谓，但 AE2 的 ME 线缆不是**：`CableBusBakedModel.getQuads()` 第一件事就是 `ModelData.get(...)` 取渲染状态，取不到直接 `Collections.emptyList()` —— **一个面都画不出来**。

所以两处都做了兜底：记录画不出任何面的方块，循环结束后统一补轮廓。

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
    @Override public int acceptInto(IItemHandler src, Map<Item,Integer> filter) { ... }  // 可选（还料）
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

### 6.3 让某个方块在旋转时朝向正确

**适用场景**：方块把自己或子部件的朝向存在方块实体 NBT 里。

**背景**：`Schematic.rotate` 转 `BlockState` 是通用的（见 §7.3），但 **NBT 是无 schema 的任意树**，引擎不知道哪个字段是朝向。

**步骤**：

1. 实现 `BlockEntityRotation`：

```java
public class MyRotation implements BlockEntityRotation {
    @Override
    @Nullable
    public CompoundTag rotate(BlockState state, CompoundTag tag, Rotation rotation) {
        if (!(state.getBlock() instanceof MyBlock)) {
            return null;
        }
        CompoundTag result = tag.copy();       // 不要就地改传入的
        // 用 Schematic.rotateDirection(...) 得到新方向，搬运对应的键/字段
        return result;
    }
}
```

2. 在模组初始化时注册。

**三个必须注意的点**：

- **不要就地修改传入的 `tag`** —— 同一个结构可能被反复旋转。
- **不要边搬边查**。如果要把 `north` 的内容搬到 `east`，必须先把"搬运计划"整个算出来，再分两批执行（先全摘除、再全写入）。否则刚搬过去的键会被后面的迭代当成源再处理一遍，**结果多转一格**。
- 用 `Schematic.rotateDirection(dir, rotation)`，它已经处理了 `UP`/`DOWN`（`Direction.getClockWise()` 对它们会抛异常）。

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

- 模型放 `assets/blueprint/models/item/<name>.json`
- 中英文案都要加（`assets/blueprint/lang/zh_cn.json` 和 `en_us.json`）
- 配方放 `data/blueprint/recipes/<name>.json`
- 给创造模式用的物品，加进 `ModCreativeTabs` 的 `displayItems` 回调（模组独占一栏，不要塞原版标签页）
- **涉及软依赖模组物品的配方必须用 `forge:conditional` 包住**，否则没装那个模组的整合包会加载失败

**创造物品栏的注意点**：`ModCreativeTabs.MAIN` 的 `displayItems` 里，`ModItems` 的注册对象要 `.get()` 之后再 `accept`（`Output` 只收 `ItemLike`/`ItemStack`，不收 `RegistryObject`）。软依赖模组的物品**必须先在 `Ae2Compat.isLoaded()` 里包一层**再取 `.get()` —— 触碰 `Ae2TerminalRegistry` 的静态字段会初始化整个类，没装 AE2 时那是个 `NoClassDefFoundError`。标签页标题走 `itemGroup.blueprint.main` 这个翻译键。

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
| `ABORT_DISTANCE_SQR` | 256 | 超过就不去了，就地取料 |
| `PLACE_COOLDOWN` / `FETCH_COOLDOWN` | 4 / 20 | 放块、取料的间隔 tick |
| `NO_SOURCE_COOLDOWN` | 100 | 找不到材料后的冷却 |
| `RETURN_TIMEOUT` | 600 | 还料流程的超时兜底 |
| `MESSAGE_COOLDOWN_MS` | 30000 | 同一类提示的最小间隔 |
| `MAX_REPORTED_MATERIALS` | 5 | 缺料提示最多列几种 |

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

### 7.19 录制态：借旁观者的两样好处，不借它的交互封锁

框选一座建筑得能上房、能钻地下室，所以录制时给玩家**能飞**（`abilities.mayfly / flying`）与**能穿墙**（`noPhysics`）。**但别直接切成旁观者模式**：它掐掉一切交互，连方块都点不中，而录制要做的恰恰是"右键点方块选角点"这两下。三件事记住就不会踩：

- **进之前的状态原样存下来，退出时还回去**：创造模式的玩家本来就飞着，退出录制态不该把他的飞行收走。存档落在 `player.getPersistentData()`（不是内存里）：换维度不用管，服务端重启后"这人还在录制态"也认得出；而**清掉那份存档就等于"不在录制态"**，所以完成、取消、断线还原都只是同一个动作（`RecordMode.leave`）。
- **断线必须还原**（`PlayerLoggedOutEvent`）：能力是跟着玩家存档写下去的，不还原的话他下次登录就是个能飞的生存玩家。
- **`noPhysics` 不是同步字段**：服务端那份由包改，客户端这份得自己按着（录制态每刻重申一次），否则本地预测会在墙前停下——玩家看到的是"穿不过去"。

按键一侧的规矩：只有**世界里没开着任何界面**时才抢键。左键归那个**可取消**的 `InputEvent.MouseButton.Pre`（录制时贴着建筑看，很容易顺手挖掉一块）；`E`（开背包）与 `Q`（丢东西）则**不能**靠事件取消——`InputEvent.Key` **不可取消**，对它调 `setCanceled()` 会抛 `UnsupportedOperationException`，而且那一抛会把整段处理打断，按键白按、日志里还多一条 ERROR（这个坑踩过一次）。也**不能**靠"抢在原版读按键之前把 click 收掉"：在 `TickEvent.ClientTickEvent` 的 `START` 相位 `consumeClick()` 试过，玩家按 Q 照样把东西丢出去——原版读按键比任何一个 tick 阶段都早。**确定生效的做法是把这两个键在录制期间摘掉**（`KeyMapping.setKey(InputConstants.UNKNOWN)`，退出、取消、断线时原样还回去）：键都没绑，原版那句 `consumeClick()` 永远是 false。摘的是**客户端**的控制设置（`KeyMapping` 本来就是客户端状态），代价只有"这中间崩了要自己去控制里绑回来"这一点。方向键微调按"起点 / 终点"分工（起点 = 先点的那一角，终点 = 后点的）：`←` 终点 X+1、`→` 终点 Z+1、`↑` 终点 Y+1、`↓` 起点 Y−1，`Ctrl` 把这一下从终点换成起点（`Ctrl+←` 起点 X+1、`Ctrl+→` 起点 Z+1、`Ctrl+↑` 起点 Y+1、`Ctrl+↓` 终点 Y−1），Shift 一律反向。高度之所以分成上下界两条键（↑ 抬终点、↓ 压起点），是因为框的顶与底最常分开动；水平方向一根轴一个键就够。三根轴各给各的键，不让方向键去猜"你想调哪根"，猜错了反而要点两次才知道偏了。只点了一个角时，方向键照样能动它。E 那一下还留了兜底：万一还是把背包开了，就关掉背包并按"完成"处理，绝不让玩家按了 E 只看到背包、不知道录没录成。

**录下来的结构落在哪**：**直接就是 `blueprints` 目录里的文件**——`C2SCaptureToFilePacket` → 服务端 `Schematic.capture` → `S2CSchematicFilePacket` → 客户端 `BlueprintTransfer.writeToFile` + `BlueprintLibrary.refresh`。蓝图物品在这条路上只是"把某一份取到手上"的容器，录制不经过它：让录制先写进手上一张纸、再让玩家去导出，等于凭空多一步，还要占住他手上的格子。

**扫描与落盘为什么不在同一端**：扫描只有服务端做得准（客户端的方块实体 NBT 常常是残的），而文件只能写在**玩家自己那台机器**的游戏目录里——所以数据要在网络上来回一趟。中间那段字节只有一种写法（`Schematic.encode` / `decode`，文件与网络共用），别再在客户端解一遍再编一遍。

## 8. 排查手册

### 8.1 女仆不干活

按顺序检查：

1. **蓝图是否有锚点** —— 没锚点女仆不知道建在哪，会提示 `maid_no_anchor`。
2. **女仆的任务是否是「蓝图施工」** —— `MaidBuildTickHandler` 只处理任务是 `BlueprintBuildTask.UID` 的女仆。
3. **蓝图是否允许女仆施工** —— `MaidBuild` 标记。
4. **看日志** —— `BlueprintMod.LOGGER` 会记录取料出发、取到几种、还料结果等关键节点。

> **「下单了不干活」是另一条链路**：`MaidCraftTickHandler` 只处理**在「工业模式」**、
> 且**正处于工作时间**的女仆。模式由下单自动切换（`MaidIndustryTask.employ`），
> 所以先查作息：`Activity.WORK.equals(maid.getScheduleDetail())` ——
> 不在工作时间她整段都不动，单子排队等着是**预期行为**，不是坏了。

### 8.2 女仆取不到材料

日志里搜 `女仆 ... 没取到材料`，后面会带上是"容器里有"还是"容器里没有"，以及背包空余格数。

| 现象 | 可能原因 |
|---|---|
| "容器里没有还缺的" | 来源判断用错了清单（应查 `shortfall`），或者材料解析不对（见 §6.2） |
| "容器里有还缺的" | 背包真的塞不下，或者 `transferInto` 的容量判断有误 |
| 提示缺料清单但箱子里明明有 | 材料的**物品 ID** 和箱子里的不是同一个 —— 典型是 §6.2 那类方块 |
| 一直重复搬同一种材料 | `remainingBill` 没生效，或者 `missingAmounts` 算错 |

### 8.3 渲染相关

**游戏直接退出，日志末尾是 `Unreported exception` + `Stopping server`** —— 渲染线程抛了异常。堆栈里找 `ProjectionRenderer` / `BlueprintScreen` / `CableBusOutline`，多半是坐标越界（见 §7.4）。

**投影里某些方块看不见** —— 该方块的模型依赖 `ModelData`（AE2 线缆是典型）。检查 `CableBusOutline.isCableBus` 是否认出了它。

**旋转蓝图后投影错位/残留** —— `PENDING` 的失效条件（§7.4）。

### 8.4 编译/运行时错误

**`NoClassDefFoundError` 指向 AE2 类型** —— 有代码在 `Ae2Compat.isLoaded()` 之外触碰了 `integration.ae2` 的类。检查调用点。

**未装 TLM 时启动失败** —— 有代码在 `@LittleMaidExtension` 之外引用了 TLM 类型。

**配方加载失败** —— 涉及软依赖模组物品的配方没有用 `forge:conditional` 包住。

**`Rendersystem called from wrong thread`（出现在 `Server thread`），随后 `Render thread` 报
`Reported exception thrown!`** —— 有代码在非渲染线程上碰了客户端 API，多半是开界面。
典型是只按**物理端**判断、没判逻辑端，见 §7.15。

**刚学会的做法"摆法"是空的、只剩配方 id**（时好时坏，每格正好 1 个时才必现） ——
录入时存了合成格的活引用，材料被扣减后摆法跟着空了，见 §7.14。

### 8.5 验证第三方 API 的正确姿势

**不要凭记忆或推测写第三方模组的 API。**

```bash
# 1. 查版本与下载地址
https://api.modrinth.com/v2/project/<id>/version?loaders=["forge"]&game_versions=["1.20.1"]

# 2. 列出 jar 内的类，确认真实包路径
python -c "import zipfile; z=zipfile.ZipFile('xxx.jar'); print([n for n in z.namelist() if 'XXX' in n])"

# 3. 核实方法签名
javap -p -c -cp xxx.jar <全限定类名>
```

**这个方法纠正过多次凭印象写下的错误** —— 例如推测的 `AECapabilities` 类实际不存在（真名是 `appeng.capabilities.Capabilities`），以及差点把 `getUpdateTag` 的覆写误判成 `saveWithoutMetadata`。看字节码还能确认调用链，比如"线缆的部件方向到底存在哪"。

---

## 9. 构建与发布

### 构建配置要点（`build.gradle`）

- `archivesBaseName = mod_name.replace(' ', '')` → `MaidConstruction`。
- `processResources` 的 `filteringCharset = 'UTF-8'` —— 避免 Windows 下 GBK 乱码。
- `jar.finalizedBy('reobfJar')`；**没有配置 jarJar**。
- `jar` 会把根目录的 `LICENSE` 打进 `META-INF/LICENSE`。MIT 要求协议随「所有副本」分发，而 `mods.toml` 的 `license` 字段只是一个协议名；`reobfJar` 只重映射 `.class`，这个条目会原样留下。
- 编译参数带 `-Xlint:deprecation`，所以**新增的过时 API 调用会以警告形式暴露**，别忽略。

### 署名（`mod_authors`）的坑

`gradle.properties` 的 `mod_authors` 是署名的**唯一来源**，它会同时流向：

- `mods.toml` 的 `authors` → 游戏内模组列表的 Authors 行
- jar 清单的 `Specification-Vendor` / `Implementation-Vendor`

**汉字要写成 `\uXXXX` 转义**（`\u4F0A\u7EB3` = 伊纳）。`java.util.Properties` 按 ISO-8859-1 解析 `gradle.properties`，直接写 UTF-8 汉字会得到乱码。转义在两种解析方式下都成立，所以是最稳的写法。

改完必须重新构建才对已发布的 jar 生效。

### 发布流程

```bash
# 1. 升版本号
#    gradle.properties 的 mod_version

# 2. 写更新说明
#    CHANGELOG.md 顶部加一段，格式：## [x.y.z] - YYYY-MM-DD

# 3. 本地构建验证
./gradlew build

# 4. 提交并打标签
git add -A && git commit -m "Release x.y.z"
git tag vx.y.z && git push origin main && git push origin vx.y.z
```

推 tag 会触发 `.github/workflows/release.yml`：

1. 构建，失败时把日志末尾 150 行写进 job summary 并上传 `build/reports/`。
2. 从 `CHANGELOG.md` 用 `awk` 抽当前版本段落作为 Release 说明（**所以 CHANGELOG 的标题格式不能错**）。
3. `softprops/action-gh-release` 发布，附上 `build/libs/*.jar`。

`workflow_dispatch` 手动触发时只构建、不发版。

两个容易踩的点：

- **标签要用附注标签**（`git tag -a vx.y.z -m "..."`）。`git push --follow-tags`
  **只推附注标签**，轻量标签会被静默留下——结果只推了 `main`、Release 压根不触发，
  而命令行看起来是"推送成功了"。上面那行是显式 `git push origin vx.y.z`，没有这个问题。
  可以用 `git ls-remote --tags origin` 确认标签是否真的到了远端（附注标签会多出一条
  `refs/tags/vx.y.z^{}`）。
- **CHANGELOG 的标题必须严格是 `## [x.y.z]` 开头**。抽说明的 `awk` 用的是
  `index($0, "## [" ver "]") == 1` 前缀匹配，多一个空格都抽不到，会退化成兜底文案
  "CHANGELOG.md 里没有 x.y.z 的条目"。本机没有 Git Bash 时，可以用等价的 Python 逻辑
  先验证一遍再打标签：按行找 `startswith("## [" + ver + "]")` 起抄，遇到下一个 `## [` 停。

### 版本号的语义

- **补丁位**：修 bug、改文案、调数值
- **次版本位**：行为变更、新增扩展点、换判定逻辑
- **主版本位**：破坏存档兼容或协议兼容的改动（同时要升 `PROTOCOL_VERSION`）

---

## 10. 给 AI 的特别提示

如果你是被要求修改这个项目的 AI，请先读这一节。

### 动手前的检查清单

1. **读完 [§7 关键陷阱](#7-关键陷阱与设计决策)**。那里每一条都是踩过的坑，重复踩的代价是玩家崩溃或物品丢失。
2. **确认改动落在哪一层**。核心包（`build`/`schematic`/`item`）不能 import 模组类型；模组相关的逻辑必须走注册链或安全门面。
3. **改动涉及第三方 API 时，先用 `javap` 验证签名**（见 §8.5）。不要凭记忆写。
4. **改网络包要同步考虑 `PROTOCOL_VERSION`。**
5. **改 `Schematic` / `BuildSession` 的数据语义时，检查所有调用方** —— 这两个类被多处依赖，`bill` / `remainingBill` / `shortfall` 的区分尤其容易搞混。

### 容易被改错的地方

| 位置 | 风险 |
|---|---|
| `BuildSession.bill` vs `remainingBill` | 用途不同，混用会导致反复搬运（§7.5） |
| `shortfall` vs `pendingBill` | 前者用于判断来源，后者传给 `transferInto`（§7.5） |
| `BlockEntityRotation` 的实现 | 边搬边查会导致"多转一格"（§6.3） |
| `Schematic.transformState` | 用的是**原始状态**的值，改成转换后的值会双重旋转（§7.3） |
| `ProjectionRenderer` 的 `PENDING` 失效条件 | 少一个条件就会在世界里崩溃（§7.4） |
| `Ae2Compat` | 加入任何 AE2 类型的引用都会破坏软依赖隔离（§7.2） |
| 渲染循环里调 `Schematic.entries()` | 会为每方块新建对象，大结构下严重卡顿 |

### 这个项目的验证现状

**绝大多数改动只做过编译验证，没有实机测试。** 涉及运行时行为的改动（渲染、AI 状态机、网络同步、实际放置）都需要在游戏里验证。

验证时优先覆盖这些路径：

- 手持蓝图/绑定书右键**容器**（交互顺序）
- **旋转**一个含 AE2 机器和线缆的结构，然后完整建一遍（§7.3 的两条路径）
- 让女仆在**材料种类超过 8 种**的结构上施工（取料上限）
- **缺少材料**时观察提示内容和频率
- 中途打断女仆、存档重载后恢复（幂等性）

### 代码风格

- 注释写**为什么**，不写**是什么**。这个项目的注释密度较高，都是在解释设计取舍和历史原因 —— 保持这个风格。
- 面向玩家的文本走翻译键，中英文都要加。
- 日志用 `BlueprintMod.LOGGER`，区分 `warn`（可恢复）和 `error`（异常）。
- 可空返回值标 `@Nullable`（`javax.annotation`）。

## 11. 待办

### 展示物料

> 生成脚本（贴图、图标、指挥台）都在**本地** `tools/` 下：2026-10-03 起不再进版本库。
> 换配色照样改脚本重跑，只是脚本不再随仓库分发——接手的人拿不到，这一点先知道。

- ~~**模组 logo**~~ **已完成**：原图 `docs/logo-source.jpg`（画好的成品），
  处理脚本 `tools/make_logo.py`（按比例内缩 1.2% 削掉那圈浅色底 → 缩到 256/512 → 自画圆角遮罩）。
  产物：`src/main/resources/logo.png`（256，给 `mods.toml` 的 `logoFile`，游戏内模组列表显示它）、
  `docs/logo-512.png`（发布页上传用）。换图：覆盖 `docs/logo-source.jpg` 再跑一次脚本。
- **游戏内截图 3~5 张**：蓝图面板/录制、投影 + 女仆施工、进度条三行、学习池界面、说明书翻页。
- 物品/方块贴图：4 张**保留**，由 `python tools/make_textures.py` 从原版/AE2 底图改色生成
  （换配色只改脚本里那几张 ramp 表）。

### 界面缺陷（待确认归属）

- ~~无线女仆终端 / 绑定书的升级槽两排显示重叠~~ **先别当成我们的缺陷**：核过代码，本模组
  **没有给任何界面加槽位**——无线女仆终端直接继承 AE2 的 `WirelessTerminalItem`，
  面板、槽位、布局全是官方的；女仆终端与创造女仆接口登记的是 3 个槽（AE2 标准终端的数量），
  界面也走官方的 `MEStorageMenu`；绑定书**根本没有升级槽**（`BindingBookItem` 里没有任何槽位代码），
  原来那句"绑定书也有槽位"是写错了。
- 真正会多出第二排的，是**同样给 AE2 无线终端补槽位的附属模组**（AE2WTLib 那类，猜测）。
  这类模组自己往同一个界面塞槽，位置由它负责排开；我们的界面就是 AE2 的界面，插不上手。
- **怎么确认是谁加的**：只装 AE2 + 车万女仆 + 本模组，打开无线女仆终端——应当只有一排 3 个槽，
  与官方无线终端一致；再把那个附属装回去，多出的一排若与原有槽位叠在一起，就是它的问题。
  确认前不要改我们的界面：改了只会把官方布局也带歪。
- 要动的话，唯一该做的是**在资料里说明这属于兼容现象**，别写成"本模组已知缺陷"。

### 对外说明（发布页/介绍文案）

- 写清 **车万女仆（Touhou Little Maid）是必需前置**：`mods.toml` 里写的是软依赖（不装也不崩），
  但核心玩法"女仆施工"离不开她；**AE2 是可选的**（装上才有无线终端取料那套）。
- 联机注意事项：模组自带协议版本校验，**客户端与服务端必须装同一个版本**，不匹配会直接拒连。

### 明确不做（设计取舍）

- **撤销与拆除都不做。** 这里是**真实建造**：材料真被消耗，与目标不一致的方块会被替换
  （旧方块按战利品表回收，见 `Salvage`），所以既没有"一键回到之前"，也没有"照蓝图拆掉"。
  建错了就自己拿镐子处理——这是取舍不是缺口，别再当"缺功能"提。
  真要做，那是另一个玩法（拆除机），得单独设计，不是补漏。

### 候选方向（待讨论）

按"收益 / 成本"粗排，都还没定：

- **图纸格式互操作**：见下一条的清单。这是目前最容易变成"用不起来"的短板——
  建筑圈的存量图纸大多不在我们的格式里。
- **蓝图格式加版本字段**：`Schematic.write` 现在没有版本号，以后改格式只能靠校验失败拒载；
  接外部格式之前先把它加上。
- **多女仆分工**：同一张图派多只女仆，现在靠放置幂等凑出并行，没有任务切分，进度条也各算各的。
- **"只补空、不替换"开关**：想保护已有建筑，眼下只能不给她们这张图。
- **建筑清单导出**：清单只在界面里，不能存成文件分给队友。
- **施工尺寸上限**：单边 128 / 体积 262144，是网络同步与内存的安全线；要不要为大结构另开一条路待议。
- **跨模组路径的回归**：AE2 附属补槽位会让界面重叠（见上），Create 蓝图那条刚修过"朝向算两遍"——
  这类跨模组路径值得再统一走一遍。
- **导航让位规则**：学习池与施工共用一套导航，现在靠"谁先说话"的人工约定，值得抽成明确规则（§7.13）。

### 图纸格式：现在认什么、缺哪几家

**能读的**：自家 `.blueprint`（gzip NBT：`size` / `palette` / `blocks` / `block_entities`）；
机械动力的蓝图（`CreateSchematic`，只读）。**导出的**只有自家格式。

| 生态 | 格式 | 里面装什么 | 接入成本 |
|---|---|---|---|
| Litematica（含原版结构 `.nbt`） | `*.litematic` | NBT：多 region，每 region 一个 palette（`Name` + `Properties` 字符串，**与我们的写法几乎一致**）+ `BlockStates` 的 long 位压缩 | **最低**：语义同构，只差位压缩的读写（照 `PalettedContainer` 那套写），顺带覆盖"原版结构 `.nbt`" |
| WorldEdit / Sponge Schematic | `*.schem`（Sponge v1/v2/v3） | NBT：`Palette`（状态 → 注册表 varint id）、`BlockData`、`BlockEntities`、`Entities`、`Metadata` | 中等：状态要按注册表数字 id 双向映射，且数字 id 依赖两端模组一致 |
| 原版结构方块 / 数据包 | `*.nbt`（structure template） | palette + 逐方块的 pos / state / nbt | 低，而且**导出**价值最大：结构方块、数据包、Structure Gel 等都吃它 |
| 旧的 MCEdit / Schematica | `*.schematic` | 数字 id:meta 的字节数组（1.12 时代） | 不建议：表达不了现代方块状态，只能读个大概 |
| Axiom | 自有工程格式 | 客户端编辑器的文件 | 不划算：让它那边导出成 litematic / schem 再进来更实际 |
| Building Gadgets 等模板类 | 各家私有（JSON / NBT 不一） | 剪贴板与模板 | 优先级低，格式稳定性也待核实 |

**建议的顺序**：先"读 `*.litematic`"，再"读 Sponge `*.schem`"，导出先做"原版结构 `*.nbt`"
（最省事、受众最广）。三者共用同一层 palette ↔ 方块状态转换——先把这层抽出来，
之后每加一家就只是换个外壳。

## 12. 蓝图指挥台：托管投影与远程下单（进行中）

设计已定，正在实现。要点：

- **两件东西、一条绑定链**：**蓝图终端（物品）**是钥匙兼图纸库入口，**指挥台（方块）**放在世界里干活。
  拿终端右键指挥台即完成绑定，**绑定是玩家私有的**（每台指挥台记住它属于谁），
  绑定之后才能在界面里指派女仆。状态挂方块实体——存档天然、多人各用各的、可以长期挂着。
- **右键指挥台看三样**：当前投影的蓝图、指派给它的女仆、以及各自的建筑进度。
- **绑定语义**（先定死，免得以后打架）：一台指挥台只属于一个绑定玩家（可解绑换人）；
  一只女仆同一时间只绑一台指挥台——"她该建哪处工地"必须唯一，这也是现有施工模型的前提。
- **投影的可见性**：托管投影对所有人显示（全息本来就是给人看的，队友也能围观），
  但**只有绑定玩家**能暂停、取消、改指派。
- **投影由终端托管**：不再是"手持才显示"，放下去就在，**除非在终端里取消**。
  这是客户端投影的第三个来源（现有两个：自己手持、附近女仆手持），见
  `ProjectionRenderer.projectionSource`。
- **一次性下单**：选图 → 右键方块定面（与锚点定位同一套）→ 选女仆（**可多选**）→
  把结构 id、锚点、朝向与材料清单**一次性**交给她，之后她照单自己干，不再回调终端。
- **停下只有两条路**：手动改她的工作模式（原有机制），或者在终端里**暂停 / 取消**。
  暂停 = 保住进度停下；取消 = 撤单，投影随之消失。不做"超时自动撤单"——
  全息投影挂着不碍事，撤单必须是人点的。
- **多女仆**：同一张图可以分给多只，靠放置幂等凑并行；界面必须把进度**合并**显示
  （三只各报各的百分比对主人没有意义）。
- **停工的边界**：终端被拆 = 撤单并让她们停下，别留一个谁也管不着的工地。
- 女仆列表要显示"长相"：渲染她的 3D 形象必须碰 TLM 的客户端类，按老规矩关进
  `MaidClientBridge`，没装 TLM 的客户端不许加载到那些类（1.6.3 崩过的那一类）。

实现顺序（每一步都要能单独跑起来）：

1. ~~方块 + 方块实体 + 状态存档 + 打开界面（复用图纸库 / 详情页）~~ **已完成**
2. ~~托管投影：状态同步（数据小，全局发）+ 结构数据按需点播（复用现有请求包）~~ **已完成**：
   - **绑定**：拿蓝图终端右键指挥台（`C2SBindCommandPostPacket`，潜行 = 解绑）。走 `onItemUseFirst`
     而不是 `useOn`，这样绑定这一下不会顺带把界面打开；两端判定条件一致（点到指挥台就吃掉），
     否则会出现"界面时开时不开"。想看界面就空手右键。
   - **放投影：图纸库里点「投影」→ 回到世界摆位置与朝向**。入口在图纸详情页：
     进图纸库 → 点开一份 → 点「投影」。**不需要手上拿着蓝图**——库里那些东西是**客户端硬盘上的文件**，
     服务端读不到，所以文件字节先留在客户端会话里，等按 E 定下时才随位置与朝向一起发出去
     （`C2SCommandPostProjectionPacket`，服务端存成一份结构数据、指挥台记它的 id；
     渲染、施工、别人围观都照旧按 id 走）。
   - **定位用的是"在世界里"那一套**（`BlueprintRecordSession` 的 PROJECT 模式）：能飞、能穿墙、
     摘掉原版那两个键、屏幕角上写提示——与录制共用同一副架子，差别只有三处：
     右键选的是**一个位置**（结构最小角钉在那儿）、方向键留给**朝向**（`←`/`→` 转 90°、`↑`/`↓` 翻面）、
     `R` 是"清空位置"而不是"重来一遍"。世界里的线框按结构尺寸与当前旋转画（转 90° 时宽长互换），
     尺寸在进入时解一次就够——每帧解一份几千方的结构只为画个框，不值。
   - **放到哪一台**：从某台指挥台的界面进库的话就是那一台（坐标一路带进详情页）；
     从终端进的话取**最近一台属于我的**（客户端那张表记了每台的主人）。一台都没有就在界面上说一句。
   - **取消投影**在指挥台界面里点。界面里的反馈必须画在界面里（§7.17），所以前提判断客户端先做一遍、
     服务端再验一次。
   - **客户端那张投影表**（`CommandPostProjections`）**不靠新包**：登记挂在方块实体的 `load` 上
     ——区块加载带的整份 NBT 与状态变更的更新包都会经过它；`setRemoved` 时划掉。
     于是"走远了方块实体被卸掉、投影自己就没了"是白来的，也不必维护"该发给谁"。
   - **渲染**把"来源"抽成了 `ProjectionSource`（id / 名字 / 朝向 / 锚点），三个来源按
     **自己手持 &gt; 指挥台托管 &gt; 附近女仆** 取第一个可用的；结构本体仍按需点播（同一个 id 那套）。
3. ~~指派施工：给 `BlueprintBuildController` 加"外部指派"来源~~ **已完成**：
   - `server/CommandPostAssignments`：女仆 → 指挥台（带维度）的**索引**。真相在指挥台的方块实体里
     （它记着指派了哪些女仆），索引随方块实体 `load` 重建、`setRemoved` 划掉——不另存一份，
     两份记录一旦对不上，女仆会照着一份过期名单干活。
   - `BlueprintBuildController.tick`：手上没图时先问"有没有指挥台指派我"（`Assigned.NONE / IDLE / BUILD`）。
     取数只在"从哪儿来"上分岔（`refreshAssignedSession` 从方块实体取 id/锚点/朝向），
     建会话、算 `siteId`、站位那段与蓝图那条**共用**（抽成了 `afterSessionBuilt`）——
     同一个工地走两条路进来，客户端的进度条认得出是同一处。
   - 完工记在**指挥台**上（新增 `completed` 字段与存档）；**取消投影 = 撤单**，挂在上面的女仆一并撤下。
4. ~~女仆列表~~ **已完成**（下单、进度合并、暂停还差）：
   - `server/MaidOwnership`：**主人 → 他的女仆**（UUID + 名字 + 维度）。**进游戏时扫一遍**
     （`MaidOwnershipTracker`，注册在 `MaidExtension` 里——那段只在女仆模组存在时执行，
     否则类里的 `EntityMaid` 会让没装女仆的客户端 NoClassDefFoundError，见 §7.10）。
     没进过表的只有"从没被加载过"的女仆，那基本只发生在"后来才装这个模组"的时候。
   - `MaidRoster`（客户端）+ `C2SMaidListRequestPacket` / `S2CMaidListPacket`：名单与"已指派到哪台"
     **一次给全**，分两次发只会出现"名单到了、状态还没到"的一帧错位。
   - 名单**单开一个界面**（`MaidRosterScreen`）：每只一张卡、画成 3D 立绘——姓名那行文字认不出是谁。
     立绘走原版实体渲染（`InventoryScreen.renderEntityInInventoryFollowsMouse`），实体按 UUID 在
     `entitiesForRendering()` 里找（客户端没有按 UUID 查的入口）；她不在附近就只画名字。
     **指挥台界面与蓝图终端都只留一个「女仆」按钮**：指挥台那点地方连"名字 + 状态"都摆不下，
     更摆不下立绘（早先挤在图纸库底部时，窗口被顶出了屏幕）。
     点卡片即指派：从指挥台进来指**这一台**，从终端进来指**最近一台属于我的**（远程指派）。
   - 指派时**远处的直接传送**到指挥台上方一格（`teleportTo`）；她的区块没加载时先记下指派，
     等她加载后自己开工。只能指派**自己的**女仆——UUID 是客户端给的，先用那张表认一遍。
   - **指派必须把她的工作模式一并切过来**（`BlueprintBuildTask.employ/release`，与「工业模式」同一套）：
     `MaidBuildTickHandler` 只在她的任务是「蓝图建造」时才驱动她（那道闸门有意为之，免得别的模式下
     她突然开始搬方块），而指派只改指挥台那一侧的状态——不切模式，界面里看着就是"指派了没反应，
     她照旧种地"。切换按**边沿**做（只在她刚被指派、刚被撤单时动一次）：她干活期间主人手动换了模式，
     那是主人的意思，不该被一直掰回来。**还没放投影时她就是这个模式在空转 = 待命**，
     不干扰别的活，等投影一放下就自己开工。
