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
| `FREE` | `tickFree()` / `preassignFreeTargets()` | 以 `targetChunk` 为中心 **7×7** 预分配追击；受击且攻击者仍在 7×7 内才改当前追击；目标离开 7×7 放弃该目标并重分配（默认不回血，见 `healOnTargetLeaveZone`）；**3×3** 无敌对则 IDLE 回中心，全员到位后 `anchor=target` 再进 `ASSEMBLE` |

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
| 维度存档 | `SavedData` / `fortress_besieged_clusters.dat` | `SiegeClusterSavedData.java` |
| 玩家保护期（服务器级） | 主世界 `SavedData` / `fortress_besieged_player_protection.dat`：`daysInGame` + `isTimeToBesiege` | `SiegePlayerProtectionSavedData.java` |
| 阶段解锁（服务器级） | 主世界 `SavedData` / `fortress_besieged_progression.dat`：sticky `hasIron` / `hasDiamond`（轮询目标 id） | `SiegeProgressionSavedData.java` |
| 7×7 会话票 | `addTicketWithRadius` 半径 5（实体 tick 切比雪夫 3） | `SiegeChunkTickets.java` |

### Goals（AI）

| Goal | 优先级 | 文件 | 作用 |
|------|--------|------|------|
| `SiegeDestroyBlockGoal` | 1 | `goal/SiegeDestroyBlockGoal.java` | 单位自选最近 PBS 块；代价寻路接近并敲；破后回 IDLE |
| `SiegeAttackGoal` | 2 | `goal/SiegeAttackGoal.java` | 原版 `moveTo` 追打；目标无效或离开中心 7×7→IDLE |
| `SiegeIdleReturnGoal` | 3 | `goal/SiegeIdleReturnGoal.java` | IDLE 且不在 `targetChunk`：导航回区块；已在区内不抢游荡 |
| `SiegeIdleDecideGoal` | 4 | `goal/SiegeIdleDecideGoal.java` | ASSAULT+IDLE 降频决策：首次 1/3、之后 1/30 找人，否则挖最近 PBS 块 |
| `WaterAvoidingRandomStrollGoal` | 7 | 原版 | IDLE 游荡（DESTROY/ATTACK 更高优） |
| `WildernessChaseGoal` | 2 | `goal/WildernessChaseGoal.java` | **野外集群专用**：追白名单目标；无人则让位给游荡。不破块、不进围城状态机 |

安装：`SiegeAiHelper.install`（卸旧游荡后加回 `WaterAvoidingRandomStroll` + 无限久 Glowing；经 mixin 访问 `goalSelector`）。决策常量见 `SiegeTargetDecision`。野外集群走 `installWilderness`（无 Glowing、不绑 `SiegeCluster`）。**恶魂**不加 `SiegeDestroyBlockGoal` / `SiegeIdleReturnGoal`，不抢 MOVE/LOOK，保留原版 `Ghast.GhastShootFireballGoal`；围城/野外只 `setTarget`。僵尸猪灵在 install 后立刻 `startPersistentAngerTimer` + 长仇恨。

破块接近（`SiegeDestroyBlockGoal` + `SiegeCostPathfinder` + `SiegeBreachExecutor`）：

- 配置：`config/fortress-besieged-pathfinding.json`（启动时 `SiegePathCostConfig.load()`；缺失则写默认）
- 集群行为：`config/fortress-besieged-behavior.json`（启动时 `SiegeClusterBehaviorConfig.load()`；缺失则写默认）。`healOnTargetLeaveZone` 默认 `false`：目标离开中心 7×7 时不回满血
- 默认代价：空气走 **1**；硬度 `&lt; softHardnessMax(0.6)` **2**；其余可破 **3**；`placeCost=2`；负硬度不可走；可用 `blockOverrides`（如草方块/黑曜石）
- 进盒（切比雪夫立方盒，非欧氏）：怪与 assigned 满足 `max(|dx|,|dz|) ≤ localBoxXz`(默认 16) 且 `|dy| ≤ localBoxY`(默认 16) → 约 33³
- 盒内：代价 A*（WALK / BREAK / PLACE），搜索范围钳在局部盒内；`h = 曼哈顿(dx+dy+dz) × airCost`；`f` 平手取更大 `g`；`maxNodes` 仍为上限；成功则返回完整动作队列
- 盒外：x 前沿贪心（`greedyFrontierX` 默认 2）。维护约 x 个前沿节点及其邻边 f，每次取全局最小 f 扩展并丢掉最旧前沿邻边；父指针树倒查到局部盒入口，再拼盒内 A*。**一次规划产出完整动作队列**（旧 `realtimeMemory` / `realtimeCommitSteps` 已停用）
- 敲击/规划终点为临时验证用挖掘盒（切比雪夫，非寻路局部盒）：脚格 ≠ assigned 且 `max(|dx|,|dz|) ≤ mineReachXz`(默认 2) 且 `|dy| ≤ mineReachY`(默认 2)；不走进 assigned 格拆它。旧 `reach`(1.5) 仅保留给启发
- 挖掘：按原版硬度 + 主手工具速度每 tick 累计进度（效率附魔计入；无急迫/水中/腾空修正），破块不掉落。`breakTickBase` / `assignedBreakTicks` 仍可读配置但不驱动挖掘时长
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
| `/siege spawn [recipe]` | 按比例从 PBS 轮询列表加权抽样目标（**先去掉** `isTimeToBesiege=false` 的保护期目标），对每个跑选点（Bernoulli p=0.15）并刷怪建集群 |
| `/siege spawn self [recipe]` | 以执行者玩家 UUID 为目标跑完整生成链路（OP 调试，**绕过**保护期） |
| `/siege spawn entity <uuid> [recipe]` | 指定组织或独立玩家 UUID（OP 调试，**绕过**保护期） |
| `/siege sites [uuid]` | 预览领土中心 / 最近敌对边界候选 / **E≈n×0.15** / 本次 Bernoulli 抽中站点 |
| `/siege poll` | 列出当前可选围城目标 UUID；标 `protected` / `siegeable` 与天数 |
| `/siege recipes` | 列出刷怪配方（id、总数、带权单位 id） |
| `/siege create [chunkRadius]` | **遗留调试**：收编附近自然 `MONSTER`（非正式生成路径） |
| `/siege status` | 活跃集群数量 |
| `/siege disband` | 解散本维度全部集群并卸 Goal |

注册于 `FortressBesieged.onInitialize()`。

### 独立刷怪生成链路（正式路径）

包：`siege/spawn/`

本模组有两条互不替代的刷怪线：

| 线路 | 调度 | 落点 | 生命周期 |
|------|------|------|----------|
| **自然围城调度** | `SiegeSpawnScheduler` | `HOSTILE_BORDER`（地狱档在恶魔区块） | 7×7 票 + `setPersistenceRequired` + `SiegeClusterSavedData` |
| **野外集群** | `WildernessSpawnScheduler` | 仅 PBS `NATURAL` | 无票、不持久、不入存档；卸载即消失 |

#### 自然围城调度

| 组件 | 文件 | 作用 |
|------|------|------|
| 单位定义 | `SiegeUnitDefinition` / `SiegeUnitDefinitions` | id、EntityType、盔甲/武器；`equip` 刷怪时装配 |
| 配方条目 | `SiegeMobSpawnEntry` | 单位定义 + 权重 |
| 配方 | `SiegeSpawnRecipe` | id、总数 `m`、带权单位；`rollUnits` 按权抽取 |
| 配方表 | `SiegeSpawnRecipes` | 内置 `default` / `light` / `heavy`（已迁到单位定义）；可 `register` |
| 选点常量 | `SiegeSpawnConfig` | BORDER 抽样 4、外扩切比雪夫 ≤2、候选 ≤20、p=0.15、命令波次抽样比例、规划间隔 200 tick、每 tick 预算 1、新玩家保护天数默认 5 |
| 目标抽样 | `SiegeTargetSampler` | 仅 `/siege spawn`：从 EntityPollList **已可围城**条目抽比例数量（默认均匀权重）；保护期目标不进抽样池 |
| 玩家保护期 | `SiegePlayerProtection` | 主世界游戏日 `daysInGame` + sticky `isTimeToBesiege`；组织条目看 owner |
| 选点 | `SiegeSpawnSiteSelector` | 抽 BORDER → 外扩 HOSTILE_BORDER → ≤20 → **Bernoulli p=0.15**（可为 0） |
| 刷怪建群 | `SiegeClusterSpawner` | 每站点刷怪+装备 → `createCluster` → **最近 OCCUPIED/BORDER 作 target** → `startAssaultOn`。恶魂刷出 Y+6–10 |
| 模板抽取 | `SiegeTemplateSelector` | 下界只滚 `race=nether`；主世界/末地排除烬军。指定 id 的 `/siege spawn` 不按维度过滤 |
| 自然调度 | `SiegeSpawnScheduler` | 阶段窗口内按 200 tick 节奏掷 chance（未中可再掷）；每 tick 最多 1 个目标 `spawnRolled`；聊天广播位置。下界用主世界 `dayTime` |
| 阶段时钟 | `SiegeDayClock` | 下界读主世界日晷；`inWindow` |

选点细节（**BORDER 抽样 + 外扩**，不再全半径 / 全 owned 四邻接）：

1. `PbsTerritoryAdapter.listSiegeTargets` → PBS `EntityPollList`（独立玩家 + 组织）。
2. 自然刷新与 `/siege spawn` 会跳过 `isTimeToBesiege=false` 的目标（组织看 owner）；`/siege spawn self` / `entity` 不挡。
3. 无 OCCUPIED/BORDER 领土的目标跳过。
4. `PlayerBlockStatusLib.queryTerritoryCentroid` → PBS 领土区块中心（交界向上取整）。
5. `queryOrgTerritory(...).border()` 取所属 **BORDER**；随机抽最多 **4** 个（不足则全取；0 则无候选）。飞地边界被抽到是预期。
6. 对每个采样 BORDER，在切比雪夫距离 **≤2** 内查找 `HOSTILE_BORDER`（约 O(4×5×5) 次状态查询），去重后按距领土中心排序，最多保留 **≤20**。**禁止**对大半径调用 `queryChunksInRadius`。
7. 对每个候选独立 `random.nextFloat() < 0.15`；期望站点数 ≈ `candidates × 0.15`，**可以为 0**。
8. 每个选中 anchor：找距离最近的占领/边界区块设为 `targetChunk`，生成后直接 `ASSAULT`。

TODO（未实现）：加一个占领和边界的占比，来区分领土类型，改变集群类型和取边界区块数。

与旧版差异：

| 旧 | 新 |
|----|----|
| 对全部 owned 四邻接收集 HOSTILE_BORDER / 大半径扫描 | 抽样 BORDER → 外扩切比雪夫 ≤2 找 HOSTILE_BORDER |
| 候选中随机固定抽 2～3 站 | Bernoulli p=0.15，数量浮动（含 0） |
| 集群先 `ASSEMBLE`，再从 4-邻接随机选 target | 生成时即设最近占领/边界并 `startAssaultOn` |
| `/siege spawn` 随机 1 个目标 | 命令波次按 `TARGET_SAMPLE_RATIO`（默认 20%）抽样多个目标 |
| 无自然触发 | 窗口内按节奏掷 chance，未中可再掷 |
| 配方仅 EntityType+权重 | 单位定义含盔甲/武器，刷怪时装备 |

#### 野外集群

替代被关掉的原版 `MONSTER` 自然补员，**不是**自然围城调度的另一条排期。两条线共用 `END_WORLD_TICK`，互不读写对方的票 / `SavedData` / 冷却表。

| 组件 | 文件 | 作用 |
|------|------|------|
| 常量 | `WildernessSpawnConfig` | 扫描 400 tick、失败 6000、距玩家 24–96；缺省 `attemptChance` 0.40 |
| 调度 | `WildernessSpawnScheduler` | 主世界 / 下界 / 末地同一套：扫在线玩家 → 掷是否刷新 → `NATURAL` 抽格 |
| 选点+刷怪 | `WildernessSpawner` | 仅 PBS `NATURAL`（排除占领与各类边界）；模板 `rollWilderness`（读 `fortress-besieged-wilderness.json`；地狱权重 0） |
| 内存表 | `WildernessPackTracker` | 不进 `SiegeClusterSavedData`、不上区块票；卸载/死亡从内存摘掉；同一玩家可有多包 |
| 不落盘 | `WildernessNoSaveMixin` | `shouldBeSaved=false`，区块卸载不把这批怪写回 |

行为摘要：

1. 先有玩家，才在其附近已加载区块上尝试。接管原版怪口之后：先掷本次是否刷新（`attemptChance`，默认 0.40），再抽格（只要 `NATURAL`，不要求够暗），最后读野外配置表抽档/种族/人数。
2. 不碰 `OCCUPIED` / `BORDER` / `HOSTILE_BORDER` / `DEMON`。阶段 / 模板读 `config/fortress-besieged-wilderness.json`，与围城 json 独立。野外 `excludeHell=true` 把地狱档权重视为 0 再归一。下界同样走这套扫描，只抽烬军，路边仍可出；**不用**围城阶段时间窗，也不会在下界安静时段做全选。
3. 保护期：`resolvePollTargetForPlayer`（PBS `queryPlayerOrganization`，否则玩家 UUID；再退回 poll 里 `queryOrganization.owner()` 匹配）→ `SiegePlayerProtection.isTimeToBesiege`（组织看 owner）。
4. AI 只追 `SiegeTargeting` 白名单；没人追就游荡。友伤 / `setTarget` mixin 把野外成员与围城单位同样拦住。本模组僵尸猪灵持久敌对；凋灵骷髅不掉头颅。
5. **不** `setPersistenceRequired()`（围城那条才会设 true），走原版距离自然消失。无 7×7 票。
6. **全维度**：每 20 秒扫一次、按野外 json 的 `attemptChance` 尝试；选点失败 5 分钟退避；成功不锁天、不因已有活包而停刷；每维每扫最多成功 1 次。

### 自然触发

- 挂钩：`FortressBesieged` → `ServerTickEvents.END_WORLD_TICK` → `SiegeSpawnScheduler.onWorldTick`。下界调度读**主世界** `dayTime`（`SiegeDayClock`）；末地仍用自己的日晷。
- **阶段解锁**（sticky，主世界 `fortress_besieged_progression.dat`）：
  - 故土 / `host`：进度 `minecraft:story/smelt_iron`（来硬的）。
  - 困难 / `legion`：进度 `minecraft:story/mine_diamond`。
  - 终末：已有钻石 **且** 本维有恶魔区块。旅途始终。
  - 记在轮询目标 id（组织 UUID 或独立玩家 UUID）上，只升不降。组织：主人或任一成员有进度即解锁。
  - JOIN 扫描在线玩家进度；`snapshot` 时再扫在线成员并把成员已有 sticky OR 到组织。`worldOwned` 只进日志，不挡阶段。旧配置 `hostUnlockWorldOwned` / `legionUnlockWorldOwned` 忽略。
- **新玩家保护期**（默认 1 个主世界游戏日，`SiegeSpawnConfig.NEW_PLAYER_PROTECTION_DAYS`）：
  - 每个**玩家**存 `daysInGame`（主世界 `dayTime/24000`）与 sticky `isTimeToBesiege`。首次 JOIN / 首次需要记录时 days=0、bool=false。退组织再入表、重复 APPENDED **不重置**。
  - 主世界跨午夜（`DimensionDay.reset`，跳过该维度第一次见到以免读档 +1）对已记录玩家 `daysInGame++`；若 bool 仍为 false 且天数 ≥ 阈值则置 true。只跟主世界日走。
  - 组织轮询条目用 `queryOrganization` → `owner()` 的 bool；创始人还在保护期内则组织也不会被自然围城。
  - `refreshTargets` 对 `isTimeToBesiege==false` **不跟踪**（不掷 chance）。`/siege spawn` 抽样前同样过滤；`/siege spawn self` / `entity` 可强制刷。
- **低频规划**（每 200 tick，或刚跨过午夜）：刷新 PBS poll；对已可围城的目标缓存当前阶段窗口。主世界用 `spawn`；下界用 `netherSpawn`（缺省整天）。不每 tick 对全 poll 打 PBS `snapshot()`。
- **窗口**写在 `progression.stages[].spawn`（含 start、不含 end，end 不跨午夜）。缺省按阶段 id：旅途 `7000–18000` chance 0.25、故土 `10000–22000` 0.20、困难 `11000–19000` 0.15、终末 `16000–23000` 0.10；未知 id 退回旅途窗口。主世界窗口外不掷；窗口内可重复掷。
- **下界权重**：落在主世界当前阶段窗口内用该阶段表；落在安静时段（窗口补集）则全选（已解锁阶段权重相加再归一）。围城（含 `/siege spawn` 在下界）路边权重为 0。野外不走这套窗口：全维度 40% 稀疏扫描，始终用当前阶段表，路边保留。
- **不预抽到期时刻**：不哈希 `dueTime`。窗口开着就按 200 tick 节奏入队掷 `chance`。
- **每 tick**：窗口内且已过该目标下次尝试时刻则入队；每 tick 最多对 1 个目标 `spawnRolled`。睡觉跳夜 / `/time set` 只要落在窗口内就会开始掷。
- **开火**：再 `snapshot`/`resolveStage` 一次。不在窗口则跳过（不锁当天）。在窗口内先记下下次尝试时刻再掷该阶段（下界为 `netherSpawn`）`chance`（`nextFloat() < chance`）；未中、无领土、`spawnRolled` 失败都可以在间隔后再掷。站点仍是 Bernoulli `p=0.15`。
- **主世界不是**把在场阶段权重归一成 1 再分配额（那只发生在下界安静时段全选）。也**不用** `TARGET_SAMPLE_RATIO` / `sampleWaveTargets`（那只给 `/siege spawn`）。
- 成功刷出时聊天广播区块坐标与约略方块中心；同时播袭击号角（整波一次）。集群全灭或 `m≤2` 解散时播钟声 + 挑战完成音（`/siege disband` 不播）。

### PBS 集成（依赖 `player-block-status` ≥0.2.7）

`pbs/PbsTerritoryAdapter.java`：

| 用途 | 调用的 API |
|------|------------|
| 围城目标列表 | `getEntityPollOrder` / `getEntityPollList` / `indexOfEntityInPollList` |
| 保护期玩家解析 | `queryOrganization` → `owner()`（组织）；否则目标 UUID 自身 |
| 阶段解锁成员 | `queryOrganization` → `owner()` + `members()`，并并上在线 `queryPlayerOrganization` 匹配 |
| 野外在场玩家 → 轮询目标 | `queryPlayerOrganization`；空则玩家 UUID，或 poll 里 `queryOrganization.owner()` 匹配 |
| 占领区 | `PlayerBlockStatusLib.queryOrgTerritory` |
| **领土区块中心** | **`queryTerritoryCentroid`**（不再本地算术均值） |
| **持有区块计数** | **`queryEntityTerritoryCounts`**（`owned` 仅日志/快照，不挡阶段） |
| **恶魔区块** | **`queryDemonChunks`**（终末门槛 / 地狱档选点） |
| 敌对边界候选 | `queryOrgTerritory.border()` 抽样 → 切比雪夫外扩收集 `HOSTILE_BORDER`（避免全半径 / 全 owned 扫描） |
| 占领归属 | `queryChunkView` → `getOccupyingOrg` |
| 查区块状态 | `queryChunkState`（0.2.7 含 `DEMON`） |
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

- `ServerPlayConnectionEvents.JOIN` → `SiegePlayerProtection.ensurePlayer`（建档，不重置）+ `SiegeProgressionFlags.onPlayerJoin`（扫描铁/钻石进度）
- `ServerTickEvents.END_WORLD_TICK` → `SiegeClusterManager.tickLevel` + `SiegeSpawnScheduler.onWorldTick` + `WildernessSpawnScheduler.onWorldTick`
- `ServerLivingEntityEvents.AFTER_DAMAGE` → FREE 阶段受击改追击
- `ServerLivingEntityEvents.AFTER_DEATH` → 确认死亡后从单位表移除（未加载 ≠ 死亡）；野外成员同步从 `WildernessPackTracker` 摘掉
- `ServerEntityEvents.ENTITY_LOAD` → 读档后把已进世界的怪重新 `SiegeAiHelper.install`
- `ServerEntityEvents.ENTITY_UNLOAD` → 野外成员从内存表摘掉（它们不落盘，卸载即消失）
- `ServerWorldEvents.LOAD` → 读 `SavedData`、恢复集群、按 `targetChunk` 重新上 7×7 票
- `ServerWorldEvents.UNLOAD` / `SERVER_STOPPING` → 先把当前快照写入存档，再**摘会话票**并清内存；**不** `forceDisband`（避免把存档抹空）；同时清空野外内存表
- `BEFORE_SAVE` → `syncSave`

原版刷怪抑制（不改 `doMobSpawning`）：mixin 取消 `NaturalSpawner` 的 `MONSTER` 类别（含要塞烈焰人等结构 `spawn_overrides`）、`PhantomSpawner`、`VillageSiege`，以及 `EntitySpawnReason.REINFORCEMENT`。围城 `EVENT`、监守者 `TRIGGERED`、袭击、巡逻、刷怪笼、转化等仍可用。

### 7×7 临时区块票（不是 `/forceload`）

集群活着时，以 `targetChunk`（尚无 target 则用 `anchorChunk`）为中心保持 **7×7 实体 tick**，玩家不在附近怪也继续拆/搭/走。

| 项 | 实现 |
|----|------|
| API | 1.21.5 `ServerChunkCache.addTicketWithRadius` / `removeTicketWithRadius` |
| 票类型 | 自建 `TicketType`：`timeout=0`、**`persist=false`**、`LOADING_AND_SIMULATION`。**禁止** `TicketType.FORCED` / `setChunkForced` / `/forceload`（那些会写入 `chunks.dat`，漏卸会永久加载） |
| 半径换算 | `addTicketWithRadius` 的票等级 = `33 - radius`。实体 tick 要求等级 `<= 31`，且按切比雪夫 +1 扩散，故实体 tick 半径 = `radius - 2`。盖住切比雪夫 3（7×7）须 **`radius = 5`**。外围会多圈方块 tick / 弱加载，可接受 |
| 重叠 | 按中心区块引用计数；多个集群钉同一中心时，卸一个不会拆另一个的票 |
| 挪票 | `targetChunk` / `anchorChunk` 变化时卸旧中心、钉新中心（ASSEMBLE 换邻接、FREE 结束改锚、`startAssaultOn`） |
| 摘票 | 解散、`forceDisband`、`/siege disband`、维度卸载、停服。崩溃路径票不落盘 |

### 集群持久化

随维度 `SavedData` 走世界存档，路径：

- 主世界：`saves/<world>/data/fortress_besieged_clusters.dat`
- 下界/末地：对应维度 `data/fortress_besieged_clusters.dat`

玩家保护期是**服务器级**一份，存在主世界 `saves/<world>/data/fortress_besieged_player_protection.dat`（不要每维度各存，否则下界会再加一天或丢数据）。阶段解锁同样服务器级一份：`fortress_besieged_progression.dat`。无老档迁移。

每个集群写入：UUID、维度、`state`、`anchorChunk` / `targetChunk`、模板 id/name/race/tier、单位（怪 UUID、mode、trackedBlock、chase ids、`firstTargetRollDone`）、FREE 用的 `attackTrackList` / `attackToUnits` / `globalPlayerMap`。**不**保存破块动作队列；加载后 DESTROY 单位按 trackedBlock 再规划。

加载：

1. 维度就绪后读档，重建 `SiegeCluster` 并按 `targetChunk` 上 7×7 票。
2. 单位 UUID 预先记入 `MOB_TO_CLUSTER`。实体已在世界则立刻 `install`；否则等 `ENTITY_LOAD` 或 tick 里 `getEntity` 再绑 Goal。
3. `pruneDeadUnits`：**不再**把 `getEntity==null` 当死亡。读档宽限（400 tick）内跳过；宽限结束仍从未出现则视为离线已死；曾经出现过、现在 null 视为走出加载区，保留 UUID 等重绑。确认死亡走 `!isAlive()` / `AFTER_DEATH`。
4. 怪刷出时已有 `setPersistenceRequired()`。
5. 解散时从快照删除该集群；世界保存时写出。

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
4. **ASSAULT**：集群只维护 `targetChunk`；单位自行 IDLE→ATTACK/DESTROY；搜敌与追击都限制在中心 **7×7**；破块后单位回 IDLE；目标区块无（含邻接）可破块或非占领/边界 → FREE。
5. **转 FREE**：
   - （1）`targetChunk`+邻接无可破 PBS 块；
   - （2）目标区块状态变为非 `OCCUPIED`/`BORDER`（每 tick 轮询）。
6. **FREE**：中心 **7×7** 预分配；仅当攻击者仍在 7×7 内才因受击改追击；目标离开 7×7 → 放弃该目标并重分配（默认不回血；`healOnTargetLeaveZone=true` 才回满血）；中心 **3×3** 无敌对 → 全员 IDLE 回 `targetChunk`，到位后再集结。
7. **IDLE** = 游荡；若在区外则 `SiegeIdleReturnGoal` 回 `targetChunk`。
8. **敌对** = 对玩家友善的单位（白名单见上）。区外玩家即使在打怪，围城单位也不 `setTarget` / 还手。
9. **发光**：入集群时无限久 Glowing；解散时清除。

### 与规格的已知偏差（详见「存疑」）

- ASSEMBLE 阶段 `m ≤ 2` **不会**解散，会一直空转等待邻接可进攻区块。
- 「日更脏页主动注入」未接 PBS 事件，仅为轮询。
- OverCapacity 超容量仇恨策略 **v1 未做**（只随机截取容器容量个目标）。
- 生成时的初始 target 可为非 4-邻接的「最近占领/边界」；后续 FREE→ASSEMBLE 仍用 4-邻接规则。

---

## 存疑

1. **`globalPlayerMap` 作用域命名** — 挂在单个集群下，字段名易误解为跨集群全局。
2. **友善实体白名单是否完备** — 显式白名单 + OwnableEntity。
3. **OverCapacity 仇恨分配（v1 跳过）**。
4. **公式边界** — 整数除法、半再平衡仅一次。
5. **ASSEMBLE 与 `m≤2`**。
6. **破块时长与掉落** — 已按原版硬度 + 主手工具速度累计挖掘进度，破坏不掉落。
7. **解散后「攻击模式」** — 仅卸 Goal + 随机 `setTarget`。
8. **目标抽样权重** — 当前均匀；可按领土规模加权（`SiegeTargetSampler.weightOf` 预留）。
9. **每站点是否共用同一配方总数** — 仍各自刷满 `recipe.totalCount()`。
10. **「宽度适合」约束** — 依赖 PBS `HOSTILE_BORDER` 环宽。

---

## 未完成 / 已知缺口

| 项 | 现状 |
|----|------|
| **日更/脏页注入** | ASSAULT 每 tick 轮询状态。 |
| **持久化** | 维度 `SavedData`（`fortress_besieged_clusters.dat`）；保护期主世界 `fortress_besieged_player_protection.dat`；阶段解锁 `fortress_besieged_progression.dat`。关服/重进恢复集群并重绑 Goal。 |
| **配方外部配置** | 围城 `config/fortress-besieged-clusters.json`，野外 `config/fortress-besieged-wilderness.json`（各自含阶段表；缺则写默认）。旧 `default`/`light`/`heavy` 仍走围城表。编制见 [siege-cluster-templates.md](siege-cluster-templates.md)。 |
| **围城单位友伤/怪物仇恨** | mixin 拦 `setTarget` / `hurtServer`：围城单位不打其他怪物，也不被怪物攻击拉仇恨。区外 7×7 的玩家友善目标同样拦 `setTarget`。 |
| **阶段解锁** | 铁/钻石进度 sticky + 本维恶魔区块；`worldOwned` 不挡阶段。 |
| **测试** | 无自动化测试。 |
| **OverCapacity AI** | 未实现。 |
| **补兵** | 按设计不做。 |
| **原版 Goal 冲突** | 已恢复 `WaterAvoidingRandomStroll`（优先级低于围城 Goal）。 |
| **模板残留** | `ExampleMixin` 等与围城无关。 |

---

## 测试用法

### 前置（正式生成路径）

1. 加载本模组与 **player-block-status** ≥0.2.7。
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
- `spawn`：按比例抽样多个**已可围城**目标（命令即时波次，不是自然调度）；仍在 1 日保护期内的不进抽样。
- `spawn self` / `spawn entity`：OP 调试，可对保护中的目标强制刷。
- 自然刷新：`isTimeToBesiege=true` 的目标在当前阶段窗口内按节奏掷 chance（可 `/time set` 到窗口内验证）；聊天广播成功刷出的集群。保护中的目标不掷。
- 生成/状态机监控：logger 名 `fortress-besieged`。INFO 记抽中玩家、实际刷出集群、状态转换；DEBUG 记选点失败与各阶段耗时（把该 logger 设为 DEBUG，例如 `log4j2.xml`）。
- `create`：仅遗留「收编附近怪」；正式玩法请用 `spawn`。

### 代码入口

```java
SiegeClusterSpawner.spawnForTarget(level, targetUuid, SiegeSpawnRecipes.getDefault(), random);
SiegeSpawnSiteSelector.select(level, targetUuid, random);
SiegeTargetSampler.sampleWaveTargets(server, level, random); // 仅命令波次
SiegeSpawnScheduler.onWorldTick(level); // 自然：窗口内规划 + 机会队列 + 预算 1
SiegeClusterManager.createCluster(level, anchorChunk, mobList); // 底层
```

### 预期观察

1. `spawn` 后在敌对边界站点出现带装备的持久化怪物，集群多为 `ASSAULT`（已设 target）；以 target 为中心 7×7 无玩家也会继续实体 tick。
2. 可破块耗尽或区块不再占领/边界 → `FREE`。
3. 目标跑出中心 7×7 → 停止追踪/还手并换目标（默认不回血）。
4. 存活 ≤2 → 解散。
5. 自然刷新在阶段窗口内掷中 chance 且选到站后，聊天出现 `[Siege] Cluster spawned ...`（每 tick 最多处理一个目标；未中下次节奏再掷）。
6. 关服 / 退出单人 / 换维度再进：集群从 `fortress_besieged_clusters.dat` 恢复，怪重新安装围城 Goal；`/forceload query` 不应留下永久票。

---

## 主要源文件索引

```
src/main/java/luowei/fortress_besieged/
  FortressBesieged.java
  mixin/MobGoalSelectorAccessor.java
  mixin/WildernessNoSaveMixin.java
  siege/
    SiegeCluster.java
    SiegeClusterManager.java
    SiegeClusterSavedData.java
    SiegePlayerProtection.java
    SiegePlayerProtectionSavedData.java
    SiegeProgressionFlags.java
    SiegeProgressionSavedData.java
    SiegeChunkTickets.java
    SiegeClusterState.java
    SiegeUnitMode.java
    SiegeUnitRecord.java
    SiegeCommands.java
    SiegeSounds.java
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
      SiegeDayClock.java
      SiegeProgression.java
      SiegeClusterConfig.java
      WildernessSpawnConfig.java
      WildernessSpawnScheduler.java
      WildernessSpawner.java
      WildernessPackTracker.java
    goal/SiegeAiHelper.java
    goal/WildernessChaseGoal.java
    goal/SiegeTargetDecision.java
    goal/SiegeIdleDecideGoal.java
    goal/SiegeDestroyBlockGoal.java
    goal/SiegeApproachPlanner.java
    goal/SiegeBreachExecutor.java
    goal/BreachAction.java
    goal/SiegeAttackGoal.java
    goal/SiegeIdleReturnGoal.java
```
