package luowei.fortress_besieged.siege;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * 单只集群单位的运行时记录：模式 + 追踪方块 + 追击目标 + 首次目标掷骰标记。
 */
public final class SiegeUnitRecord {
	private final UUID mobId;
	private SiegeUnitMode mode = SiegeUnitMode.IDLE;
	private BlockPos trackedBlock;
	private UUID initialChaseId;
	private UUID currentChaseId;
	/** 是否已做过「生成后首次」IDLE→目标 掷骰（此后用 1/30）。 */
	private boolean firstTargetRollDone;

	public SiegeUnitRecord(UUID mobId) {
		this.mobId = mobId;
	}

	public UUID getMobId() {
		return mobId;
	}

	public SiegeUnitMode getMode() {
		return mode;
	}

	public void setMode(SiegeUnitMode mode) {
		this.mode = mode == null ? SiegeUnitMode.IDLE : mode;
	}

	public BlockPos getTrackedBlock() {
		return trackedBlock;
	}

	public void setTrackedBlock(BlockPos trackedBlock) {
		this.trackedBlock = trackedBlock == null ? null : trackedBlock.immutable();
	}

	public UUID getInitialChaseId() {
		return initialChaseId;
	}

	public void setInitialChaseId(UUID initialChaseId) {
		this.initialChaseId = initialChaseId;
	}

	public UUID getCurrentChaseId() {
		return currentChaseId;
	}

	public void setCurrentChaseId(UUID currentChaseId) {
		this.currentChaseId = currentChaseId;
	}

	public boolean isFirstTargetRollDone() {
		return firstTargetRollDone;
	}

	public void setFirstTargetRollDone(boolean firstTargetRollDone) {
		this.firstTargetRollDone = firstTargetRollDone;
	}

	/** 标记已用过首次找人概率（1/3）；之后 IDLE 决策用 1/30。 */
	public void markFirstTargetRollDone() {
		firstTargetRollDone = true;
	}

	public void clearAssignments() {
		trackedBlock = null;
		initialChaseId = null;
		currentChaseId = null;
	}

	public void assignDestroy(BlockPos block) {
		mode = SiegeUnitMode.DESTROY;
		trackedBlock = block == null ? null : block.immutable();
		initialChaseId = null;
		currentChaseId = null;
	}

	public void assignAttack(LivingEntity target) {
		mode = SiegeUnitMode.ATTACK;
		trackedBlock = null;
		UUID id = target == null ? null : target.getUUID();
		initialChaseId = id;
		currentChaseId = id;
	}

	public void assignIdle() {
		mode = SiegeUnitMode.IDLE;
		trackedBlock = null;
		// 保留追击 UUID 供 FREE 阶段映射；ASSAULT 下 IDLE 不追击
	}

	/** 目标失效：清追踪并回到 IDLE，供下一次掷骰。 */
	public void returnToIdle() {
		mode = SiegeUnitMode.IDLE;
		trackedBlock = null;
		initialChaseId = null;
		currentChaseId = null;
	}

	public boolean matches(Mob mob) {
		return mob != null && mobId.equals(mob.getUUID());
	}
}
