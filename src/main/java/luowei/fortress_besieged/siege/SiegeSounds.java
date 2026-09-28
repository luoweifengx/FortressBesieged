package luowei.fortress_besieged.siege;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnMonitor;

/**
 * Seek goat horn on spawn (belonging players only, 3D at the spawn chunk) and
 * victory sting when a cluster ends by wipe or ≤2 left.
 */
public final class SiegeSounds {
	/** Vanilla goat horn is volume 16 (~256 blocks); farther so a large territory still hears it. */
	private static final float HORN_VOLUME = 32.0F;
	private static final float LEVELUP_WORLD_VOLUME = 1.0F;

	private SiegeSounds() {
	}

	/**
	 * Per spawned cluster: Seek horn at the anchor (same-dimension belonging players)
	 * and a chat line with region + compass from territory center to that anchor.
	 */
	public static void playInvasionHorn(
			ServerLevel level,
			UUID targetId,
			ChunkPos territoryCenter,
			List<SiegeCluster> clusters
	) {
		if (level == null || clusters == null || clusters.isEmpty()) {
			return;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return;
		}
		Holder<SoundEvent> horn = SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(2);
		List<UUID> belonging = PbsTerritoryAdapter.listProgressionPlayerIds(server, targetId);
		String region = PbsTerritoryAdapter.regionDisplayName(server, targetId);
		for (SiegeCluster cluster : clusters) {
			if (cluster == null) {
				continue;
			}
			ChunkPos spawn = cluster.getAnchorChunk();
			String dir = SiegeSpawnMonitor.compassCn(territoryCenter, spawn);
			Component message = Component.literal("附近".equals(dir)
					? "在「" + region + "」附近生成了集群"
					: "在「" + region + "」的" + dir + "生成了集群");
			for (UUID id : belonging) {
				ServerPlayer player = server.getPlayerList().getPlayer(id);
				if (player == null) {
					continue;
				}
				player.sendSystemMessage(message);
				if (spawn != null && player.serverLevel() == level) {
					playHornAt(player, level, spawn, horn);
				}
			}
		}
	}

	/**
	 * Hell-tier: server-wide chat with block coordinates (not belonging-only).
	 */
	public static void broadcastHellSpawn(ServerLevel level, ChunkPos spawn) {
		if (level == null || spawn == null) {
			return;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return;
		}
		int x = spawn.getMiddleBlockX();
		int z = spawn.getMiddleBlockZ();
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		Component message = Component.literal(
				"地狱级别怪物已在" + dimensionCn(level) + " (" + x + ", " + y + ", " + z + ") 生成");
		server.getPlayerList().broadcastSystemMessage(message, false);
		playWorld(level, spawn, SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(2).value(), SoundSource.RECORDS, HORN_VOLUME, 1.0F);
	}

	private static String dimensionCn(ServerLevel level) {
		if (level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
			return "主世界";
		}
		if (level.dimension().equals(net.minecraft.world.level.Level.NETHER)) {
			return "下界";
		}
		if (level.dimension().equals(net.minecraft.world.level.Level.END)) {
			return "末地";
		}
		return level.dimension().location().toString();
	}

	/** Victory for {@code no_living} and {@code living_le_2}. Not {@code force}. */
	public static void playVictory(SiegeCluster cluster) {
		if (cluster == null || cluster.getLevel() == null) {
			return;
		}
		ServerLevel level = cluster.getLevel();
		ChunkPos at = cluster.getTargetChunk() != null ? cluster.getTargetChunk() : cluster.getAnchorChunk();
		playWorld(level, at, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, LEVELUP_WORLD_VOLUME, 1.0F);
		Set<UUID> defenders = resolveDefenderIds(level, cluster.getSiegeTargetId(), cluster);
		notifyDefenders(level, defenders, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
	}

	private static void playHornAt(
			ServerPlayer player,
			ServerLevel level,
			ChunkPos chunk,
			Holder<SoundEvent> sound
	) {
		double x = (chunk.x << 4) + 8.5D;
		double z = (chunk.z << 4) + 8.5D;
		double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
		player.connection.send(new ClientboundSoundPacket(
				sound,
				SoundSource.RECORDS,
				x,
				y,
				z,
				HORN_VOLUME,
				1.0F,
				level.random.nextLong()));
	}

	private static Set<UUID> resolveDefenderIds(ServerLevel level, UUID targetId, SiegeCluster cluster) {
		Set<UUID> ids = new HashSet<>();
		if (targetId != null) {
			ids.addAll(PbsTerritoryAdapter.listProgressionPlayerIds(level.getServer(), targetId));
		}
		ChunkPos occupied = cluster != null && cluster.getTargetChunk() != null
				? cluster.getTargetChunk()
				: (cluster == null ? null : cluster.getAnchorChunk());
		PbsTerritoryAdapter.getOccupyingOrg(level, occupied).ifPresent(orgId -> {
			ids.addAll(PbsTerritoryAdapter.listProgressionPlayerIds(level.getServer(), orgId));
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
