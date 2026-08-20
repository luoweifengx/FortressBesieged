package luowei.fortress_besieged.siege;

/**
 * 围城集群阶段状态机：集结 → 进攻 → 自由 →（再）集结；无补兵。
 */
public enum SiegeClusterState {
	/** 等待；扫描 4-邻接 OCCUPIED/BORDER 后进入 ASSAULT */
	ASSEMBLE,
	/** 对目标区块破块/攻击 */
	ASSAULT,
	/** 7×7 自由追击；3×3 无敌对则回中心并可能回到 ASSEMBLE */
	FREE
}
