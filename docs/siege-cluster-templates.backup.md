# 围城集群模板（设计稿）

本文只描述**集群编制**与**配置文件样式**。尚未接入加载器、刷怪与运行时字段；当前游戏仍走内存配方 `default` / `light` / `heavy`。爬行类本版不做。

目标文件（落地后）：`config/fortress-besieged-clusters.json`（缺失则写默认，风格对齐 `fortress-besieged-pathfinding.json`）。

---

## 通用规则

### 装备对谁生效

同一档集群的 `slots` 对**所有能装备该槽的单位**生效：僵尸、尸壳、溺尸、僵尸村民、骷髅、流浪者、凋灵骷髅、僵尸猪灵等。抽到铁胸甲的骷髅就穿铁胸甲；抽到木铲的尸壳就拿木铲。

不能穿甲/持械的单位（女巫、唤魔者、劫掠兽等）跳过对应槽，只吃 `stats`。

### 头盔优先

任意档位：`头盔有货权重 ≥ 胸 ≥ 腿 ≥ 脚`。用「空槽权重」表示时，头盔的 `empty` 必须最小。低档仍可以大量光头，只是「有甲时先有头盔」。

### 友伤与仇恨（集群行为，配置外）

围城刷出的单位：

- 不对其他怪物造成友伤（含原版自然怪、其他围城单位）
- 不被其他怪物的攻击吸引仇恨（不因被苦力怕炸、蜘蛛咬而反击或转火）

围城 AI 仍只打玩家友善目标（玩家、铁傀、雪傀、村民、玩家宠物）。此条需改索敌 / team / 无敌队伤害，不写进 JSON。

### 伤害怎么读

近战 ≈ `stats.attack`（空手）+ 武器加成 + 附魔。武器算入伤害。

| 武器 | Java 加成（不含玩家那 1 点） |
|------|------------------------------|
| 木/金剑 | +3 |
| 石剑 | +4 |
| 铁剑 | +5 |
| 钻剑 | +6 |
| 合金剑 | +7 |
| 铁斧 | +8 |
| 木铲 / 木镐 | +1 |
| 木斧 | +3 |
| 铁铲 | +3.5 |
| 铁镐 | +3 |

移速写原版属性，不要写整数 2。僵尸默认 `0.23`，骷髅 `0.25`，卫道士 `0.35`。生命单位是 HP（20 = 10 心）。

### 掷骰顺序

人数 → 每只兵种 → health / speed / attack → 六槽物品（不能装备则跳过）→ 耐久档 → 是否附魔。

人数下限 ≥ 3（现有 `m≤2` 会解散）。

---

## 生成阶段与解锁

自然波次 / `/siege spawn` 先定**世界阶段**，再按该阶段权重抽 **档位** `tier`，最后在已解锁的模板里抽具体集群。

### 持有区块口径

`worldOwned` = 当前维度 **OCCUPIED + BORDER** 数量（不含 `HOSTILE_BORDER` / `NATURAL`）。PBS 最新版已提供该计数，落地时直接读，禁止全图扫描。

「一群」与「军团」的门槛默认 20 / 30，**必须可配**（`hostUnlockWorldOwned` / `legionUnlockWorldOwned`）。改门槛时阶段切分跟着改，不要在阶段表里再写死 20、30。

### 档位 `tier` 与解锁

| `tier` | 含义 | 默认解锁 |
|--------|------|----------|
| `roadkill` | 路边野尸 | 始终（旅途起） |
| `small` | 小股 | 始终（旅途起） |
| `host` | 一群 | `worldOwned >= hostUnlockWorldOwned`（默认 20） |
| `legion` | 军团 | `worldOwned >= legionUnlockWorldOwned`（默认 30） |
| `hell` | 地狱亡魂 | 当前维度存在 **恶魔区块**（见下） |

未解锁的档位权重视为 0，其余权重重归一。

掠夺者无 `roadkill`：若本波种族抽到 `illager` 且档位是 `roadkill`，并入 `small`（或重抽档位，实现时二选一，默认并入小股）。

### 四个阶段

取**满足条件的最高阶段**（终末 > 困难 > 故土 > 旅途）。阶段的 `unlock` 只引用 `always` / `host` / `legion`，门槛数字只出现在 `hostUnlockWorldOwned` / `legionUnlockWorldOwned`。

| 阶段 | id | 进入条件 | 路边 | 小股 | 一群 | 军团 | 地狱 |
|------|----|----------|------|------|------|------|------|
| 旅途 | `journey` | 默认（未达一群门槛） | 0.7 | 0.3 | — | — | — |
| 故土 | `homeland` | 已解锁一群，未解锁军团 | 0.2 | 0.5 | 0.3 | — | — |
| 困难 | `hard` | 已解锁军团，且无恶魔区块 | 0.05 | 0.15 | 0.3 | 0.5 | — |
| 终末 | `end` | 已解锁军团，且维度内有恶魔区块 | 0.07 | 0.022 | 0.2 | 0.7 | 0.008 |

各阶段权重之和为 1。终末若暂时没有恶魔区块，不会进入终末，停在困难。终末里地狱权重极低，且生成点走恶魔区块，不走普通 HOSTILE_BORDER 抽样。

### 恶魔区块（附属模组）

地狱档**暂定**只在恶魔区块生成。本模组不负责定义、绘制或持久化该状态，只读查询。

附属模组（PBS 扩展或独立附属）需要能：

- **增**：把某区块标为恶魔区块（可带过期、强度、来源维度等自有字段）
- **删**：清除标记（仪式、占领、过期、命令）
- **查**：`isDemonChunk(level, chunk)`、`listDemonChunks(level)`（当前维度列表，供选点）

本模组约定（占位，API 名以附属模组为准）：

- 非恶魔区块：永不刷 `tier=hell`
- 有恶魔区块：阶段可进终末；抽中地狱后，锚点从恶魔区块列表抽（可再外扩其 HOSTILE_BORDER，细节与普通选点分开）
- 附属模组未加载或列表为空：地狱权重为 0，阶段不会因「终末」而误判

---

## 配置文件样式

根对象含 `progression` + `clusters`。集群内权重均为正整数，同字段内相加后按权抽取。`empty` 表示该槽空手/空甲。`stats` 里数字键是属性值、值为权重。阶段权重用 0–1 概率，和为 1。

```json
{
  "progression": {
    "hostUnlockWorldOwned": 20,
    "legionUnlockWorldOwned": 30,
    "stages": [
      {
        "id": "journey",
        "name": "旅途",
        "unlock": "always",
        "weights": { "roadkill": 0.7, "small": 0.3 }
      },
      {
        "id": "homeland",
        "name": "故土",
        "unlock": "host",
        "weights": { "small": 0.5, "roadkill": 0.2, "host": 0.3 }
      },
      {
        "id": "hard",
        "name": "困难",
        "unlock": "legion",
        "weights": { "legion": 0.5, "host": 0.3, "small": 0.15, "roadkill": 0.05 }
      },
      {
        "id": "end",
        "name": "终末",
        "unlock": "legion",
        "requireDemonChunk": true,
        "weights": {
          "legion": 0.7,
          "host": 0.2,
          "small": 0.022,
          "roadkill": 0.07,
          "hell": 0.008
        }
      }
    ]
  },
  "clusters": [
    {
      "id": "undead_roadkill",
      "name": "路边野尸",
      "race": "undead",
      "tier": "roadkill",
      "size": "mixed",
      "count": { "min": 5, "max": 100, "buckets": [
        { "min": 5, "max": 15, "weight": 40 },
        { "min": 16, "max": 40, "weight": 35 },
        { "min": 41, "max": 70, "weight": 18 },
        { "min": 71, "max": 100, "weight": 7 }
      ]},
      "species": [
        { "entity": "minecraft:zombie", "weight": 70 },
        { "entity": "minecraft:husk", "weight": 10 },
        { "entity": "minecraft:skeleton", "weight": 18 },
        { "entity": "minecraft:zombie", "weight": 2, "baby": true }
      ],
      "stats": {
        "health": { "6": 40, "8": 45, "10": 15 },
        "speed": { "0.20": 40, "0.23": 60 },
        "attack": { "1.0": 70, "2.0": 30 }
      },
      "slots": {
        "helmet":     { "empty": 50, "leather_helmet": 38, "golden_helmet": 8, "chainmail_helmet": 4 },
        "chestplate": { "empty": 70, "leather_chestplate": 22, "golden_chestplate": 6, "chainmail_chestplate": 2 },
        "leggings":   { "empty": 76, "leather_leggings": 18, "golden_leggings": 5, "chainmail_leggings": 1 },
        "boots":      { "empty": 82, "leather_boots": 14, "golden_boots": 4 },
        "mainHand": {
          "empty": 55,
          "wooden_shovel": 15,
          "wooden_axe": 12,
          "wooden_pickaxe": 12,
          "iron_shovel": 4,
          "iron_pickaxe": 2
        },
        "offHand": { "empty": 100 }
      },
      "durability": { "ruined": 50, "worn": 35, "used": 12, "fresh": 3 },
      "enchant": { "armorChance": 0.0, "weaponChance": 0.0, "level": [0, 0] }
    }
  ]
}
```

字段约定：

| 字段 | 含义 |
|------|------|
| `id` | 稳定键，命令与自然波次引用 |
| `name` | 显示名 |
| `race` | `undead` / `illager` |
| `tier` | 进度档：`roadkill` / `small` / `host` / `legion` / `hell`；决定解锁与阶段权重 |
| `size` | 编制规模 `small` / `medium` / `large` / `mixed`（与 `tier` 无关） |
| `count.min` / `max` | 人数闭区间 |
| `count.buckets` | 可选；有则先抽桶再在桶内取人数 |
| `species[].baby` | 可选；幼体 |
| `slots.<slot>.empty` | 该槽为空的权重 |
| `durability` | 剩余耐久：破烂 5–25% / 磨损 25–55% / 堪用 55–85% / 接近全新 85–100% |
| `enchant.level` | 附魔台等价等级 `[min, max]` |
| `progression.hostUnlockWorldOwned` | 开放 `host` 的世界持有区块阈值，默认 20 |
| `progression.legionUnlockWorldOwned` | 开放 `legion` 的世界持有区块阈值，默认 30 |
| `stages[].unlock` | `always` / `host` / `legion`；与上面两个阈值挂钩 |
| `stages[].requireDemonChunk` | `true` 时还要当前维度存在恶魔区块（终末） |
| `stages[].weights` | 按 `tier` 的概率，和为 1；未解锁档位剔除后重归一 |

`mainHand` 未列出的物品权重视为 0，不会出现。

---

## 共享表

### 耐久（全档共用档位，权重按集群变）

| 档 | 剩余 |
|----|------|
| `ruined` | 5%–25% |
| `worn` | 25%–55% |
| `used` | 55%–85% |
| `fresh` | 85%–100% |

### 附魔条目权重（先过 chance，再抽 1–3 条）

盔甲：保护 I–II / III / IV，弹射物保护，火焰保护，荆棘。  
剑：锋利 I–II / III / IV–V，击退，火焰附加。  
弓：力量对齐锋利，火矢对齐火焰附加。  
绑定 / 消失诅咒全档 ≤2%。亡灵杀手对玩家无效，高档也不抽。

---

## 亡灵 `undead`

### 路边野尸 `undead_roadkill`

- `tier`：`roadkill`，**始终可生成**
- 规模：`mixed`，人数 **5–100**（偏小桶权重大）
- 血薄、装差。武器**只允许**：木铲、木斧、木镐、铁铲、铁镐。无剑、无石工具、无弓、无更高级兵器
- 盔甲仅皮 / 金 / 少量锁链；无铁甲、无钻甲
- 全员同一套 `slots`（含骷髅、尸壳）

| 项 | 值 |
|----|----|
| 兵种 | 僵尸 70，尸壳 10，骷髅 18，幼体僵尸 2 |
| 血量 | 6×40，8×45，10×15 |
| 移速 | 0.20×40，0.23×60 |
| 空手攻击 | 1.0×70，2.0×30 |
| 头盔 empty | **50**（有头盔 50；高于其他甲槽） |
| 胸 / 腿 / 脚 empty | 70 / 76 / 82 |
| 主手 | 空 55，木铲 15，木斧 12，木镐 12，铁铲 4，铁镐 2 |
| 副手 | 空 100 |
| 耐久 | ruined 50，worn 35，used 12，fresh 3 |
| 附魔 | 0 |

### 小股亡灵 `undead_warband_small`

- `tier`：`small`，**始终可生成**
- 规模：`medium`，**15–20**
- 皮甲能盖住但不齐；木剑～铁剑；少量铁甲；血 12–16
- 骷髅可抽到弓或剑（与僵尸共用槽表；弓只对能用弓的兵种有意义，僵尸抽到弓则改持木剑或按 `mainHand` 再掷近战）

落地时 `mainHand` 建议按兵种过滤：近战种忽略 `bow`，骷髅/流浪者忽略剑以外的近战工具。过滤后权重重归一。

| 项 | 值 |
|----|----|
| 兵种 | 僵尸 62，尸壳 10，骷髅 18，流浪者 5，僵尸村民 5 |
| 血量 | 12×35，14×40，16×25 |
| 移速 | 0.23（骷髅种 0.25） |
| 空手攻击 | 2.0×60，3.0×40 |
| 头盔 empty | **25** |
| 胸 / 腿 / 脚 empty | 35 / 42 / 48 |
| 甲材质（非空时） | 皮 70，金 12，锁链 10，铁 8 |
| 主手（近战池） | 空 15，木剑 40，石剑 25，铁剑 18，金剑 2 |
| 主手（远程池） | 空 15，弓 85 |
| 耐久 | 30 / 40 / 25 / 5 |
| 附魔 | 甲 5%，器 8%，等级 5–12，I–II |

### 一群亡灵 `undead_host`

- `tier`：`host`，`worldOwned >= hostUnlockWorldOwned`（默认 **20**）后开放
- 规模：`medium`，**28–32**（中枢 30）
- 铁甲为主、极少皮甲；铁剑为主、少数木/石剑；血 20–32
- 可有 2% 混搭彩蛋（钻头+皮胸+铁腿+金靴+木剑），不进主权重表

| 项 | 值 |
|----|----|
| 兵种 | 僵尸 55，尸壳 8，骷髅 22，流浪者 8，溺尸 5，女巫 2 |
| 血量 | 20×25，24×35，28×25，32×15 |
| 移速 | 0.23×50，0.25×50 |
| 空手攻击 | 3.0 |
| 头盔 empty | **8** |
| 胸 / 腿 / 脚 empty | 12 / 16 / 20 |
| 甲材质（非空时） | 皮 8，锁链 12，铁 77，钻 3 |
| 主手（近战） | 空 5，木剑 8，石剑 12，铁剑 72，钻剑 3 |
| 主手（远程） | 空 5，弓 95 |
| 副手盾 | 8% |
| 耐久 | 10 / 35 / 40 / 15 |
| 附魔 | 甲 12%，器 18%，等级 8–18，I–III |

### 亡灵军团 `undead_legion`

- `tier`：`legion`，`worldOwned >= legionUnlockWorldOwned`（默认 **30**）后开放
- 规模：`large`，**50–70**
- 钻甲标配；钻剑；少部分附魔；缺武器时补铁剑；**头盔 empty = 0**；血 35–55

| 项 | 值 |
|----|----|
| 兵种 | 僵尸 55，骷髅 20，流浪者 8，凋灵骷髅 6，女巫 6，尸壳 5 |
| 血量 | 35×20，42×35，48×30，55×15 |
| 移速 | 0.24×50，0.26×50 |
| 空手攻击 | 3.5×50，4.5×50 |
| 头盔 empty | **0** |
| 胸 / 腿 / 脚 empty | 2 / 2 / 2 |
| 甲材质 | 铁 18，钻 80，合金 2 |
| 主手（近战） | 铁剑 22，钻剑 70，合金剑 3，空 5（空则 100% 改铁剑） |
| 主手（远程） | 弓 100 |
| 副手盾 | 20% |
| 耐久 | 5 / 20 / 45 / 30 |
| 附魔 | 甲 28%，器 35%，等级 15–25，可 III–IV |

### 地狱亡魂 `nether_wraith`

不限定只在下界生成。精锐、人少。`tier`：`hell`。

- **置入**：当前维度存在恶魔区块（附属模组增删）；阶段权重仅出现在终末（0.008）
- 锚点走恶魔区块，不走普通 HOSTILE_BORDER 抽样
- 规模：`small`，**10–20**
- 合金套；高附魔；血 60–90；全槽 empty = 0

| 项 | 值 |
|----|----|
| 兵种 | 僵尸 40，凋灵骷髅 25，僵尸猪灵 15，骷髅 10，女巫 10 |
| 血量 | 60×25，72×35，84×25，90×15 |
| 移速 | 0.26×50，0.30×50 |
| 空手攻击 | 4.0×50，6.0×50 |
| 头胸腿脚 empty | **0** |
| 甲材质 | 合金 88，钻 12 |
| 主手（近战） | 合金剑 70，钻剑 18，合金斧 8，空 4（空则补合金剑） |
| 主手（远程） | 弓 100 |
| 副手盾 | 40% |
| 耐久 | 2 / 10 / 38 / 50 |
| 附魔 | 甲 75%，器 85%，等级 20–30；保护 III–IV、锋利 III–V、力量 IV–V |

---

## 掠夺者 `illager`

**无路边档。** `roadkill` 权重并入 `small`。解锁与亡灵同一套 `worldOwned` / 恶魔区块门槛。头盔优先规则同样适用。卫道士近战 ≈ `attack` 5 + 铁斧 8 ≈ 普通难度 13。

### 小股掠夺 `illager_band_small`

- `tier`：`small`，始终可生成
- 规模：`medium`，**15–20**
- 皮～锁链；弩 / 铁斧；血 16–22

| 项 | 值 |
|----|----|
| 兵种 | 掠夺者 55，卫道士 35，女巫 10 |
| 血量 | 16×35，20×40，22×25 |
| 移速 | 掠夺者/卫道士 0.35，女巫 0.25 |
| 空手攻击 | 卫道士 5.0；其余 2.0 |
| 头盔 empty | **30** |
| 胸 / 腿 / 脚 empty | 42 / 50 / 55 |
| 甲材质 | 皮 55，锁链 30，铁 15 |
| 主手 | 掠夺者：空 10，弩 90；卫道士：铁斧 100 |
| 耐久 | 25 / 40 / 28 / 7 |
| 附魔 | 甲 6%，器 10%，等级 5–12 |

### 掠夺大队 `illager_host`

- `tier`：`host`，默认持有区块 ≥ 20
- 规模：`medium`，**28–32**
- 铁装；铁斧 / 弩；附魔约 15%；血 24–32

| 项 | 值 |
|----|----|
| 兵种 | 掠夺者 40，卫道士 40，唤魔者 8，女巫 12 |
| 血量 | 24×30，28×45，32×25 |
| 头盔 empty | **10** |
| 胸 / 腿 / 脚 empty | 16 / 20 / 24 |
| 甲材质 | 锁链 15，铁 80，钻 5 |
| 主手 | 掠夺者弩 100；卫道士铁斧 92、钻斧 8 |
| 耐久 | 10 / 32 / 42 / 16 |
| 附魔 | 甲 15%，器 18%，等级 8–18 |

### 灾厄军团 `illager_legion`

- `tier`：`legion`，默认持有区块 ≥ 30
- 规模：`large`，**50–70**
- 钻 / 铁混；斧必出；旗手约 8%；血 28–48，劫掠兽 100–140

| 项 | 值 |
|----|----|
| 兵种 | 卫道士 45，掠夺者 25，唤魔者 15，劫掠兽 5，女巫 10 |
| 血量（人形） | 28×20，36×40，42×25，48×15 |
| 血量（劫掠兽） | 100×40，120×40，140×20 |
| 头盔 empty | **0** |
| 胸 / 腿 / 脚 empty | 4 / 4 / 6 |
| 甲材质 | 铁 25，钻 72，合金 3 |
| 主手 | 卫道士：铁斧 40，钻斧 55，合金斧 5；掠夺者弩 100 |
| 旗手 | 掠夺者中 8% 插不祥旗帜（不占主手权重） |
| 耐久 | 5 / 20 / 45 / 30 |
| 附魔 | 甲 28%，器 35%，等级 15–25 |

### 暗影会众 `illager_wraith`

- `tier`：`hell`，对齐地狱：恶魔区块 + 终末阶段
- 规模：`small`，**10–20**
- 合金 / 钻；高附魔斧；图腾 20%；血 40–70，劫掠兽 120–160

| 项 | 值 |
|----|----|
| 兵种 | 唤魔者 30，卫道士 40，劫掠兽 15，女巫 15 |
| 血量（人形） | 40×25，55×40，70×35 |
| 血量（劫掠兽） | 120×50，160×50 |
| 头胸腿脚 empty | **0** |
| 甲材质 | 合金 80，钻 20 |
| 主手 | 卫道士：合金斧 70，钻斧 30 |
| 副手 | 图腾 20%（仅人形近战种） |
| 耐久 | 2 / 10 / 38 / 50 |
| 附魔 | 甲 70%，器 85%，等级 20–30 |

---

## 完整示例文件

下面是可直接当默认配置草稿的样式（亡灵路边 + 小股、掠夺小股各一条；其余档按上表补 `slots` / `stats` 即可）。

```json
{
  "progression": {
    "hostUnlockWorldOwned": 20,
    "legionUnlockWorldOwned": 30,
    "stages": [
      {
        "id": "journey",
        "name": "旅途",
        "unlock": "always",
        "weights": { "roadkill": 0.7, "small": 0.3 }
      },
      {
        "id": "homeland",
        "name": "故土",
        "unlock": "host",
        "weights": { "small": 0.5, "roadkill": 0.2, "host": 0.3 }
      },
      {
        "id": "hard",
        "name": "困难",
        "unlock": "legion",
        "weights": { "legion": 0.5, "host": 0.3, "small": 0.15, "roadkill": 0.05 }
      },
      {
        "id": "end",
        "name": "终末",
        "unlock": "legion",
        "requireDemonChunk": true,
        "weights": {
          "legion": 0.7,
          "host": 0.2,
          "small": 0.022,
          "roadkill": 0.07,
          "hell": 0.008
        }
      }
    ]
  },
  "clusters": [
    {
      "id": "undead_roadkill",
      "name": "路边野尸",
      "race": "undead",
      "tier": "roadkill",
      "size": "mixed",
      "count": {
        "min": 5,
        "max": 100,
        "buckets": [
          { "min": 5, "max": 15, "weight": 40 },
          { "min": 16, "max": 40, "weight": 35 },
          { "min": 41, "max": 70, "weight": 18 },
          { "min": 71, "max": 100, "weight": 7 }
        ]
      },
      "species": [
        { "entity": "minecraft:zombie", "weight": 70 },
        { "entity": "minecraft:husk", "weight": 10 },
        { "entity": "minecraft:skeleton", "weight": 18 },
        { "entity": "minecraft:zombie", "weight": 2, "baby": true }
      ],
      "stats": {
        "health": { "6": 40, "8": 45, "10": 15 },
        "speed": { "0.20": 40, "0.23": 60 },
        "attack": { "1.0": 70, "2.0": 30 }
      },
      "slots": {
        "helmet": {
          "empty": 50,
          "minecraft:leather_helmet": 38,
          "minecraft:golden_helmet": 8,
          "minecraft:chainmail_helmet": 4
        },
        "chestplate": {
          "empty": 70,
          "minecraft:leather_chestplate": 22,
          "minecraft:golden_chestplate": 6,
          "minecraft:chainmail_chestplate": 2
        },
        "leggings": {
          "empty": 76,
          "minecraft:leather_leggings": 18,
          "minecraft:golden_leggings": 5,
          "minecraft:chainmail_leggings": 1
        },
        "boots": {
          "empty": 82,
          "minecraft:leather_boots": 14,
          "minecraft:golden_boots": 4
        },
        "mainHand": {
          "empty": 55,
          "minecraft:wooden_shovel": 15,
          "minecraft:wooden_axe": 12,
          "minecraft:wooden_pickaxe": 12,
          "minecraft:iron_shovel": 4,
          "minecraft:iron_pickaxe": 2
        },
        "offHand": { "empty": 100 }
      },
      "durability": { "ruined": 50, "worn": 35, "used": 12, "fresh": 3 },
      "enchant": { "armorChance": 0.0, "weaponChance": 0.0, "level": [0, 0] }
    },
    {
      "id": "undead_warband_small",
      "name": "小股亡灵",
      "race": "undead",
      "tier": "small",
      "size": "medium",
      "count": { "min": 15, "max": 20 },
      "species": [
        { "entity": "minecraft:zombie", "weight": 62 },
        { "entity": "minecraft:husk", "weight": 10 },
        { "entity": "minecraft:skeleton", "weight": 18 },
        { "entity": "minecraft:stray", "weight": 5 },
        { "entity": "minecraft:zombie_villager", "weight": 5 }
      ],
      "stats": {
        "health": { "12": 35, "14": 40, "16": 25 },
        "speed": { "0.23": 100 },
        "attack": { "2.0": 60, "3.0": 40 }
      },
      "slots": {
        "helmet": {
          "empty": 25,
          "minecraft:leather_helmet": 48,
          "minecraft:golden_helmet": 10,
          "minecraft:chainmail_helmet": 9,
          "minecraft:iron_helmet": 8
        },
        "chestplate": {
          "empty": 35,
          "minecraft:leather_chestplate": 42,
          "minecraft:golden_chestplate": 8,
          "minecraft:chainmail_chestplate": 8,
          "minecraft:iron_chestplate": 7
        },
        "leggings": {
          "empty": 42,
          "minecraft:leather_leggings": 38,
          "minecraft:golden_leggings": 7,
          "minecraft:chainmail_leggings": 7,
          "minecraft:iron_leggings": 6
        },
        "boots": {
          "empty": 48,
          "minecraft:leather_boots": 34,
          "minecraft:golden_boots": 7,
          "minecraft:chainmail_boots": 6,
          "minecraft:iron_boots": 5
        },
        "mainHand": {
          "empty": 15,
          "minecraft:wooden_sword": 40,
          "minecraft:stone_sword": 25,
          "minecraft:iron_sword": 18,
          "minecraft:golden_sword": 2,
          "minecraft:bow": 0
        },
        "mainHandRanged": {
          "empty": 15,
          "minecraft:bow": 85
        },
        "offHand": { "empty": 100 }
      },
      "durability": { "ruined": 30, "worn": 40, "used": 25, "fresh": 5 },
      "enchant": { "armorChance": 0.05, "weaponChance": 0.08, "level": [5, 12] }
    },
    {
      "id": "illager_band_small",
      "name": "小股掠夺",
      "race": "illager",
      "tier": "small",
      "size": "medium",
      "count": { "min": 15, "max": 20 },
      "species": [
        { "entity": "minecraft:pillager", "weight": 55 },
        { "entity": "minecraft:vindicator", "weight": 35 },
        { "entity": "minecraft:witch", "weight": 10 }
      ],
      "stats": {
        "health": { "16": 35, "20": 40, "22": 25 },
        "speed": { "0.35": 100 },
        "attack": { "5.0": 100 }
      },
      "slots": {
        "helmet": {
          "empty": 30,
          "minecraft:leather_helmet": 40,
          "minecraft:chainmail_helmet": 20,
          "minecraft:iron_helmet": 10
        },
        "chestplate": {
          "empty": 42,
          "minecraft:leather_chestplate": 32,
          "minecraft:chainmail_chestplate": 18,
          "minecraft:iron_chestplate": 8
        },
        "leggings": {
          "empty": 50,
          "minecraft:leather_leggings": 28,
          "minecraft:chainmail_leggings": 15,
          "minecraft:iron_leggings": 7
        },
        "boots": {
          "empty": 55,
          "minecraft:leather_boots": 25,
          "minecraft:chainmail_boots": 13,
          "minecraft:iron_boots": 7
        },
        "mainHand": {
          "empty": 0,
          "minecraft:crossbow": 55,
          "minecraft:iron_axe": 45
        },
        "offHand": { "empty": 100 }
      },
      "durability": { "ruined": 25, "worn": 40, "used": 28, "fresh": 7 },
      "enchant": { "armorChance": 0.06, "weaponChance": 0.10, "level": [5, 12] }
    }
  ]
}
```

掠夺者 `mainHand` 落地时按兵种过滤：掠夺者只抽弩，卫道士只抽斧，抽到对方武器则重掷或映射到本兵种默认武器。女巫跳过全部槽。

其余模板（`undead_host`、`undead_legion`、`nether_wraith`、`illager_host`、`illager_legion`、`illager_wraith`）用同一结构，权重见上表。

---

## 索引

| id | 名称 | 种族 | tier | 解锁 | 人数 |
|----|------|------|------|------|------|
| `undead_roadkill` | 路边野尸 | undead | roadkill | 始终 | 5–100 |
| `undead_warband_small` | 小股亡灵 | undead | small | 始终 | 15–20 |
| `undead_host` | 一群亡灵 | undead | host | 持有区块 ≥ 20（可配） | 28–32 |
| `undead_legion` | 亡灵军团 | undead | legion | 持有区块 ≥ 30（可配） | 50–70 |
| `nether_wraith` | 地狱亡魂 | undead | hell | 恶魔区块 + 终末 | 10–20 |
| `illager_band_small` | 小股掠夺 | illager | small | 始终（无路边，roadkill 并入） | 15–20 |
| `illager_host` | 掠夺大队 | illager | host | 同 host | 28–32 |
| `illager_legion` | 灾厄军团 | illager | legion | 同 legion | 50–70 |
| `illager_wraith` | 暗影会众 | illager | hell | 同地狱 | 10–20 |
