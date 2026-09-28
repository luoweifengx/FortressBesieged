package luowei.fortress_besieged;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import luowei.fortress_besieged.net.BloodMoonS2CPayload;
import luowei.fortress_besieged.siege.SiegeChunkTickets;
import luowei.fortress_besieged.siege.SiegeClusterBehaviorConfig;
import luowei.fortress_besieged.siege.SiegeClusterManager;
import luowei.fortress_besieged.siege.SiegeCommands;
import luowei.fortress_besieged.siege.SiegePlayerProtection;
import luowei.fortress_besieged.siege.SiegeProgressionFlags;
import luowei.fortress_besieged.siege.path.SiegePathCostConfig;
import luowei.fortress_besieged.siege.spawn.BloodMoon;
import luowei.fortress_besieged.siege.spawn.HellSpawnScheduler;
import luowei.fortress_besieged.siege.spawn.SiegeClusterConfig;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnScheduler;
import luowei.fortress_besieged.siege.spawn.UndeadInvasion;
import luowei.fortress_besieged.siege.spawn.WildernessPackTracker;
import luowei.fortress_besieged.siege.spawn.WildernessSpawnScheduler;

public class FortressBesieged implements ModInitializer {
	public static final String MOD_ID = "fortress-besieged";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		SiegePathCostConfig.load();
		SiegeClusterBehaviorConfig.load();
		SiegeClusterConfig.load();
		SiegeChunkTickets.bootstrap();
		PayloadTypeRegistry.playS2C().register(BloodMoonS2CPayload.TYPE, BloodMoonS2CPayload.STREAM_CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				SiegeCommands.register(dispatcher));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			var player = handler.getPlayer();
			SiegePlayerProtection.ensurePlayer(server, player.getUUID());
			SiegeProgressionFlags.onPlayerJoin(server, player);
			BloodMoon.syncTo(player);
			UndeadInvasion.syncTo(player);
		});

		ServerTickEvents.END_WORLD_TICK.register(world -> {
			if (world instanceof ServerLevel serverLevel) {
				SiegeClusterManager.tickLevel(serverLevel);
				BloodMoon.onWorldTick(serverLevel);
				UndeadInvasion.onWorldTick(serverLevel);
				SiegeSpawnScheduler.onWorldTick(serverLevel);
				HellSpawnScheduler.onWorldTick(serverLevel);
				WildernessSpawnScheduler.onWorldTick(serverLevel);
			}
		});

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (entity instanceof Mob mob) {
				SiegeClusterManager.onMobDamaged(mob, source.getEntity());
				WildernessPackTracker.onMobDamaged(mob, source.getEntity());
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof Mob mob) {
				SiegeClusterManager.onMobDeath(mob);
				WildernessPackTracker.onMobRemoved(mob.getUUID());
			}
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof Mob mob) {
				SiegeClusterManager.onMobLoaded(mob);
			}
		});

		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (WildernessPackTracker.isMember(entity)) {
				WildernessPackTracker.onMobRemoved(entity.getUUID());
			}
		});

		ServerWorldEvents.LOAD.register((server, world) -> {
			if (world instanceof ServerLevel serverLevel) {
				SiegeClusterManager.loadLevel(serverLevel);
			}
		});

		ServerWorldEvents.UNLOAD.register((server, world) -> {
			if (world instanceof ServerLevel serverLevel) {
				SiegeClusterManager.unloadLevel(serverLevel);
				WildernessPackTracker.clearLevel(serverLevel);
				WildernessSpawnScheduler.clearLevel(serverLevel.dimension());
				HellSpawnScheduler.clearLevel(serverLevel.dimension());
			}
		});

		ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> {
			for (ServerLevel level : server.getAllLevels()) {
				SiegeClusterManager.syncSave(level);
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				SiegeClusterManager.unloadLevel(level);
			}
			WildernessPackTracker.clearAll();
			WildernessSpawnScheduler.clearAll();
			HellSpawnScheduler.clearAll();
		});

		LOGGER.info("Fortress Besieged siege cluster system initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
