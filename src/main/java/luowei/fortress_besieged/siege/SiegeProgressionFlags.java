package luowei.fortress_besieged.siege;

import java.util.UUID;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * Stage gates: sticky {@code hasIron} / {@code hasDiamond} on the poll-target id.
 * Iron = {@code minecraft:story/smelt_iron}（来硬的）；diamond = {@code minecraft:story/mine_diamond}.
 */
public final class SiegeProgressionFlags {
	public static final ResourceLocation ADV_SMELT_IRON =
			ResourceLocation.withDefaultNamespace("story/smelt_iron");
	public static final ResourceLocation ADV_MINE_DIAMOND =
			ResourceLocation.withDefaultNamespace("story/mine_diamond");

	private SiegeProgressionFlags() {
	}

	public static void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
		if (server == null || player == null) {
			return;
		}
		boolean iron = hasAdvancement(player, ADV_SMELT_IRON);
		boolean diamond = hasAdvancement(player, ADV_MINE_DIAMOND);
		if (!iron && !diamond) {
			return;
		}
		UUID playerId = player.getUUID();
		SiegeProgressionSavedData data = SiegeProgressionSavedData.get(server);
		data.grant(playerId, iron, diamond);
		UUID pollTargetId = PbsTerritoryAdapter.resolvePollTargetForPlayer(server, playerId);
		if (pollTargetId != null && !pollTargetId.equals(playerId)) {
			data.grant(pollTargetId, iron, diamond);
		}
	}

	/**
	 * Re-check online members and OR in per-player stickies onto the poll target.
	 * Call before reading {@link #hasIron} / {@link #hasDiamond} for a snapshot.
	 */
	public static void refresh(MinecraftServer server, UUID pollTargetId) {
		if (server == null || pollTargetId == null) {
			return;
		}
		SiegeProgressionSavedData data = SiegeProgressionSavedData.get(server);
		if (data.hasIron(pollTargetId) && data.hasDiamond(pollTargetId)) {
			return;
		}
		boolean iron = data.hasIron(pollTargetId);
		boolean diamond = data.hasDiamond(pollTargetId);
		for (UUID playerId : PbsTerritoryAdapter.listProgressionPlayerIds(server, pollTargetId)) {
			iron |= data.hasIron(playerId);
			diamond |= data.hasDiamond(playerId);
			ServerPlayer online = server.getPlayerList().getPlayer(playerId);
			if (online != null) {
				boolean playerIron = hasAdvancement(online, ADV_SMELT_IRON);
				boolean playerDiamond = hasAdvancement(online, ADV_MINE_DIAMOND);
				if (playerIron || playerDiamond) {
					data.grant(playerId, playerIron, playerDiamond);
				}
				iron |= playerIron;
				diamond |= playerDiamond;
			}
			if (iron && diamond) {
				break;
			}
		}
		data.grant(pollTargetId, iron, diamond);
	}

	public static boolean hasIron(MinecraftServer server, UUID pollTargetId) {
		if (server == null || pollTargetId == null) {
			return false;
		}
		return SiegeProgressionSavedData.get(server).hasIron(pollTargetId);
	}

	public static boolean hasDiamond(MinecraftServer server, UUID pollTargetId) {
		if (server == null || pollTargetId == null) {
			return false;
		}
		return SiegeProgressionSavedData.get(server).hasDiamond(pollTargetId);
	}

	public static boolean hasAdvancement(ServerPlayer player, ResourceLocation id) {
		if (player == null || id == null) {
			return false;
		}
		AdvancementHolder holder = player.getServer().getAdvancements().get(id);
		if (holder == null) {
			return false;
		}
		return player.getAdvancements().getOrStartProgress(holder).isDone();
	}
}
