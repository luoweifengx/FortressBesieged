package luowei.fortress_besieged.siege.path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import luowei.fortress_besieged.siege.goal.BreachAction;
import luowei.fortress_besieged.siege.goal.SiegeApproachPlanner;

/**
 * DESTROY 两段寻路：以 assigned 为中心的切比雪夫立方盒内代价 A*；
 * 盒外为有界 x 前沿贪心（默认 x=2），一次规划产出完整 WALK/BREAK/PLACE 队列。
 * 终点为挖掘盒内（切比雪夫 mineReach）且非 assigned 本身的立足格。
 */
public final class SiegeCostPathfinder {
	private SiegeCostPathfinder() {
	}

	public static final class Result {
		public static final String MODE_NONE = "none";
		public static final String MODE_ASTAR = "astar";
		public static final String MODE_GREEDY = "greedy";
		public static final String MODE_GREEDY_ASTAR = "greedy_astar";
		/** @deprecated 旧盒外 realtime；保留常量以免日志/外部引用崩溃 */
		@Deprecated
		public static final String MODE_REALTIME = "realtime";

		public final List<BreachAction> actions;
		public final double cost;
		public final int expanded;
		public final String mode;

		private Result(List<BreachAction> actions, double cost, int expanded, String mode) {
			this.actions = List.copyOf(actions);
			this.cost = cost;
			this.expanded = expanded;
			this.mode = mode;
		}

		public static Result empty() {
			return new Result(List.of(), 0.0D, 0, MODE_NONE);
		}
	}

	/**
	 * 按是否在局部立方盒内分发。盒外一次贪心到盒入口，再拼盒内 A* 成完整队列。
	 */
	public static Result find(Mob mob, BlockPos assigned) {
		if (mob == null || assigned == null || !(mob.level() instanceof Level)) {
			return null;
		}
		SiegePathCostConfig cfg = SiegePathCostConfig.get();
		if (inReach(mob.blockPosition(), assigned, cfg)) {
			return Result.empty();
		}
		if (inLocalBox(mob.blockPosition(), assigned, cfg)) {
			return findAstar(mob, assigned);
		}
		return findGreedyThenAstar(mob, assigned);
	}

	/**
	 * 怪与 assigned 同时落在切比雪夫立方盒内：
	 * {@code max(|dx|,|dz|) <= localBoxXz} 且 {@code |dy| <= localBoxY}（默认均为 16 → 约 33³）。
	 */
	public static boolean inLocalBox(BlockPos foot, BlockPos assigned, SiegePathCostConfig cfg) {
		if (foot == null || assigned == null || cfg == null) {
			return false;
		}
		int dx = Math.abs(foot.getX() - assigned.getX());
		int dy = Math.abs(foot.getY() - assigned.getY());
		int dz = Math.abs(foot.getZ() - assigned.getZ());
		return Math.max(dx, dz) <= cfg.localBoxXz && dy <= cfg.localBoxY;
	}

	/**
	 * 盒内代价 A*：搜索范围钳在 assigned 的局部盒内。
	 */
	public static Result findAstar(Mob mob, BlockPos assigned) {
		if (mob == null || assigned == null || !(mob.level() instanceof Level level)) {
			return null;
		}
		return findAstarFrom(level, mob.blockPosition(), assigned);
	}

	/**
	 * 盒外 x 前沿贪心到局部盒入口，再接盒内 A*，返回完整动作队列。
	 */
	public static Result findGreedyThenAstar(Mob mob, BlockPos assigned) {
		if (mob == null || assigned == null || !(mob.level() instanceof Level level)) {
			return null;
		}
		SiegePathCostConfig cfg = SiegePathCostConfig.get();
		BlockPos start = mob.blockPosition();
		if (inReach(start, assigned, cfg)) {
			return Result.empty();
		}
		if (inLocalBox(start, assigned, cfg)) {
			return findAstarFrom(level, start, assigned);
		}

		GreedyOutcome greedy = runGreedyToBox(level, start, assigned, cfg);
		if (greedy == null) {
			return null;
		}
		List<BreachAction> actions = reconstruct(start, greedy.entry, greedy.came);
		if (actions.isEmpty() && !greedy.entry.equals(start)) {
			return null;
		}
		if (inReach(greedy.entry, assigned, cfg)) {
			return new Result(actions, greedy.g, greedy.expanded, Result.MODE_GREEDY);
		}

		Result inside = findAstarFrom(level, greedy.entry, assigned);
		if (inside == null) {
			// 至少送到盒内立足格，Goal 可立即再规划盒内 A*
			return new Result(actions, greedy.g, greedy.expanded, Result.MODE_GREEDY);
		}
		if (inside.actions.isEmpty()) {
			return new Result(actions, greedy.g, greedy.expanded, Result.MODE_GREEDY);
		}
		List<BreachAction> combined = new ArrayList<>(actions.size() + inside.actions.size());
		combined.addAll(actions);
		combined.addAll(inside.actions);
		return new Result(combined, greedy.g + inside.cost, greedy.expanded + inside.expanded, Result.MODE_GREEDY_ASTAR);
	}

	/**
	 * @deprecated 使用 {@link #findGreedyThenAstar}；保留以免旧调用方编译失败。
	 */
	@Deprecated
	public static Result findRealtime(Mob mob, BlockPos assigned) {
		return findGreedyThenAstar(mob, assigned);
	}

	static Result findAstarFrom(Level level, BlockPos start, BlockPos assigned) {
		if (level == null || start == null || assigned == null) {
			return null;
		}
		SiegePathCostConfig cfg = SiegePathCostConfig.get();
		if (inReach(start, assigned, cfg)) {
			return Result.empty();
		}

		int minX = assigned.getX() - cfg.localBoxXz;
		int maxX = assigned.getX() + cfg.localBoxXz;
		int minY = Math.max(assigned.getY() - cfg.localBoxY, level.getMinY());
		int maxY = Math.min(assigned.getY() + cfg.localBoxY + 1, level.getMaxY() - 2);
		int minZ = assigned.getZ() - cfg.localBoxXz;
		int maxZ = assigned.getZ() + cfg.localBoxXz;

		SearchCtx ctx = new SearchCtx(level, assigned, cfg, minX, maxX, minY, maxY, minZ, maxZ);
		ctx.seed(start);

		int expanded = 0;
		BlockPos found = null;
		double foundG = 0.0D;
		while (!ctx.open.isEmpty() && expanded < cfg.maxNodes) {
			Frontier cur = ctx.open.poll();
			if (ctx.stale(cur)) {
				continue;
			}
			expanded++;
			if (inReach(cur.pos, assigned, cfg) && !cur.pos.equals(assigned)) {
				found = cur.pos;
				foundG = cur.g;
				break;
			}
			expandNeighbors(ctx, cur.pos, cur.g);
		}
		if (found == null) {
			return null;
		}
		List<BreachAction> actions = reconstruct(start, found, ctx.came);
		if (actions.isEmpty() && !inReach(start, assigned, cfg)) {
			return null;
		}
		return new Result(actions, foundG, expanded, Result.MODE_ASTAR);
	}

	/**
	 * x 前沿贪心：维护约 {@code greedyFrontierX} 个前沿节点及其邻边 f；
	 * 每次取全局最小 f 邻边扩展，并丢掉最旧前沿的邻边数据；父指针树倒查路线。
	 * 目标：抵达局部盒内立足格（交给 A*）或直接挖掘盒。
	 */
	private static GreedyOutcome runGreedyToBox(Level level, BlockPos start, BlockPos assigned, SiegePathCostConfig cfg) {
		int margin = Math.max(cfg.searchExpand, 8);
		int minX = Math.min(start.getX(), assigned.getX() - cfg.localBoxXz) - margin;
		int maxX = Math.max(start.getX(), assigned.getX() + cfg.localBoxXz) + margin;
		int minY = Math.max(Math.min(start.getY(), assigned.getY() - cfg.localBoxY) - margin, level.getMinY());
		int maxY = Math.min(Math.max(start.getY(), assigned.getY() + cfg.localBoxY) + margin + 1, level.getMaxY() - 2);
		int minZ = Math.min(start.getZ(), assigned.getZ() - cfg.localBoxXz) - margin;
		int maxZ = Math.max(start.getZ(), assigned.getZ() + cfg.localBoxXz) + margin;

		SearchCtx ctx = new SearchCtx(level, assigned, cfg, minX, maxX, minY, maxY, minZ, maxZ);
		ctx.bestG.put(start.asLong(), 0.0D);

		int frontierX = cfg.greedyFrontierX;
		ArrayDeque<FrontierSlot> frontier = new ArrayDeque<>(frontierX + 1);
		List<EdgeCand> startEdges = collectEdges(ctx, start, 0.0D);
		if (startEdges.isEmpty()) {
			return null;
		}

		EdgeCand first = minEdge(startEdges);
		if (first == null || !acceptEdge(ctx, first)) {
			return null;
		}
		if (isGreedyGoal(first.dest, assigned, cfg)) {
			return new GreedyOutcome(first.dest, first.g, 1, ctx.came);
		}

		frontier.addLast(new FrontierSlot(remainingEdges(startEdges, first.dest)));
		List<EdgeCand> secondEdges = collectEdges(ctx, first.dest, first.g);
		frontier.addLast(new FrontierSlot(secondEdges));
		trimFrontier(frontier, frontierX);

		int expanded = 1;
		while (expanded < cfg.maxNodes) {
			EdgePick pick = pickMinEdge(frontier);
			if (pick == null) {
				return null;
			}
			pick.slot.edges.remove(pick.index);
			EdgeCand edge = pick.edge;
			if (!acceptEdge(ctx, edge)) {
				continue;
			}
			expanded++;
			if (isGreedyGoal(edge.dest, assigned, cfg)) {
				return new GreedyOutcome(edge.dest, edge.g, expanded, ctx.came);
			}
			if (frontier.size() >= frontierX) {
				frontier.removeFirst();
			}
			frontier.addLast(new FrontierSlot(collectEdges(ctx, edge.dest, edge.g)));
			trimFrontier(frontier, frontierX);
		}
		return null;
	}

	private static boolean isGreedyGoal(BlockPos pos, BlockPos assigned, SiegePathCostConfig cfg) {
		return inReach(pos, assigned, cfg) || inLocalBox(pos, assigned, cfg);
	}

	private static boolean acceptEdge(SearchCtx ctx, EdgeCand edge) {
		long key = edge.dest.asLong();
		Double prev = ctx.bestG.get(key);
		if (prev != null && edge.g >= prev - 1.0E-6D) {
			return false;
		}
		ctx.bestG.put(key, edge.g);
		ctx.came.put(key, new CameFrom(edge.from.immutable(), edge.step.actions, edge.g));
		return true;
	}

	private static EdgeCand minEdge(List<EdgeCand> edges) {
		EdgeCand best = null;
		for (EdgeCand e : edges) {
			if (best == null || e.compareTo(best) < 0) {
				best = e;
			}
		}
		return best;
	}

	private static List<EdgeCand> remainingEdges(List<EdgeCand> edges, BlockPos excludeDest) {
		List<EdgeCand> out = new ArrayList<>(Math.max(0, edges.size() - 1));
		for (EdgeCand e : edges) {
			if (!e.dest.equals(excludeDest)) {
				out.add(e);
			}
		}
		return out;
	}

	private static void trimFrontier(ArrayDeque<FrontierSlot> frontier, int frontierX) {
		while (frontier.size() > Math.max(1, frontierX)) {
			frontier.removeFirst();
		}
	}

	private static EdgePick pickMinEdge(ArrayDeque<FrontierSlot> frontier) {
		EdgePick best = null;
		for (FrontierSlot slot : frontier) {
			for (int i = 0; i < slot.edges.size(); i++) {
				EdgeCand e = slot.edges.get(i);
				if (best == null || e.compareTo(best.edge) < 0) {
					best = new EdgePick(slot, i, e);
				}
			}
		}
		return best;
	}

	private static List<EdgeCand> collectEdges(SearchCtx ctx, BlockPos pos, double g) {
		List<EdgeCand> edges = new ArrayList<>(16);
		expandNeighbors(ctx, pos, g, (from, dest, fromG, step) -> {
			double ng = fromG + step.cost;
			double f = ng + heuristic(dest, ctx.assigned, ctx.cfg);
			edges.add(new EdgeCand(from.immutable(), dest.immutable(), f, ng, step));
		});
		return edges;
	}

	private static void expandNeighbors(SearchCtx ctx, BlockPos pos, double g) {
		expandNeighbors(ctx, pos, g, (from, dest, fromG, step) -> offer(ctx, from, dest, fromG, step));
	}

	private static void expandNeighbors(SearchCtx ctx, BlockPos pos, double g, NeighborSink sink) {
		Level level = ctx.level;
		BlockPos assigned = ctx.assigned;
		SiegePathCostConfig cfg = ctx.cfg;
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			for (int dy = -1; dy <= 1; dy++) {
				BlockPos dest = pos.relative(dir).offset(0, dy, 0);
				offerMove(ctx, pos, dest, g, sink);
			}
			BlockPos fluidCell = pos.relative(dir);
			if (ctx.inSearchBox(fluidCell)
					&& SiegeApproachPlanner.isFluid(level, fluidCell)
					&& canPlace(level, fluidCell, assigned, cfg)) {
				BlockPos stand = fluidCell.above();
				if (ctx.inSearchBox(stand) && !stand.equals(assigned)) {
					Step step = buildStep(level, assigned, cfg, stand, true, fluidCell);
					if (step != null) {
						sink.accept(pos, stand, g, step);
					}
				}
			}
		}

		BlockPos up = pos.above();
		if (ctx.inSearchBox(up) && !up.equals(assigned)) {
			Step pillar = buildPillarUp(level, assigned, cfg, pos, up);
			if (pillar != null) {
				sink.accept(pos, up, g, pillar);
			}
		}

		BlockPos down = pos.below();
		if (ctx.inSearchBox(down) && !down.equals(assigned)) {
			offerMove(ctx, pos, down, g, sink);
		}
	}

	private static void offerMove(SearchCtx ctx, BlockPos from, BlockPos dest, double fromG, NeighborSink sink) {
		if (!ctx.inSearchBox(dest)
				|| !ctx.inSearchBox(dest.above())
				|| dest.equals(ctx.assigned)) {
			return;
		}
		Step step = buildStep(ctx.level, ctx.assigned, ctx.cfg, dest, false, null);
		if (step == null) {
			return;
		}
		sink.accept(from, dest, fromG, step);
	}

	/**
	 * 构造走到 dest 所需的 BREAK/PLACE，再加上 WALK。不走进 assigned。
	 */
	private static Step buildStep(
			Level level,
			BlockPos assigned,
			SiegePathCostConfig cfg,
			BlockPos dest,
			boolean forcePlaceSupport,
			BlockPos forcedPlacePos
	) {
		BlockPos head = dest.above();
		BlockPos support = dest.below();
		List<BreachAction> prep = new ArrayList<>(4);
		int cost = 0;

		ClearResult footClear = clearBodyCell(level, assigned, cfg, dest, prep);
		if (footClear == ClearResult.BLOCKED) {
			return null;
		}
		cost += footClear.cost;

		ClearResult headClear = clearBodyCell(level, assigned, cfg, head, prep);
		if (headClear == ClearResult.BLOCKED) {
			return null;
		}
		cost += headClear.cost;

		if (forcePlaceSupport && forcedPlacePos != null) {
			if (forcedPlacePos.equals(assigned) || !canPlace(level, forcedPlacePos, assigned, cfg)) {
				return null;
			}
			prep.add(new BreachAction(BreachAction.Type.PLACE, forcedPlacePos));
			cost += cfg.placeCost;
		} else {
			SupportResult supportResult = ensureSupport(level, assigned, cfg, support, prep);
			if (supportResult == SupportResult.BLOCKED) {
				return null;
			}
			cost += supportResult.cost;
		}

		cost += cfg.airCost;
		prep.add(new BreachAction(BreachAction.Type.WALK, dest));
		return new Step(prep, cost);
	}

	private static Step buildPillarUp(Level level, BlockPos assigned, SiegePathCostConfig cfg, BlockPos from, BlockPos up) {
		BlockPos upHead = up.above();
		if (up.equals(assigned) || upHead.equals(assigned)) {
			return null;
		}
		List<BreachAction> prep = new ArrayList<>(4);
		int cost = 0;

		ClearResult upClear = clearBodyCell(level, assigned, cfg, up, prep);
		if (upClear == ClearResult.BLOCKED) {
			return null;
		}
		cost += upClear.cost;
		ClearResult headClear = clearBodyCell(level, assigned, cfg, upHead, prep);
		if (headClear == ClearResult.BLOCKED) {
			return null;
		}
		cost += headClear.cost;

		if (!canPlace(level, from, assigned, cfg) && !SiegeApproachPlanner.isSturdySupport(level, from)) {
			return null;
		}
		if (!SiegeApproachPlanner.isSturdySupport(level, from) || !SiegeApproachPlanner.isSolid(level, from)) {
			if (!canPlace(level, from, assigned, cfg)) {
				return null;
			}
			prep.add(new BreachAction(BreachAction.Type.PLACE, from));
			cost += cfg.placeCost;
		}
		cost += cfg.airCost;
		prep.add(new BreachAction(BreachAction.Type.WALK, up));
		return new Step(prep, cost);
	}

	private static ClearResult clearBodyCell(
			Level level,
			BlockPos assigned,
			SiegePathCostConfig cfg,
			BlockPos cell,
			List<BreachAction> prep
	) {
		if (cell.equals(assigned)) {
			return ClearResult.BLOCKED;
		}
		if (SiegeApproachPlanner.isPassable(level, cell)) {
			return ClearResult.ok(0);
		}
		if (cfg.isImpassable(level, cell)) {
			return ClearResult.BLOCKED;
		}
		OptionalInt breakCost = cfg.breakCost(level, cell);
		if (breakCost.isEmpty()) {
			if (canPlace(level, cell, assigned, cfg) && !SiegeApproachPlanner.isSolid(level, cell)) {
				float hardness = level.getBlockState(cell).getDestroySpeed(level, cell);
				int c = hardness < 0.0F ? -1
						: hardness < cfg.softHardnessMax ? cfg.softCost : cfg.hardCost;
				if (c < 0) {
					return ClearResult.BLOCKED;
				}
				prep.add(new BreachAction(BreachAction.Type.BREAK, cell));
				return ClearResult.ok(c);
			}
			return ClearResult.BLOCKED;
		}
		prep.add(new BreachAction(BreachAction.Type.BREAK, cell));
		return ClearResult.ok(breakCost.getAsInt());
	}

	private static SupportResult ensureSupport(
			Level level,
			BlockPos assigned,
			SiegePathCostConfig cfg,
			BlockPos support,
			List<BreachAction> prep
	) {
		if (SiegeApproachPlanner.isSturdySupport(level, support)) {
			return SupportResult.ok(0);
		}
		if (support.equals(assigned)) {
			return SupportResult.BLOCKED;
		}
		if (canPlace(level, support, assigned, cfg)) {
			prep.add(new BreachAction(BreachAction.Type.PLACE, support));
			return SupportResult.ok(cfg.placeCost);
		}
		if (cfg.isImpassable(level, support)) {
			return SupportResult.BLOCKED;
		}
		if (SiegeApproachPlanner.isSolid(level, support)) {
			OptionalInt breakCost = cfg.breakCost(level, support);
			if (breakCost.isEmpty()) {
				return SupportResult.BLOCKED;
			}
			prep.add(new BreachAction(BreachAction.Type.BREAK, support));
			if (!canPlaceAfterBreak(level, support, assigned)) {
				return SupportResult.BLOCKED;
			}
			prep.add(new BreachAction(BreachAction.Type.PLACE, support));
			return SupportResult.ok(breakCost.getAsInt() + cfg.placeCost);
		}
		return SupportResult.BLOCKED;
	}

	private static boolean canPlace(Level level, BlockPos pos, BlockPos assigned, SiegePathCostConfig cfg) {
		if (pos.equals(assigned) || cfg.isImpassable(level, pos)) {
			return false;
		}
		return SiegeApproachPlanner.canPlaceCobble(level, pos, assigned);
	}

	private static boolean canPlaceAfterBreak(Level level, BlockPos pos, BlockPos assigned) {
		return !pos.equals(assigned);
	}

	private static void offer(SearchCtx ctx, BlockPos from, BlockPos dest, double fromG, Step step) {
		double g = fromG + step.cost;
		long key = dest.asLong();
		Double prev = ctx.bestG.get(key);
		if (prev != null && g >= prev - 1.0E-6D) {
			return;
		}
		ctx.bestG.put(key, g);
		ctx.came.put(key, new CameFrom(from.immutable(), step.actions, g));
		ctx.open.add(new Frontier(g + heuristic(dest, ctx.assigned, ctx.cfg), g, dest.immutable(), ctx.gen++));
	}

	private static List<BreachAction> reconstruct(BlockPos start, BlockPos goal, Map<Long, CameFrom> came) {
		List<BreachAction> reversed = new ArrayList<>();
		BlockPos cur = goal;
		while (!cur.equals(start)) {
			CameFrom cf = came.get(cur.asLong());
			if (cf == null) {
				return List.of();
			}
			for (int i = cf.actions.size() - 1; i >= 0; i--) {
				reversed.add(cf.actions.get(i));
			}
			cur = cf.prev;
		}
		Collections.reverse(reversed);
		return reversed;
	}

	/**
	 * 挖掘盒（切比雪夫，非寻路局部盒 localBox）：脚格 ≠ assigned，且
	 * {@code max(|dx|, |dz|) <= mineReachXz}、{@code |dy| <= mineReachY}。
	 */
	public static boolean inReach(BlockPos foot, BlockPos assigned, SiegePathCostConfig cfg) {
		if (foot == null || assigned == null || cfg == null || foot.equals(assigned)) {
			return false;
		}
		int dx = Math.abs(foot.getX() - assigned.getX());
		int dy = Math.abs(foot.getY() - assigned.getY());
		int dz = Math.abs(foot.getZ() - assigned.getZ());
		return Math.max(dx, dz) <= cfg.mineReachXz && dy <= cfg.mineReachY;
	}

	public static boolean inReach(Vec3 feet, BlockPos assigned, SiegePathCostConfig cfg) {
		if (feet == null) {
			return false;
		}
		return inReach(BlockPos.containing(feet), assigned, cfg);
	}

	/** 与四向走法一致：XZ+Y 曼哈顿，避免欧氏低估铺矩形。 */
	private static double heuristic(BlockPos foot, BlockPos assigned, SiegePathCostConfig cfg) {
		int dx = Math.abs(foot.getX() - assigned.getX());
		int dy = Math.abs(foot.getY() - assigned.getY());
		int dz = Math.abs(foot.getZ() - assigned.getZ());
		return Math.max(0.0D, dx + dy + dz - cfg.reach) * cfg.airCost;
	}

	@FunctionalInterface
	private interface NeighborSink {
		void accept(BlockPos from, BlockPos dest, double fromG, Step step);
	}

	private record Step(List<BreachAction> actions, int cost) {
	}

	private record ClearResult(int cost, boolean blocked) {
		static final ClearResult BLOCKED = new ClearResult(0, true);

		static ClearResult ok(int cost) {
			return new ClearResult(cost, false);
		}
	}

	private record SupportResult(int cost, boolean blocked) {
		static final SupportResult BLOCKED = new SupportResult(0, true);

		static SupportResult ok(int cost) {
			return new SupportResult(cost, false);
		}
	}

	private record Frontier(double f, double g, BlockPos pos, int gen) implements Comparable<Frontier> {
		@Override
		public int compareTo(Frontier other) {
			int cmp = Double.compare(f, other.f);
			if (cmp != 0) {
				return cmp;
			}
			int gCmp = Double.compare(other.g, g);
			if (gCmp != 0) {
				return gCmp;
			}
			return Integer.compare(other.gen, gen);
		}
	}

	private record CameFrom(BlockPos prev, List<BreachAction> actions, double g) {
		private CameFrom {
			actions = List.copyOf(actions);
		}
	}

	private record EdgeCand(BlockPos from, BlockPos dest, double f, double g, Step step) implements Comparable<EdgeCand> {
		@Override
		public int compareTo(EdgeCand other) {
			int cmp = Double.compare(f, other.f);
			if (cmp != 0) {
				return cmp;
			}
			return Double.compare(other.g, g);
		}
	}

	private static final class FrontierSlot {
		final List<EdgeCand> edges;

		FrontierSlot(List<EdgeCand> edges) {
			this.edges = edges;
		}
	}

	private record EdgePick(FrontierSlot slot, int index, EdgeCand edge) {
	}

	private record GreedyOutcome(BlockPos entry, double g, int expanded, Map<Long, CameFrom> came) {
	}

	private static final class SearchCtx {
		final Level level;
		final BlockPos assigned;
		final SiegePathCostConfig cfg;
		final int minX;
		final int maxX;
		final int minY;
		final int maxY;
		final int minZ;
		final int maxZ;
		final PriorityQueue<Frontier> open = new PriorityQueue<>();
		final Map<Long, Double> bestG = new HashMap<>();
		final Map<Long, CameFrom> came = new HashMap<>();
		int gen;

		SearchCtx(
				Level level,
				BlockPos assigned,
				SiegePathCostConfig cfg,
				int minX,
				int maxX,
				int minY,
				int maxY,
				int minZ,
				int maxZ
		) {
			this.level = level;
			this.assigned = assigned;
			this.cfg = cfg;
			this.minX = minX;
			this.maxX = maxX;
			this.minY = minY;
			this.maxY = maxY;
			this.minZ = minZ;
			this.maxZ = maxZ;
		}

		void seed(BlockPos start) {
			bestG.put(start.asLong(), 0.0D);
			open.add(new Frontier(heuristic(start, assigned, cfg), 0.0D, start, gen++));
		}

		boolean inSearchBox(BlockPos pos) {
			return pos.getX() >= minX && pos.getX() <= maxX
					&& pos.getY() >= minY && pos.getY() <= maxY
					&& pos.getZ() >= minZ && pos.getZ() <= maxZ;
		}

		boolean stale(Frontier cur) {
			Double known = bestG.get(cur.pos.asLong());
			return known == null || cur.g > known + 1.0E-6D;
		}
	}
}
