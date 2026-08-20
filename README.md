# Fortress Besieged

Fabric 模组：围城集群（Siege Cluster）——怪物集群对 PBS 占领/边界区块破块与追击。
依赖的模组为https://github.com/luoweifengx/PlayerBlockState(PBS)

## 文档

围城状态机完成情况、选点/自然触发与已知缺口见：

- [docs/siege-cluster.md](docs/siege-cluster.md)

## 快速调试（权限 ≥2）

```text
/siege poll
/siege recipes
/siege sites              # 预览候选 HOSTILE_BORDER 与 Bernoulli(p=0.15) 结果
/siege spawn self         # 对当前玩家领土生成（带装备，初始 ASSAULT）
/siege spawn              # 按比例抽样多个目标（接近自然波次）
/siege status
/siege disband
```

自然波次：当 `level.getDayTime() % 24000 == 11000` 时，每个维度每天自动抽样目标并生成集群，聊天广播位置。

遗留：`/siege create [chunkRadius]` 仅收编附近自然怪，非正式路径。

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

[MIT](LICENSE) © 2026 luoweifengx
