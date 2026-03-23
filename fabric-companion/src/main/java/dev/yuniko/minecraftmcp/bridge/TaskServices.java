package dev.yuniko.minecraftmcp.bridge;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

final class TaskServices {
    interface Hooks {
        void digBlockSync(BlockPos pos) throws Exception;
        void moveToPosition(Vec3d target, double range, long timeoutMs, boolean allowJump, boolean clearSoftObstructions) throws Exception;
        boolean isDigReachable(BlockPos pos);
        boolean canRepositionForDig(BlockPos pos);
        boolean hasClearableDigObstruction(BlockPos pos);
        void sendJobProgress(String channel, String message);
    }

    record HarvestWoodResult(int requested, int collected, int processed, String filter, BlockPos position) {
    }

    record MineCobblestoneResult(int requested, int collected, int processed, String block, BlockPos position) {
    }

    record HarvestTarget(BlockPos position, String blockId) {
    }

    record MineTarget(BlockPos position, String blockId) {
    }

    private final Hooks hooks;

    TaskServices(Hooks hooks) {
        this.hooks = hooks;
    }

    HarvestWoodResult harvestWoodSync(int amount, String preferredType, int maxRadius, int reportEvery) throws Exception {
        String normalizedType = normalizeHarvestType(preferredType);
        int startingCount = countMatchingLogsInInventory(normalizedType);
        int processed = 0;
        int lastCollected = 0;
        Set<BlockPos> ignoredTargets = new HashSet<>();

        while (lastCollected < amount) {
            HarvestTarget target = findNearestHarvestTarget(normalizedType, maxRadius, ignoredTargets);
            if (target == null) {
                break;
            }

            try {
                hooks.digBlockSync(target.position());
                collectDropsNearSync(target.position());
                processed++;
                lastCollected = Math.max(0, countMatchingLogsInInventory(normalizedType) - startingCount);
                ignoredTargets.clear();

                if (processed % reportEvery == 0 || lastCollected >= amount) {
                    hooks.sendJobProgress("harvest-wood", "Progress: " + lastCollected + "/" + amount + " logs collected after processing " + processed + " block(s).");
                }
            } catch (Exception error) {
                ignoredTargets.add(target.position());
                if (ignoredTargets.size() >= 24) {
                    break;
                }
            }
        }

        int collected = Math.max(0, countMatchingLogsInInventory(normalizedType) - startingCount);
        BlockPos finalPosition = currentPlayerBlockPos();
        if (collected < amount) {
            throw new IllegalStateException(
                "Stopped after collecting " + collected + "/" + amount + " log(s). No more valid targets were found within " + maxRadius + " blocks."
            );
        }

        return new HarvestWoodResult(amount, collected, processed, normalizedType, finalPosition);
    }

    MineCobblestoneResult mineCobblestoneSync(int amount, int maxRadius, int reportEvery) throws Exception {
        Set<String> targetBlocks = Set.of("stone", "cobblestone");
        String targetItem = "cobblestone";
        int startingCount = countMatchingInventoryItems(targetItem);
        int processed = 0;
        int lastCollected = 0;
        Set<BlockPos> ignoredTargets = new HashSet<>();

        while (lastCollected < amount) {
            MineTarget target = findNearestMineTarget(targetBlocks, maxRadius, ignoredTargets);
            if (target == null) {
                break;
            }

            try {
                hooks.digBlockSync(target.position());
                collectDropsNearSync(target.position());
                processed++;
                lastCollected = Math.max(0, countMatchingInventoryItems(targetItem) - startingCount);
                ignoredTargets.clear();

                if (processed % reportEvery == 0 || lastCollected >= amount) {
                    hooks.sendJobProgress(
                        "mine-cobblestone",
                        "Progress: " + lastCollected + "/" + amount + " cobblestone collected after processing " + processed + " block(s)."
                    );
                }
            } catch (Exception error) {
                ignoredTargets.add(target.position());
                if (ignoredTargets.size() >= 24) {
                    break;
                }
            }
        }

        int collected = Math.max(0, countMatchingInventoryItems(targetItem) - startingCount);
        BlockPos finalPosition = currentPlayerBlockPos();
        if (collected < amount) {
            throw new IllegalStateException(
                "Stopped after collecting " + collected + "/" + amount + " cobblestone. No more valid stone or cobblestone targets were found within " + maxRadius + " blocks."
            );
        }

        return new MineCobblestoneResult(amount, collected, processed, targetItem, finalPosition);
    }

    private void collectDropsNearSync(BlockPos pos) throws Exception {
        int targetY = Math.max(0, pos.getY() - 1);
        hooks.moveToPosition(new Vec3d(pos.getX() + 0.5D, targetY, pos.getZ() + 0.5D), 1.35D, 8000L, true, true);
        Thread.sleep(200L);
    }

    private HarvestTarget findNearestHarvestTarget(String preferredType, int maxRadius, Set<BlockPos> ignoredTargets) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return null;
        }

        BlockPos origin = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
        HarvestTarget bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        int verticalRadius = Math.min(12, maxRadius);

        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (ignoredTargets.contains(candidate)) {
                        continue;
                    }

                    BlockState state = world.getBlockState(candidate);
                    Identifier id = Registries.BLOCK.getId(state.getBlock());
                    if (!matchesHarvestType(id.toString(), preferredType)) {
                        continue;
                    }

                    boolean reachableNow = hooks.isDigReachable(candidate);
                    boolean canReposition = hooks.canRepositionForDig(candidate);
                    if (!reachableNow && !canReposition) {
                        continue;
                    }

                    double horizontalDistance = Math.pow(candidate.getX() - origin.getX(), 2) + Math.pow(candidate.getZ() - origin.getZ(), 2);
                    double verticalPenalty = Math.max(0, candidate.getY() - origin.getY()) * 5.0D + Math.abs(candidate.getY() - origin.getY()) * 1.5D;
                    double score = horizontalDistance + verticalPenalty;
                    if (score < bestScore) {
                        bestScore = score;
                        bestTarget = new HarvestTarget(candidate, id.toString());
                    }
                }
            }
        }

        return bestTarget;
    }

    private int countMatchingLogsInInventory(String preferredType) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return 0;
        }

        int total = 0;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            if (matchesHarvestType(itemId, preferredType)) {
                total += stack.getCount();
            }
        }

        return total;
    }

    private MineTarget findNearestMineTarget(Set<String> blockTypes, int maxRadius, Set<BlockPos> ignoredTargets) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return null;
        }

        BlockPos origin = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
        MineTarget bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        int verticalRadius = Math.min(16, maxRadius);

        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (ignoredTargets.contains(candidate)) {
                        continue;
                    }

                    BlockState state = world.getBlockState(candidate);
                    Identifier id = Registries.BLOCK.getId(state.getBlock());
                    if (!matchesAnyExactBlockType(id.toString(), blockTypes)) {
                        continue;
                    }

                    boolean reachableNow = hooks.isDigReachable(candidate);
                    boolean canReposition = hooks.canRepositionForDig(candidate);
                    boolean obstruction = !reachableNow && !canReposition && hooks.hasClearableDigObstruction(candidate);
                    if (!reachableNow && !canReposition && !obstruction) {
                        continue;
                    }

                    double horizontalDistance = Math.pow(candidate.getX() - origin.getX(), 2) + Math.pow(candidate.getZ() - origin.getZ(), 2);
                    double verticalPenalty = Math.max(0, candidate.getY() - origin.getY()) * 6.0D + Math.abs(candidate.getY() - origin.getY()) * 2.0D;
                    double obstructionPenalty = obstruction ? 3.0D : 0.0D;
                    double score = horizontalDistance + verticalPenalty + obstructionPenalty;
                    if (score < bestScore) {
                        bestScore = score;
                        bestTarget = new MineTarget(candidate, id.toString());
                    }
                }
            }
        }

        return bestTarget;
    }

    private int countMatchingInventoryItems(String targetItem) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return 0;
        }

        int total = 0;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            if (matchesExactBlockType(itemId, targetItem)) {
                total += stack.getCount();
            }
        }

        return total;
    }

    private String normalizeHarvestType(String preferredType) {
        if (preferredType == null || preferredType.isBlank()) {
            return null;
        }

        String normalized = preferredType.toLowerCase();
        if (normalized.contains(":")) {
            normalized = normalized.substring(normalized.indexOf(':') + 1);
        }

        if (!normalized.endsWith("_log")) {
            normalized = normalized + "_log";
        }

        return normalized;
    }

    private boolean matchesHarvestType(String id, String preferredType) {
        String normalized = id.toLowerCase();
        int separatorIndex = normalized.indexOf(':');
        String localId = separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
        if (preferredType != null) {
            return localId.equals(preferredType);
        }

        return localId.endsWith("_log");
    }

    private boolean matchesExactBlockType(String id, String targetType) {
        String normalized = id.toLowerCase();
        int separatorIndex = normalized.indexOf(':');
        String localId = separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
        return localId.equals(targetType.toLowerCase());
    }

    private boolean matchesAnyExactBlockType(String id, Set<String> targetTypes) {
        for (String targetType : targetTypes) {
            if (matchesExactBlockType(id, targetType)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos currentPlayerBlockPos() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return BlockPos.ORIGIN;
        }

        return BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
    }
}
