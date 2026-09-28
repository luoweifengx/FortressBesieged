package luowei.fortress_besieged.client;

/**
 * Client copy of the server blood-moon flag. Selects the red moon atlas.
 */
public final class BloodMoonClient {
	private static boolean active;

	private BloodMoonClient() {
	}

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean active) {
		BloodMoonClient.active = active;
	}
}
