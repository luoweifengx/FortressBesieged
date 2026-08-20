package luowei.fortress_besieged.siege;

/**
 * 集群单位行为状态机。
 *
 * <p>典型流转：{@code IDLE → ATTACK | DESTROY}；目标失效后回到 {@code IDLE} 再决策。
 */
public enum SiegeUnitMode {
	/** 追击/攻击已选定的友善目标 */
	ATTACK,
	/** 破坏最近的 PBS 玩家放置可破方块 */
	DESTROY,
	/** 游荡 / 回目标区块；由单位自行掷骰决定下一目标 */
	IDLE
}
