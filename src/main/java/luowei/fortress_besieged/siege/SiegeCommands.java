package luowei.fortress_besieged.siege;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;

import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.structure.MedievalCastleBuilder;
import luowei.fortress_besieged.siege.spawn.SiegeClusterConfig;
import luowei.fortress_besieged.siege.spawn.SiegeClusterSpawner;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnConfig;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnMonitor;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnSiteSelector;
import luowei.fortress_besieged.siege.spawn.SiegeTargetSampler;
import luowei.fortress_besieged.siege.spawn.SiegeTemplateSelector;
import luowei.player_block_status.lib.api.PlayerBlockStatusLib;

/**
 * Debug commands.
 *
 * <pre>
 * /siege spawn [recipe]           — sample a proportion of poll targets, Bernoulli sites, spawn
 * /siege spawn entity &lt;uuid&gt; [recipe] — one org / solo-player UUID
 * /siege spawn self [recipe]      — executor player UUID
 * /siege sites [uuid]             — preview candidates + Bernoulli roll (E = n×p)
 * /siege poll                     — list siege target UUIDs
 * /siege recipes                  — list siege and wilderness templates (and legacy aliases)
 * /siege create [chunkRadius]     — legacy: absorb nearby natural monsters
 * /siege castle                   — place the blueprint courtyard castle in front of the player
 * /siege status
 * /siege disband
 * </pre>
 * {@code recipe} 可为模板 id，或旧配方 {@code default}/{@code light}/{@code heavy}
 *（default=按阶段抽取，light=路边野尸，heavy=一群亡灵）。
 */
public final class SiegeCommands {
	private static final SuggestionProvider<CommandSourceStack> RECIPE_SUGGESTIONS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(SiegeTemplateSelector.suggestionIds(), builder);

	private SiegeCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("siege")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("spawn")
						.executes(ctx -> spawnWave(ctx.getSource(), SiegeTemplateSelector.LEGACY_DEFAULT))
						.then(Commands.literal("self")
								.executes(ctx -> spawnSelf(ctx.getSource(), SiegeTemplateSelector.LEGACY_DEFAULT))
								.then(Commands.argument("recipe", StringArgumentType.word())
										.suggests(RECIPE_SUGGESTIONS)
										.executes(ctx -> spawnSelf(ctx.getSource(),
												StringArgumentType.getString(ctx, "recipe")))))
						.then(Commands.literal("entity")
								.then(Commands.argument("target", UuidArgument.uuid())
										.executes(ctx -> spawnFor(
												ctx.getSource(),
												UuidArgument.getUuid(ctx, "target"),
												SiegeTemplateSelector.LEGACY_DEFAULT))
										.then(Commands.argument("recipe", StringArgumentType.word())
												.suggests(RECIPE_SUGGESTIONS)
												.executes(ctx -> spawnFor(
														ctx.getSource(),
														UuidArgument.getUuid(ctx, "target"),
														StringArgumentType.getString(ctx, "recipe"))))))
						.then(Commands.argument("recipe", StringArgumentType.word())
								.suggests(RECIPE_SUGGESTIONS)
								.executes(ctx -> spawnWave(ctx.getSource(), StringArgumentType.getString(ctx, "recipe")))))
				.then(Commands.literal("sites")
						.executes(ctx -> previewSites(ctx.getSource(), null))
						.then(Commands.argument("target", UuidArgument.uuid())
								.executes(ctx -> previewSites(ctx.getSource(), UuidArgument.getUuid(ctx, "target")))))
				.then(Commands.literal("poll")
						.executes(ctx -> listPoll(ctx.getSource())))
				.then(Commands.literal("recipes")
						.executes(ctx -> listRecipes(ctx.getSource())))
				.then(Commands.literal("create")
						.executes(ctx -> createFromNearby(ctx.getSource(), 1))
						.then(Commands.argument("chunkRadius", IntegerArgumentType.integer(0, 8))
								.executes(ctx -> createFromNearby(
										ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "chunkRadius")))))
				.then(Commands.literal("castle")
						.executes(ctx -> placeCastle(ctx.getSource())))
				.then(Commands.literal("status")
						.executes(ctx -> listStatus(ctx.getSource())))
				.then(Commands.literal("disband")
						.executes(ctx -> disbandAll(ctx.getSource()))));
	}

	private static int placeCastle(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Player required"));
			return 0;
		}
		Direction facing = player.getDirection();
		BlockPos gate = player.blockPosition().relative(facing, 4);
		int placed = MedievalCastleBuilder.place(source.getLevel(), gate, facing);
		source.sendSuccess(() -> Component.literal(
				"Placed courtyard castle (" + placed + " blocks) facing " + facing.getName()), true);
		return placed;
	}

	/** Sample a proportional subset of the poll list (command waves only). */
	private static int spawnWave(CommandSourceStack source, String recipeId) {
		ServerLevel level = source.getLevel();
		Random random = new Random(level.random.nextLong());
		List<UUID> poll = PbsTerritoryAdapter.listSiegeTargets(source.getServer());
		List<UUID> targets = SiegeTargetSampler.sampleWaveTargets(source.getServer(), level, random);
		if (targets.isEmpty()) {
			if (poll.isEmpty()) {
				source.sendFailure(Component.literal("Siege target poll is empty (no orgs / solo players)"));
			} else {
				source.sendFailure(Component.literal(
						"No siegeable targets (all still in new-player protection, days<"
								+ SiegeSpawnConfig.NEW_PLAYER_PROTECTION_DAYS + ")"));
			}
			return 0;
		}
		source.sendSuccess(() -> Component.literal(
				"Wave sample " + targets.size() + "/" + poll.size()
						+ " (ratio=" + SiegeSpawnConfig.TARGET_SAMPLE_RATIO + ")"), false);
		int total = 0;
		for (UUID target : targets) {
			total += spawnFor(source, target, recipeId, random);
		}
		return total;
	}

	private static int spawnSelf(CommandSourceStack source, String recipeId) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Player required"));
			return 0;
		}
		return spawnFor(source, player.getUUID(), recipeId);
	}

	private static int spawnFor(CommandSourceStack source, UUID targetId, String recipeId) {
		ServerLevel level = source.getLevel();
		Random random = new Random(level.random.nextLong());
		return spawnFor(source, targetId, recipeId, random);
	}

	private static int spawnFor(CommandSourceStack source, UUID targetId, String recipeId, Random random) {
		SiegeSpawnMonitor.info(
				"picked player={} dim={} gameTime={} source=command recipe={}",
				SiegeSpawnMonitor.target(source.getServer(), targetId),
				SiegeSpawnMonitor.dim(source.getLevel()),
				source.getLevel().getGameTime(),
				recipeId);
		SiegeClusterSpawner.SpawnResult result;
		try {
			result = SiegeClusterSpawner.spawnById(source.getLevel(), targetId, recipeId, random);
		} catch (IllegalArgumentException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		reportSpawn(source, result);
		return result.clusters().size();
	}

	private static void reportSpawn(CommandSourceStack source, SiegeClusterSpawner.SpawnResult result) {
		SiegeSpawnSiteSelector.SiteSelection sites = result.sites();
		source.sendSuccess(() -> Component.literal(result.message()
				+ " target=" + sites.targetId()
				+ " center=" + formatChunk(sites.territoryCenter())
				+ " candidates=" + sites.nearestHostileBorders().size()
				+ " E≈" + String.format("%.2f", sites.expectedSites())
				+ " chosen=" + sites.chosenSites().size()
				+ " (p=" + SiegeSpawnConfig.SITE_ACCEPT_PROBABILITY + ")"), true);
		for (ChunkPos site : sites.chosenSites()) {
			source.sendSuccess(() -> Component.literal("  site " + formatChunk(site)
					+ " ~" + blockCenterHint(site)), false);
		}
		for (SiegeCluster cluster : result.clusters()) {
			source.sendSuccess(() -> Component.literal("  cluster " + cluster.getId()
					+ " " + cluster.describeTemplate()
					+ " state=" + cluster.getState()
					+ " anchor=" + formatChunk(cluster.getAnchorChunk())
					+ " target=" + formatChunk(cluster.getTargetChunk())), false);
		}
	}

	private static int previewSites(CommandSourceStack source, UUID targetOrNull) {
		UUID target = targetOrNull;
		if (target == null) {
			ServerPlayer player = source.getPlayer();
			if (player == null) {
				source.sendFailure(Component.literal("Provide target UUID or run as player"));
				return 0;
			}
			target = player.getUUID();
		}
		ServerLevel level = source.getLevel();
		SiegeSpawnSiteSelector.SiteSelection sites = SiegeSpawnSiteSelector.select(
				level, target, new Random(level.random.nextLong()));
		if (sites.territoryCenter() == null) {
			source.sendFailure(Component.literal("No territory for " + target));
			return 0;
		}
		UUID finalTarget = target;
		List<ChunkPos> owned = PbsTerritoryAdapter.getOwnedChunks(level, finalTarget);
		source.sendSuccess(() -> Component.literal("target=" + finalTarget
				+ " center=" + formatChunk(sites.territoryCenter())
				+ " nearestHB=" + sites.nearestHostileBorders().size()
				+ " p=" + SiegeSpawnConfig.SITE_ACCEPT_PROBABILITY
				+ " E≈" + String.format("%.2f", sites.expectedSites())
				+ " wouldChoose=" + sites.chosenSites().size()
				+ " (Bernoulli, may be 0)"), false);
		for (ChunkPos c : sites.nearestHostileBorders()) {
			ChunkPos nearestOwned = PbsTerritoryAdapter.findNearestOwnedChunk(owned, c);
			source.sendSuccess(() -> Component.literal("  candidate " + formatChunk(c)
					+ " → nearestOwned=" + formatChunk(nearestOwned)), false);
		}
		for (ChunkPos c : sites.chosenSites()) {
			source.sendSuccess(() -> Component.literal("  chosen " + formatChunk(c)
					+ " ~" + blockCenterHint(c)), false);
		}
		return sites.chosenSites().size();
	}

	private static int listPoll(CommandSourceStack source) {
		var server = source.getServer();
		List<UUID> targets = PbsTerritoryAdapter.listSiegeTargets(server);
		int eligibleCount = 0;
		for (UUID id : targets) {
			if (SiegePlayerProtection.isTimeToBesiege(server, id)) {
				eligibleCount++;
			}
		}
		int eligible = eligibleCount;
		int sample = SiegeTargetSampler.sampleCount(eligible);
		source.sendSuccess(
				() -> Component.literal("EntityPollList order (" + targets.size() + ") [solo player | organization]"
						+ " siegeable=" + eligible
						+ " waveSample≈" + sample
						+ " (ratio=" + SiegeSpawnConfig.TARGET_SAMPLE_RATIO
						+ ", protection=" + SiegeSpawnConfig.NEW_PLAYER_PROTECTION_DAYS + "d):"),
				false);
		int i = 0;
		for (UUID id : targets) {
			int index = i++;
			String kind = describePollEntry(server, id);
			source.sendSuccess(() -> Component.literal("  [" + index + "] " + id + " " + kind), false);
		}
		return targets.size();
	}

	private static String describePollEntry(MinecraftServer server, UUID id) {
		boolean ready = SiegePlayerProtection.isTimeToBesiege(server, id);
		int days = SiegePlayerProtection.daysInGame(server, id);
		String protection = ready
				? "siegeable days=" + days
				: "protected days=" + days + "/" + SiegeSpawnConfig.NEW_PLAYER_PROTECTION_DAYS;
		var org = PlayerBlockStatusLib.queryOrganization(server, id);
		if (org.isPresent()) {
			return "(org name=" + org.get().name() + " owner=" + org.get().owner() + " " + protection + ")";
		}
		var memberOf = PlayerBlockStatusLib.queryPlayerOrganization(server, id);
		if (memberOf.isPresent()) {
			return "(player in org " + memberOf.get() + " — unexpected in poll, " + protection + ")";
		}
		return "(solo player, " + protection + ")";
	}

	private static int listRecipes(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal("Aliases: default=stage roll, light=undead_roadkill, heavy=undead_host"), false);
		source.sendSuccess(() -> Component.literal("Siege (" + SiegeClusterConfig.FILE_NAME + "):"), false);
		int n = listTemplates(source, SiegeClusterConfig.siege());
		source.sendSuccess(() -> Component.literal("Wilderness (" + SiegeClusterConfig.FILE_NAME_WILDERNESS + "):"), false);
		n += listTemplates(source, SiegeClusterConfig.wilderness());
		return n;
	}

	private static int listTemplates(CommandSourceStack source, SiegeClusterConfig config) {
		int n = 0;
		for (SiegeClusterTemplate template : config.templates()) {
			n++;
			SiegeClusterTemplate row = template;
			source.sendSuccess(() -> Component.literal(
					"  " + row.id() + " " + row.name()
							+ " race=" + row.race()
							+ " tier=" + row.tier()
							+ " count=" + row.count().min() + "-" + row.count().max()
							+ " species=" + row.species().size()), false);
		}
		return n;
	}

	private static int listStatus(CommandSourceStack source) {
		int count = SiegeClusterManager.clusterCount();
		source.sendSuccess(() -> Component.literal("Active siege clusters: " + count), false);
		for (SiegeCluster cluster : SiegeClusterManager.clusters()) {
			if (cluster.getLevel() != source.getLevel()) {
				continue;
			}
			source.sendSuccess(() -> Component.literal("  " + cluster.getId()
					+ " " + cluster.describeTemplate()
					+ " state=" + cluster.getState()
					+ " m=" + cluster.getLivingCount()
					+ " anchor=" + formatChunk(cluster.getAnchorChunk())
					+ " target=" + formatChunk(cluster.getTargetChunk())), false);
		}
		return count;
	}

	private static int createFromNearby(CommandSourceStack source, int radius) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Player required"));
			return 0;
		}
		ServerLevel level = player.serverLevel();
		ChunkPos anchor = player.chunkPosition();
		SiegeCluster cluster = SiegeClusterManager.createClusterFromNearbyMonsters(level, anchor, radius);
		if (cluster == null) {
			source.sendFailure(Component.literal("No eligible monsters nearby (or already in a cluster)"));
			return 0;
		}
		source.sendSuccess(
				() -> Component.literal("Created siege cluster " + cluster.getId()
						+ " state=" + cluster.getState()
						+ " anchor=" + formatChunk(anchor)
						+ " (legacy: collected nearby monsters)"),
				true);
		return 1;
	}

	private static int disbandAll(CommandSourceStack source) {
		int before = SiegeClusterManager.clusterCount();
		SiegeClusterManager.clearLevel(source.getLevel());
		source.sendSuccess(() -> Component.literal("Cleared clusters in this dimension (was " + before + ")"), true);
		return before;
	}

	private static String formatChunk(ChunkPos chunk) {
		return chunk == null ? "null" : (chunk.x + "," + chunk.z);
	}

	private static String blockCenterHint(ChunkPos chunk) {
		if (chunk == null) {
			return "null";
		}
		return ((chunk.x << 4) + 8) + ",?," + ((chunk.z << 4) + 8);
	}
}
