package luowei.fortress_besieged.siege;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import luowei.fortress_besieged.siege.SiegePlayerProtectionSavedData.PlayerRecord;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnConfig;

/**
 * 新玩家围城保护期：按玩家 UUID 记 {@code daysInGame} / {@code isTimeToBesiege}。
 * 轮询条目若是组织，查 owner 的字段。记录 sticky，退组织/再入表不重置。
 */
public final class SiegePlayerProtection {
	private SiegePlayerProtection() {
	}

	public static void ensurePlayer(MinecraftServer server, UUID playerId) {
		if (server == null || playerId == null) {
			return;
		}
		SiegePlayerProtectionSavedData.get(server).ensure(playerId);
	}

	/** 主世界跨午夜：已记录玩家 {@code daysInGame++}，再更新 sticky bool。 */
	public static void onOverworldDayAdvance(MinecraftServer server) {
		if (server == null) {
			return;
		}
		SiegePlayerProtectionSavedData.get(server).advanceDays(SiegeSpawnConfig.NEW_PLAYER_PROTECTION_DAYS);
	}

	/**
	 * 轮询目标（独立玩家或组织 UUID）当前是否允许自然围城 / 命令波抽样。
	 * 无记录则按首次见到建档（days=0, false），不重置已有档。
	 */
	public static boolean isTimeToBesiege(MinecraftServer server, UUID pollTargetId) {
		UUID playerId = PbsTerritoryAdapter.resolveProtectionPlayerId(server, pollTargetId);
		if (server == null || playerId == null) {
			return false;
		}
		SiegePlayerProtectionSavedData data = SiegePlayerProtectionSavedData.get(server);
		PlayerRecord record = data.ensure(playerId);
		if (record.isTimeToBesiege()) {
			return true;
		}
		int threshold = SiegeSpawnConfig.NEW_PLAYER_PROTECTION_DAYS;
		if (record.daysInGame() >= threshold) {
			data.put(playerId, new PlayerRecord(record.daysInGame(), true));
			return true;
		}
		return false;
	}

	public static int daysInGame(MinecraftServer server, UUID pollTargetId) {
		UUID playerId = PbsTerritoryAdapter.resolveProtectionPlayerId(server, pollTargetId);
		if (server == null || playerId == null) {
			return 0;
		}
		PlayerRecord record = SiegePlayerProtectionSavedData.get(server).get(playerId);
		return record == null ? 0 : record.daysInGame();
	}
}
