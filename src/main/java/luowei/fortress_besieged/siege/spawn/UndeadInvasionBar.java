package luowei.fortress_besieged.siege.spawn;

import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

/**
 * Shared identity for the undead-invasion boss bar. The client uses the name
 * because {@code ServerBossEvent} assigns a random id.
 */
public final class UndeadInvasionBar {
	public static final String NAME = "亡灵入侵";

	private UndeadInvasionBar() {
	}

	public static Component title() {
		return Component.literal(NAME);
	}

	public static boolean isBar(BossEvent event) {
		return event != null && event.getName() != null && NAME.equals(event.getName().getString());
	}
}
