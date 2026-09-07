package luowei.fortress_besieged.siege;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Sticky iron / iron-armor / diamond stage unlocks per poll-target id (org UUID or solo player UUID).
 * Stored on the overworld: {@code saves/&lt;world&gt;/data/fortress_besieged_progression.dat}.
 */
public final class SiegeProgressionSavedData extends SavedData {
	public static final String ID = "fortress_besieged_progression";

	public record TargetRecord(boolean hasIron, boolean hasIronArmor, boolean hasDiamond) {
		public static final Codec<TargetRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("hasIron", false).forGetter(TargetRecord::hasIron),
				Codec.BOOL.optionalFieldOf("hasIronArmor", false).forGetter(TargetRecord::hasIronArmor),
				Codec.BOOL.optionalFieldOf("hasDiamond", false).forGetter(TargetRecord::hasDiamond)
		).apply(instance, TargetRecord::new));
	}

	private static final Codec<SiegeProgressionSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, TargetRecord.CODEC)
					.optionalFieldOf("targets", Map.of())
					.forGetter(data -> Map.copyOf(data.targets))
	).apply(instance, SiegeProgressionSavedData::new));

	public static final SavedDataType<SiegeProgressionSavedData> TYPE = new SavedDataType<>(
			ID,
			context -> new SiegeProgressionSavedData(Map.of()),
			context -> CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<UUID, TargetRecord> targets;

	private SiegeProgressionSavedData(Map<UUID, TargetRecord> targets) {
		this.targets = new HashMap<>();
		if (targets != null) {
			this.targets.putAll(targets);
		}
	}

	public static SiegeProgressionSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public TargetRecord get(UUID targetId) {
		return targets.get(targetId);
	}

	public boolean hasIron(UUID targetId) {
		TargetRecord record = targets.get(targetId);
		return record != null && record.hasIron();
	}

	public boolean hasIronArmor(UUID targetId) {
		TargetRecord record = targets.get(targetId);
		return record != null && record.hasIronArmor();
	}

	public boolean hasDiamond(UUID targetId) {
		TargetRecord record = targets.get(targetId);
		return record != null && record.hasDiamond();
	}

	/** Sticky OR: once true, never reverts. */
	public void grant(UUID targetId, boolean iron, boolean ironArmor, boolean diamond) {
		if (targetId == null || (!iron && !ironArmor && !diamond)) {
			return;
		}
		TargetRecord prev = targets.get(targetId);
		boolean newIron = iron || (prev != null && prev.hasIron());
		boolean newArmor = ironArmor || (prev != null && prev.hasIronArmor());
		boolean newDiamond = diamond || (prev != null && prev.hasDiamond());
		if (prev != null
				&& prev.hasIron() == newIron
				&& prev.hasIronArmor() == newArmor
				&& prev.hasDiamond() == newDiamond) {
			return;
		}
		targets.put(targetId, new TargetRecord(newIron, newArmor, newDiamond));
		setDirty();
	}
}
