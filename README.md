# Fortress Besieged

Fabric 模组：围城集群对 [PBS](https://github.com/luoweifengx/PlayerBlockState) 占领/边界区块破块与追击。

当前 **0.1.0**（开发中）。核心循环可玩：生成集群 → ASSAULT 破块/攻击 → FREE 追击 → 再集结。集群随世界 `SavedData` 持久化；活着时以进攻区块为中心用**会话内区块票**保持 7×7 实体 tick（不是 `/forceload`）。

仓库：https://github.com/luoweifengx/FortressBesieged

## 环境

| 项 | 版本 |
|----|------|
| Minecraft | 1.21.5 |
| Java | 21+ |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.128.2+1.21.5 |
| player-block-status (PBS) | ≥ 0.2.7 |

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
/siege sites              # 预览候选 HOSTILE_BORDER 与 Bernoulli(p=0.15) 结果
/siege spawn self         # 对当前玩家领土生成（带装备，初始 ASSAULT）
/siege spawn              # 按比例抽样多个目标（命令即时波次）
/siege status
/siege disband
```

自然刷新（**自然围城调度**）：每个目标按当前阶段窗口独立预约到期时刻（旅途 7000–18000、故土 10000–22000、困难 11000–19000、终末 16000–23000），到期后掷该阶段 chance，每 tick 每维度最多处理一个目标，聊天广播位置。`/siege spawn` 仍按比例即时抽样，不走这套时钟。落点是 `HOSTILE_BORDER`（地狱档在恶魔区块），带 7×7 票并写入集群存档。

**野外集群**是另一条线：在 PBS `NATURAL` 区块、玩家附近低频刷一小队（同阶段权重、地狱档权重 0），只追打、无票、不入存档，走原版自然消失。原版敌对自然补员、幻翼、村庄僵尸围城与僵尸增援已关闭；围城集群仍走独立 `EVENT` 生成。

`/siege create [chunkRadius]` 是遗留调试命令（收编附近自然怪），非正式玩法路径。

## 文档

状态机、选点、寻路与已知缺口：

- [docs/siege-cluster.md](docs/siege-cluster.md)
- [docs/siege-cluster-templates.md](docs/siege-cluster-templates.md) — 集群编制；围城 `config/fortress-besieged-clusters.json`，野外 `config/fortress-besieged-wilderness.json`；行为开关 `config/fortress-besieged-behavior.json`

## License

[MIT](LICENSE) © 2026 luoweifengx
