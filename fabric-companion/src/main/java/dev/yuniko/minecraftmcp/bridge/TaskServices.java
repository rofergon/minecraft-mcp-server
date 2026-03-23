package dev.yuniko.minecraftmcp.bridge;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

final class TaskServices {
    interface Hooks {
        void digBlockSync(BlockPos pos) throws Exception;
        void moveToPosition(Vec3d target, double range, long timeoutMs, boolean allowJump, boolean clearSoftObstructions) throws Exception;
        int countMatchingLogsInInventory(String preferredType) throws Exception;
        int countMatchingInventoryItems(String targetItem) throws Exception;
        HarvestTarget findNearestHarvestTarget(String preferredType, int maxRadius, Set<BlockPos> ignoredTargets) throws Exception;
        MineTarget findNearestMineTarget(Set<String> blockTypes, int maxRadius, Set<BlockPos> ignoredTargets) throws Exception;
        BlockPos currentPlayerBlockPos() throws Exception;
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
        int startingCount = hooks.countMatchingLogsInInventory(normalizedType);
        int processed = 0;
        int lastCollected = 0;
        Set<BlockPos> ignoredTargets = new HashSet<>();
        String lastFailure = null;

        while (lastCollected < amount) {
            HarvestTarget target = hooks.findNearestHarvestTarget(normalizedType, maxRadius, ignoredTargets);
            if (target == null) {
                break;
            }

            try {
                hooks.digBlockSync(target.position());
                collectDropsNearSync(target.position());
                processed++;
                lastCollected = Math.max(0, hooks.countMatchingLogsInInventory(normalizedType) - startingCount);
                ignoredTargets.clear();

                if (processed % reportEvery == 0 || lastCollected >= amount) {
                    hooks.sendJobProgress("harvest-wood", "Progress: " + lastCollected + "/" + amount + " logs collected after processing " + processed + " block(s).");
                }
            } catch (Exception error) {
                if (isCriticalFailure(error)) {
                    throw new IllegalStateException("harvest-wood aborted: " + safeMessage(error), error);
                }

                lastFailure = safeMessage(error);
                ignoredTargets.add(target.position());
                if (ignoredTargets.size() >= 24) {
                    break;
                }
            }
        }

        int collected = Math.max(0, hooks.countMatchingLogsInInventory(normalizedType) - startingCount);
        BlockPos finalPosition = hooks.currentPlayerBlockPos();
        if (collected < amount) {
            String suffix = lastFailure == null ? "" : " Last failure: " + lastFailure;
            throw new IllegalStateException(
                "Stopped after collecting " + collected + "/" + amount + " log(s). No more valid targets were found within " + maxRadius + " blocks." + suffix
            );
        }

        return new HarvestWoodResult(amount, collected, processed, normalizedType, finalPosition);
    }

    MineCobblestoneResult mineCobblestoneSync(int amount, int maxRadius, int reportEvery) throws Exception {
        Set<String> targetBlocks = Set.of("stone", "cobblestone");
        String targetItem = "cobblestone";
        int startingCount = hooks.countMatchingInventoryItems(targetItem);
        int processed = 0;
        int lastCollected = 0;
        Set<BlockPos> ignoredTargets = new HashSet<>();
        String lastFailure = null;

        while (lastCollected < amount) {
            MineTarget target = hooks.findNearestMineTarget(targetBlocks, maxRadius, ignoredTargets);
            if (target == null) {
                break;
            }

            try {
                hooks.digBlockSync(target.position());
                collectDropsNearSync(target.position());
                processed++;
                lastCollected = Math.max(0, hooks.countMatchingInventoryItems(targetItem) - startingCount);
                ignoredTargets.clear();

                if (processed % reportEvery == 0 || lastCollected >= amount) {
                    hooks.sendJobProgress(
                        "mine-cobblestone",
                        "Progress: " + lastCollected + "/" + amount + " cobblestone collected after processing " + processed + " block(s)."
                    );
                }
            } catch (Exception error) {
                if (isCriticalFailure(error)) {
                    throw new IllegalStateException("mine-cobblestone aborted: " + safeMessage(error), error);
                }

                lastFailure = safeMessage(error);
                ignoredTargets.add(target.position());
                if (ignoredTargets.size() >= 24) {
                    break;
                }
            }
        }

        int collected = Math.max(0, hooks.countMatchingInventoryItems(targetItem) - startingCount);
        BlockPos finalPosition = hooks.currentPlayerBlockPos();
        if (collected < amount) {
            String suffix = lastFailure == null ? "" : " Last failure: " + lastFailure;
            throw new IllegalStateException(
                "Stopped after collecting " + collected + "/" + amount + " cobblestone. No more valid stone or cobblestone targets were found within " + maxRadius + " blocks." + suffix
            );
        }

        return new MineCobblestoneResult(amount, collected, processed, targetItem, finalPosition);
    }

    private void collectDropsNearSync(BlockPos pos) throws Exception {
        int targetY = Math.max(0, pos.getY() - 1);
        hooks.moveToPosition(new Vec3d(pos.getX() + 0.5D, targetY, pos.getZ() + 0.5D), 1.35D, 8000L, true, true);
        Thread.sleep(200L);
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

    private boolean isCriticalFailure(Exception error) {
        String message = safeMessage(error);
        return message.contains("[hazard]") || message.contains("[stuck]") || message.contains("[timeout]");
    }

    private String safeMessage(Exception error) {
        if (error.getMessage() == null || error.getMessage().isBlank()) {
            return error.getClass().getSimpleName();
        }
        return error.getMessage();
    }
}
