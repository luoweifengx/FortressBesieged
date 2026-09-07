package luowei.fortress_besieged.siege.pbs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import luowei.player_block_status.lib.api.PlayerBlockStatusLib;
import luowei.player_block_status.lib.api.TerritoryQueries;
import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.ChunkTerritoryAccess;
import luowei.player_block_status.lib.chunk.ChunkTerritoryData;
import luowei.player_block_status.lib.org.EntityPollList;

/**
 * PBS 领土适配：查询区块状态 / 玩家放置方块，并在非玩家破坏时同步移除记录。
 */
public final class PbsTerritoryAdapter {
	private static final int[][] CARDINAL = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1}
	};

	private PbsTerritoryAdapter() {
	}

	/**
	 * 围城目标列表：PBS {@link EntityPollList} 快照
	 *（未入组织的独立玩家 + 正式组织，同一轮询顺序）。
	 */
	public static List<UUID> listSiegeTargets(MinecraftServer server) {
		return PlayerBlockStatusLib.getEntityPollOrder(server);
	}

	/**
	 * 保护期跟踪的玩家 UUID：组织条目用 {@code OrganizationRecord.owner()}，
	 * 独立玩家条目用自身。查不到组织时按玩家 UUID 处理。
	 */
	public static UUID resolveProtectionPlayerId(MinecraftServer server, UUID pollTargetId) {
		if (server == null || pollTargetId == null) {
			return pollTargetId;
		}
		return PlayerBlockStatusLib.queryOrganization(server, pollTargetId)
				.map(org -> org.owner())
				.filter(owner -> owner != null)
				.orElse(pollTargetId);
	}

	/**
	 * Nearby player's poll target for protection / stage weights:
	 * org UUID if they are in an organization, otherwise the player UUID.
	 * Membership uses PBS {@code queryPlayerOrganization}; protection still goes
	 * through {@code SiegePlayerProtection.isTimeToBesiege} → {@link #resolveProtectionPlayerId}.
	 */
	public static UUID resolvePollTargetForPlayer(MinecraftServer server, UUID playerId) {
		if (server == null || playerId == null) {
			return playerId;
		}
		Optional<UUID> memberOf = PlayerBlockStatusLib.queryPlayerOrganization(server, playerId);
		if (memberOf.isPresent()) {
			return memberOf.get();
		}
		for (UUID pollId : listSiegeTargets(server)) {
			if (playerId.equals(pollId)) {
				return playerId;
			}
			if (PlayerBlockStatusLib.queryOrganization(server, pollId)
					.map(org -> playerId.equals(org.owner()))
					.orElse(false)) {
				return pollId;
			}
		}
		return playerId;
	}

	/**
	 * Player UUIDs whose iron/diamond advancements unlock a poll target:
	 * org owner + {@code members()} + currently online members; solo is the target itself.
	 */
	public static List<UUID> listProgressionPlayerIds(MinecraftServer server, UUID pollTargetId) {
		if (server == null || pollTargetId == null) {
			return List.of();
		}
		var org = PlayerBlockStatusLib.queryOrganization(server, pollTargetId);
		if (org.isEmpty()) {
			return List.of(pollTargetId);
		}
		Set<UUID> ids = new LinkedHashSet<>();
		UUID owner = org.get().owner();
		if (owner != null) {
			ids.add(owner);
		}
		var members = org.get().members();
		if (members != null) {
			ids.addAll(members);
		}
		for (var player : server.getPlayerList().getPlayers()) {
			if (pollTargetId.equals(
					PlayerBlockStatusLib.queryPlayerOrganization(server, player.getUUID()).orElse(null))) {
				ids.add(player.getUUID());
			}
		}
		return List.copyOf(ids);
	}

	/** 实体轮询列表对象（含 size / indexOf 等）。 */
	public static EntityPollList getEntityPollList(MinecraftServer server) {
		return PlayerBlockStatusLib.getEntityPollList(server);
	}

	/** 实体在轮询列表中的下标；不存在返回 -1。 */
	public static int indexOfSiegeTarget(MinecraftServer server, UUID entityId) {
		return PlayerBlockStatusLib.indexOfEntityInPollList(server, entityId);
	}

	public static List<ChunkPos> getOwnedChunks(ServerLevel level, UUID targetId) {
		TerritoryQueries.OrgTerritoryChunks territory = PlayerBlockStatusLib.queryOrgTerritory(level, targetId);
		return territory.allOwned();
	}

	/** 目标所属 BORDER 区块列表（EntityChunkIndex，不含 OCCUPIED）。 */
	public static List<ChunkPos> getBorderChunks(ServerLevel level, UUID targetId) {
		return PlayerBlockStatusLib.queryOrgTerritory(level, targetId).border();
	}

	/**
	 * 从所属 BORDER 中随机抽样最多 {@code count} 个区块（不足则全取；0 则空）。
	 * 抽到飞地边界是预期行为。
	 * <p>
	 * TODO: 加一个占领和边界的占比，来区分领土类型，改变集群类型和取边界区块数
	 */
	public static List<ChunkPos> sampleBorderChunks(List<ChunkPos> borders, int count, Random random) {
		if (borders == null || borders.isEmpty() || count <= 0 || random == null) {
			return List.of();
		}
		if (borders.size() <= count) {
			return List.copyOf(borders);
		}
		List<ChunkPos> copy = new ArrayList<>(borders);
		for (int i = 0; i < count; i++) {
			int j = i + random.nextInt(copy.size() - i);
			ChunkPos tmp = copy.get(i);
			copy.set(i, copy.get(j));
			copy.set(j, tmp);
		}
		return List.copyOf(copy.subList(0, count));
	}

	/**
	 * 领土区块中心（PBS {@link PlayerBlockStatusLib#queryTerritoryCentroid}）。
	 * 无 OCCUPIED/BORDER 时返回 empty。
	 */
	public static Optional<ChunkPos> getTerritoryCenter(ServerLevel level, UUID targetId) {
		return PlayerBlockStatusLib.queryTerritoryCentroid(level, targetId)
				.map(TerritoryQueries.TerritoryCentroid::center);
	}

	/** @see #getTerritoryCenter(ServerLevel, UUID) */
	public static Optional<TerritoryQueries.TerritoryCentroid> getTerritoryCentroid(
			ServerLevel level,
			UUID targetId
	) {
		return PlayerBlockStatusLib.queryTerritoryCentroid(level, targetId);
	}

	/**
	 * Nearest owned chunk (OCCUPIED/BORDER list) to {@code from} by Chebyshev distance.
	 * Used as the cluster's initial assault {@code targetChunk}.
	 */
	public static ChunkPos findNearestOwnedChunk(List<ChunkPos> owned, ChunkPos from) {
		if (owned == null || owned.isEmpty() || from == null) {
			return null;
		}
		ChunkPos best = null;
		int bestDist = Integer.MAX_VALUE;
		for (ChunkPos chunk : owned) {
			int dist = Math.max(Math.abs(chunk.x - from.x), Math.abs(chunk.z - from.z));
			if (dist < bestDist) {
				bestDist = dist;
				best = chunk;
			}
		}
		return best;
	}

	/**
	 * 从目标 BORDER 抽样 → 切比雪夫外扩搜集 HOSTILE_BORDER → 按距领土中心排序截断。
	 * <p>
	 * 流程：
	 * <ol>
	 *   <li>{@link #sampleBorderChunks} 随机取最多 {@code borderSampleCount} 个 BORDER</li>
	 *   <li>对每个采样边界，在切比雪夫距离 ≤ {@code expandChebyshev}
	 *       内查找 {@code HOSTILE_BORDER}（O(采样数 × (2r+1)²)，避免大半径扫描）</li>
	 *   <li>按距 {@code center} 切比雪夫距离排序，最多保留 {@code limit} 个</li>
	 * </ol>
	 * TODO: 加一个占领和边界的占比，来区分领土类型，改变集群类型和取边界区块数
	 */
	public static List<ChunkPos> findNearestHostileBorders(
			ServerLevel level,
			ChunkPos center,
			int limit,
			UUID ownerId,
			Random random,
			int borderSampleCount,
			int expandChebyshev
	) {
		if (center == null || limit <= 0 || ownerId == null || random == null
				|| borderSampleCount <= 0 || expandChebyshev < 0) {
			return List.of();
		}
		List<ChunkPos> borders = getBorderChunks(level, ownerId);
		List<ChunkPos> sampled = sampleBorderChunks(borders, borderSampleCount, random);
		if (sampled.isEmpty()) {
			return List.of();
		}

		Set<Long> seen = new HashSet<>();
		List<ChunkPos> candidates = new ArrayList<>();
		for (ChunkPos border : sampled) {
			for (int dx = -expandChebyshev; dx <= expandChebyshev; dx++) {
				for (int dz = -expandChebyshev; dz <= expandChebyshev; dz++) {
					ChunkPos candidate = new ChunkPos(border.x + dx, border.z + dz);
					if (!seen.add(candidate.toLong())) {
						continue;
					}
					if (getState(level, candidate) != ChunkState.HOSTILE_BORDER) {
						continue;
					}
					candidates.add(candidate);
				}
			}
		}

		candidates.sort(Comparator
				.comparingInt((ChunkPos pos) -> chebyshev(center, pos))
				.thenComparingInt(pos -> pos.x)
				.thenComparingInt(pos -> pos.z));
		if (candidates.size() > limit) {
			return List.copyOf(candidates.subList(0, limit));
		}
		return List.copyOf(candidates);
	}

	private static int chebyshev(ChunkPos a, ChunkPos b) {
		return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
	}

	/** 候选 HOSTILE_BORDER 是否紧贴该占领实体的 BORDER/OCCUPIED。 */
	public static boolean isHostileBorderOf(ServerLevel level, ChunkPos hostile, UUID ownerId) {
		if (getState(level, hostile) != ChunkState.HOSTILE_BORDER) {
			return false;
		}
		for (int[] d : CARDINAL) {
			ChunkPos neighbor = new ChunkPos(hostile.x + d[0], hostile.z + d[1]);
			ChunkState state = getState(level, neighbor);
			if (!isAssaultable(state)) {
				continue;
			}
			UUID occupying = PlayerBlockStatusLib.queryChunkView(level, neighbor)
					.map(view -> view.getOccupyingOrg())
					.orElse(null);
			if (ownerId.equals(occupying)) {
				return true;
			}
		}
		return false;
	}

	public static ChunkState getState(ServerLevel level, ChunkPos chunkPos) {
		return PlayerBlockStatusLib.queryChunkState(level, chunkPos).orElse(ChunkState.NATURAL);
	}

	public static Optional<UUID> getOccupyingOrg(ServerLevel level, ChunkPos chunkPos) {
		if (level == null || chunkPos == null) {
			return Optional.empty();
		}
		return PlayerBlockStatusLib.queryChunkView(level, chunkPos)
				.map(view -> view.getOccupyingOrg())
				.filter(id -> id != null);
	}

	public static boolean isAssaultable(ChunkState state) {
		return state == ChunkState.OCCUPIED || state == ChunkState.BORDER;
	}

	public static boolean isAssaultable(ServerLevel level, ChunkPos chunkPos) {
		return isAssaultable(getState(level, chunkPos));
	}

	/** 4-邻接（不含对角、不含自身）中状态为 OCCUPIED 或 BORDER 的区块。 */
	public static List<ChunkPos> findAdjacentAssaultChunks(ServerLevel level, ChunkPos center) {
		List<ChunkPos> result = new ArrayList<>(4);
		for (int[] d : CARDINAL) {
			ChunkPos neighbor = new ChunkPos(center.x + d[0], center.z + d[1]);
			if (isAssaultable(level, neighbor)) {
				result.add(neighbor);
			}
		}
		return result;
	}

	/**
	 * 0.2.x 公开 {@code queryChunk} 只返回 {@code ChunkTerritoryView}（无放置坐标）。
	 * 破块目标仍需要内部 attachment 上的坐标表。
	 */
	public static Optional<ChunkTerritoryData> queryChunkData(ServerLevel level, ChunkPos chunkPos) {
		return Optional.ofNullable(ChunkTerritoryAccess.getIfPresent(level, chunkPos));
	}

	/** 目标区块内 PBS 登记的玩家放置方块全局坐标（可破坏目标）。 */
	public static List<BlockPos> getBreakablePlacedBlocks(ServerLevel level, ChunkPos chunkPos) {
		Optional<ChunkTerritoryData> data = queryChunkData(level, chunkPos);
		if (data.isEmpty()) {
			return List.of();
		}
		List<BlockPos> positions = data.get().getPlacedBlockGlobalPositions(chunkPos.toLong());
		List<BlockPos> breakable = new ArrayList<>(positions.size());
		for (BlockPos pos : positions) {
			BlockState state = level.getBlockState(pos);
			if (!state.isAir() && state.getDestroySpeed(level, pos) >= 0.0F) {
				breakable.add(pos.immutable());
			}
		}
		return breakable;
	}

	public static boolean isPlayerPlacedBlock(ServerLevel level, BlockPos pos) {
		ChunkPos chunkPos = new ChunkPos(pos);
		return queryChunkData(level, chunkPos)
				.map(data -> data.getPlacedBlockOwner(pos) != null)
				.orElse(false);
	}

	/**
	 * 怪物破块后调用：走 PBS 公开 API {@link PlayerBlockStatusLib#notifyTrackedBlockRemoved}。
	 * 经 {@code LevelChunk#setBlockState} 的破坏（含 {@code destroyBlock}）会由 PBS mixin 自动通知；
	 * 本方法幂等，绕过该入口时仍需手动调用。
	 */
	public static void notifyBlockRemoved(ServerLevel level, BlockPos pos) {
		PlayerBlockStatusLib.notifyTrackedBlockRemoved(level, pos);
	}

	/**
	 * 目标实体（组织或独立玩家）的占领 / 边界 / 持有合计。
	 * {@code owned} = 该目标 OCCUPIED + BORDER，不是全图扫描。
	 */
	public static TerritoryQueries.EntityTerritoryCounts queryEntityTerritoryCounts(
			ServerLevel level,
			UUID targetId
	) {
		return PlayerBlockStatusLib.queryEntityTerritoryCounts(level, targetId);
	}

	/** {@link TerritoryQueries.EntityTerritoryCounts#owned()}：OCCUPIED + BORDER。 */
	public static int queryOwnedCount(ServerLevel level, UUID targetId) {
		return queryEntityTerritoryCounts(level, targetId).owned();
	}

	/** 当前维度全部恶魔区块（PBS 附属状态，本模组只读）。 */
	public static List<ChunkPos> queryDemonChunks(ServerLevel level) {
		return PlayerBlockStatusLib.queryDemonChunks(level);
	}

	public static boolean hasDemonChunks(ServerLevel level) {
		List<ChunkPos> chunks = queryDemonChunks(level);
		return chunks != null && !chunks.isEmpty();
	}

	public static boolean isDemonChunk(ServerLevel level, ChunkPos chunkPos) {
		return getState(level, chunkPos) == ChunkState.DEMON;
	}

	/**
	 * 从恶魔区块列表抽锚点（地狱档专用，不走 HOSTILE_BORDER Bernoulli）。
	 * 抽中后可再在切比雪夫 ≤ {@code expandChebyshev} 内找 HOSTILE_BORDER 作为实际落点；
	 * 没有邻接敌对边界时直接落在恶魔区块上。
	 */
	public static List<ChunkPos> sampleDemonAnchorSites(
			ServerLevel level,
			int limit,
			Random random,
			int expandChebyshev
	) {
		List<ChunkPos> demons = queryDemonChunks(level);
		if (demons == null || demons.isEmpty() || limit <= 0 || random == null) {
			return List.of();
		}
		List<ChunkPos> shuffled = new ArrayList<>(demons);
		for (int i = shuffled.size() - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			ChunkPos tmp = shuffled.get(i);
			shuffled.set(i, shuffled.get(j));
			shuffled.set(j, tmp);
		}
		int take = Math.min(limit, shuffled.size());
		if (expandChebyshev < 0) {
			return List.copyOf(shuffled.subList(0, take));
		}
		Set<Long> seen = new HashSet<>();
		List<ChunkPos> sites = new ArrayList<>(take);
		for (int i = 0; i < take; i++) {
			ChunkPos demon = shuffled.get(i);
			ChunkPos site = pickHostileBorderNear(level, demon, expandChebyshev, seen, random);
			sites.add(site != null ? site : demon);
		}
		return List.copyOf(sites);
	}

	private static ChunkPos pickHostileBorderNear(
			ServerLevel level,
			ChunkPos origin,
			int expandChebyshev,
			Set<Long> seen,
			Random random
	) {
		List<ChunkPos> near = new ArrayList<>();
		for (int dx = -expandChebyshev; dx <= expandChebyshev; dx++) {
			for (int dz = -expandChebyshev; dz <= expandChebyshev; dz++) {
				ChunkPos candidate = new ChunkPos(origin.x + dx, origin.z + dz);
				if (getState(level, candidate) == ChunkState.HOSTILE_BORDER && seen.add(candidate.toLong())) {
					near.add(candidate);
				}
			}
		}
		if (near.isEmpty()) {
			return seen.add(origin.toLong()) ? origin : null;
		}
		return near.get(random.nextInt(near.size()));
	}
}
