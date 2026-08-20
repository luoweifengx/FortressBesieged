package luowei.fortress_besieged.siege.pbs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
}
