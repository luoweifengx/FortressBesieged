package luowei.fortress_besieged.siege.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

/**
 * Places the medieval courtyard castle from the modular blueprint:
 * outer wall + gatehouse, four watchtowers, barracks, warehouse, fountain, keep.
 * Local +Z is into the courtyard; the gate at z=0 faces {@code gateFacing}.
 */
public final class MedievalCastleBuilder {
	public static final int SIZE_X = 41;
	public static final int SIZE_Z = 41;
	public static final int SIZE_Y = 21;
	public static final int GATE_X = 20;

	private final ServerLevel level;
	private final BlockPos gateCenter;
	private final Direction right;
	private final Direction back;
	private final Rotation rotation;
	private int placed;

	private MedievalCastleBuilder(ServerLevel level, BlockPos gateCenter, Direction gateFacing) {
		this.level = level;
		this.gateCenter = gateCenter;
		this.right = gateFacing.getClockWise();
		this.back = gateFacing.getOpposite();
		this.rotation = switch (gateFacing) {
			case EAST -> Rotation.CLOCKWISE_90;
			case SOUTH -> Rotation.CLOCKWISE_180;
			case WEST -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	/**
	 * @param gateCenter ground block at the middle of the gate threshold
	 * @param gateFacing direction the front of the gate faces (out of the castle)
	 */
	public static int place(ServerLevel level, BlockPos gateCenter, Direction gateFacing) {
		Direction facing = gateFacing.getAxis().isHorizontal() ? gateFacing : Direction.NORTH;
		return new MedievalCastleBuilder(level, gateCenter, facing).build();
	}

	private int build() {
		clearVolume();
		placeGround();
		placeOuterWalls();
		placeGatehouse();
		placeWatchtower(0, 0);
		placeWatchtower(36, 0);
		placeWatchtower(0, 36);
		placeWatchtower(36, 36);
		placeBarracks();
		placeWarehouse();
		placeFountain();
		placeKeep();
		placeYardDetails();
		return placed;
	}

	private void clearVolume() {
		for (int x = -1; x <= SIZE_X; x++) {
			for (int z = -1; z <= SIZE_Z; z++) {
				for (int y = 0; y < SIZE_Y; y++) {
					level.setBlock(toWorld(x, y, z), Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
	}

	private void placeGround() {
		fill(-1, -1, -1, SIZE_X, -1, SIZE_Z, Blocks.DIRT.defaultBlockState());
		fill(-1, 0, -1, SIZE_X, 0, SIZE_Z, Blocks.GRASS_BLOCK.defaultBlockState());
		fill(19, 0, 1, 21, 0, 27, cobble());
		fill(5, 0, 18, 35, 0, 20, cobble());
		fill(15, 0, 15, 25, 0, 25, cobble());
	}

	private void placeOuterWalls() {
		wallRun(5, 0, 14, 1, false);
		wallRun(27, 0, 35, 1, false);
		wallRun(5, 39, 35, 40, false);
		wallRun(0, 5, 1, 35, true);
		wallRun(39, 5, 40, 35, true);
	}

	private void wallRun(int x1, int z1, int x2, int z2, boolean alongZ) {
		fill(x1, 1, z1, x2, 1, z2, cobble());
		fill(x1, 2, z1, x2, 6, z2, brick());
		int walkY = 6;
		if (alongZ) {
			int innerX = x1 < 20 ? x2 + 1 : x1 - 1;
			fill(innerX, walkY, z1, innerX, walkY, z2, slab(Blocks.STONE_BRICK_SLAB));
			for (int z = z1; z <= z2; z += 2) {
				int outerX = x1 < 20 ? x1 : x2;
				set(outerX, 7, z, brick());
			}
		} else {
			int innerZ = z1 < 20 ? z2 + 1 : z1 - 1;
			fill(x1, walkY, innerZ, x2, walkY, innerZ, slab(Blocks.STONE_BRICK_SLAB));
			for (int x = x1; x <= x2; x += 2) {
				int outerZ = z1 < 20 ? z1 : z2;
				set(x, 7, outerZ, brick());
			}
		}
	}

	private void placeGatehouse() {
		gateTower(14, 0);
		gateTower(22, 0);
		fill(19, 5, 0, 21, 11, 1, brick());
		fill(19, 11, 0, 21, 11, 4, brick());
		fill(19, 1, 0, 21, 4, 0, Blocks.AIR.defaultBlockState());
		set(19, 1, 0, door(Blocks.DARK_OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER));
		set(19, 2, 0, door(Blocks.DARK_OAK_DOOR, Direction.NORTH, DoorHingeSide.LEFT, DoubleBlockHalf.UPPER));
		set(20, 1, 0, door(Blocks.DARK_OAK_DOOR, Direction.NORTH, DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER));
		set(20, 2, 0, door(Blocks.DARK_OAK_DOOR, Direction.NORTH, DoorHingeSide.RIGHT, DoubleBlockHalf.UPPER));
		set(21, 1, 0, Blocks.IRON_BARS.defaultBlockState());
		set(21, 2, 0, Blocks.IRON_BARS.defaultBlockState());
		set(21, 3, 0, Blocks.IRON_BARS.defaultBlockState());
		set(21, 4, 0, Blocks.IRON_BARS.defaultBlockState());
		fill(19, 3, 0, 20, 4, 0, Blocks.AIR.defaultBlockState());
		set(18, 5, 0, stairs(Blocks.STONE_BRICK_STAIRS, Direction.WEST, Half.BOTTOM));
		set(22, 5, 0, stairs(Blocks.STONE_BRICK_STAIRS, Direction.EAST, Half.BOTTOM));
		set(19, 5, 0, stairs(Blocks.STONE_BRICK_STAIRS, Direction.SOUTH, Half.TOP));
		set(21, 5, 0, stairs(Blocks.STONE_BRICK_STAIRS, Direction.SOUTH, Half.TOP));
		fill(19, 6, 2, 21, 6, 4, cobble());
		poleAndBanner(16, 13, 2, Direction.NORTH);
		poleAndBanner(24, 13, 2, Direction.NORTH);
	}

	private void gateTower(int x, int z) {
		fill(x, 1, z, x + 4, 1, z + 4, cobble());
		fill(x, 2, z, x + 4, 11, z + 4, brick());
		hollow(x, 2, z, x + 4, 10, z + 4);
		fill(x, 8, z, x + 4, 8, z + 4, log(Direction.Axis.X));
		set(x + 2, 6, z, Blocks.GLASS_PANE.defaultBlockState());
		set(x + 2, 7, z, Blocks.GLASS_PANE.defaultBlockState());
		set(x + 2, 6, z + 4, Blocks.GLASS_PANE.defaultBlockState());
		for (int dx = 0; dx <= 4; dx += 2) {
			set(x + dx, 12, z, brick());
			set(x + dx, 12, z + 4, brick());
		}
		for (int dz = 2; dz <= 4; dz += 2) {
			set(x, 12, z + dz, brick());
			set(x + 4, 12, z + dz, brick());
		}
		wallBanner(x + 2, 9, z, Direction.NORTH);
	}

	private void placeWatchtower(int x, int z) {
		for (int y = 1; y <= 2; y++) {
			octagonSolid(x, z, y, 5, cobble());
		}
		for (int y = 3; y <= 9; y++) {
			octagonRing(x, z, y, 5, brick());
		}
		fill(x + 1, 1, z + 1, x + 3, 1, z + 3, cobble());
		set(x + 2, 5, z, Blocks.IRON_BARS.defaultBlockState());
		set(x + 2, 6, z, Blocks.IRON_BARS.defaultBlockState());
		set(x + 2, 5, z + 4, Blocks.IRON_BARS.defaultBlockState());
		set(x, 5, z + 2, Blocks.IRON_BARS.defaultBlockState());
		set(x + 4, 5, z + 2, Blocks.IRON_BARS.defaultBlockState());
		fill(x - 1, 10, z - 1, x + 5, 10, z + 5, Blocks.OAK_PLANKS.defaultBlockState());
		fenceRing(x - 1, 11, z - 1, x + 5, z + 5);
		pyramidRoof(x - 1, z - 1, 12, 7);
		poleAndBanner(x + 2, 16, z + 2, Direction.NORTH);
	}

	private void placeBarracks() {
		int x1 = 4;
		int z1 = 14;
		int x2 = 12;
		int z2 = 23;
		fill(x1, 1, z1, x2, 1, z2, cobble());
		fill(x1, 2, z1, x2, 2, z2, Blocks.OAK_PLANKS.defaultBlockState());
		fill(x1, 2, z1, x2, 5, z1, brick());
		fill(x1, 2, z2, x2, 5, z2, brick());
		fill(x1, 2, z1, x1, 5, z2, brick());
		fill(x2, 2, z1, x2, 5, z2, brick());
		cornerLogs(x1, z1, x2, z2, 2, 5);
		set(x2, 2, 18, Blocks.AIR.defaultBlockState());
		set(x2, 3, 18, Blocks.AIR.defaultBlockState());
		set(x2, 2, 19, Blocks.AIR.defaultBlockState());
		set(x2, 3, 19, Blocks.AIR.defaultBlockState());
		placeDoubleDoor(x2, 2, 18, Direction.EAST);
		set(7, 3, z1, Blocks.GLASS_PANE.defaultBlockState());
		set(8, 3, z1, Blocks.GLASS_PANE.defaultBlockState());
		set(9, 3, z1, Blocks.GLASS_PANE.defaultBlockState());
		gableRoofX(x1, z1, x2, z2, 6, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB);
		placeBed(5, 2, 16, Direction.SOUTH);
		placeBed(5, 2, 20, Direction.NORTH);
		placeBed(10, 2, 16, Direction.SOUTH);
		placeBed(10, 2, 20, Direction.NORTH);
		wallBanner(8, 4, z1, Direction.NORTH);
		poleAndBanner(8, 10, z1, Direction.NORTH);
	}

	private void placeWarehouse() {
		int x1 = 28;
		int z1 = 14;
		int x2 = 36;
		int z2 = 23;
		fill(x1, 1, z1, x2, 1, z2, cobble());
		fill(x1, 2, z1, x2, 2, z2, Blocks.OAK_PLANKS.defaultBlockState());
		fill(x1, 2, z1, x2, 5, z1, brick());
		fill(x1, 2, z2, x2, 5, z2, brick());
		fill(x1, 2, z1, x1, 5, z2, brick());
		fill(x2, 2, z1, x2, 5, z2, brick());
		cornerLogs(x1, z1, x2, z2, 2, 5);
		set(x1, 2, 18, Blocks.AIR.defaultBlockState());
		set(x1, 3, 18, Blocks.AIR.defaultBlockState());
		set(x1, 2, 19, Blocks.AIR.defaultBlockState());
		set(x1, 3, 19, Blocks.AIR.defaultBlockState());
		placeDoubleDoor(x1, 2, 18, Direction.WEST);
		set(31, 3, z1, Blocks.GLASS_PANE.defaultBlockState());
		set(32, 3, z1, Blocks.GLASS_PANE.defaultBlockState());
		set(33, 3, z1, Blocks.GLASS_PANE.defaultBlockState());
		gableRoofX(x1, z1, x2, z2, 6, Blocks.OAK_STAIRS, Blocks.OAK_SLAB);
		set(30, 2, 16, chest(Direction.EAST));
		set(30, 2, 17, chest(Direction.EAST));
		set(30, 2, 21, chest(Direction.EAST));
		set(34, 2, 16, chest(Direction.WEST));
		set(34, 2, 17, chest(Direction.WEST));
		fill(32, 2, 16, 33, 3, 18, Blocks.HAY_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
		fill(32, 2, 20, 33, 3, 22, Blocks.HAY_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
		fill(31, 2, 19, 33, 3, 19, Blocks.SPRUCE_FENCE.defaultBlockState());
	}

	private void placeFountain() {
		fill(17, 1, 17, 23, 1, 23, brick());
		ringStairs(18, 18, 22, 22, 1);
		fill(19, 1, 19, 21, 1, 21, cobble());
		fill(19, 2, 19, 21, 2, 21, Blocks.WATER.defaultBlockState());
		set(20, 2, 20, brick());
		set(20, 3, 20, brick());
		set(20, 4, 20, Blocks.WATER.defaultBlockState());
		lampPost(16, 16);
		lampPost(24, 16);
		lampPost(16, 24);
		lampPost(24, 24);
		bush(16, 1, 18);
		bush(23, 1, 18);
		bush(16, 1, 22);
		bush(23, 1, 22);
	}

	private void placeKeep() {
		int x1 = 12;
		int z1 = 27;
		int x2 = 28;
		int z2 = 38;
		fill(x1, 1, z1, x2, 1, z2, cobble());
		fill(x1, 2, z1, x2, 2, z2, Blocks.OAK_PLANKS.defaultBlockState());
		fill(x1, 2, z1, x2, 7, z1, brick());
		fill(x1, 2, z2, x2, 7, z2, brick());
		fill(x1, 2, z1, x1, 7, z2, brick());
		fill(x2, 2, z1, x2, 7, z2, brick());
		hollow(x1, 3, z1, x2, 6, z2);
		fill(x1, 7, z1, x2, 7, z2, log(Direction.Axis.X));
		fill(x1, 8, z1, 24, 11, z1, Blocks.OAK_PLANKS.defaultBlockState());
		fill(x1, 8, z2, 24, 11, z2, Blocks.OAK_PLANKS.defaultBlockState());
		fill(x1, 8, z1, x1, 11, z2, Blocks.OAK_PLANKS.defaultBlockState());
		fill(24, 8, z1, 24, 11, 32, Blocks.OAK_PLANKS.defaultBlockState());
		set(19, 2, z1, Blocks.AIR.defaultBlockState());
		set(20, 2, z1, Blocks.AIR.defaultBlockState());
		set(19, 3, z1, Blocks.AIR.defaultBlockState());
		set(20, 3, z1, Blocks.AIR.defaultBlockState());
		placeDoubleDoor(19, 2, z1, Direction.SOUTH);
		windowRow(14, 4, z1, 3);
		windowRow(23, 4, z1, 3);
		windowRow(14, 9, z1, 3);
		set(19, 9, z1, Blocks.GLASS_PANE.defaultBlockState());
		set(20, 9, z1, Blocks.GLASS_PANE.defaultBlockState());
		set(21, 9, z1, Blocks.GLASS_PANE.defaultBlockState());
		cornerLogs(x1, z1, 24, z2, 2, 11);
		gableRoofX(x1, z1, 24, z2, 12, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB);
		set(16, 13, z1, stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH, Half.BOTTOM));
		set(17, 14, z1, stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH, Half.BOTTOM));
		set(18, 14, z1, Blocks.GLASS_PANE.defaultBlockState());
		keepTower(24, 32);
		wallBanner(20, 5, z1, Direction.SOUTH);
		fill(13, 2, 29, 14, 3, 30, Blocks.BOOKSHELF.defaultBlockState());
		set(16, 2, 29, Blocks.OAK_PLANKS.defaultBlockState());
		set(16, 2, 30, stairs(Blocks.OAK_STAIRS, Direction.WEST, Half.BOTTOM));
	}

	private void keepTower(int x, int z) {
		fill(x, 1, z, x + 4, 1, z + 5, cobble());
		fill(x, 2, z, x + 4, 14, z + 5, brick());
		hollow(x, 2, z, x + 4, 13, z + 5);
		fill(x, 9, z, x + 4, 9, z + 5, log(Direction.Axis.X));
		set(x + 2, 6, z, Blocks.GLASS_PANE.defaultBlockState());
		set(x + 2, 11, z, Blocks.GLASS_PANE.defaultBlockState());
		set(x + 4, 6, z + 2, Blocks.IRON_BARS.defaultBlockState());
		fill(x, 14, z, x + 4, 14, z + 5, Blocks.OAK_PLANKS.defaultBlockState());
		for (int dx = 0; dx <= 4; dx += 4) {
			set(x + dx, 15, z, brick());
			set(x + dx, 15, z + 5, brick());
		}
		pyramidRoof(x, z, 15, 5);
		poleAndBanner(x + 2, 19, z + 2, Direction.SOUTH);
	}

	private void placeYardDetails() {
		tree(6, 8);
		tree(34, 8);
		tree(6, 30);
		tree(34, 30);
		hedge(7, 26, 11, 27);
		hedge(30, 26, 34, 27);
		lampPost(10, 4);
		lampPost(30, 4);
		lampPost(4, 12);
		lampPost(36, 12);
		fill(16, 1, 12, 18, 1, 12, Blocks.OAK_SLAB.defaultBlockState());
		fill(22, 1, 12, 24, 1, 12, Blocks.OAK_SLAB.defaultBlockState());
	}

	private void tree(int x, int z) {
		fill(x, 1, z, x, 4, z, log(Direction.Axis.Y));
		fill(x - 1, 4, z - 1, x + 1, 6, z + 1, leaves());
		set(x, 7, z, leaves());
	}

	private void hedge(int x1, int z1, int x2, int z2) {
		fill(x1, 1, z1, x2, 2, z2, leaves());
	}

	private void bush(int x, int y, int z) {
		fill(x, y, z, x + 1, y + 1, z + 1, leaves());
	}

	private void lampPost(int x, int z) {
		set(x, 1, z, Blocks.OAK_FENCE.defaultBlockState());
		set(x, 2, z, Blocks.OAK_FENCE.defaultBlockState());
		set(x, 3, z, Blocks.LANTERN.defaultBlockState());
	}

	private void windowRow(int x, int y, int z, int count) {
		for (int i = 0; i < count; i++) {
			set(x + i * 2, y, z, Blocks.GLASS_PANE.defaultBlockState());
		}
	}

	private void cornerLogs(int x1, int z1, int x2, int z2, int y1, int y2) {
		fill(x1, y1, z1, x1, y2, z1, log(Direction.Axis.Y));
		fill(x2, y1, z1, x2, y2, z1, log(Direction.Axis.Y));
		fill(x1, y1, z2, x1, y2, z2, log(Direction.Axis.Y));
		fill(x2, y1, z2, x2, y2, z2, log(Direction.Axis.Y));
	}

	private void gableRoofX(int x1, int z1, int x2, int z2, int yBase, Block stair, Block slabBlock) {
		int mid = (x1 + x2) / 2;
		int maxLayer = Math.min(mid - x1, x2 - mid);
		for (int layer = 0; layer <= maxLayer; layer++) {
			int y = yBase + layer;
			int west = x1 + layer;
			int east = x2 - layer;
			if (west == east) {
				fill(west, y, z1, east, y, z2, slabBlock.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
			} else {
				for (int z = z1; z <= z2; z++) {
					set(west, y, z, stairs(stair, Direction.WEST, Half.BOTTOM));
					set(east, y, z, stairs(stair, Direction.EAST, Half.BOTTOM));
				}
			}
		}
	}

	private void pyramidRoof(int x, int z, int y, int size) {
		int s = size;
		int cx = x;
		int cz = z;
		int yy = y;
		while (s >= 1) {
			int x2 = cx + s - 1;
			int z2 = cz + s - 1;
			if (s == 1) {
				set(cx, yy, cz, Blocks.DARK_OAK_SLAB.defaultBlockState());
			} else if (s == 2) {
				fill(cx, yy, cz, x2, yy, z2, Blocks.DARK_OAK_PLANKS.defaultBlockState());
			} else {
				for (int i = cx; i <= x2; i++) {
					set(i, yy, cz, stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH, Half.BOTTOM));
					set(i, yy, z2, stairs(Blocks.DARK_OAK_STAIRS, Direction.SOUTH, Half.BOTTOM));
				}
				for (int j = cz + 1; j <= z2 - 1; j++) {
					set(cx, yy, j, stairs(Blocks.DARK_OAK_STAIRS, Direction.WEST, Half.BOTTOM));
					set(x2, yy, j, stairs(Blocks.DARK_OAK_STAIRS, Direction.EAST, Half.BOTTOM));
				}
				if (s > 3) {
					fill(cx + 1, yy, cz + 1, x2 - 1, yy, z2 - 1, Blocks.DARK_OAK_PLANKS.defaultBlockState());
				}
			}
			cx++;
			cz++;
			s -= 2;
			yy++;
		}
	}

	private void ringStairs(int x1, int z1, int x2, int z2, int y) {
		for (int x = x1; x <= x2; x++) {
			set(x, y, z1, stairs(Blocks.STONE_BRICK_STAIRS, Direction.NORTH, Half.BOTTOM));
			set(x, y, z2, stairs(Blocks.STONE_BRICK_STAIRS, Direction.SOUTH, Half.BOTTOM));
		}
		for (int z = z1 + 1; z <= z2 - 1; z++) {
			set(x1, y, z, stairs(Blocks.STONE_BRICK_STAIRS, Direction.WEST, Half.BOTTOM));
			set(x2, y, z, stairs(Blocks.STONE_BRICK_STAIRS, Direction.EAST, Half.BOTTOM));
		}
	}

	private void fenceRing(int x1, int y, int z1, int x2, int z2) {
		for (int x = x1; x <= x2; x++) {
			set(x, y, z1, Blocks.OAK_FENCE.defaultBlockState());
			set(x, y, z2, Blocks.OAK_FENCE.defaultBlockState());
		}
		for (int z = z1; z <= z2; z++) {
			set(x1, y, z, Blocks.OAK_FENCE.defaultBlockState());
			set(x2, y, z, Blocks.OAK_FENCE.defaultBlockState());
		}
	}

	private void octagonSolid(int x, int z, int y, int size, BlockState state) {
		int x2 = x + size - 1;
		int z2 = z + size - 1;
		for (int i = x; i <= x2; i++) {
			for (int j = z; j <= z2; j++) {
				if ((i == x || i == x2) && (j == z || j == z2)) {
					continue;
				}
				set(i, y, j, state);
			}
		}
	}

	private void octagonRing(int x, int z, int y, int size, BlockState state) {
		int x2 = x + size - 1;
		int z2 = z + size - 1;
		for (int i = x; i <= x2; i++) {
			for (int j = z; j <= z2; j++) {
				boolean edge = i == x || i == x2 || j == z || j == z2;
				boolean corner = (i == x || i == x2) && (j == z || j == z2);
				if (edge && !corner) {
					set(i, y, j, state);
				}
			}
		}
	}

	private void hollow(int x1, int y1, int z1, int x2, int y2, int z2) {
		if (x2 - x1 < 2 || z2 - z1 < 2) {
			return;
		}
		fill(x1 + 1, y1, z1 + 1, x2 - 1, y2, z2 - 1, Blocks.AIR.defaultBlockState());
	}

	private void poleAndBanner(int x, int y, int z, Direction face) {
		set(x, y, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		set(x, y + 1, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		set(x, y + 2, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		wallBanner(x, y + 2, z, face);
	}

	private void wallBanner(int x, int y, int z, Direction face) {
		set(x + face.getStepX(), y, z + face.getStepZ(),
				Blocks.BLUE_WALL_BANNER.defaultBlockState().setValue(WallBannerBlock.FACING, face));
	}

	private void placeDoubleDoor(int x, int y, int z, Direction facing) {
		boolean alongX = facing.getAxis() == Direction.Axis.Z;
		int x2 = alongX ? x + 1 : x;
		int z2 = alongX ? z : z + 1;
		set(x, y, z, door(Blocks.DARK_OAK_DOOR, facing, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER));
		set(x, y + 1, z, door(Blocks.DARK_OAK_DOOR, facing, DoorHingeSide.LEFT, DoubleBlockHalf.UPPER));
		set(x2, y, z2, door(Blocks.DARK_OAK_DOOR, facing, DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER));
		set(x2, y + 1, z2, door(Blocks.DARK_OAK_DOOR, facing, DoorHingeSide.RIGHT, DoubleBlockHalf.UPPER));
	}

	private void placeBed(int x, int y, int z, Direction facing) {
		int hx = x + facing.getStepX();
		int hz = z + facing.getStepZ();
		set(x, y, z, Blocks.BLUE_BED.defaultBlockState()
				.setValue(BedBlock.FACING, facing)
				.setValue(BedBlock.PART, BedPart.FOOT));
		set(hx, y, hz, Blocks.BLUE_BED.defaultBlockState()
				.setValue(BedBlock.FACING, facing)
				.setValue(BedBlock.PART, BedPart.HEAD));
	}

	private BlockState door(Block block, Direction facing, DoorHingeSide hinge, DoubleBlockHalf half) {
		return block.defaultBlockState()
				.setValue(DoorBlock.FACING, facing)
				.setValue(DoorBlock.HINGE, hinge)
				.setValue(DoorBlock.HALF, half);
	}

	private BlockState stairs(Block block, Direction facing, Half half) {
		return block.defaultBlockState()
				.setValue(StairBlock.FACING, facing)
				.setValue(StairBlock.HALF, half)
				.setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
	}

	private BlockState slab(Block block) {
		return block.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
	}

	private BlockState chest(Direction facing) {
		return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
	}

	private BlockState log(Direction.Axis axis) {
		return Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
	}

	private BlockState leaves() {
		return Blocks.OAK_LEAVES.defaultBlockState()
				.setValue(LeavesBlock.PERSISTENT, true)
				.setValue(LeavesBlock.DISTANCE, 1);
	}

	private BlockState brick() {
		return Blocks.STONE_BRICKS.defaultBlockState();
	}

	private BlockState cobble() {
		return Blocks.COBBLESTONE.defaultBlockState();
	}

	private void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
		int xa = Math.min(x1, x2);
		int xb = Math.max(x1, x2);
		int ya = Math.min(y1, y2);
		int yb = Math.max(y1, y2);
		int za = Math.min(z1, z2);
		int zb = Math.max(z1, z2);
		for (int x = xa; x <= xb; x++) {
			for (int y = ya; y <= yb; y++) {
				for (int z = za; z <= zb; z++) {
					set(x, y, z, state);
				}
			}
		}
	}

	private void set(int x, int y, int z, BlockState state) {
		BlockPos pos = toWorld(x, y, z);
		if (level.setBlock(pos, state.rotate(rotation), 3)) {
			placed++;
		}
	}

	private BlockPos toWorld(int x, int y, int z) {
		return gateCenter.relative(right, x - GATE_X).relative(back, z).above(y);
	}
}
