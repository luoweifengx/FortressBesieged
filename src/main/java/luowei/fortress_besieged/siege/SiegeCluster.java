package luowei.fortress_besieged.siege;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.goal.SiegeAiHelper;
import luowei.fortress_besieged.siege.goal.SiegeTargetDecision;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.targeting.SiegeTargeting;
import luowei.player_block_status.lib.chunk.ChunkState;

/**
 * 单个围城集群：确定进攻区块、存活 tick、解散；ASSAULT 下任务由单位自行决策。
 */
public final class SiegeCluster {
	public static final int FREE_CHASE_RADIUS = 3; // 7×7 Chebyshev
	public static final int FREE_SCAN_RADIUS = 1;  // 3×3 Chebyshev

	private final UUID id = UUID.randomUUID();
	private final ServerLevel level;
	private final Random random = new Random();

	private SiegeClusterState state = SiegeClusterState.ASSEMBLE;
	private ChunkPos anchorChunk;
	private ChunkPos targetChunk;
	private boolean disbanded;

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

	public SiegeCluster(ServerLevel level, ChunkPos anchorChunk, List<Mob> mobs) {
		this.level = level;
		this.anchorChunk = anchorChunk;
		for (Mob mob : mobs) {
			if (mob == null || !mob.isAlive()) {
				continue;
			}
			units.put(mob.getUUID(), new SiegeUnitRecord(mob.getUUID()));
			SiegeAiHelper.install(mob, this);
		}
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
		if (record == null) {
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
		if (entity instanceof LivingEntity living && living.isAlive()) {
			return living;
		}
		return null;
	}

	public void tick() {
		if (disbanded) {
			return;
		}
		tickCounter++;
		pruneDeadUnits();
		int living = countLiving();
		if (living == 0) {
			disband(false);
			return;
		}
		if (state != SiegeClusterState.ASSEMBLE && living <= 2) {
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
		List<ChunkPos> candidates = PbsTerritoryAdapter.findAdjacentAssaultChunks(level, anchorChunk);
		if (candidates.isEmpty()) {
			return;
		}
		targetChunk = candidates.get(random.nextInt(candidates.size()));
		enterAssault();
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
		enterAssault();
	}

	private void enterAssault() {
		state = SiegeClusterState.ASSAULT;
		int living = countLiving();
		if (living <= 2) {
			disband(true);
			return;
		}
		clearAttackContainers();
		for (SiegeUnitRecord record : units.values()) {
			record.returnToIdle();
		}
	}

	private void tickAssault(int living) {
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

	/** 目标区块 3×3（含对角）无可破 PBS 块 → FREE。 */
	private void maybeTransitionToFreeWhenNoBlocks() {
		if (!SiegeTargetDecision.hasAnyBreakable(level, targetChunk)) {
			enterFree("no_breakable_blocks");
		}
	}

	private void enterFree(String reason) {
		FortressBesieged.LOGGER.info(
				"[siege] enter FREE cluster={} targetChunk={} reason={}",
				id,
				targetChunk,
				reason);
		state = SiegeClusterState.FREE;
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
			disband(true);
			return;
		}

		handleTargetsLeavingFreeZone();

		if (tickCounter % 40 != 0) {
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
		clearAttackContainers();
		for (SiegeUnitRecord record : units.values()) {
			record.clearAssignments();
			record.setMode(SiegeUnitMode.IDLE);
		}
		globalPlayerMap.clear();
		state = SiegeClusterState.ASSEMBLE;
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
			mob.setHealth(mob.getMaxHealth());

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
		if (disbanded || state != SiegeClusterState.FREE) {
			return;
		}
		if (!(attacker instanceof LivingEntity living) || !SiegeTargeting.isPlayerFriendly(living)) {
			return;
		}
		SiegeUnitRecord record = units.get(mob.getUUID());
		if (record == null) {
			return;
		}
		record.setCurrentChaseId(living.getUUID());
		record.setMode(SiegeUnitMode.ATTACK);
		globalPlayerMap.computeIfAbsent(living.getUUID(), k -> new ArrayList<>());
		List<UUID> list = globalPlayerMap.get(living.getUUID());
		if (!list.contains(mob.getUUID())) {
			list.add(mob.getUUID());
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
		int x = floorLog2(m);
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
			disband(true);
		}
	}

	private void disband(boolean switchToAttack) {
		disbanded = true;
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
		Iterator<Map.Entry<UUID, SiegeUnitRecord>> it = units.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, SiegeUnitRecord> entry = it.next();
			Mob mob = resolveMob(entry.getKey());
			if (mob == null || !mob.isAlive()) {
				removeUnitFromAttackMaps(entry.getKey());
				SiegeAiHelper.discardVanillaBackup(entry.getKey());
				SiegeClusterManager.unbindMobId(entry.getKey());
				it.remove();
			}
		}
	}

	private int countLiving() {
		int n = 0;
		for (UUID id : units.keySet()) {
			if (resolveMob(id) != null) {
				n++;
			}
		}
		return n;
	}

	private List<UUID> livingUnitIds() {
		List<UUID> ids = new ArrayList<>();
		for (UUID id : units.keySet()) {
			if (resolveMob(id) != null) {
				ids.add(id);
			}
		}
		return ids;
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

	static int chebyshevChunkDistance(ChunkPos a, ChunkPos b) {
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
		List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class, box, SiegeTargeting::isPlayerFriendly);
		found.removeIf(e -> chebyshevChunkDistance(center, e.chunkPosition()) > radius);
		return found;
	}
}
