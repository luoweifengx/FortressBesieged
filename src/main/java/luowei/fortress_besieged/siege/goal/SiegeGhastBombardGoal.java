package luowei.fortress_besieged.siege.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeUnitMode;
import luowei.fortress_besieged.siege.SiegeUnitRecord;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * DESTROY：恶魂不走路挖块，飞到射程内后按原版蓄力节奏朝追踪方块发射火焰弹。
 */
public class SiegeGhastBombardGoal extends Goal {
	/** 与原版 {@code GhastShootFireballGoal} 相同：64 格内才开火。 */
	private static final double SHOOT_RANGE_SQR = 64.0D * 64.0D;
	/** 火球出生点沿视线外推，避开 4×4 身体。 */
	private static final double MUZZLE_OFFSET = 4.0D;
	private static final int CHARGE_WARN = 10;
	private static final int CHARGE_SHOOT = 20;
	private static final int COOLDOWN = -40;
	/** 进入射程但仍挡视线时，绕目标转一圈找射角。 */
	private static final double STANDOFF = 28.0D;
	private static final double HOVER_ABOVE = 8.0D;

	private final Ghast ghast;
	private final SiegeCluster cluster;
	private BlockPos targetPos;
	private int chargeTime;
	private int lostSightTicks;

	public SiegeGhastBombardGoal(Ghast ghast, SiegeCluster cluster) {
		this.ghast = ghast;
		this.cluster = cluster;
		this.setFlags(SiegeAiHelper.moveLookFlags());
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public boolean canUse() {
		if (cluster.isDisbanded() || !(ghast.level() instanceof ServerLevel serverLevel)) {
			return false;
		}
		SiegeUnitRecord record = cluster.getUnit(ghast);
		if (record == null || record.getMode() != SiegeUnitMode.DESTROY) {
			return false;
		}
		BlockPos pos = record.getTrackedBlock();
		if (pos == null || !PbsTerritoryAdapter.isPlayerPlacedBlock(serverLevel, pos)) {
			record.returnToIdle();
			return false;
		}
		BlockState state = serverLevel.getBlockState(pos);
		if (state.isAir()) {
			record.returnToIdle();
			return false;
		}
		targetPos = pos;
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		return canUse();
	}

	@Override
	public void start() {
		chargeTime = 0;
		lostSightTicks = 0;
		ghast.setTarget(null);
	}

	@Override
	public void stop() {
		ghast.setCharging(false);
		targetPos = null;
		chargeTime = 0;
		lostSightTicks = 0;
	}

	@Override
	public void tick() {
		if (targetPos == null || !(ghast.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		ghast.setTarget(null);
		Vec3 aim = Vec3.atCenterOf(targetPos);
		face(aim);
		boolean inRange = ghast.distanceToSqr(aim) < SHOOT_RANGE_SQR;
		boolean clear = inRange && hasLineOfSight(serverLevel, aim);
		if (clear) {
			lostSightTicks = 0;
			hold();
			tickShoot(serverLevel, aim);
			return;
		}
		if (chargeTime > 0) {
			chargeTime--;
		}
		ghast.setCharging(chargeTime > CHARGE_WARN);
		if (inRange) {
			lostSightTicks++;
		} else {
			lostSightTicks = 0;
		}
		Vec3 hover = hoverPoint(aim);
		ghast.getMoveControl().setWantedPosition(hover.x, hover.y, hover.z, 1.0D);
	}

	private void tickShoot(ServerLevel level, Vec3 aim) {
		chargeTime++;
		if (chargeTime == CHARGE_WARN && !ghast.isSilent()) {
			level.levelEvent(null, 1015, ghast.blockPosition(), 0);
		}
		if (chargeTime == CHARGE_SHOOT) {
			Vec3 view = ghast.getViewVector(1.0F);
			double sx = ghast.getX() + view.x * MUZZLE_OFFSET;
			double sy = ghast.getY(0.5D) + 0.5D;
			double sz = ghast.getZ() + view.z * MUZZLE_OFFSET;
			Vec3 velocity = new Vec3(aim.x - sx, aim.y - sy, aim.z - sz).normalize();
			if (!ghast.isSilent()) {
				level.levelEvent(null, 1016, ghast.blockPosition(), 0);
			}
			LargeFireball fireball = new LargeFireball(level, ghast, velocity, ghast.getExplosionPower());
			fireball.setPos(sx, sy, sz);
			level.addFreshEntity(fireball);
			chargeTime = COOLDOWN;
		}
		ghast.setCharging(chargeTime > CHARGE_WARN);
	}

	private void hold() {
		ghast.getMoveControl().setWantedPosition(ghast.getX(), ghast.getY(), ghast.getZ(), 1.0D);
	}

	private Vec3 hoverPoint(Vec3 aim) {
		double dx = ghast.getX() - aim.x;
		double dz = ghast.getZ() - aim.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		if (horiz < 0.5D) {
			dx = 1.0D;
			dz = 0.0D;
			horiz = 1.0D;
		}
		if (lostSightTicks > 20) {
			double spin = (lostSightTicks - 20) * 0.05D;
			double cos = Math.cos(spin);
			double sin = Math.sin(spin);
			double rx = dx * cos - dz * sin;
			double rz = dx * sin + dz * cos;
			dx = rx;
			dz = rz;
			horiz = Math.sqrt(dx * dx + dz * dz);
		}
		double scale = STANDOFF / horiz;
		return new Vec3(aim.x + dx * scale, targetPos.getY() + HOVER_ABOVE, aim.z + dz * scale);
	}

	private void face(Vec3 aim) {
		double dx = aim.x - ghast.getX();
		double dy = aim.y - ghast.getEyeY();
		double dz = aim.z - ghast.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yRot = (float) (-Mth.atan2(dx, dz) * Mth.RAD_TO_DEG);
		float xRot = (float) (-Mth.atan2(dy, horiz) * Mth.RAD_TO_DEG);
		ghast.setYRot(yRot);
		ghast.setXRot(xRot);
		ghast.yBodyRot = yRot;
		ghast.setYHeadRot(yRot);
	}

	private boolean hasLineOfSight(ServerLevel level, Vec3 aim) {
		Vec3 eye = ghast.getEyePosition();
		BlockHitResult hit = level.clip(new ClipContext(
				eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ghast));
		if (hit.getType() == HitResult.Type.MISS) {
			return true;
		}
		return targetPos.equals(hit.getBlockPos());
	}
}
