package luowei.fortress_besieged.siege;

import java.util.ArrayList;
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
import net.minecraft.world.level.ChunkPos;

import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.spawn.SiegeClusterSpawner;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnConfig;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnRecipe;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnRecipes;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnSiteSelector;
import luowei.fortress_besieged.siege.spawn.SiegeTargetSampler;
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
 * /siege recipes                  — list recipes (id, count, weighted units)
 * /siege create [chunkRadius]     — legacy: absorb nearby natural monsters
 * /siege status
 * /siege disband
 * </pre>
 */
public final class SiegeCommands {
	private static final SuggestionProvider<CommandSourceStack> RECIPE_SUGGESTIONS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(
					SiegeSpawnRecipes.all().stream().map(SiegeSpawnRecipe::id),
					builder);

	private SiegeCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("siege")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("spawn")
						.executes(ctx -> spawnWave(ctx.getSource(), SiegeSpawnRecipes.DEFAULT_ID))
						.then(Commands.literal("self")
								.executes(ctx -> spawnSelf(ctx.getSource(), SiegeSpawnRecipes.DEFAULT_ID))
								.then(Commands.argument("recipe", StringArgumentType.word())
										.suggests(RECIPE_SUGGESTIONS)
										.executes(ctx -> spawnSelf(ctx.getSource(),
												StringArgumentType.getString(ctx, "recipe")))))
						.then(Commands.literal("entity")
								.then(Commands.argument("target", UuidArgument.uuid())
										.executes(ctx -> spawnFor(
												ctx.getSource(),
												UuidArgument.getUuid(ctx, "target"),
												SiegeSpawnRecipes.DEFAULT_ID))
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
				.then(Commands.literal("status")
						.executes(ctx -> {
							ctx.getSource().sendSuccess(
									() -> Component.literal("Active siege clusters: " + SiegeClusterManager.clusterCount()),
									false);
							return SiegeClusterManager.clusterCount();
						}))
				.then(Commands.literal("disband")
						.executes(ctx -> disbandAll(ctx.getSource()))));
	}

	/** Sample a proportional subset of the poll list (same idea as natural waves). */
	private static int spawnWave(CommandSourceStack source, String recipeId) {
		SiegeSpawnRecipe recipe;
		try {
			recipe = SiegeSpawnRecipes.require(recipeId);
		} catch (IllegalArgumentException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		ServerLevel level = source.getLevel();
		Random random = new Random(level.random.nextLong());
		List<UUID> targets = SiegeTargetSampler.sampleWaveTargets(source.getServer(), level, random);
		if (targets.isEmpty()) {
			source.sendFailure(Component.literal("Siege target poll is empty (no orgs / solo players)"));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(
				"Wave sample " + targets.size() + "/" + PbsTerritoryAdapter.listSiegeTargets(source.getServer()).size()
						+ " (ratio=" + SiegeSpawnConfig.TARGET_SAMPLE_RATIO + ")"), false);
		int total = 0;
		for (UUID target : targets) {
			SiegeClusterSpawner.SpawnResult result = SiegeClusterSpawner.spawnForTarget(level, target, recipe, random);
			reportSpawn(source, result);
			total += result.clusters().size();
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
		SiegeSpawnRecipe recipe;
		try {
			recipe = SiegeSpawnRecipes.require(recipeId);
		} catch (IllegalArgumentException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		ServerLevel level = source.getLevel();
		Random random = new Random(level.random.nextLong());
		SiegeClusterSpawner.SpawnResult result = SiegeClusterSpawner.spawnForTarget(level, targetId, recipe, random);
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
		int sample = SiegeTargetSampler.sampleCount(targets.size());
		source.sendSuccess(
				() -> Component.literal("EntityPollList order (" + targets.size() + ") [solo player | organization]"
						+ " waveSample≈" + sample
						+ " (ratio=" + SiegeSpawnConfig.TARGET_SAMPLE_RATIO + "):"),
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
		var org = PlayerBlockStatusLib.queryOrganization(server, id);
		if (org.isPresent()) {
			return "(org name=" + org.get().name() + ")";
		}
		var memberOf = PlayerBlockStatusLib.queryPlayerOrganization(server, id);
		if (memberOf.isPresent()) {
			return "(player in org " + memberOf.get() + " — unexpected in poll)";
		}
		return "(solo player)";
	}

	private static int listRecipes(CommandSourceStack source) {
		for (SiegeSpawnRecipe recipe : SiegeSpawnRecipes.all()) {
			List<String> parts = new ArrayList<>();
			for (var entry : recipe.entries()) {
				parts.add(entry.unit().id() + "×" + entry.weight());
			}
			source.sendSuccess(() -> Component.literal(
					recipe.id() + " count=" + recipe.totalCount()
							+ " units=[" + String.join(", ", parts) + "]"), false);
		}
		return SiegeSpawnRecipes.all().size();
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
