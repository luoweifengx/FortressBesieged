package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Integer-weight tables: pick one key, optionally drop zero/unlocked entries and renormalize.
 */
public final class WeightedPicker {
	private WeightedPicker() {
	}

	public static String pick(Map<String, Integer> weights, Random random) {
		int total = total(weights);
		if (total <= 0 || random == null) {
			return null;
		}
		int roll = random.nextInt(total);
		int acc = 0;
		String last = null;
		for (Map.Entry<String, Integer> entry : weights.entrySet()) {
			int w = entry.getValue() == null ? 0 : entry.getValue();
			if (w <= 0) {
				continue;
			}
			acc += w;
			last = entry.getKey();
			if (roll < acc) {
				return entry.getKey();
			}
		}
		return last;
	}

	public static String pick(Map<String, Integer> weights, Random random, Predicate<String> keep) {
		return pick(filter(weights, keep), random);
	}

	public static String pickDoubleKey(Map<String, Integer> weights, Random random) {
		return pick(weights, random);
	}

	/** Probability table (0–1 weights). Unlocked-only entries renormalized. */
	public static String pickProbability(Map<String, Double> weights, Random random, Predicate<String> keep) {
		double total = 0.0D;
		List<Map.Entry<String, Double>> kept = new ArrayList<>();
		for (Map.Entry<String, Double> entry : weights.entrySet()) {
			if (keep != null && !keep.test(entry.getKey())) {
				continue;
			}
			double w = entry.getValue() == null ? 0.0D : entry.getValue();
			if (w <= 0.0D) {
				continue;
			}
			total += w;
			kept.add(entry);
		}
		if (total <= 0.0D || random == null || kept.isEmpty()) {
			return null;
		}
		double roll = random.nextDouble() * total;
		double acc = 0.0D;
		String last = kept.get(kept.size() - 1).getKey();
		for (Map.Entry<String, Double> entry : kept) {
			acc += entry.getValue();
			last = entry.getKey();
			if (roll < acc) {
				return entry.getKey();
			}
		}
		return last;
	}

	public static Map<String, Integer> filter(Map<String, Integer> weights, Predicate<String> keep) {
		if (weights == null || weights.isEmpty()) {
			return Map.of();
		}
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : weights.entrySet()) {
			if (keep != null && !keep.test(entry.getKey())) {
				continue;
			}
			int w = entry.getValue() == null ? 0 : entry.getValue();
			if (w > 0) {
				out.put(entry.getKey(), w);
			}
		}
		return out;
	}

	public static int total(Map<String, Integer> weights) {
		if (weights == null) {
			return 0;
		}
		int total = 0;
		for (Integer w : weights.values()) {
			if (w != null && w > 0) {
				total += w;
			}
		}
		return total;
	}

	public static int inRange(int min, int max, Random random) {
		int lo = Math.min(min, max);
		int hi = Math.max(min, max);
		if (hi == lo) {
			return lo;
		}
		return lo + random.nextInt(hi - lo + 1);
	}
}
