package me.sand.minecraftaimod.agent;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.FallingBlock;

import java.util.*;

public class MazeGenerator {

    private final int width;
    private final int length;
    private final int wallHeight;
    private final Random random;

    private boolean[][] visited;
    private boolean[][][] walls; // [x][z][direction] — N=0, E=1, S=2, W=3
    private int[][] heights;     // Tracks floor elevation relative to origin.getY()

    public BlockPos startPos;
    public BlockPos endPos;

    public boolean lavaEnabled = false;

    private List<BlockState> solidBlocks = null;

    public MazeGenerator(int width, int length, int wallHeight, ServerWorld world) {
        this(width, length, wallHeight, new Random().nextLong(), world);
    }

    public MazeGenerator(int width, int length, int wallHeight, long seed, ServerWorld world) {
        this.width = width;
        this.length = length;
        this.wallHeight = wallHeight;
        this.random = new Random(seed);
        buildSolidBlockList(world);
    }

    private void buildSolidBlockList(ServerWorld world) {
        solidBlocks = new ArrayList<>();
        Set<Block> excluded = Set.of(
                Blocks.BEDROCK, Blocks.BARRIER, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK,
                Blocks.REPEATING_COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW, Blocks.SPAWNER,
                Blocks.TNT, Blocks.INFESTED_STONE, Blocks.INFESTED_COBBLESTONE, Blocks.INFESTED_STONE_BRICKS,
                Blocks.INFESTED_MOSSY_STONE_BRICKS, Blocks.INFESTED_CRACKED_STONE_BRICKS, Blocks.INFESTED_CHISELED_STONE_BRICKS,
                Blocks.INFESTED_DEEPSLATE, Blocks.REINFORCED_DEEPSLATE, Blocks.BUDDING_AMETHYST, Blocks.SPONGE,
                Blocks.WET_SPONGE, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.END_PORTAL_FRAME, Blocks.RESPAWN_ANCHOR
        );

        BlockPos testPos = BlockPos.ORIGIN;
        for (Block block : Registries.BLOCK) {
            BlockState state = block.getDefaultState();
            if (excluded.contains(block)) continue;
            if (!state.isOpaque()) continue;
            if (!Block.isShapeFullCube(state.getCollisionShape(world, testPos))) continue;
            if (block instanceof FallingBlock) continue;

            // Prevent fire hazards from lava contact
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOOL) || state.isIn(BlockTags.LEAVES)) {
                continue;
            }

            solidBlocks.add(state);
        }

        if (solidBlocks.isEmpty()) {
            solidBlocks.add(Blocks.STONE.getDefaultState());
        }
        System.out.println("[MazeGen] Loaded " + solidBlocks.size() + " non-flammable full-cube block types");
    }

    private BlockState randomSolidBlock() {
        return solidBlocks.get(random.nextInt(solidBlocks.size()));
    }

    public void generate(ServerWorld world, BlockPos origin) {
        if (solidBlocks == null) {
            buildSolidBlockList(world);
        }
        generateGrid();
        buildInWorld(world, origin);
        if (lavaEnabled) {
            addLavaHazards(world, origin);
        }
    }

    private void generateGrid() {
        visited = new boolean[width][length];
        heights = new int[width][length];
        walls = new boolean[width][length][4];

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < length; z++) {
                Arrays.fill(walls[x][z], true);
            }
        }

        carve(0, 0);
    }

    private void carve(int cx, int cz) {
        visited[cx][cz] = true;

        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        int[] order = {0, 1, 2, 3};
        shuffle(order);

        for (int i : order) {
            int nx = cx + dirs[i][0];
            int nz = cz + dirs[i][1];

            if (nx >= 0 && nx < width && nz >= 0 && nz < length && !visited[nx][nz]) {
                walls[cx][cz][i] = false;
                walls[nx][nz][(i + 2) % 4] = false;

                // Phase 1: flat terrain — height variation disabled so agent learns
                // basic navigation before dealing with jumps
                heights[nx][nz] = heights[cx][cz];
                carve(nx, nz);
            }
        }
    }

    private void shuffle(int[] array) {
        for (int i = array.length - 1; i > 0; i--) {
            int index = random.nextInt(i + 1);
            int temp = array[index];
            array[index] = array[i];
            array[i] = temp;
        }
    }
    private void buildInWorld(ServerWorld world, BlockPos origin) {
        int totalX = width * 2 + 1;
        int totalZ = length * 2 + 1;
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();

        // Pass 1 & 2: Fill solid envelope structural footprint safely
        for (int x = 0; x < totalX; x++) {
            for (int z = 0; z < totalZ; z++) {
                // FIXED OPTION: Place a solid bedrock anchor floor layer under the whole maze
                world.setBlockState(new BlockPos(ox + x, oy - 2, oz + z), Blocks.BEDROCK.getDefaultState());

                world.setBlockState(new BlockPos(ox + x, oy - 1, oz + z), randomSolidBlock());
                BlockState structuralBlock = randomSolidBlock();
                for (int y = 0; y < wallHeight; y++) {
                    world.setBlockState(new BlockPos(ox + x, oy + y, oz + z), structuralBlock);
                }
                world.setBlockState(new BlockPos(ox + x, oy + wallHeight, oz + z), randomSolidBlock());
            }
        }

        // Pass 3: Hollow pathways and repair floor layers dynamically
        for (int cx = 0; cx < width; cx++) {
            for (int cz = 0; cz < length; cz++) {
                int wx = cx * 2 + 1;
                int wz = cz * 2 + 1;
                int currentCellHeight = heights[cx][cz];

                // Fill space all the way up to the currentCellHeight floor level
                for (int y = 0; y < currentCellHeight; y++) {
                    world.setBlockState(new BlockPos(ox + wx, oy + y, oz + wz), randomSolidBlock());
                }
                // Clear the walking space above the floor level
                for (int y = currentCellHeight; y < wallHeight; y++) {
                    world.setBlockState(new BlockPos(ox + wx, oy + y, oz + wz), Blocks.AIR.getDefaultState());
                }

                // Handle corridor transitions cleanly without floor holes
                if (!walls[cx][cz][0] && cz > 0) { // North
                    int neighborHeight = heights[cx][cz - 1];
                    int lowestFloor = Math.min(currentCellHeight, neighborHeight);
                    for (int y = 0; y < lowestFloor; y++)
                        world.setBlockState(new BlockPos(ox + wx, oy + y, oz + wz - 1), randomSolidBlock());
                    for (int y = lowestFloor; y < wallHeight; y++)
                        world.setBlockState(new BlockPos(ox + wx, oy + y, oz + wz - 1), Blocks.AIR.getDefaultState());
                }
                if (!walls[cx][cz][1] && cx < width - 1) { // East
                    int neighborHeight = heights[cx + 1][cz];
                    int lowestFloor = Math.min(currentCellHeight, neighborHeight);
                    for (int y = 0; y < lowestFloor; y++)
                        world.setBlockState(new BlockPos(ox + wx + 1, oy + y, oz + wz), randomSolidBlock());
                    for (int y = lowestFloor; y < wallHeight; y++)
                        world.setBlockState(new BlockPos(ox + wx + 1, oy + y, oz + wz), Blocks.AIR.getDefaultState());
                }
                if (!walls[cx][cz][2] && cz < length - 1) { // South
                    int neighborHeight = heights[cx][cz + 1];
                    int lowestFloor = Math.min(currentCellHeight, neighborHeight);
                    for (int y = 0; y < lowestFloor; y++)
                        world.setBlockState(new BlockPos(ox + wx, oy + y, oz + wz + 1), randomSolidBlock());
                    for (int y = lowestFloor; y < wallHeight; y++)
                        world.setBlockState(new BlockPos(ox + wx, oy + y, oz + wz + 1), Blocks.AIR.getDefaultState());
                }
                if (!walls[cx][cz][3] && cx > 0) { // West
                    int neighborHeight = heights[cx - 1][cz];
                    int lowestFloor = Math.min(currentCellHeight, neighborHeight);
                    for (int y = 0; y < lowestFloor; y++)
                        world.setBlockState(new BlockPos(ox + wx - 1, oy + y, oz + wz), randomSolidBlock());
                    for (int y = lowestFloor; y < wallHeight; y++)
                        world.setBlockState(new BlockPos(ox + wx - 1, oy + y, oz + wz), Blocks.AIR.getDefaultState());
                }
            }
        }

        // Configure dynamic coordinates (FIXED 2D Array compilation error)
        startPos = new BlockPos(ox + 1, oy + heights[0][0], oz + 1);

        int exitX = (width - 1) * 2 + 1;
        int exitZ = (length - 1) * 2 + 1;
        int exitHeight = heights[width - 1][length - 1];
        endPos = new BlockPos(ox + exitX, oy + exitHeight, oz + exitZ);

        // Clear exit walking space
        for (int y = exitHeight; y < wallHeight; y++) {
            world.setBlockState(new BlockPos(ox + exitX, oy + y, oz + exitZ), Blocks.AIR.getDefaultState());
        }

        // Punch exit hole through outer structural frame
        for (int y = exitHeight; y < wallHeight; y++) {
            world.setBlockState(new BlockPos(ox + exitX + 1, oy + y, oz + exitZ), Blocks.AIR.getDefaultState());
        }

        // Set goal verification block
        world.setBlockState(new BlockPos(ox + exitX, oy + exitHeight - 1, oz + exitZ), Blocks.DIAMOND_BLOCK.getDefaultState());

        // Pass 4: Place fire safe liquid hazards
//        addLavaHazards(world, origin);
    }

    private void addLavaHazards(ServerWorld world, BlockPos origin) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();

        for (int cx = 0; cx < width; cx++) {
            for (int cz = 0; cz < length; cz++) {
                if ((cx == 0 && cz == 0) || (cx == width - 1 && cz == length - 1)) {
                    continue;
                }

                int openPaths = 0;
                for (int d = 0; d < 4; d++) {
                    if (!walls[cx][cz][d]) openPaths++;
                }

                // Only add hazard when height >= 1 so there is room below the walkable floor
                if (openPaths == 1) {
                    int wx = cx * 2 + 1;
                    int wz = cz * 2 + 1;
                    int floorY = oy + heights[cx][cz];

//                    BlockState liquidBlock = (random.nextBoolean())
//                            ? Blocks.LAVA.getDefaultState()
//                            : Blocks.WATER.getDefaultState();

                    // Place liquid 2 levels below the walkable floor so floorY-1 stays solid.
                    // The agent's block scan can detect it; the agent cannot fall in by walking.
                    world.setBlockState(new BlockPos(ox + wx, oy - 1, oz + wz),
                            Blocks.LAVA.getDefaultState());
                    world.setBlockState(new BlockPos(ox + wx, oy - 2, oz + wz),
                            Blocks.BEDROCK.getDefaultState());
                    // floorY - 1 is left as the solid floor set by buildInWorld

                    // Contain the liquid at the new depth
                    if (!walls[cx][cz][0]) {
                        world.setBlockState(new BlockPos(ox + wx, floorY - 2, oz + wz - 1), randomSolidBlock());
                    }
                    if (!walls[cx][cz][1]) {
                        world.setBlockState(new BlockPos(ox + wx + 1, floorY - 2, oz + wz), randomSolidBlock());
                    }
                    if (!walls[cx][cz][2]) {
                        world.setBlockState(new BlockPos(ox + wx, floorY - 2, oz + wz + 1), randomSolidBlock());
                    }
                    if (!walls[cx][cz][3]) {
                        world.setBlockState(new BlockPos(ox + wx - 1, floorY - 2, oz + wz), randomSolidBlock());
                    }
                }
            }
        }
    }
}
