# 围城集群（Siege Cluster）实现说明

本文档依据当前仓库源码整理，记录**已完成**、**存疑**与**未完成/已知缺口**。以代码为准；设计讨论中未落地的条目会标为存疑或缺口，不虚构功能。

相关包根路径：`src/main/java/luowei/fortress_besieged/siege/`

---

## 完成情况

### 状态机 ASSEMBLE / ASSAULT / FREE

| 阶段 | 实现位置 | 行为摘要 |
|------|----------|----------|
| `ASSEMBLE` | `SiegeCluster.tickAssemble()` | **后续循环**（FREE→ASSEMBLE）：扫描锚点 `anchorChunk` 的 **4-邻接** `OCCUPIED`/`BORDER`，随机选一个作为 `targetChunk`，进入 `ASSAULT` |
| `ASSAULT` | `tickAssault()` / `enterAssault()` / `startAssaultOn()` | **不再集群分配任务**；单位 IDLE 自决策 ATTACK/DESTROY；无可破块/区块非占领→`FREE`；`m≤2`→解散。**生成时**已通过 `startAssaultOn(最近占领/边界)` 直接进入 |
| `FREE` | `tickFree()` / `preassignFreeTargets()` | 以 `targetChunk` 为中心 **7×7** 预分配追击；受击改当前追击；目标离开 7×7 回血并重分配；**3×3** 无敌对则 IDLE 回中心，全员到位后 `anchor=target` 再进 `ASSEMBLE` |

枚举：`SiegeClusterState.java`。无补兵逻辑；单位只减不增。

### 数据结构

| 结构 | 字段 / 类型 | 文件 |
|------|-------------|------|
| 单位表 | `Map<UUID, SiegeUnitRecord> units` | `SiegeCluster.java` |
| 攻击追踪列表 | `List<UUID> attackTrackList` | 同上（主要 FREE） |
| 攻击目标→单位 | `Map<UUID, List<UUID>> attackToUnits` | 同上（主要 FREE） |
| 集群内「全局玩家映射」 | `Map<UUID, List<UUID>> globalPlayerMap` | 同上（主要 FREE；**嵌套在集群下**，非跨集群全局） |
| 单位记录 | `mode` / `trackedBlock` / chase ids / `firstTargetRollDone` | `SiegeUnitRecord.java` |
| 单位定义 | `id` / `EntityType` / 盔甲与武器 `Item` | `spawn/SiegeUnitDefinition.java` |
| 配方条目 | `SiegeUnitDefinition` + 权重 | `spawn/SiegeMobSpawnEntry.java` |
| 配方 | id、`totalCount`、带权单位表 | `spawn/SiegeSpawnRecipe.java` |
| 内存管理器 | `CLUSTERS` + `MOB_TO_CLUSTER` | `SiegeClusterManager.java` |

### Goals（AI）

| Goal | 优先级 | 文件 | 作用 |
|------|--------|------|------|
| `SiegeDestroyBlockGoal` | 1 | `goal/SiegeDestroyBlockGoal.java` | 单位自选最近 PBS 块；代价寻路接近并敲；破后回 IDLE |
| `SiegeAttackGoal` | 2 | `goal/SiegeAttackGoal.java` | 原版 `moveTo` 追打；目标无效→IDLE |
| `SiegeIdleReturnGoal` | 3 | `goal/SiegeIdleReturnGoal.java` | IDLE 且不在 `targetChunk`：导航回区块；已在区内不抢游荡 |
| `SiegeIdleDecideGoal` | 4 | `goal/SiegeIdleDecideGoal.java` | ASSAULT+IDLE 降频决策：首次 1/3、之后 1/30 找人，否则挖最近 PBS 块 |
| `WaterAvoidingRandomStrollGoal` | 7 | 原版 | IDLE 游荡（DESTROY/ATTACK 更高优） |

安装：`SiegeAiHelper.install`（卸旧游荡后加回 `WaterAvoidingRandomStroll` + 无限久 Glowing；经 mixin 访问 `goalSelector`）。决策常量见 `SiegeTargetDecision`。

破块接近（`SiegeDestroyBlockGoal` + `SiegeCostPathfinder` + `SiegeBreachExecutor`）：

- 配置：`config/fortress-besieged-pathfinding.json`（启动时 `SiegePathCostConfig.load()`；缺失则写默认）
- 默认代价：空气走 **1**；硬度 `&lt; softHardnessMax(0.6)` **2**；其余可破 **3**；`placeCost=2`；负硬度不可走；可用 `blockOverrides`（如草方块/黑曜石）
- 进盒（切比雪夫立方盒，非欧氏）：怪与 assigned 满足 `max(|dx|,|dz|) ≤ localBoxXz`(默认 16) 且 `|dy| ≤ localBoxY`(默认 16) → 约 33³
- 盒内：代价 A*（WALK / BREAK / PLACE），搜索范围钳在局部盒内；`h = 曼哈顿(dx+dy+dz) × airCost`；`f` 平手取更大 `g`；`maxNodes` 仍为上限；成功则返回完整动作队列
- 盒外：x 前沿贪心（`greedyFrontierX` 默认 2）。维护约 x 个前沿节点及其邻边 f，每次取全局最小 f 扩展并丢掉最旧前沿邻边；父指针树倒查到局部盒入口，再拼盒内 A*。**一次规划产出完整动作队列**（旧 `realtimeMemory` / `realtimeCommitSteps` 已停用）
- 敲击/规划终点为临时验证用挖掘盒（切比雪夫，非寻路局部盒）：脚格 ≠ assigned 且 `max(|dx|,|dz|) ≤ mineReachXz`(默认 2) 且 `|dy| ≤ mineReachY`(默认 2)；不走进 assigned 格拆它。旧 `reach`(1.5) 仅保留给启发
- **执行跟格**：不用原版 `PathNavigation` / `createPath` / `moveTo` 走路。对当前 WALK 格 `MoveControl.setWantedPosition(x+0.5, y, z+0.5)`；到达判定为脚格 `blockPosition` 等于目标格；目标格比脚格高 1 则跳跃
- **WALK 超时**：`walkTimeoutTicks`（默认 40）仍未到达则 STUCK / `WALK_TIMEOUT`，`failCooldownTicks`（默认 100）后对**同一** trackedBlock 再规划；**不**无限 RUNNING；**不**把 `requestDestroyReassign` 当规划失败主路径
- **破坏改队列、不重搜**：任意单位真正 `destroyBlock` 成功后 `SiegeCluster.onRouteBlockRemoved`：各 DESTROY 单位正在执行的队列里若还有 `BREAK pos` 且该格已空，把该动作 **改成 `WALK pos`**。不因此 `tryPlan` / `findGreedyThenAstar` / `findAstar` 整段重搜。assigned 被挖仍走 `onTrackedBlockDestroyed`；路上 PBS 玩家放置仍 `notifyBlockRemoved`
- 完整队列走完（FINISHED）仍够不着：立即再规划（无 `replanIntervalTicks` 惩罚）；失败/超时仍用 `failCooldownTicks`
- 路上可挖非 PBS；仅玩家放置才 `notifyBlockRemoved`；仅 tracked 才 `onTrackedBlockDestroyed`
- 日志：`PLAN_FAIL` / `plan ok mode=greedy|greedy_astar|astar` / `walkTo=` / `WALK_TIMEOUT`；rewrite 为 debug

### 命令

`SiegeCommands.java`（权限等级 ≥2）：

| 命令 | 作用 |
|------|------|
| `/siege spawn [recipe]` | 按比例从 PBS 轮询列表加权抽样目标，对每个跑选点（Bernoulli p=0.15）并刷怪建集群 |
| `/siege spawn self [recipe]` | 以执行者玩家 UUID 为目标跑完整生成链路 |
| `/siege spawn entity <uuid> [recipe]` | 指定组织或独立玩家 UUID |
| `/siege sites [uuid]` | 预览领土中心 / 最近敌对边界候选 / **E≈n×0.15** / 本次 Bernoulli 抽中站点 |
| `/siege poll` | 列出当前可选围城目标 UUID（含 waveSample 提示） |
| `/siege recipes` | 列出刷怪配方（id、总数、带权单位 id） |
| `/siege create [chunkRadius]` | **遗留调试**：收编附近自然 `MONSTER`（非正式生成路径） |
| `/siege status` | 活跃集群数量 |
| `/siege disband` | 解散本维度全部集群并卸 Goal |

注册于 `FortressBesieged.onInitialize()`。

### 独立刷怪生成链路（正式路径）

包：`siege/spawn/`

| 组件 | 文件 | 作用 |
|------|------|------|
| 单位定义 | `SiegeUnitDefinition` / `SiegeUnitDefinitions` | id、EntityType、盔甲/武器；`equip` 刷怪时装配 |
| 配方条目 | `SiegeMobSpawnEntry` | 单位定义 + 权重 |
| 配方 | `SiegeSpawnRecipe` | id、总数 `m`、带权单位；`rollUnits` 按权抽取 |
| 配方表 | `SiegeSpawnRecipes` | 内置 `default` / `light` / `heavy`（已迁到单位定义）；可 `register` |
| 选点常量 | `SiegeSpawnConfig` | BORDER 抽样 4、外扩切比雪夫 ≤2、候选 ≤20、p=0.15、目标抽样比例、自然触发时刻 |
| 目标抽样 | `SiegeTargetSampler` | 从 EntityPollList 抽比例数量（默认均匀权重） |
| 选点 | `SiegeSpawnSiteSelector` | 抽 BORDER → 外扩 HOSTILE_BORDER → ≤20 → **Bernoulli p=0.15**（可为 0） |
| 刷怪建群 | `SiegeClusterSpawner` | 每站点刷怪+装备 → `createCluster` → **最近 OCCUPIED/BORDER 作 target** → `startAssaultOn` |
| 自然调度 | `SiegeSpawnScheduler` | `getDayTime() % 24000 == 11000`，每维度每天防抖一次；聊天广播位置 |

选点细节（**BORDER 抽样 + 外扩**，不再全半径 / 全 owned 四邻接）：

1. `PbsTerritoryAdapter.listSiegeTargets` → PBS `EntityPollList`（独立玩家 + 组织）。
2. 无 OCCUPIED/BORDER 领土的目标跳过。
3. `PlayerBlockStatusLib.queryTerritoryCentroid` → PBS 领土区块中心（交界向上取整）。
4. `queryOrgTerritory(...).border()` 取所属 **BORDER**；随机抽最多 **4** 个（不足则全取；0 则无候选）。飞地边界被抽到是预期。
5. 对每个采样 BORDER，在切比雪夫距离 **≤2** 内查找 `HOSTILE_BORDER`（约 O(4×5×5) 次状态查询），去重后按距领土中心排序，最多保留 **≤20**。**禁止**对大半径调用 `queryChunksInRadius`。
6. 对每个候选独立 `random.nextFloat() < 0.15`；期望站点数 ≈ `candidates × 0.15`，**可以为 0**。
7. 每个选中 anchor：找距离最近的占领/边界区块设为 `targetChunk`，生成后直接 `ASSAULT`。

TODO（未实现）：加一个占领和边界的占比，来区分领土类型，改变集群类型和取边界区块数。

与旧版差异：

| 旧 | 新 |
|----|----|
| 对全部 owned 四邻接收集 HOSTILE_BORDER / 大半径扫描 | 抽样 BORDER → 外扩切比雪夫 ≤2 找 HOSTILE_BORDER |
| 候选中随机固定抽 2～3 站 | Bernoulli p=0.15，数量浮动（含 0） |
| 集群先 `ASSEMBLE`，再从 4-邻接随机选 target | 生成时即设最近占领/边界并 `startAssaultOn` |
| `/siege spawn` 随机 1 个目标 | 按 `TARGET_SAMPLE_RATIO`（默认 20%）抽样多个目标 |
| 无自然触发 | 每日 `dayTime % 24000 == 11000` 自动波次 |
| 配方仅 EntityType+权重 | 单位定义含盔甲/武器，刷怪时装备 |

### 自然触发

- 挂钩：`FortressBesieged` → `ServerTickEvents.END_WORLD_TICK` → `SiegeSpawnScheduler.onWorldTick`。
- 条件：`level.getDayTime() % 24000 == 11000`（常量 `SiegeSpawnConfig.NATURAL_TRIGGER_DAY_TIME`）。
- 防抖：按维度记录已触发的 `dayTime/24000` 日序号，同一游戏日同一维度只跑一次。
- 流程：抽样目标 → 对每个有领土者 `spawnForTarget`（默认配方）→ 聊天广播每个集群的区块坐标与约略方块中心。

### PBS 集成（依赖 `player-block-status` ≥0.2.3）

`pbs/PbsTerritoryAdapter.java`：

| 用途 | 调用的 API |
|------|------------|
| 围城目标列表 | `getEntityPollOrder` / `getEntityPollList` / `indexOfEntityInPollList` |
| 占领区 | `PlayerBlockStatusLib.queryOrgTerritory` |
| **领土区块中心** | **`queryTerritoryCentroid`**（不再本地算术均值） |
| 敌对边界候选 | `queryOrgTerritory.border()` 抽样 → 切比雪夫外扩收集 `HOSTILE_BORDER`（避免全半径 / 全 owned 扫描） |
| 占领归属 | `queryChunkView` → `getOccupyingOrg` |
| 查区块状态 | `queryChunkState` |
| 玩家放置方块 | `ChunkTerritoryAccess.getIfPresent` → `getPlacedBlockGlobalPositions`（公开 `queryChunk` 只返回只读 View，不含坐标） |
| 4-邻接可进攻区块 | 本地偏移 + `OCCUPIED`/`BORDER` |
| 最近占领区块 | 本地对 `allOwned()` 切比雪夫最近（生成时初始 target） |
| 怪物破块后同步 | **`PlayerBlockStatusLib.notifyTrackedBlockRemoved`**（`setBlockState` 路径 mixin 也会自动通知，本调用幂等） |

### 敌对判定（玩家友善白名单）

`targeting/SiegeTargeting.java`：

- `Player`
- `IronGolem` / `SnowGolem` / `Villager`
- 已驯服且主人是玩家的 `Wolf`
- 其他 `OwnableEntity` 且主人是玩家
- `MobCategory.MONSTER` **永不**视为友善目标

### 生命周期挂钩

`FortressBesieged.java`：

- `ServerTickEvents.END_WORLD_TICK` → `SiegeClusterManager.tickLevel` + `SiegeSpawnScheduler.onWorldTick`
- `ServerLivingEntityEvents.AFTER_DAMAGE` → FREE 阶段受击改追击
- `ServerWorldEvents.UNLOAD` → `clearLevel`（内存清理，无持久化）

### 单位 IDLE 决策（ASSAULT）

- 首次：找人概率 **1/3**（`firstTargetRollDone=false` → 决策后置 true）
- 之后：找人概率 **1/30**
- 未中或 AABB 无人 → 选 `targetChunk`（含 4-邻接）内距该怪最近的 PBS 可破块 → DESTROY
- 仍无块 → 保持 IDLE（游荡）
- ATTACK/DESTROY 目标失效或挖完 → IDLE 再决策

### FREE 容量公式（仍用）

- \( x = \lfloor \log_2 m \rfloor \)；预分配目标数 `max(1, m / (2 * x))`
- ASSAULT **不再**做破坏/攻击人数帽与半再平衡分配表

### 编译状态

- 请在有 Gradle 缓存/网络的环境执行 `.\gradlew.bat compileJava` 复验。

---

## 状态机说明（简要）

以下为用户已拍板、且与当前代码基本一致的规则：

1. **无补兵**：集群只循环，不生成新单位加入。
2. **循环**：`ASSEMBLE → ASSAULT → FREE → ASSEMBLE …`；`m ≤ 2`（且非仅卡在 ASSEMBLE 等待）时解散。生成入口可直接 `ASSAULT`。
3. **ASSEMBLE**（循环内）：只看锚点 **4-邻接** 的 `BORDER`/`OCCUPIED`，随机一个作进攻目标。
4. **ASSAULT**：集群只维护 `targetChunk`；单位自行 IDLE→ATTACK/DESTROY；破块后单位回 IDLE；目标区块无（含邻接）可破块或非占领/边界 → FREE。
5. **转 FREE**：
   - （1）`targetChunk`+邻接无可破 PBS 块；
   - （2）目标区块状态变为非 `OCCUPIED`/`BORDER`（每 tick 轮询）。
6. **FREE**：中心 **7×7** 预分配；受击更新 `currentChaseId`；目标离开 7×7 → 满血回复 + 重分配；中心 **3×3** 无敌对 → 全员 IDLE 回 `targetChunk`，到位后再集结。
7. **IDLE** = 游荡；若在区外则 `SiegeIdleReturnGoal` 回 `targetChunk`。
8. **敌对** = 对玩家友善的单位（白名单见上）。
9. **发光**：入集群时无限久 Glowing；解散时清除。

### 与规格的已知偏差（详见「存疑」）

- ASSEMBLE 阶段 `m ≤ 2` **不会**解散，会一直空转等待邻接可进攻区块。
- 「日更脏页主动注入」未接 PBS 事件，仅为轮询。
- OverCapacity 超容量仇恨策略 **v1 未做**（只随机截取容器容量个目标）。
- 离开 7×7 回血为 **满血**，非受伤快照血量。
- 生成时的初始 target 可为非 4-邻接的「最近占领/边界」；后续 FREE→ASSEMBLE 仍用 4-邻接规则。

---

## 存疑

1. **`globalPlayerMap` 作用域命名** — 挂在单个集群下，字段名易误解为跨集群全局。
2. **友善实体白名单是否完备** — 显式白名单 + OwnableEntity。
3. **OverCapacity 仇恨分配（v1 跳过）**。
4. **回血：满血 vs 快照**。
5. **公式边界** — 整数除法、半再平衡仅一次。
6. **ASSAULT 搜敌范围** — radius 0+1，是否扩到 7×7 未定。
7. **ASSEMBLE 与 `m≤2`**。
8. **破块时长与掉落** — 固定约 60 tick。
9. **解散后「攻击模式」** — 仅卸 Goal + 随机 `setTarget`。
10. **目标抽样权重** — 当前均匀；可按领土规模加权（`SiegeTargetSampler.weightOf` 预留）。
11. **每站点是否共用同一配方总数** — 仍各自刷满 `recipe.totalCount()`。
12. **「宽度适合」约束** — 依赖 PBS `HOSTILE_BORDER` 环宽。

---

## 未完成 / 已知缺口

| 项 | 现状 |
|----|------|
| **日更/脏页注入** | ASSAULT 每 tick 轮询状态。 |
| **持久化** | 纯内存；维度卸载清集群。 |
| **配方外部配置** | 仅内存内置；无 JSON/数据包加载。 |
| **测试** | 无自动化测试。 |
| **OverCapacity AI** | 未实现。 |
| **补兵** | 按设计不做。 |
| **原版 Goal 冲突** | 已恢复 `WaterAvoidingRandomStroll`（优先级低于围城 Goal）。 |
| **模板残留** | `ExampleMixin` 等与围城无关。 |

---

## 测试用法

### 前置（正式生成路径）

1. 加载本模组与 **player-block-status** ≥0.2.3。
2. 目标须有 `OCCUPIED`/`BORDER` 领土，且外侧已派生 `HOSTILE_BORDER`。
3. 执行者权限 ≥2。

### 推荐调试命令

```text
/siege poll
/siege recipes
/siege sites
/siege spawn self
/siege spawn self light
/siege spawn entity <uuid> default
/siege spawn
/siege status
/siege disband
```

- `sites`：确认 `center`、候选数、**E≈n×0.15**、本次 Bernoulli 选中站，以及各候选的 `nearestOwned`。
- `spawn self`：对当前玩家领土跑完整链路（装备单位、初始 ASSAULT）。
- `spawn`：按比例抽样多个目标（接近自然波次）。
- 自然波次：游戏日时间到 **11000** 时自动触发并聊天广播。
- `create`：仅遗留「收编附近怪」；正式玩法请用 `spawn`。

### 代码入口

```java
SiegeClusterSpawner.spawnForTarget(level, targetUuid, SiegeSpawnRecipes.getDefault(), random);
SiegeSpawnSiteSelector.select(level, targetUuid, random);
SiegeTargetSampler.sampleWaveTargets(server, level, random);
SiegeSpawnScheduler.runNaturalWave(level); // 或等 dayTime%24000==11000
SiegeClusterManager.createCluster(level, anchorChunk, mobList); // 底层
```

### 预期观察

1. `spawn` 后在敌对边界站点出现带装备的持久化怪物，集群多为 `ASSAULT`（已设 target）。
2. 可破块耗尽或区块不再占领/边界 → `FREE`。
3. 目标跑出 7×7 → 满血 + 换目标逻辑。
4. 存活 ≤2 → 解散。
5. 游戏日 11000 前后聊天出现 `[Siege] Cluster spawned ...`。

---

## 主要源文件索引

```
src/main/java/luowei/fortress_besieged/
  FortressBesieged.java
  mixin/MobGoalSelectorAccessor.java
  siege/
    SiegeCluster.java
    SiegeClusterManager.java
    SiegeClusterState.java
    SiegeUnitMode.java
    SiegeUnitRecord.java
    SiegeCommands.java
    targeting/SiegeTargeting.java
    pbs/PbsTerritoryAdapter.java
    spawn/
      SiegeSpawnConfig.java
      SiegeUnitDefinition.java
      SiegeUnitDefinitions.java
      SiegeMobSpawnEntry.java
      SiegeSpawnRecipe.java
      SiegeSpawnRecipes.java
      SiegeSpawnSiteSelector.java
      SiegeTargetSampler.java
      SiegeClusterSpawner.java
      SiegeSpawnScheduler.java
    goal/SiegeAiHelper.java
    goal/SiegeTargetDecision.java
    goal/SiegeIdleDecideGoal.java
    goal/SiegeDestroyBlockGoal.java
    goal/SiegeApproachPlanner.java
    goal/SiegeBreachExecutor.java
    goal/BreachAction.java
    goal/SiegeAttackGoal.java
    goal/SiegeIdleReturnGoal.java
```
