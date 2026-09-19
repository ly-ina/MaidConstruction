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
│   ├── BlueprintScreenOpener
│   └── gui/BlueprintScreen 蓝图面板
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
| `rotateState(BlockState, Rotation)` | 私有，见 §7.3 |
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
```

**`ClientBlueprintBinder` 为什么存在**：不走服务端物品 NBT 同步。那条路要经过容器槽位广播，慢半拍会让面板一直显示旧结构、得关掉重开才对。收到包就当场绑定，界面立刻正确。

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
    MOVE_TO_SPOT   走到站位（锚点外扩 STAND_MARGIN=2 的一圈里找落脚点）
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

这一节是文档里最重要的部分。下面每一条都是实际踩过的坑。

### 7.1 原版交互是「方块优先」

**现象**：手持绑定书右键容器 → 容器打开了，绑定没生效。手持空白蓝图点容器 → 同样打不开选区。

**原因**：原版的调用顺序是**方块的 `use()` 先执行**。方块返回 `CONSUME`/`SUCCESS` 时，物品的 `useOn()` 根本不会被调用。

**解法**：覆写 `Item#onItemUseFirst(ItemStack, UseOnContext)` —— Forge 的这个钩子跑在方块处理**之前**，返回非 `PASS` 就能完整接管这次右键。

`BlueprintItem` 和 `BindingBookItem` 都这么做了。`BindingBookEvents` 那个 `setUseBlock(DENY)` 是兜底（在物品自身抢不到时生效）。

### 7.2 软依赖的隔离方式

**TLM**：类上标 `@LittleMaidExtension`，只有 TLM 存在时才会被反射实例化。事件注册（`MaidBuildTickHandler`）放在 `addMaidTask` 回调里，用静态标志防重复。

**AE2**：三重隔离。

1. 全部代码关在 `integration.ae2` 包。
2. `Ae2Compat` **本身不引用任何 AE2 类型**，只用 `ModList.get().isLoaded("ae2")` 判断。JVM 是懒加载的，方法体里提到的类要等真正执行到那一行才解析 —— 所以没装 AE2 时它永远不会去解析 `Ae2ItemProvider`。
3. `build.gradle` 里**故意只给 `compileOnly`，不加 `runtimeOnly`**。

**代价**：开发环境跑不了 AE2 功能，要测需临时加 runtimeOnly。

**延伸**：`CableBusOutline` 用注册名 `ae2:cable_bus` 识别线缆，也就不需要引用 AE2 类型。

### 7.3 旋转的两种失效（都是引擎层面的限制）

**失效一：方块状态没转**

```java
BlockState.rotate(Rotation)  →  Block.rotate(state, rotation)
                                     ↑ 默认实现直接 return state
```

原版 `Block.rotate` 的**默认实现什么都不做**。只有主动覆写过的方块（楼梯、箱子这类继承 `HorizontalDirectionalBlock` 的）才会真的转朝向。

**AE2 的机器属于没覆写的那一类**（`DriveBlock` 等继承自己的 `AEBaseEntityBlock`），所以磁盘驱动器的 `facing` 纹丝不动。

**解法**：`Schematic.rotateState` 在方块自己转完之后，**按原始状态的值**把所有 `DirectionProperty` 重设一遍。用原值算目标值，所以不会出现"转了两次"。

**失效二：NBT 里的朝向**

有些方块的朝向**压根不在 BlockState 里**。AE2 的线缆把"部件挂在哪个面"记在 NBT 的**键名**上：

```json
{ "cable": {...}, "north": {"id": "ae2:terminal"}, "east": {...} }
```

而 `CableBusContainer.readFromNBT` 是这么读的：

```
for (Direction side : DIRECTIONS_WITH_NULL) {
    tag.get(NBT_KEY_SIDES[getSideIndex(side)])
    loadPart(side, compound)      // 方向完全由键名决定
}
```

**没有别的通道** —— 想让部件跟着转，只能搬这些键。这由 `Ae2BlockEntityRotation` 负责。

**为什么不能通用处理**：BlockState 有 `Property` 系统，引擎能按 `Rotation` 通用变换；而 NBT 是**没有 schema 的任意树**，引擎看到 `north: {...}` 不知道那是朝向、物品名还是自定义标签。**原版的 `StructureTemplate`（结构方块）同样做不到** —— 它旋转时也只处理 BlockState，NBT 原样搬运。

### 7.4 缓存与结构的一致性

`ProjectionRenderer` 用静态 `PENDING` 列表缓存"待渲染方块"，坐标是**结构内的相对坐标**。它有四个失效条件：

```java
now - lastScan > RESCAN_INTERVAL_MS      // 定时刷新
|| !Objects.equals(id, cachedId)          // 换了蓝图
|| !Objects.equals(origin, cachedOrigin)  // 锚点移动
|| schematic != cachedSchematic           // ★ 结构实例变了
```

**最后一条是崩溃修复留下的**。旋转蓝图时，`ClientSchematicCache.get(id, rotation)` 会返回一个**宽长互换的新副本** —— 而 `id` 和 `origin` 都没变。沿用旧坐标去访问新结构就会 `ArrayIndexOutOfBoundsException`。

渲染线程上抛异常会**直接退出游戏**（日志里表现为一次 `Unreported exception` 紧跟 `Stopping server`）。

**防御**：`CableBusOutline.outlinesOf` 开头也做了 `inBounds` 检查。调用方来自渲染循环，坐标未必和传进来的结构对得上 —— 宁可少画，不能崩。

### 7.5 取料的三层数量账

这三个概念很容易混，改代码时务必分清：

| 名字 | 含义 | 用在哪 |
|---|---|---|
| `bill` | **整座结构**从零建起需要多少 | 还料时判断"哪些是这次工程带来的" |
| `pendingBill` | 跳过已建成的方块后，**还缺多少** | 传给 `transferInto`（内部再减背包） |
| `shortfall` | 再扣掉**背包已有**，真正要去拿的 | 判断"值不值得跑一趟" |

**踩过的两个坑**：

**坑一：用 `bill` 取料** → 每缺一次料都把整座建筑的量搬一遍。已建好的部分会被反复要一回，女仆来回跑不说，手上的材料还越堆越多。解法是 `remainingBill`。

**坑二：用 `pendingBill` 判断来源** → 容器里只有"背包早就备齐的那几种"时，`hasAny` 通过，女仆白跑一趟，然后报"背包满了"（实际背包没满、容器里也没有她缺的）。解法是用 `shortfall` 判断。

**另一个相关的**：取料上限原本硬编码 `MAX_PULL_SLOTS = 8`，建筑用超过 8 种材料就永远凑不齐，装了背包升级也不会多拿。现在传的是 `countEmptySlots(backpack)`。

### 7.6 数值与单位约定

**所有距离比较都用平方距离**（`distanceToSqr` / `distSqr`），避免多余的 `sqrt`。常量的命名也跟着是 `XXX_SQR`。

| 常量 | 值 | 含义 |
|---|---|---|
| `CONTAINER_SEARCH_RADIUS` / `_HEIGHT` | 10 / 4 | 就近取料的搜索范围 |
| 到位距离² | 4 | 到站判定的水平距离 |
| `STAND_MARGIN` | 2 | 站位在锚点外扩多少格 |
| 离岗距离² | 64 | 超过就重新走回站位 |
| 导航放弃阈值² | 256 | 超过就不去了，就地取料 |
| `PLACE_COOLDOWN` / `FETCH_COOLDOWN` | 4 / 20 | 放块、取料的间隔 tick |
| `NO_SOURCE_COOLDOWN` | 100 | 找不到材料后的冷却 |
| `RETURN_TIMEOUT` | 600 | 还料流程的超时兜底 |
| `RESCAN_INTERVAL` | 100 | 施工期间重扫间隔 |
| `MESSAGE_COOLDOWN_MS` | 30000 | 同一类提示的最小间隔 |
| `MAX_REPORTED_MATERIALS` | 5 | 缺料提示最多列几种 |

### 7.7 提示信息的几个约定

- **面向玩家的文本一律走翻译键**（`Component.translatable`），不硬编码。
- **`Schematic` 抛的 `IllegalStateException` 消息本身就是翻译键**，上层直接 `Component.translatable(e.getMessage())`。
- **物品名用 `Component` 传递，不预先 `.getString()`** —— 否则会在服务端固定成某种语言，客户端换语言也翻不动。
- **缺料提示不走 `notify`**：那条路径有 30 秒冷却，会被其他消息挤掉。而且同一批缺料只播报一次（比较 `shortfall` 内容），女仆每隔几秒重试也不会刷屏。
- **`lastReportedShortfall` 要在确认有玩家在附近之后才设置** —— 否则女仆在远处缺料时会先被标记成"说过了"，等玩家走过去反而听不到。

### 7.8 幂等性

多处刻意做成幂等，改动时别破坏：

- `BuildSession.step` 跳过已是目标状态的方块。
- `ClientBlueprintBinder.bind` 只在 id 不同时才写入。
- `Schematic.rotate(NONE)` 和 `BlockEntityRotationResolver.rotate(NONE)` 直接返回自身，不产生复制。
- `ClientSchematicCache.get(id, NONE)` 返回原始实例。
- `MaidTerminalBlockEntity.ensureNodeCreated()` 可重复调用。

### 7.9 几个"看着奇怪但别改"的地方

| 位置 | 说明 |
|---|---|
| `MaidTerminalBlockEntity` 待机功耗 `0.0` | **与 AE2 官方终端一致**，不是随手填的 |
| `MaidItemSource.getBackpack()` 用 `getMaidInv()` | 不用 `ITEM_HANDLER` 能力 —— 那个是含主手/副手/盔甲的组合视图，往里面塞材料会让建筑方块跑到女仆装备栏里 |
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
| `ITerminalHost.getInventory()` | 接上网络就是**网络库存**，否则是 `maidStorage` | AE2 终端界面 | 上了网就该看到全网（其中已包含挂上去的创造库存）；没上网也不能是空的 |

**两份 `InfiniteItemStorage` 实例，差别只在收不收东西**（`insert`）：

| 实例 | `insert` | 理由 |
|---|---|---|
| `forMaid()` | **收下**（等于销毁） | 女仆建完房会把剩料还回来，还料的流程是"从背包取出 → 往来源里塞"。收下比让她抱着一堆材料、或者塞到别的箱子里干净——反正这里什么都是无限的 |
| `forGrid()` | **拒收**（返回 0） | AE2 的网络库存写东西时是按优先级逐个问下来的（`NetworkStorage.insert`）。挂上去的这份一旦答"我全要"，玩家往**任意**终端里放进去的东西就会被静默销毁，而且他自己不会知道 |

**别再合并回一个实例**。这两件事看着都是"收下物品"，实际完全不同：前者是我们主动清场，后者是物品蒸发。要改成全网也收下，得先想清楚玩家能不能接受往终端里放东西会消失。

**为什么不能统一成一个**：如果能力也返回网络库存，就会出现一个窗口期——节点已就绪但 `mountInventories` 还没跑，女仆这时来取料会看到"没有材料"，白跑一趟再等 100 tick 冷却。反过来，如果 `getInventory` 只返回自己的库存，联网状态下右上角的搜索框就搜不到网络里别的东西了。

**挂进网络的机制**：方块实体实现 `IStorageProvider`，并在构造函数里 `mainNode.addService(IStorageProvider.class, this)`。**这一句必须在节点 `create` 之前**——它是"这个节点能对外提供什么服务"的登记，等网格建起来再补登记，存储就挂不上去了。`mountInventories(IStorageMounts)` 里调 `mounts.mount(storage)` 即可。

**申报数量**用 `Integer.MAX_VALUE`，与 AE2 自己的创造存储一致。别改成 `Long.MAX_VALUE`：网络库存是若干来源相加的，几张创造接口同处一网会把计数加溢出；而且那个数字会原样显示在终端里。

**贴图是自己画的，没有沿用 AE2 的。** 女仆终端用的 `ae2:part/terminal` 是**部件**贴图——16×16 里只有 44 个像素不透明，画的是一个 12×12 的空心边框，连屏幕都没有。当整方块贴图（`cube_all`）用时，渲染出来是个透空的深灰框，既不是"终端"也改不成白色。所以这个方块的 `assets/blueprint/textures/block/creative_maid_interface.png` 是本 mod 唯一的自有贴图。

**"发光"靠的是模型面级全亮**，不是 `lightLevel` 单独能做到的：

```json
"forge_data": { "block_light": 15, "sky_light": 15 }
```

这是 Forge 的 `ForgeFaceData`（1.20.1 有效，AE2 自己的终端屏幕也这么写）。只设 `lightLevel` 的话，方块能照亮周围，但它自己的六个面仍然按环境光照渲染，暗处看着是块灰砖。两个都设才是"发光方块"。

### 7.11 无线女仆终端：只覆写取电、跨维度耗电与断开，其余照抄官方

**必须继承 `WirelessTerminalItem`，不能自己写一个物品。** `WirelessTerminalMenuHost` 的构造函数里写死了：

```java
if (item instanceof WirelessTerminalItem terminal) { ... }
else throw new IllegalArgumentException("Can only use this class with subclasses of WirelessTerminalItem");
```

不继承就拿不到 AE2 的终端界面。继承过来之后，面板、升级槽、"在物品栏里直接打开"的入口全是现成的。

| 覆写 | 作用 |
|---|---|
| `use` | Shift + 右键空气断开链接，其余交给父类去开面板 |
| `getAECurrentPower` | 插卡后对外声明满电（见下面"供电路径"） |
| `hasPower` / `usePower` | 插卡后从网络取电 |
| `getMenuHost` | **只为压住跨维度耗电**，且只在插卡时用自己的宿主（见下），判定逻辑全留在官方那边 |

**其余一律不覆写。** 这一节最值钱的就是这句话，它是踩完坑之后的结论：
原先为了让"没链接也能开面板"，把 `getLinkedGrid` / `checkPreconditions` /
`getMenuHost` 三处都换成了自己那套，结果整台终端**右键没反应，而且没有任何提示**。
逐个说清楚为什么不能碰：

**`getLinkedGrid`：提示就写在它里面。** 官方实现（javap 逐条读过）的分支是：

```
level 不是 ServerLevel          → 返回 null（客户端本来就不解析）
没有链接                        → 提示 DeviceNotLinked（"设备未链接"）→ null
链接的维度/方块找不到            → 提示 LinkedNetworkNotFound → null
那台访问点的网格为 null          → 提示 LinkedNetworkNotFound → null
```

把它换成"自己的解析"，等于把这些提示全部吞掉——玩家右键之后什么都没发生，
连知道这个模组的人都只会以为坏了。**要加自己的判定，就加在它返回 null 之后**，
不要在它前面截断。

**`checkPreconditions`：能不能开面板的判定归官方。** 官方实现（同样 javap 读过）：

```
物品对不上                   → false（不吭声）
getLinkedGrid(...) == null   → false（提示在 getLinkedGrid 里已经发过了）
hasPower(player, 0.5, …) 不过 → 提示 DeviceNotPowered（"设备未通电"）→ false
```

"没链接"和"没电"两种提示是**分工**的，覆写任何一个都会让对应的提示消失。

**`getMenuHost`：官方宿主并没有射程上限。** 一度以为"官方宿主会按射程把面板关掉"，
才换成了自己的宿主。读过字节码发现不是这样：`WirelessTerminalMenuHost.rangeCheck()`
只做两件事——`targetGrid` 是否为 null、以及**网格里还找不找得到一台
`WirelessAccessPointBlockEntity`**；它把最近的那台存进 `myWap`、把距离算出来给耗电速率用，
**没有任何"超出射程就拒绝"的比较**。AE2 的无线终端本来就不限距离，真正的限制是
"目标区块得加载着"（`Platform.getTickingBlockEntity` 取不到方块实体就当没网络）。
自己换宿主，反而把官方的耗电与失效逻辑一起换掉了。

**唯一的例外：跨维度耗电必须压住，这只能靠换宿主。** 所以后来还是加回了 `getMenuHost`，
但它只做一件事——覆写 `setPowerDrainPerTick(double)`，而且**只在插了女仆绑定卡时**才用自己的宿主。
起因是 AE2 自己算不出跨维度的距离：

```
getWapSqDistance(wap):
    访问点跟玩家不同维度   → 返回 Double.MAX_VALUE
    访问点没在工作         → 返回 Double.MAX_VALUE
```

于是 `currentDistanceFromGrid = sqrt(MAX) ≈ 1.3e154`，`checkWirelessRange` 再拿它去
`AEConfig.wireless_getDrainRate(...)`（实现就是 `wirelessTerminalDrainMultiplier * 距离`）
——速率变成 1e154 量级，**一 tick 就能把整张网络抽干**，跨维度实际上没法用。

**取值直接照抄 AE2WTLib 的量子桥卡：`22.5` AE/tick。** 它补的正是同一个洞，做法也一样——
覆写 `setPowerDrainPerTick`，判定过不去时就不接受 AE2 给的速率。区别只在触发条件：
它是"射程判定没过"（那边是靠量子网络桥连着的），我们是"距离算不出来"
（跨维度，或者链接的那台访问点不在工作）。

`setPowerDrainPerTick` 在 `ItemMenuHost` 里是 `protected`，跨包覆写没问题。
**但这一个宿主只覆写这一个方法**：`rangeCheck()` / `onBroadcastChanges()` 一律不碰——
"判定与失效逻辑留在官方那边"是上一轮刚换回来的教训（见上）。

**`onBroadcastChanges` 的极性（万一以后真要覆写）。** 它返回的是"菜单还算有效吗"，
`AEBaseMenu.broadcastChanges` 里是 `if (!host.onBroadcastChanges(this)) setValidMenu(false);`
——**`true` 才是继续开着**，返回 `false` 是当场关掉，表现就是"面板一闪而过"。
跟 `rangeCheck()`、`ItemProvider.requiresTravel()` 那种"返回 true 表示有问题"的直觉正好相反。

**`onItemUseFirst` / `useOn` 都不要碰，方块上的右键一律让给方块。** 这里踩过一次：
为了让"对着方块右键也能开面板"，覆写了 `onItemUseFirst`，结果这台终端
**再也放不进 AE2 的充能器**，也放不进无线访问点去链接。原因很实在：

- **充能器没有界面**（整个 AE2 里没有 `ChargerMenu` 这个类），它只有一个
  `ChargerBlock.onActivated(...)`，**右键把物品收进去是唯一入口**；
- **无线访问点是自己开面板的**（`WirelessAccessPointBlock.m_6227_` 里直接
  `MenuOpener.open(WirelessAccessPointMenu.TYPE, …)`），只在玩家**潜行**时让位——
  `InteractionUtil.isInAlternateUseMode(player)` 就是 `isSecondaryUseActive()`，
  跟手里拿什么无关。

结论：面板只从**右键空气**进（官方终端就是这么做的），方块上的右键一律让给方块。
想验证"是不是物品抢了右键"，把 `onItemUseFirst` 注释掉、空手右键同一个方块对比即可。

**链接必须走 AE2 原生的那套，而且要记得登记。** 链接是把终端放进 **ME 无线访问点**的槽位里完成的，
槽位按 `RestrictedInputSlot$PlacableItemType.GRID_LINKABLE_ITEM` 放行，而它查的是
`GridLinkables.get(item)` —— **每个物品都要显式登记处理器**：

```java
GridLinkables.register(WIRELESS_MAID_TERMINAL.get(), WirelessTerminalItem.LINKABLE_HANDLER);
```

直接用官方那个处理器就行：它的 `canLink` 是 `instanceof WirelessTerminalItem`（我们的终端本来就是子类），
`link`/`unlink` 读写的也是官方终端那套 NBT 键。**没登记的表现是"终端根本放不进访问点"**，
而且同样没有任何提示。登记和升级卡关联一起放在 `registerItemHooks()`（`commonSetup` 里调）。

**供电路径要看仔细。** 链路是
`ItemMenuHost.drainPower() → WirelessTerminalMenuHost.extractAEPower() → WirelessTerminalItem.usePower(player, amount, stack)`，
而 `extractAEPower` 里先用 `Math.min(amount, getAECurrentPower(stack))` 夹了一次上限。
所以"插卡后从网络取电"必须**同时**覆写两个方法：只覆写 `usePower` 是不够的——
电池空的时候，宿主算出来的上限就是 0，它压根不会来问 `usePower`。

两个容易写错的地方：

- **`hasPower` 不能无条件返回 true。** 官方只是先问它，得到 true 就照常去 `drainPower()`，
  扣不到照样把菜单判为失效——表现是**面板一闪而过**。插卡时拿网络 SIMULATE 一次如实回答，
  没电就会走到官方那条"设备未通电"的提示上。
- **`usePower` 必须"先 SIMULATE 再 MODULATE"。** `extractAEPower` 是能抽多少抽多少，
  抽不满时我们会转去用内置电池，而**那半截已经被从网络里扣掉了**——等于凭空烧掉。
  先用 `Actionable.SIMULATE` 确认能给够，再真扣。

**升级槽能不能插一张卡，不看物品类型。** AE2 的过滤器只有一行：

```java
return getInstalledUpgrades(item) < getMaxInstalled(item);
```

`getMaxInstalled` 来自 `Upgrades.add(卡, 机器, 张数)` 的登记。**没登记就是 0，
卡会被默默拒绝**，而且不会有任何提示。`Upgrades.add` 的第一个参数是卡、第二个是机器
（map 的键取的是 `Association.upgradeCard()`，也就是第一个参数）。
登记必须放在**物品注册完成之后**（本项目的调用点是 `commonSetup`），因为要取 `RegistryObject.get()`。

**无线来源不能让女仆走动。** `ItemProvider.requiresTravel()` 默认 `true`，无线终端返回 `false`，
控制器的 `tickFetch` / `tickReturn` 据此跳过寻路和开箱动画。
不这么做的话，女仆会照着绑定坐标一路跑过去——那个坐标可能在地图另一头，甚至在别的维度。

**"没电就不给开面板"这条原生规则保留了，代价是刚做出来要先去充一次电。**
`AEBasePoweredItem.getAECurrentPower` 读的是 NBT 里的 `internalCurrentPower`，
**没有 NBT 就是 0**（javap 读过），所以合成出来的终端和官方无线终端一样是空的：
右键会提示「设备未通电」，在**充能器**里充一次就能开面板插卡了。别为了跳过这一步
再去覆写 `checkPreconditions` —— 那正是让所有提示一起消失的原因（见上）。
真要跳过，正确做法是给合成产物直接充满（覆写 `onCraftedBy`），
而不是放宽"能不能开面板"的判定。

**充能器本身没问题**，别去改物品的可充能性：`ChargerBlockEntity$ChargerInvFilter.allowInsert`
的判据是 `Platform.isChargeable(stack)`，也就是
`instanceof IAEItemPowerStorage && getAEMaxPower(stack) > 0` —— 继承 `AEBasePoweredItem`
就自动满足。充能器显示"供能不足"是**它自己没接电**（它是网络设备，手边没网时可以用 AE2 的手摇曲柄）。

**跨维度有一条解不开的限制。** `resolveGrid` 会去对应的 `ServerLevel` 找节点宿主，
但**区块没加载就拿不到网格**。绑定卡放开的是"距离"和"维度"两条，放开不了"区块加载"——
ME 网络只存在于已加载的区块里，AE2 自己也做不到隔空访问。要长时间远程取料，
得靠区块加载器撑着那边。

### 7.12 学习池：展示按产物，存储按配方

**这两件事必须分开。** 早先那版只存产物，理由写在注释里："配方现场去配方表反查就行了"。
那条路在"一样产物只有一个配方"时成立，一旦**一样产物有好几个配方**就塌了：
反查拿到哪个是**配方表的顺序**，不是主人的意愿；而且"她到底见过哪一种做法"这件事根本没记下来，
主人想指定也没处可指。现在的结构是：

```java
Learned(ItemStack product, List<Recipe> recipes, int selected)   // selected = 主人点名的那条做法
Recipe(@Nullable ResourceLocation id, List<ItemStack> grid)   // 配方身份 + 演示时那 3×3 的摆法
```

**每个配方存两样，不是冗余。** `id` 是数据包写的那个身份（`minecraft:torch`），
按它去重、也按它反查原版配方；`grid` 是她**亲眼看见的摆法**——数据包被换掉、配方被删掉之后，
`id` 就查不出东西了，摆法还能读、还能显示给主人看。少存任何一样都会缺一块。

**去重按 id，没有 id 才按摆法**（`hasRecipe`）。同一个配方换个材料再演示一遍不该算第二个配方
（原版自己就常常 `Ingredient` 吃 tag，橡木换云杉还是同一个 `minecraft:stick`）；
但真换个做法（这一步配方表里有两条不同记录）就该是新的一条，排在后面。

**做法是"选"出来的，不是"排"出来的。** `Learned.selected` 记一个下标，`select` 只改它，
列表顺序（学会的先后）稳定不动，界面把选中的那条高亮出来。早先那版是 `promote` 把选中的挪到最前、
拿"第 0 个"当优先——看着省了一个字段，代价是**每次改选择都重排一次列表**：
顺序一直在动，主人反而记不住自己选的到底是哪条，"取消优先"更是没有对应的操作。
（没有做法、或者下标越界时 `chosenIndex()` 退回 0，所以"她照第 1 条做"这个默认是稳的。）

**"能读到配方"这件事全靠事件时机。** `ItemCraftedEvent` 是在 `ResultSlot#onTake` 的
**第一步**（`checkTakeAchievements`）发出来的，之后才轮到"按 `getRemainingItemsFor` 逐格扣减材料"。
所以处理器里读到的合成格还是**满的**：摆法与配方都必须**当场**取，等到下一 tick 就只剩产物了。
这也是"演示一次"能记住做法的根本原因。

**"这次用的是哪个配方"要去问那次合成自己，不要猜界面类型。** `ItemCraftedEvent` 本来就带着
合成容器（`getInventory()`）——原版工作台与背包给的正是 `CraftingContainer`，连"配方几乘几"
都顺带有了。早先那版拿 `player.containerMenu` 判断是 `CraftingMenu` 还是 `InventoryMenu`
再自己拼一个容器，是绕远路：**谁合成的，容器就在谁手里**。

**AE2 的合成终端是这条路上的例外。** 它发事件时传的是 `craftingGrid.toContainer()`——
`InternalInventory` 包装出来的适配器，**不是 `CraftingContainer`**，所以"把事件里那个容器
当合成格读"在它这里什么也读不到，池子里就落一条没有配方的记录（界面显示"认不出的配方"）。
它留的口子是 `CraftingTermMenu#getCurrentRecipe()`（公开，**无线合成终端与便携合成终端都是
它的子类**）。从它拿到的配方要用**配方自己的材料表**摊展示摆法，**别去读它内部网格的顺序**：
那是它三乘三槽位的顺序，未必等于配方的行宽；有序配方按 `ShapedRecipe#getWidth()` 摆，
无序的排一行。跨模组的这种调用一律走 `Ae2Compat` 那层（类本身不引用 AE2 类型，
确认加载了才碰 `Ae2CraftingCapture`）。

**还有一条兜底：按产物反查，且只认唯一一条匹配。** 多条匹配时那正是"一个产物有好几种做法"
本身——该由主人自己挑，不是我们随便选一条塞进池子。

**认不出配方就只记产物，别塞假配方。** "没有 id、摆法全空"的记录在界面上就是一行
"认不出的配方"，占着位置还挡着主人重演示一遍；只记产物的话，界面会老实说
"只见过产物，没见过做法（再演示一次她就记住了）"。

**取摆法要分工作台与背包两种容器。** 工作台 3×3 的 `craftSlots` **没有公开 getter**
（只有 `getGridWidth`/`getRecipeBookType` 那些），只能按槽位号读 `slots` 1~9（0 号是产物格）；
背包 2×2 的 `InventoryMenu#getCraftSlots()` 是公开的。反查配方时要把这两种容器**原样**递给
`getRecipeFor`——容器大小本身参与匹配（2×2 的配方在 3×3 里未必匹配得上），自己拼一个 3×3 去问是错的。
另外 1.20.1 **没有 `RecipeHolder`**（那是 1.20.2 的包装），配方 id 就在 `Recipe#getId()` 上。

**`registerTaskData` 漏了，是不报错的那种坏。** `TaskDataKey` 只是"一把钥匙"，
必须在这个回调里 `register(KET)` 交给 TLM 登记，`maid.getData(key)` 才查得到。
漏掉的表现是：数据**照样写、照样进存档**，但读回来永远是 null——"学过了全不记得"，
日志里一个字都没有。凡是新增 `TaskDataKey`，先看 `MaidExtension.registerTaskData` 有没有登记。

**界面入口：`InteractMaidEvent` 的 `post` 返回值就是"要不要跳过它自己的女仆界面"。**
女仆那边的顺序是"post 事件 → 手里物品的 `interactLivingEntity` → `openMaidGui`"，
而 `post()` 返回的是"事件被取消了"，所以**取消它 = 直接 SUCCESS 收场**（TLM 自己也这么用）。
条件是"**蹲下 + 空手**"：女仆界面是主人最常用的东西，普通右键、拿东西右键都得让给 TLM。
事件两端都会走一遍（右键本来就有客户端预测），所以界面在客户端开就行，
不需要额外发一个"打开界面"的包。

**界面不用请求数据。** 池子是 `TaskDataKey`，TLM 的 `TASK_DATA_SYNC` 已经把女仆身上那份同步到
客户端了，界面直接 `MaidStudyPool.known(maid)` 就行；只有**换做法**要发 C2S 包，
服务端 `setAndSyncData` 之后新选择会顺着同一条同步链路推回来，界面自己就变了。

### 7.13 学习池下单：单子只记产物，配方每次现查

**订单里不存配方，只存"做什么、还剩几个"。** 配方每次从她的学习池里取那条**选中的**。
理由跟池子那边一致：主人在界面上换一次做法，就该立刻作用于还没做完的单；
下单时抄一份配方，等于多出一份要同步的旧数据，而且两份一旦不一致，谁也说不清她该照哪份做。

**取料那套是借来的，但清单得传进去。** 就近容器 → 无线终端 → 绑定书仓库这个顺序、
以及"哪些方块算容器""无线终端怎么认""要不要扫饰品栏"那些细节，施工那边都磨过了，
重写必然走样。所以把 `findNearbyContainer` 与 `hasWantedItem` 改成静态、
并且**把清单当参数传**（原来读的是实例上的 `shortfall`）：手搓要的料跟施工那份完全是两回事。
只有绑定书那一支自己写——那边那支会顺带播报"隔着维度""仓库空了"，是施工口径的话术。

**"探针清单"与"真清单"是两张不同的单。** 吃 tag 的材料（"任意木板"）在清单里没法表达
"任意"，所以分两步：

```
探针清单：每个材料位的**所有候选**都列上 → 用来找"哪个来源值得跑一趟"
真清单：  每个材料位挑一件**真拿得到**的 → 用来搬料与扣料
          （先看她背包有没有，再拿 hasAny(单件) 逐个候选问来源）
```

少了探针那一张，箱子里明明有云杉木板，她却会认准材料表里排第一的橡木，然后卡在"缺材料"。

**手搓用一个自己的合成格，不借 `TransientCraftingContainer`。** 后者必须挂一个
`AbstractContainerMenu`，每次 `setItem` 都回调那个菜单的 `slotsChanged`；我们只是替她
"空手摆一遍"，没有菜单可挂，传 null 会在 `setItem` 时炸，造个假菜单则是把假状态塞进真流程。
`CraftingGrid` 就是个 3×3 + 空实现的 `setChanged`。

**先摆格、让配方认一遍，再扣料。** 顺序是"按她手上真有的东西摆 → `recipe.matches` →
确认每种都够 → 才 `consume` → `assemble` → `getRemainingItems`"。这样就没有
"扣了料才发现合成失败"的回滚路径要写（施工那边同一条规矩：先确认全都够再动手）。
摆法本身来自**配方自己写的材料表**（`StudyRecipeCapture.layout`，跟记录"她看过什么"共用一份），
不读任何一家模组内部网格的顺序。

**一步一 tick。** 认配方 → 找来源 → 赶路 → 搬料 → 手搓，每八 tick 只推进一步：
赶路要时间，一趟也可能搬不完，全塞进一个 tick 里就只能成功一次。

**缺料只提醒一次。** 提示写在 `Order.warned` 上（女仆每隔几秒就重试一遍，
照实播报会把聊天栏刷满）。做不了的单（池子里没记下做法、或者那条配方已被数据包删掉）
直接撤掉并说明——留着只会把后面的单永远堵住。

**"跟着主人"和"走去取料"用的是同一套导航，只能让一个说话。** 学习模式的 tick 驱动**每 tick**
都 `moveTo(主人)`，而取料要 `moveTo(仓库)`；两套各写各的，她的导航目标就每 tick 被顶回去一次，
**表现是站在原地不动、永远到不了仓库**。附近的箱子几步就到，所以这个坑**只在远程仓库上才露头**
（一开始的字段就是"绑定书明明绑了容器，她不去拿"）。
规则：**手上有单就不跟人**（她正在干活）。同一只女仆身上挂多个 tick 驱动时，
凡是会发导航指令的，都要写明让位条件——这类冲突不会报错，只会表现为"她傻了"。

**分得清"仓库里没有"与"背包塞不下"。** 搬运回来是 0 不等于来源里没有：先用
`provider.hasAny(shortfall)` 问一句，再决定说哪句提示。写错提示会让主人顺着错的线索去翻箱子，
而问题其实在她背包里（施工那边同一个讲究，见 `pullFromProvider`）。

**"做完了来告诉我"是临时状态，不进存档。** 只存在内存里的 `REPORTS`（值是"做好了的那几样"）：
跑过去说一声这件事**过期就没意义**，存档里留个"还没汇报"的尾巴，下次进游戏她突然跑来报一句反而怪。
开口的条件三条缺一不可：**手头没别的活**（他问的是"做完了吗"，不是"做到哪了"）、
**主人得在身边**（不在就走过去，走到 4 格内才开口——在聊天栏里飘一句和在眼前被拍一下，
感觉完全不同）、**没等太久**（约五分钟作废，不留一笔永远报不掉的账）。
**零件单不报**：那是她自己给自己排的活，主人不需要知道木棍做完了。

**嵌套合成用"插队"，不用递归。** 缺的零件她要是自己会做，就往**队首插一张"先做这个零件"的单**：

```java
tryNest(shortfall):
    对每一样缺的材料：
        她会做吗（她池子里有配方）？不会 → 看下一样
        会   → insertFirst(零件, ceil(缺的量 / 一次出几个), depth + 1)  ← 插到最前面
```

递归得自己管调用栈、超产与失败回滚；插队只用那一份顺序表，而且**主人看得见"她接下来要做什么"**
（排队行会直接把零件显示出来）。零件做完，原来那张单自然又有料了；零件自己也缺料，它那一步会再插一层。

**挡循环配方靠"来路"，不靠层数上限。** 一开始写的是"最多套三层"，那是**把错的尺子**：
AE2 的处理器那类东西正常就套四五层，按层数卡会把正当配方一起卡死；而真正无解的只有
**绕回自己**（A 要 B、B 要 A）。所以每张单都带着自己的**来路**（从根单到上一层那一串产物），
插零件时只要求"这个零件不出现在它自己的来路上"：

```java
insertFirst(零件, 数量, 来路):
    零件 ∈ 来路        → 拒绝（循环）
    队列里已有这号产物 → 拒绝（等她做完那张就有了）
    队满               → 拒绝
    否则插到队首，来路 = 原单的来路 + 原单自己
```

这样正当的深套一路放行、循环的当场断掉，而且**不用维护任何魔法数字**——这也是
"层数该怎么定"这种争论的根源：那个数字本来就不该存在。

代价是来路要跟着单一起存（`Order.lineage`，NBT 里一串物品）。它有界：链条上不会出现重复产物，
所以长度天然受"不同产物个数"限制，不会无限膨胀。

**"用途"视图查的是她自己的池子，不是配方表。** 右键产物列出**她会做的、用到这个产物的东西**
（`MaidStudyScreen#learnedUsesOf`：遍历池子，看哪样产物的配方摆法里含它），点一条就跳过去。

最初的实现是扫配方表（`ingredient.test(产物)`），后来改成反查池子，两个原因：

1. **扫全表必须自己设上限**，木板这类几百条只能砍到 32 条，主人看着就是"缺失特别严重"；
   而池子本来就有界，反查可以不砍条数、完整给全。
2. 扫全表列出来的**绝大多数她根本不会做**，点过去只撞上"她不会做"，
   对"接下来让她做什么"这件事没有帮助。反查池子则**每一条都点得过去、都能直接下单**。

代价是：她还没学过用它做的东西时，这一页是空的。这是**口径本身的取舍，不是丢数据**——
真要"全世界还有哪些做法"（用来决定接下来教她什么），那是接 JEI 那条路。

### 7.14 录入配方必须先复制：合成格里取到的是活引用

`ItemCraftedEvent` 是在 `ResultSlot#onTake` 的**第一步**发出来的，所以事件里读合成格，
格子还是满的——**读的时机没问题，坑在"存"**：

```java
slots[index] = container.getItem(row * width + col);   // ← 存的是那件物品本身
```

`container.getItem()` 返回的是合成格里**那件 ItemStack 自己**，不是副本。而主人的下一步操作
就是"逐格扣减材料"，于是我们刚记下来的摆法跟着一起被扣空。

**表现**（很容易被误判成"数据没存上"）：

- 刚学会的做法，摆法那一片过后全空、只剩配方 id；
- id 也没反查到的那些，`MaidStudyPool.isEmpty()` 判定为"什么都没看出来"，
  **整条做法等于压根没记上**；
- 而且它**只在"每格正好放 1 个"时才丢干净**（配方书自动填充、或材料正好够），
  每格有剩料时反而侥幸留着——同一个 bug 看起来时好时坏，特别难查。

**规矩：凡是从别人的容器里取出来的 ItemStack，要存进自己的数据结构就先 `copyWithCount(1)`。**

顺带说一句它为什么藏得住：`StudyRecipeCapture#layout` 一开始就复制了
（`chosen.copyWithCount(1)`），所以走 AE2 合成终端与"按产物反查"兜底的配方从来没有这个问题，
只有"工作台 / 背包格子手搓"这一条路会丢。**同一个概念两条写入路径、写法不一致**，
正是这类 bug 的温床。

### 7.15 开界面要判逻辑端：`DistExecutor` 只认物理端

```java
DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MaidStudyScreenOpener.open(maid.getId()));
```

看着"已经限定客户端了"，其实**只挡住了专用服务端**。`DistExecutor` 判断的是**物理端**，
而**单人游戏的物理端就是 `CLIENT`**。像 `InteractMaidEvent`（以及 Forge 的
`PlayerInteractEvent`）这类事件**两端都会走一遍**——客户端线程一遍、集成服务端线程一遍——
于是服务端线程上那一份也会执行，跑去调 `Minecraft.getInstance().setScreen()`，
撞上 `RenderSystem` 的线程断言：

```
[Server thread/ERROR] Exception caught during firing event: Rendersystem called from wrong thread
[Render thread/ERROR] Reported exception thrown!
```

所以**"物理端是客户端" ≠ "可以碰客户端 API"**，还差一个"当前线程是不是客户端线程"。

正确写法是分成两件事：

- **取消事件**这类逻辑，两端都要做（服务端不取消，TLM 那边照样开它自己的界面）；
- **开界面**只能在这一份是**逻辑客户端**时做：

```java
event.setCanceled(true);                  // 两端都要
if (!player.level().isClientSide()) {     // 逻辑端：服务端线程那份到此为止
    return;
}
DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MaidStudyScreenOpener.open(maid.getId()));
```

外层那个 `DistExecutor` 仍然要留着：它保证在**专用服务端**上不会去加载 `client` 包里的类
（否则 `NoClassDefFoundError`）。两层各管一件事，不能互相替代。

---

### 7.16 工业模式：下单即上工，做完把模式还回去

`MaidIndustryTask` 是照学习池的单子干活的工作模式，骨架与「蓝图施工」一致
（`createBrainTasks` 返回空列表 + 服务端 tick 驱动），差在两点：

- **下单自动切换**：`employ` 先记下她**原来**的模式（`RETURN_TO`，只在内存里），再切到工业模式；
  已经在工业模式时什么都不做 —— 否则连着下几单会把"原来是什么模式"覆盖成工业模式本身。
- **待做清单空了才还回去**：`MaidCraftTickHandler.onLevelTick` 里，先让她把"做好了"那句说完
  （`REPORTS` 里还有她的账就再等一拍），**说完了才 `release`** —— 顺序反了的话，一还回去她就不归
  那段代码管了，那句话永远没机会说。
- **只在工作时间干活**：判据用 TLM 自己的作息（`Activity.WORK.equals(maid.getScheduleDetail())`），
  **别自己按 `dayTime` 算时段** —— 那等于把 TLM 的三张作息表在本模组里抄一遍，它一改我们就错。
  查不出来时**放行**（返回 true）：宁可她在休息时段多干一点，也好过"下了单她一动不动还不报错"。

### 7.17 界面里的反馈必须在界面里画

**开着任何 GUI 时，游戏那一层（HUD、聊天栏、动作栏）整个不画。** 所以

```java
player.displayClientMessage(component, true);   // 动作栏：界面开着时看不见
owner.sendSystemMessage(component);              // 聊天栏：同上
```

**在界面里点出来的反馈等于没发**。学习池界面的做法是自己在界面上飘一句
（`MaidStudyScreen.flashAt` / `drawFlash`），并且把这句话占的方块记下来（`flashOverlaps`），
让跟它重叠的悬停提示让开 —— 她说的话优先级最高。

同理，界面里的操作**不再由服务端回话**：换做法、忘掉、下单失败都在客户端就地反馈；
下单上限也在客户端先算一遍（`canFitInQueue`），**规矩必须与服务端 `MaidCraftOrder.order`
一模一样**，否则会出现"界面说能下、服务端不收"这种最难查的错位。

> 服务端那些提示（施工缺料、做单卡住、做好了）仍然走聊天栏 —— 它们发生在女仆干活的时候，
> 那时界面通常是关着的。

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
| `Schematic.rotateState` | 用的是**原始状态**的值，改成转换后的值会双重旋转（§7.3） |
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
