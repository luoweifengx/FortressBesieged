package luowei.fortress_besieged.siege;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * Invasion horn on spawn (once per wave) and victory sting when a cluster ends by wipe or ≤2 left.
 */
public final class SiegeSounds {
	private static final float HORN_WORLD_VOLUME = 4.0F;
	private static final float BELL_WORLD_VOLUME = 2.0F;
	private static final float BELL_PITCH = 1.15F;

	private SiegeSounds() {
	}

	public static void playInvasionHorn(ServerLevel level, UUID targetId, List<SiegeCluster> clusters) {
		if (level == null || clusters == null || clusters.isEmpty()) {
			return;
		}
		SiegeCluster first = clusters.get(0);
		playWorld(level, first.getAnchorChunk(), SoundEvents.RAID_HORN, SoundSource.HOSTILE, HORN_WORLD_VOLUME, 1.0F);
		notifyDefenders(level, resolveDefenderIds(level, targetId, first), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.0F, 1.0F);
	}

	/** Victory for {@code no_living} and {@code living_le_2}. Not {@code force}. */
	public static void playVictory(SiegeCluster cluster) {
		if (cluster == null || cluster.getLevel() == null) {
			return;
		}
		ServerLevel level = cluster.getLevel();
		ChunkPos at = cluster.getTargetChunk() != null ? cluster.getTargetChunk() : cluster.getAnchorChunk();
		playWorld(level, at, SoundEvents.BELL_RESONATE, SoundSource.NEUTRAL, BELL_WORLD_VOLUME, BELL_PITCH);
		Set<UUID> defenders = resolveDefenderIds(level, cluster.getSiegeTargetId(), cluster);
		notifyDefenders(level, defenders, SoundEvents.BELL_RESONATE, SoundSource.NEUTRAL, 1.0F, BELL_PITCH);
		notifyDefenders(level, defenders, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
	}

	private static Set<UUID> resolveDefenderIds(ServerLevel level, UUID targetId, SiegeCluster cluster) {
		Set<UUID> ids = new HashSet<>();
		if (targetId != null) {
			ids.add(targetId);
			UUID owner = PbsTerritoryAdapter.resolveProtectionPlayerId(level.getServer(), targetId);
			if (owner != null) {
				ids.add(owner);
			}
		}
		ChunkPos occupied = cluster != null && cluster.getTargetChunk() != null
				? cluster.getTargetChunk()
				: (cluster == null ? null : cluster.getAnchorChunk());
		PbsTerritoryAdapter.getOccupyingOrg(level, occupied).ifPresent(orgId -> {
			ids.add(orgId);
			UUID owner = PbsTerritoryAdapter.resolveProtectionPlayerId(level.getServer(), orgId);
			if (owner != null) {
				ids.add(owner);
			}
		});
		return ids;
	}

	private static void notifyDefenders(
			ServerLevel level,
			Set<UUID> ids,
			SoundEvent sound,
			SoundSource source,
			float volume,
			float pitch
	) {
		MinecraftServer server = level.getServer();
		if (server == null || ids == null || sound == null) {
			return;
		}
		for (UUID id : ids) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) {
				player.playNotifySound(sound, source, volume, pitch);
			}
		}
	}

	private static void playWorld(
			ServerLevel level,
			ChunkPos chunk,
			Holder<SoundEvent> sound,
			SoundSource source,
			float volume,
			float pitch
	) {
		if (chunk == null) {
			return;
		}
		double x = (chunk.x << 4) + 8.5D;
		double z = (chunk.z << 4) + 8.5D;
		double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
		level.playSeededSound(null, x, y, z, sound, source, volume, pitch, level.random.nextLong());
	}

	private static void playWorld(
			ServerLevel level,
			ChunkPos chunk,
			SoundEvent sound,
			SoundSource source,
			float volume,
			float pitch
	) {
		if (chunk == null || sound == null) {
			return;
		}
		double x = (chunk.x << 4) + 8.5D;
		double z = (chunk.z << 4) + 8.5D;
		double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
		level.playSound(null, x, y, z, sound, source, volume, pitch);
	}
}
