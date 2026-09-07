package luowei.fortress_besieged.siege;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegeClusterSavedData.ClusterSnapshot;
import luowei.fortress_besieged.siege.SiegeClusterSavedData.UnitSnapshot;
import luowei.fortress_besieged.siege.goal.SiegeAiHelper;
import luowei.fortress_besieged.siege.goal.SiegeTargetDecision;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.spawn.HellSpawnConfig;
import luowei.fortress_besieged.siege.spawn.SiegeProgression;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnMonitor;
import luowei.fortress_besieged.siege.targeting.SiegeTargeting;
import luowei.player_block_status.lib.chunk.ChunkState;

/**
 * 单个围城集群：确定进攻区块、存活 tick、解散；ASSAULT 下任务由单位自行决策。
 */
public final class SiegeCluster {
	public static final int FREE_CHASE_RADIUS = 3; // 7×7 Chebyshev
	public static final int FREE_SCAN_RADIUS = 1;  // 3×3 Chebyshev
	/** 读档后：未进世界的单位 UUID 不按死亡 prune；超时仍未出现则视为离线期间已死。 */
	private static final int LOAD_GRACE_TICKS = 400;

	private final UUID id;
	private final ServerLevel level;
	private final Random random = new Random();

	private SiegeClusterState state = SiegeClusterState.ASSEMBLE;
	private ChunkPos anchorChunk;
	private ChunkPos targetChunk;
	private boolean disbanded;
	private ChunkPos ticketCenter;
	private int loadGraceTicks;
	/** 本会话里曾经 resolve 到过活实体的单位；用于区分「从未加载」与「走出加载区」。 */
	private final Set<UUID> seenInWorld = new HashSet<>();

	/** 怪物 UUID → 单位记录 */
	private final Map<UUID, SiegeUnitRecord> units = new HashMap<>();

	/** FREE 阶段：攻击目标列表 + 目标→单位（ASSAULT 不再用配额分配表） */
	private final List<UUID> attackTrackList = new ArrayList<>();
	private final Map<UUID, List<UUID>> attackToUnits = new HashMap<>();

	/**
	 * 集群内「全局玩家映射」：目标 → 单位列表（FREE 预分配/追击用）。
	 */
	private final Map<UUID, List<UUID>> globalPlayerMap = new HashMap<>();

	private int tickCounter;

	private String templateId;
	private String templateName;
	private String race;
	private String tier;
	/** Poll target (org or solo player) this cluster was spawned against. Not in SavedData yet. */
	private UUID siegeTargetId;

	public SiegeCluster(ServerLevel level, ChunkPos anchorChunk, List<Mob> mobs) {
		this.id = UUID.randomUUID();
		this.level = level;
		this.anchorChunk = anchorChunk;
		for (Mob mob : mobs) {
			if (mob == null || !mob.isAlive()) {
				continue;
			}
			units.put(mob.getUUID(), new SiegeUnitRecord(mob.getUUID()));
			seenInWorld.add(mob.getUUID());
			SiegeAiHelper.install(mob, this);
		}
		updateTickets();
	}

	/** 从世界存档恢复；Goal 等实体进世界后再 {@link #onUnitEntityLoaded}。 */
	SiegeCluster(ServerLevel level, ClusterSnapshot snapshot) {
		this.id = snapshot.id();
		this.level = level;
		this.state = snapshot.state();
		this.anchorChunk = snapshot.anchorChunk();
		this.targetChunk = snapshot.targetChunk().orElse(null);
		this.loadGraceTicks = LOAD_GRACE_TICKS;
		snapshot.templateId().ifPresent(v -> this.templateId = v);
		snapshot.templateName().ifPresent(v -> this.templateName = v);
		snapshot.race().ifPresent(v -> this.race = v);
		snapshot.tier().ifPresent(v -> this.tier = v);
		for (UnitSnapshot unit : snapshot.units()) {
			units.put(unit.mobId(), unit.toRecord());
		}
		attackTrackList.addAll(snapshot.attackTrackList());
		putUuidListMap(attackToUnits, snapshot.attackToUnits());
		putUuidListMap(globalPlayerMap, snapshot.globalPlayerMap());
		updateTickets();
	}

	public UUID getId() {
		return id;
	}

	public ServerLevel getLevel() {
		return level;
	}

	public SiegeClusterState getState() {
		return state;
	}

	public ChunkPos getTargetChunk() {
		return targetChunk;
	}

	public ChunkPos getAnchorChunk() {
		return anchorChunk;
	}

	public boolean isDisbanded() {
		return disbanded;
	}

	public String getTemplateId() {
		return templateId;
	}

	public String getTemplateName() {
		return templateName;
	}

	public String getRace() {
		return race;
	}

	public String getTier() {
		return tier;
	}

	public void setTemplateInfo(String templateId, String templateName, String race, String tier) {
		this.templateId = templateId;
		this.templateName = templateName;
		this.race = race;
		this.tier = tier;
	}

	public UUID getSiegeTargetId() {
		return siegeTargetId;
	}

	public void setSiegeTargetId(UUID siegeTargetId) {
		this.siegeTargetId = siegeTargetId;
	}

	public boolean isHell() {
		return SiegeProgression.TIER_HELL.equals(tier);
	}

	/** 闲时回家的区块：地狱档是刷出时的恶魔格，其余是进攻格。 */
	public ChunkPos getHomeChunk() {
		if (isHell() && anchorChunk != null) {
			return anchorChunk;
		}
		return targetChunk != null ? targetChunk : anchorChunk;
	}

	public String describeTemplate() {
		if (templateId == null) {
			return "unspecified";
		}
		StringBuilder sb = new StringBuilder(templateId);
		if (templateName != null) {
			sb.append('/').append(templateName);
		}
		if (race != null) {
			sb.append(" race=").append(race);
		}
		if (tier != null) {
			sb.append(" tier=").append(tier);
		}
		return sb.toString();
	}

	public SiegeUnitRecord getUnit(Mob mob) {
		return mob == null ? null : units.get(mob.getUUID());
	}

	public SiegeUnitRecord getUnit(UUID mobId) {
		return units.get(mobId);
	}

	/** 是否有单位正在追踪该方块（单位自选 DESTROY 目标）。 */
	public boolean isTrackedBlock(BlockPos pos) {
		return isTrackedBlockByOther(pos, null);
	}

	/** 是否有<strong>其他</strong>单位正在追踪该方块（{@code selfId} 自己占用的格不算）。 */
	public boolean isTrackedBlockByOther(BlockPos pos, UUID selfId) {
		if (pos == null) {
			return false;
		}
		for (SiegeUnitRecord record : units.values()) {
			if (!pos.equals(record.getTrackedBlock())) {
				continue;
			}
			if (selfId == null || !selfId.equals(record.getMobId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 当前单位走不到自己的追踪块：清掉后回 IDLE，由单位自行再决策。
	 */
	public void requestDestroyReassign(Mob mob) {
		if (disbanded || mob == null) {
			return;
		}
		SiegeUnitRecord record = units.get(mob.getUUID());
		if (record == null || record.hasLockedAggro() || record.isReturningHome()) {
			return;
		}
		record.returnToIdle();
	}

	public Map<UUID, List<UUID>> getGlobalPlayerMap() {
		return globalPlayerMap;
	}

	public LivingEntity resolveChaseTarget(ServerLevel serverLevel, SiegeUnitRecord record) {
		if (record == null || record.getCurrentChaseId() == null) {
			return null;
		}
		Entity entity = serverLevel.getEntity(record.getCurrentChaseId());
		if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
			return null;
		}
		if (record.hasLockedAggro()) {
			Mob mob = resolveMob(record.getMobId());
			return isWithinAggroLeash(mob, living) ? living : null;
		}
		if (isInChaseZone(living)) {
			return living;
		}
		return null;
	}

	/** 锁定仇恨绳长：地狱档相对恶魔格水平距离，其余相对追击者。 */
	public boolean isWithinAggroLeash(Mob mob, LivingEntity target) {
		if (target == null) {
			return false;
		}
		double max = HellSpawnConfig.AGGRO_LEASH_BLOCKS;
		double maxSq = max * max;
		if (isHell()) {
			ChunkPos home = anchorChunk;
			if (home == null) {
				return false;
			}
			double dx = target.getX() - (home.getMiddleBlockX() + 0.5D);
			double dz = target.getZ() - (home.getMiddleBlockZ() + 0.5D);
			return dx * dx + dz * dz <= maxSq;
		}
		return mob != null && mob.distanceToSqr(target) <= maxSq;
	}

	/** 追击中心：有进攻区块用 {@code targetChunk}，否则锚点。 */
	public ChunkPos getChaseCenter() {
		return targetChunk != null ? targetChunk : anchorChunk;
	}

	/**
	 * 目标是否在追击中心的 7×7（切比雪夫 ≤ {@link #FREE_CHASE_RADIUS}）内。
	 * 区外不追、不还手（锁定仇恨除外）。
	 */
	public boolean isInChaseZone(Entity entity) {
		return isInChunkRing(entity, getChaseCenter(), FREE_CHASE_RADIUS);
	}

	/** 预警圈：地狱档以恶魔格为中心，其余与追击圈相同。 */
	public boolean isInWarningZone(Entity entity) {
		ChunkPos center = isHell() ? anchorChunk : getChaseCenter();
		return isInChunkRing(entity, center, FREE_CHASE_RADIUS);
	}

	private boolean isInChunkRing(Entity entity, ChunkPos center, int radius) {
		if (entity == null || center == null || entity.level() != level) {
			return false;
		}
		return chebyshevChunkDistance(center, entity.chunkPosition()) <= radius;
	}

	public void tick() {
		if (disbanded) {
			return;
		}
		tickCounter++;
		updateTickets();
		tryBindLoadedUnits();
		pruneDeadUnits();
		int living = countLiving();
		if (living == 0) {
			disband(false);
			return;
		}
		if (!isHell() && state != SiegeClusterState.ASSEMBLE && living <= 2) {
			disband(true);
			return;
		}

		switch (state) {
			case ASSEMBLE -> tickAssemble();
			case ASSAULT -> tickAssault(living);
			case FREE -> tickFree(living);
		}
	}

	private void tickAssemble() {
		if (isHell()) {
			return;
		}
		List<ChunkPos> candidates = PbsTerritoryAdapter.findAdjacentAssaultChunks(level, anchorChunk);
		if (candidates.isEmpty()) {
			return;
		}
		targetChunk = candidates.get(random.nextInt(candidates.size()));
		updateTickets();
		enterAssault("assemble_adjacent");
	}

	/**
	 * Spawn-time entry: set assault target to the nearest OCCUPIED/BORDER chunk
	 * and enter ASSAULT immediately (skips the ASSEMBLE adjacency roll).
	 */
	public void startAssaultOn(ChunkPos assaultTarget) {
		if (disbanded || assaultTarget == null) {
			return;
		}
		this.targetChunk = assaultTarget;
		updateTickets();
		enterAssault("spawn_nearest_owned");
	}

	private void enterAssault(String reason) {
		SiegeClusterState from = state;
		state = SiegeClusterState.ASSAULT;
		logState(from, "ASSAULT", reason);
		int living = countLiving();
		if (!isHell() && living <= 2) {
			disband(true);
			return;
		}
		clearAttackContainers();
		for (SiegeUnitRecord record : units.values()) {
			record.returnToIdle();
		}
	}

	private void tickAssault(int living) {
		if (isHell()) {
			tickHellAssault();
			return;
		}
		if (living <= 2) {
			disband(true);
			return;
		}

		ChunkState chunkState = PbsTerritoryAdapter.getState(level, targetChunk);
		if (!PbsTerritoryAdapter.isAssaultable(chunkState)) {
			enterFree("chunk_not_assaultable");
			return;
		}

		if (tickCounter % 20 == 0) {
			maybeTransitionToFreeWhenNoBlocks();
		}
	}

	private void tickHellAssault() {
		completeHellHomeReturns();
		if (tickCounter % 10 == 0) {
			acquireHellWarning();
		}
	}

	/** 7×7 预警（恶魔格为中心）：进圈则全队锁定追击。 */
	private void acquireHellWarning() {
		List<LivingEntity> found = findFriendlyInChunkRadius(anchorChunk, FREE_CHASE_RADIUS);
		if (found.isEmpty()) {
			return;
		}
		LivingEntity pick = pickNearestToHome(found);
		if (pick == null) {
			return;
		}
		for (UUID mobId : units.keySet()) {
			SiegeUnitRecord record = units.get(mobId);
			if (record == null || record.hasLockedAggro()) {
				continue;
			}
			record.beginLockedAggro(pick);
		}
	}

	private LivingEntity pickNearestToHome(List<LivingEntity> found) {
		ChunkPos home = anchorChunk;
		if (home == null || found == null || found.isEmpty()) {
			return found == null || found.isEmpty() ? null : found.get(0);
		}
		double hx = home.getMiddleBlockX() + 0.5D;
		double hz = home.getMiddleBlockZ() + 0.5D;
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (LivingEntity entity : found) {
			double dx = entity.getX() - hx;
			double dz = entity.getZ() - hz;
			double dist = dx * dx + dz * dz;
			if (dist < bestDist) {
				bestDist = dist;
				best = entity;
			}
		}
		return best;
	}

	private void completeHellHomeReturns() {
		for (UUID mobId : units.keySet()) {
			SiegeUnitRecord record = units.get(mobId);
			if (record == null || !record.isReturningHome()) {
				continue;
			}
			Mob mob = resolveMob(mobId);
			if (mob == null) {
				continue;
			}
			if (anchorChunk != null && mob.chunkPosition().equals(anchorChunk)) {
				mob.setHealth(mob.getMaxHealth());
				record.finishReturningHome();
			}
		}
	}

	/** 锁定仇恨丢失：非地狱立刻接回原工作；地狱档先回家回血再接回。 */
	public void onLockedAggroLost(Mob mob) {
		if (disbanded || mob == null) {
			return;
		}
		SiegeUnitRecord record = units.get(mob.getUUID());
		if (record == null) {
			return;
		}
		mob.setTarget(null);
		if (!record.hasLockedAggro()) {
			if (!record.isReturningHome()) {
				record.returnToIdle();
			}
			return;
		}
		if (isHell()) {
			record.startReturningHome();
			return;
		}
		record.releaseLockedAggroAndResume();
	}

	/** 目标区块 3×3（含对角）无可破 PBS 块 → FREE。 */
	private void maybeTransitionToFreeWhenNoBlocks() {
		if (!SiegeTargetDecision.hasAnyBreakable(level, targetChunk)) {
			enterFree("no_breakable_blocks");
		}
	}

	private void enterFree(String reason) {
		SiegeClusterState from = state;
		state = SiegeClusterState.FREE;
		logState(from, "FREE", reason);
		for (UUID mobId : units.keySet()) {
			Mob mob = resolveMob(mobId);
			if (mob != null) {
				mob.setTarget(null);
			}
			SiegeUnitRecord record = units.get(mobId);
			if (record != null) {
				record.returnToIdle();
			}
		}
		preassignFreeTargets();
	}

	private void tickFree(int living) {
		if (living <= 2) {
			if (isHell()) {
				return;
			}
			disband(true);
			return;
		}

		handleTargetsLeavingFreeZone();

		if (tickCounter % 40 != 0) {
			return;
		}
		if (isHell()) {
			return;
		}

		List<LivingEntity> nearby = findFriendlyInChunkRadius(targetChunk, FREE_SCAN_RADIUS);
		if (!nearby.isEmpty()) {
			return;
		}

		boolean allInside = true;
		for (UUID mobId : units.keySet()) {
			Mob mob = resolveMob(mobId);
			if (mob == null) {
				continue;
			}
			SiegeUnitRecord record = units.get(mobId);
			if (record != null) {
				record.setMode(SiegeUnitMode.IDLE);
			}
			if (!mob.chunkPosition().equals(targetChunk)) {
				allInside = false;
			}
		}
		if (!allInside) {
			return;
		}

		anchorChunk = targetChunk;
		updateTickets();
		clearAttackContainers();
		for (SiegeUnitRecord record : units.values()) {
			record.clearAssignments();
			record.setMode(SiegeUnitMode.IDLE);
		}
		globalPlayerMap.clear();
		SiegeClusterState from = state;
		state = SiegeClusterState.ASSEMBLE;
		logState(from, "ASSEMBLE", "free_scan_clear");
	}

	private void handleTargetsLeavingFreeZone() {
		if (targetChunk == null) {
			return;
		}
		List<UUID> leftTargets = new ArrayList<>();
		for (UUID targetId : new ArrayList<>(globalPlayerMap.keySet())) {
			Entity entity = level.getEntity(targetId);
			if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
				leftTargets.add(targetId);
				continue;
			}
			if (chebyshevChunkDistance(targetChunk, living.chunkPosition()) > FREE_CHASE_RADIUS) {
				leftTargets.add(targetId);
			}
		}
		for (UUID leftId : leftTargets) {
			onFreeTargetLeftZone(leftId);
		}
	}

	private void onFreeTargetLeftZone(UUID leftTargetId) {
		List<UUID> affected = new ArrayList<>(globalPlayerMap.getOrDefault(leftTargetId, List.of()));
		for (Map.Entry<UUID, SiegeUnitRecord> entry : units.entrySet()) {
			SiegeUnitRecord record = entry.getValue();
			if (record.hasLockedAggro() || record.isReturningHome()) {
				continue;
			}
			if (leftTargetId.equals(record.getInitialChaseId()) || leftTargetId.equals(record.getCurrentChaseId())) {
				if (!affected.contains(entry.getKey())) {
					affected.add(entry.getKey());
				}
			}
		}

		List<UUID> reassignPool = new ArrayList<>();
		for (UUID mobId : affected) {
			Mob mob = resolveMob(mobId);
			SiegeUnitRecord record = units.get(mobId);
			if (mob == null || record == null) {
				continue;
			}
			if (SiegeClusterBehaviorConfig.get().healOnTargetLeaveZone) {
				mob.setHealth(mob.getMaxHealth());
			}

			boolean initialWas = leftTargetId.equals(record.getInitialChaseId());
			boolean currentWas = leftTargetId.equals(record.getCurrentChaseId());

			if (currentWas) {
				record.setCurrentChaseId(record.getInitialChaseId());
				if (leftTargetId.equals(record.getCurrentChaseId())) {
					reassignPool.add(mobId);
				}
			}
			if (initialWas && !reassignPool.contains(mobId)) {
				reassignPool.add(mobId);
			}
		}

		globalPlayerMap.remove(leftTargetId);
		for (List<UUID> list : globalPlayerMap.values()) {
			list.removeIf(affected::contains);
		}
		attackTrackList.remove(leftTargetId);
		attackToUnits.remove(leftTargetId);

		fillFreeTargets();
		if (!reassignPool.isEmpty() && !globalPlayerMap.isEmpty()) {
			List<UUID> targetIds = new ArrayList<>(globalPlayerMap.keySet());
			for (UUID mobId : reassignPool) {
				SiegeUnitRecord record = units.get(mobId);
				if (record == null) {
					continue;
				}
				UUID newTarget = targetIds.get(random.nextInt(targetIds.size()));
				record.setInitialChaseId(newTarget);
				if (record.getCurrentChaseId() == null || leftTargetId.equals(record.getCurrentChaseId())) {
					record.setCurrentChaseId(newTarget);
				}
				record.setMode(SiegeUnitMode.ATTACK);
				globalPlayerMap.computeIfAbsent(newTarget, k -> new ArrayList<>()).add(mobId);
			}
		} else if (!reassignPool.isEmpty()) {
			for (UUID mobId : reassignPool) {
				SiegeUnitRecord record = units.get(mobId);
				if (record != null) {
					record.clearAssignments();
					record.setMode(SiegeUnitMode.IDLE);
				}
			}
		}
	}

	public void onMobDamaged(Mob mob, Entity attacker) {
		if (disbanded || mob == null) {
			return;
		}
		if (!(attacker instanceof LivingEntity living) || !SiegeTargeting.isValidAttackTarget(living)) {
			return;
		}
		if (living.level() != level) {
			return;
		}
		SiegeUnitRecord record = units.get(mob.getUUID());
		if (record == null) {
			return;
		}
		record.beginLockedAggro(living);
		if (state == SiegeClusterState.FREE) {
			globalPlayerMap.computeIfAbsent(living.getUUID(), k -> new ArrayList<>());
			List<UUID> list = globalPlayerMap.get(living.getUUID());
			if (!list.contains(mob.getUUID())) {
				list.add(mob.getUUID());
			}
		}
	}

	/**
	 * 路上某格已被挖掉：通知各 DESTROY 单位把队列里该格 BREAK 改成 WALK。
	 */
	public void onRouteBlockRemoved(BlockPos pos) {
		if (disbanded || pos == null) {
			return;
		}
		BlockPos key = pos.immutable();
		int rewritten = 0;
		for (Map.Entry<UUID, SiegeUnitRecord> entry : units.entrySet()) {
			if (entry.getValue().getMode() != SiegeUnitMode.DESTROY) {
				continue;
			}
			Mob mob = resolveMob(entry.getKey());
			if (mob != null && SiegeAiHelper.rewriteDestroyRouteBreakToWalk(mob, key)) {
				rewritten++;
			}
		}
		if (rewritten > 0) {
			FortressBesieged.LOGGER.debug(
					"[siege destroy] onRouteBlockRemoved pos={} rewrittenUnits={}",
					key,
					rewritten);
		}
	}

	/**
	 * 追踪块被挖掉：相关单位回 IDLE，由单位自行再掷骰（之后 1/30）。
	 */
	public void onTrackedBlockDestroyed(BlockPos broken, Mob breaker) {
		if (disbanded || broken == null) {
			return;
		}
		BlockPos key = broken.immutable();
		for (Map.Entry<UUID, SiegeUnitRecord> entry : units.entrySet()) {
			SiegeUnitRecord record = entry.getValue();
			if (key.equals(record.getTrackedBlock())) {
				record.returnToIdle();
			}
		}
		if (breaker != null) {
			SiegeUnitRecord breakerRecord = units.get(breaker.getUUID());
			if (breakerRecord != null && breakerRecord.getMode() == SiegeUnitMode.DESTROY
					&& (breakerRecord.getTrackedBlock() == null || key.equals(breakerRecord.getTrackedBlock()))) {
				breakerRecord.returnToIdle();
			}
		}
	}

	private void preassignFreeTargets() {
		globalPlayerMap.clear();
		attackTrackList.clear();
		attackToUnits.clear();

		List<LivingEntity> hostiles = findFriendlyInChunkRadius(targetChunk, FREE_CHASE_RADIUS);
		Collections.shuffle(hostiles, random);
		int m = countLiving();
		if (m <= 2) {
			if (isHell()) {
				return;
			}
			disband(true);
			return;
		}
		int x = floorLog2(m);
		int size = Math.max(1, m / (2 * x));
		for (int i = 0; i < size && i < hostiles.size(); i++) {
			UUID tid = hostiles.get(i).getUUID();
			attackTrackList.add(tid);
			globalPlayerMap.put(tid, new ArrayList<>());
		}

		// 进 FREE 只登记附近友善目标，单位一律 IDLE；禁止无掷骰全员 assignAttack。
		for (UUID mobId : livingUnitIds()) {
			SiegeUnitRecord record = units.get(mobId);
			if (record != null) {
				record.clearAssignments();
				record.setMode(SiegeUnitMode.IDLE);
			}
		}
	}

	private void fillFreeTargets() {
		int m = countLiving();
		if (m <= 2) {
			return;
		}
		int x = Math.max(1, floorLog2(m));
		int size = Math.max(1, m / (2 * x));
		if (globalPlayerMap.size() >= size) {
			return;
		}
		List<LivingEntity> hostiles = findFriendlyInChunkRadius(targetChunk, FREE_CHASE_RADIUS);
		Collections.shuffle(hostiles, random);
		for (LivingEntity entity : hostiles) {
			if (globalPlayerMap.size() >= size) {
				break;
			}
			UUID tid = entity.getUUID();
			if (!globalPlayerMap.containsKey(tid)) {
				globalPlayerMap.put(tid, new ArrayList<>());
				if (!attackTrackList.contains(tid)) {
					attackTrackList.add(tid);
				}
			}
		}
	}

	public void forceDisband() {
		if (!disbanded) {
			disband(true, "force");
		}
	}

	private void disband(boolean switchToAttack) {
		disband(switchToAttack, switchToAttack ? "living_le_2" : "no_living");
	}

	private void disband(boolean switchToAttack, String reason) {
		SiegeClusterState from = state;
		SiegeSpawnMonitor.disbanded(this, from, reason, tickCounter, countLiving());
		disbanded = true;
		if ("no_living".equals(reason) || "living_le_2".equals(reason)) {
			SiegeSounds.playVictory(this);
		}
		dropTickets();
		for (UUID mobId : new ArrayList<>(units.keySet())) {
			Mob mob = resolveMob(mobId);
			if (mob != null) {
				SiegeAiHelper.clearGlowing(mob);
				if (switchToAttack) {
					SiegeAiHelper.switchToVanillaAttack(mob);
					List<LivingEntity> near = findFriendlyInChunkRadius(mob.chunkPosition(), 1);
					if (!near.isEmpty()) {
						mob.setTarget(near.get(random.nextInt(near.size())));
					}
				} else {
					SiegeAiHelper.uninstall(mob);
				}
			}
		}
		units.clear();
		clearAttackContainers();
		globalPlayerMap.clear();
	}

	private void clearAttackContainers() {
		attackTrackList.clear();
		attackToUnits.clear();
	}

	private void removeUnitFromAttackMaps(UUID mobId) {
		for (List<UUID> list : attackToUnits.values()) {
			list.remove(mobId);
		}
		for (List<UUID> list : globalPlayerMap.values()) {
			list.remove(mobId);
		}
	}

	private void pruneDeadUnits() {
		if (loadGraceTicks > 0) {
			loadGraceTicks--;
		}
		Iterator<Map.Entry<UUID, SiegeUnitRecord>> it = units.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, SiegeUnitRecord> entry = it.next();
			UUID mobId = entry.getKey();
			Entity entity = level.getEntity(mobId);
			if (entity instanceof Mob mob) {
				if (!mob.isAlive() || mob.isRemoved()) {
					forgetUnit(mobId);
					it.remove();
					continue;
				}
				seenInWorld.add(mobId);
				continue;
			}
			// getEntity==null：读档宽限内、或从未出现过且仍在宽限 → 不当死亡
			if (loadGraceTicks > 0) {
				continue;
			}
			if (!seenInWorld.contains(mobId)) {
				// 宽限结束仍未进世界：视为离线期间已死
				forgetUnit(mobId);
				it.remove();
			}
			// 曾经在世界里、现在 null：走出加载区，保留 UUID 等重绑
		}
	}

	void onUnitEntityLoaded(Mob mob) {
		if (disbanded || mob == null) {
			return;
		}
		UUID mobId = mob.getUUID();
		if (!units.containsKey(mobId) || !mob.isAlive()) {
			return;
		}
		seenInWorld.add(mobId);
		if (!SiegeAiHelper.hasSiegeGoals(mob)) {
			SiegeAiHelper.install(mob, this);
		} else {
			SiegeClusterManager.bindMob(mob, this);
			SiegeAiHelper.applyGlowing(mob);
		}
	}

	void tryBindLoadedUnits() {
		if (disbanded) {
			return;
		}
		for (UUID mobId : units.keySet()) {
			Mob mob = resolveMob(mobId);
			if (mob != null) {
				onUnitEntityLoaded(mob);
			}
		}
	}

	void removeDeadUnit(UUID mobId) {
		if (mobId == null || !units.containsKey(mobId)) {
			return;
		}
		forgetUnit(mobId);
		units.remove(mobId);
	}

	Set<UUID> unitIds() {
		return units.keySet();
	}

	ClusterSnapshot toSnapshot() {
		List<UnitSnapshot> unitSnaps = new ArrayList<>();
		for (SiegeUnitRecord record : units.values()) {
			unitSnaps.add(UnitSnapshot.from(record));
		}
		return new ClusterSnapshot(
				id,
				level.dimension().location(),
				state,
				anchorChunk,
				Optional.ofNullable(targetChunk),
				Optional.ofNullable(templateId),
				Optional.ofNullable(templateName),
				Optional.ofNullable(race),
				Optional.ofNullable(tier),
				unitSnaps,
				List.copyOf(attackTrackList),
				ClusterSnapshot.copyUuidListMap(attackToUnits),
				ClusterSnapshot.copyUuidListMap(globalPlayerMap));
	}

	void updateTickets() {
		if (disbanded) {
			dropTickets();
			return;
		}
		ChunkPos next = isHell()
				? (anchorChunk != null ? anchorChunk : targetChunk)
				: (targetChunk != null ? targetChunk : anchorChunk);
		if (next == null) {
			dropTickets();
			return;
		}
		if (next.equals(ticketCenter)) {
			return;
		}
		dropTickets();
		ticketCenter = next;
		SiegeChunkTickets.retain(level, ticketCenter);
	}

	void dropTickets() {
		if (ticketCenter != null) {
			SiegeChunkTickets.release(level, ticketCenter);
			ticketCenter = null;
		}
	}

	private void forgetUnit(UUID mobId) {
		removeUnitFromAttackMaps(mobId);
		SiegeAiHelper.discardVanillaBackup(mobId);
		SiegeClusterManager.unbindMobId(mobId);
		seenInWorld.remove(mobId);
	}

	private static void putUuidListMap(Map<UUID, List<UUID>> dest, Map<UUID, List<UUID>> src) {
		dest.clear();
		if (src == null) {
			return;
		}
		for (Map.Entry<UUID, List<UUID>> entry : src.entrySet()) {
			dest.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}
	}

	private void logState(SiegeClusterState from, String to, String reason) {
		SiegeSpawnMonitor.state(
				id, from, to, reason, level, tickCounter, anchorChunk, targetChunk, countLiving());
	}

	private int countLiving() {
		return units.size();
	}

	public int getLivingCount() {
		return countLiving();
	}

	private List<UUID> livingUnitIds() {
		return new ArrayList<>(units.keySet());
	}

	private Mob resolveMob(UUID id) {
		Entity entity = level.getEntity(id);
		if (entity instanceof Mob mob && mob.isAlive()) {
			return mob;
		}
		return null;
	}

	private LivingEntity resolveLiving(UUID id) {
		Entity entity = level.getEntity(id);
		if (entity instanceof LivingEntity living && living.isAlive()) {
			return living;
		}
		return null;
	}

	/** floor(log2(m))，调用方保证 m &gt;= 3。 */
	static int floorLog2(int m) {
		return 31 - Integer.numberOfLeadingZeros(m);
	}

	public static int chebyshevChunkDistance(ChunkPos a, ChunkPos b) {
		return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
	}

	private List<LivingEntity> findFriendlyInChunkRadius(ChunkPos center, int radius) {
		if (center == null) {
			return List.of();
		}
		int minX = (center.x - radius) << 4;
		int maxX = ((center.x + radius) << 4) + 16;
		int minZ = (center.z - radius) << 4;
		int maxZ = ((center.z + radius) << 4) + 16;
		AABB box = new AABB(minX, level.getMinY(), minZ, maxX, level.getMaxY() + 1, maxZ);
		List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class, box, SiegeTargeting::isValidAttackTarget);
		found.removeIf(e -> chebyshevChunkDistance(center, e.chunkPosition()) > radius);
		return found;
	}
}
