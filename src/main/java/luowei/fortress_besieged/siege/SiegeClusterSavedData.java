package luowei.fortress_besieged.siege;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 维度级集群存档。文件：{@code <dimension>/data/fortress_besieged_clusters.dat}
 *（主世界在 {@code saves/<world>/data/}）。
 */
public final class SiegeClusterSavedData extends SavedData {
	public static final String ID = "fortress_besieged_clusters";

	private static final Codec<SiegeClusterState> STATE_CODEC = Codec.STRING.xmap(
			s -> {
				try {
					return SiegeClusterState.valueOf(s);
				} catch (IllegalArgumentException e) {
					return SiegeClusterState.ASSEMBLE;
				}
			},
			Enum::name);

	private static final Codec<SiegeUnitMode> MODE_CODEC = Codec.STRING.xmap(
			s -> {
				try {
					return SiegeUnitMode.valueOf(s);
				} catch (IllegalArgumentException e) {
					return SiegeUnitMode.IDLE;
				}
			},
			Enum::name);

	private static final Codec<Map<UUID, List<UUID>>> UUID_LIST_MAP_CODEC =
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, UUIDUtil.CODEC.listOf());

	public static final Codec<UnitSnapshot> UNIT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("mobId").forGetter(UnitSnapshot::mobId),
			MODE_CODEC.fieldOf("mode").forGetter(UnitSnapshot::mode),
			BlockPos.CODEC.optionalFieldOf("trackedBlock").forGetter(UnitSnapshot::trackedBlock),
			UUIDUtil.CODEC.optionalFieldOf("initialChaseId").forGetter(UnitSnapshot::initialChaseId),
			UUIDUtil.CODEC.optionalFieldOf("currentChaseId").forGetter(UnitSnapshot::currentChaseId),
			Codec.BOOL.optionalFieldOf("firstTargetRollDone", false).forGetter(UnitSnapshot::firstTargetRollDone)
	).apply(instance, UnitSnapshot::new));

	public static final Codec<ClusterSnapshot> CLUSTER_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(ClusterSnapshot::id),
			ResourceLocation.CODEC.fieldOf("dimension").forGetter(ClusterSnapshot::dimension),
			STATE_CODEC.fieldOf("state").forGetter(ClusterSnapshot::state),
			ChunkPos.CODEC.fieldOf("anchorChunk").forGetter(ClusterSnapshot::anchorChunk),
			ChunkPos.CODEC.optionalFieldOf("targetChunk").forGetter(ClusterSnapshot::targetChunk),
			Codec.STRING.optionalFieldOf("templateId").forGetter(ClusterSnapshot::templateId),
			Codec.STRING.optionalFieldOf("templateName").forGetter(ClusterSnapshot::templateName),
			Codec.STRING.optionalFieldOf("race").forGetter(ClusterSnapshot::race),
			Codec.STRING.optionalFieldOf("tier").forGetter(ClusterSnapshot::tier),
			UNIT_CODEC.listOf().fieldOf("units").forGetter(ClusterSnapshot::units),
			UUIDUtil.CODEC.listOf().optionalFieldOf("attackTrackList", List.of())
					.forGetter(ClusterSnapshot::attackTrackList),
			UUID_LIST_MAP_CODEC.optionalFieldOf("attackToUnits", Map.of())
					.forGetter(ClusterSnapshot::attackToUnits),
			UUID_LIST_MAP_CODEC.optionalFieldOf("globalPlayerMap", Map.of())
					.forGetter(ClusterSnapshot::globalPlayerMap)
	).apply(instance, ClusterSnapshot::new));

	private static final Codec<SiegeClusterSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			CLUSTER_CODEC.listOf().fieldOf("clusters").forGetter(SiegeClusterSavedData::clusters)
	).apply(instance, SiegeClusterSavedData::new));

	public static final SavedDataType<SiegeClusterSavedData> TYPE = new SavedDataType<>(
			ID,
			ctx -> new SiegeClusterSavedData(List.of()),
			ctx -> CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private List<ClusterSnapshot> clusters;

	private SiegeClusterSavedData(List<ClusterSnapshot> clusters) {
		this.clusters = clusters == null ? List.of() : List.copyOf(clusters);
	}

	public static SiegeClusterSavedData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	public List<ClusterSnapshot> clusters() {
		return clusters;
	}

	public void replace(List<ClusterSnapshot> next) {
		this.clusters = next == null ? List.of() : List.copyOf(next);
		setDirty();
	}

	public record UnitSnapshot(
			UUID mobId,
			SiegeUnitMode mode,
			Optional<BlockPos> trackedBlock,
			Optional<UUID> initialChaseId,
			Optional<UUID> currentChaseId,
			boolean firstTargetRollDone
	) {
		public static UnitSnapshot from(SiegeUnitRecord record) {
			return new UnitSnapshot(
					record.getMobId(),
					record.getMode(),
					Optional.ofNullable(record.getTrackedBlock()),
					Optional.ofNullable(record.getInitialChaseId()),
					Optional.ofNullable(record.getCurrentChaseId()),
					record.isFirstTargetRollDone());
		}

		public SiegeUnitRecord toRecord() {
			SiegeUnitRecord record = new SiegeUnitRecord(mobId);
			record.setMode(mode);
			trackedBlock.ifPresent(record::setTrackedBlock);
			initialChaseId.ifPresent(record::setInitialChaseId);
			currentChaseId.ifPresent(record::setCurrentChaseId);
			record.setFirstTargetRollDone(firstTargetRollDone);
			return record;
		}
	}

	public record ClusterSnapshot(
			UUID id,
			ResourceLocation dimension,
			SiegeClusterState state,
			ChunkPos anchorChunk,
			Optional<ChunkPos> targetChunk,
			Optional<String> templateId,
			Optional<String> templateName,
			Optional<String> race,
			Optional<String> tier,
			List<UnitSnapshot> units,
			List<UUID> attackTrackList,
			Map<UUID, List<UUID>> attackToUnits,
			Map<UUID, List<UUID>> globalPlayerMap
	) {
		public ClusterSnapshot {
			units = units == null ? List.of() : List.copyOf(units);
			attackTrackList = attackTrackList == null ? List.of() : List.copyOf(attackTrackList);
			attackToUnits = copyUuidListMap(attackToUnits);
			globalPlayerMap = copyUuidListMap(globalPlayerMap);
			targetChunk = targetChunk == null ? Optional.empty() : targetChunk;
			templateId = templateId == null ? Optional.empty() : templateId;
			templateName = templateName == null ? Optional.empty() : templateName;
			race = race == null ? Optional.empty() : race;
			tier = tier == null ? Optional.empty() : tier;
		}

		static Map<UUID, List<UUID>> copyUuidListMap(Map<UUID, List<UUID>> src) {
			if (src == null || src.isEmpty()) {
				return Map.of();
			}
			Map<UUID, List<UUID>> out = new HashMap<>();
			for (Map.Entry<UUID, List<UUID>> entry : src.entrySet()) {
				out.put(entry.getKey(), new ArrayList<>(entry.getValue()));
			}
			return out;
		}
	}
}
