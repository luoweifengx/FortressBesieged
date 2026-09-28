# Fortress Besieged

Fabric 模组：围城集群对 [PBS](https://github.com/luoweifengx/PlayerBlockState) 占领/边界区块破块与追击。

当前 **0.1.0**（开发中）。核心循环可玩：生成集群 → ASSAULT 破块/攻击 → FREE 追击 → 再集结。集群随世界 `SavedData` 持久化；活着时以进攻区块（地狱档用恶魔格）为中心用**会话内区块票**保持 7×7 实体 tick（不是 `/forceload`）。自然围城间隔全难度 2000 tick；野外从离开自家领地起计时 600 tick；地狱波次独立扫恶魔区块。

仓库：https://github.com/luoweifengx/FortressBesieged

## 环境

| 项 | 版本 |
|----|------|
| Minecraft | 1.21.5 |
| Java | 21+ |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.128.2+1.21.5 |
| player-block-status (PBS) | ≥ 0.3.2 |

构建本模组前，需要先把 PBS 安装到本机 Maven（`mavenLocal`）：

```text
在 PlayerBlockState 仓库执行:  ./gradlew publishToMavenLocal
```

然后在本仓库：

```text
./gradlew.bat build
```

产物在 `build/libs/`。运行时把本模组 jar 与 PBS jar 一起放进 `mods/`。

## 调试命令（权限 ≥2）

```text
/siege poll
/siege recipes
/siege sites              # 预览候选 HOSTILE_BORDER / HOSTILE 与 Bernoulli(p=0.25) 结果
/siege spawn self         # 对当前玩家领土生成（带装备，初始 ASSAULT）
/siege spawn              # 按比例抽样多个目标（命令即时波次）
/siege status
/siege bloodmoon          # 查看；start / stop 强制开关
/siege disband
```

自然刷新（**自然围城调度**）：每个在线玩家一个槽，间隔全难度 2000 tick。值班窗全体 `11000–23000`，掷该阶段 chance（下界为 2 倍）；**血月**跳过 chance、到期必出一波。每 tick 每维度最多处理一个槽。聊天给所属玩家。`/siege spawn` 仍按比例即时抽样，不走这套时钟。落点是距玩家 `BORDER` 切比雪夫 2–4 的 `HOSTILE_BORDER` / `HOSTILE` 地表。**地狱波次**另每 20 秒扫恶魔区块（p=0.008），全服报坐标。

**野外集群**是另一条线：玩家离开自己/组织的 `OCCUPIED`/`BORDER` 后计时 600 tick，到期必试选点（`attemptChance` 1.0），在附近 PBS `NATURAL` 刷一小队（同阶段权重、地狱档权重 0）。人数简单用 3–5 / 6–8 / 10–15，普通 ×1.4、困难 ×1.9。先试地表也可进洞穴；只追打、无票、不入存档。回家清计时；选点失败 / 成功都重新计 600 tick。原版敌对自然补员、幻翼、村庄僵尸围城与僵尸增援已关闭；围城集群仍走独立 `EVENT` 生成。和平难度三条线都不刷。

`/siege create [chunkRadius]` 是遗留调试命令（收编附近自然怪），非正式玩法路径。

## 文档

状态机、选点、寻路与已知缺口：

- [docs/siege-cluster.md](docs/siege-cluster.md)
- [docs/siege-cluster-templates.md](docs/siege-cluster-templates.md) — 集群编制；围城 `config/fortress-besieged-clusters.json`，野外 `config/fortress-besieged-wilderness.json`；行为开关 `config/fortress-besieged-behavior.json`

## License

[MIT](LICENSE) © 2026 luoweifengx
