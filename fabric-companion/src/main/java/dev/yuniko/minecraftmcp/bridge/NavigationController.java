package dev.yuniko.minecraftmcp.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

final class NavigationController {
    private static final double MIN_ARRIVAL_RANGE = 0.35D;
    private static final double RECOVERY_DIRECTION_TIE_EPSILON = 0.15D;

    interface Hooks {
        boolean isBlockReachable(BlockPos pos);
        boolean tickClearObstruction(BlockPos pos);
        boolean isClearableFoliage(ClientWorld world, BlockPos pos);
        void stopDigging(MinecraftClient client);
    }

    record MoveResult(Vec3d position) {
    }

    private final ClientThreadExecutor clientThreadExecutor;
    private final Hooks hooks;

    NavigationController(ClientThreadExecutor clientThreadExecutor, Hooks hooks) {
        this.clientThreadExecutor = clientThreadExecutor;
        this.hooks = hooks;
    }

    MoveResult moveToPositionSync(Vec3d target, double range, long timeoutMs) throws Exception {
        return moveToPositionSync(target, range, timeoutMs, true, false);
    }

    MoveResult moveToPositionSync(Vec3d target, double range, long timeoutMs, boolean allowJump) throws Exception {
        return moveToPositionSync(target, range, timeoutMs, allowJump, false);
    }

    MoveResult moveToPositionSync(Vec3d target, double range, long timeoutMs, boolean allowJump, boolean clearSoftObstructions) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StuckTracker stuckTracker = new StuckTracker();
        SafetyTracker safetyTracker = new SafetyTracker();

        try {
            while (System.currentTimeMillis() < deadline) {
                MoveTickResult tick = callOnClientThread(() -> updateNavigationTick(target, range, allowJump, clearSoftObstructions, stuckTracker, safetyTracker));
                if (tick.arrived()) {
                    return new MoveResult(tick.position());
                }

                if (tick.state() == NavigationState.HAZARD) {
                    throw new IllegalStateException(
                        "Move failed [hazard] near (" +
                            Math.floor(tick.position().x) + ", " +
                            Math.floor(tick.position().y) + ", " +
                            Math.floor(tick.position().z) + "): " + tick.status()
                    );
                }

                if (tick.state() == NavigationState.STUCK_RECOVERY && stuckTracker.recoveryAttempts() > 4) {
                    throw new IllegalStateException(
                        "Move failed [stuck] near (" +
                            Math.floor(tick.position().x) + ", " +
                            Math.floor(tick.position().y) + ", " +
                            Math.floor(tick.position().z) + "): " + tick.status()
                    );
                }

                Thread.sleep(50L);
            }

            MoveTickResult current = callOnClientThread(() -> currentMoveTick(target, range));
            throw new IllegalStateException(
                "Move failed [timeout] after " + timeoutMs + "ms. Current position: (" +
                    Math.floor(current.position().x) + ", " +
                    Math.floor(current.position().y) + ", " +
                    Math.floor(current.position().z) + "). State: " + current.state() + ". " + current.status()
            );
        } finally {
            callOnClientThread(() -> {
                stopMovement(MinecraftClient.getInstance());
                return null;
            });
        }
    }

    private MoveTickResult updateNavigationTick(
        Vec3d target,
        double range,
        boolean allowJump,
        boolean clearSoftObstructions,
        StuckTracker stuckTracker,
        SafetyTracker safetyTracker
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return new MoveTickResult(true, Vec3d.ZERO, NavigationState.MOVING_DIRECT, "World not ready");
        }

        double effectiveRange = effectiveArrivalRange(range);
        Vec3d current = new Vec3d(player.getX(), player.getY(), player.getZ());
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        boolean arrived = horizontalDistance <= effectiveRange && Math.abs(target.y - current.y) <= 1.5D;
        if (arrived) {
            stopMovement(client);
            return new MoveTickResult(true, current, NavigationState.MOVING_DIRECT, "Arrived");
        }

        NavigationSafety.HazardAssessment safety = safetyTracker.evaluate(world, player, current, target);
        if (safety.hazardous()) {
            stopMovement(client);
            hooks.stopDigging(client);
            return new MoveTickResult(false, current, NavigationState.HAZARD, safety.detail());
        }

        double totalDistance = current.distanceTo(target);
        stuckTracker.record(current, totalDistance);

        if (stuckTracker.isRecovering()) {
            float recoveryYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            player.setYaw(recoveryYaw);
            player.setHeadYaw(recoveryYaw);
            player.setBodyYaw(recoveryYaw);
            client.options.forwardKey.setPressed(true);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(stuckTracker.recoverLeft());
            client.options.rightKey.setPressed(!stuckTracker.recoverLeft());
            client.options.sprintKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            stuckTracker.advanceRecovery();
            return new MoveTickResult(false, current, NavigationState.STUCK_RECOVERY, "Recovering from obstacle");
        }

        BlockPos persistentObstruction = stuckTracker.activeObstruction();
        if (persistentObstruction != null) {
            if (!hooks.isClearableFoliage(world, persistentObstruction)) {
                stuckTracker.clearObstruction();
                hooks.stopDigging(client);
            } else {
                stopMovement(client);
                boolean blockPresent = hooks.tickClearObstruction(persistentObstruction);
                if (!blockPresent) {
                    stuckTracker.clearObstruction();
                    hooks.stopDigging(client);
                }
                return new MoveTickResult(false, current, NavigationState.DIGGING, "Clearing obstructing block");
            }
        }

        boolean stepUp = allowJump && isStepUpPossible(player, world, target);
        boolean blocked = player.horizontalCollision || isObstructedAhead(player, world, target);
        BlockPos clearableObstruction = clearSoftObstructions ? findClearableObstructionAhead(player, world, target) : null;
        if (clearableObstruction != null) {
            stuckTracker.setObstruction(clearableObstruction);
            stopMovement(client);
            boolean blockPresent = hooks.tickClearObstruction(clearableObstruction);
            if (!blockPresent) {
                stuckTracker.clearObstruction();
                hooks.stopDigging(client);
            }
            return new MoveTickResult(false, current, NavigationState.DIGGING, "Clearing obstructing block");
        }

        if (stuckTracker.shouldStartRecovery(blocked)) {
            stopMovement(client);
            boolean recoverLeft = chooseRecoveryLeft(player, world, target, stuckTracker.recoverLeft());
            stuckTracker.startRecovery(recoverLeft);
            return new MoveTickResult(false, current, NavigationState.STUCK_RECOVERY, "Blocked without progress");
        }

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);

        client.options.forwardKey.setPressed(true);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(horizontalDistance > 4.0D && !stepUp);
        client.options.jumpKey.setPressed(stepUp);

        NavigationState state = stepUp ? NavigationState.STEP_UP_ATTEMPT : NavigationState.MOVING_DIRECT;
        String status = stepUp ? "Stepping up over obstacle" : "Moving directly";
        return new MoveTickResult(false, current, state, status);
    }

    private MoveTickResult currentMoveTick(Vec3d target, double range) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return new MoveTickResult(true, Vec3d.ZERO, NavigationState.MOVING_DIRECT, "Player unavailable");
        }

        Vec3d current = new Vec3d(player.getX(), player.getY(), player.getZ());
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        boolean arrived = horizontalDistance <= effectiveArrivalRange(range) && Math.abs(target.y - current.y) <= 1.5D;
        return new MoveTickResult(arrived, current, NavigationState.MOVING_DIRECT, arrived ? "Arrived" : "Awaiting progress");
    }

    private boolean isStepUpPossible(ClientPlayerEntity player, ClientWorld world, Vec3d target) {
        Vec3d direction = new Vec3d(target.x - player.getX(), 0.0D, target.z - player.getZ());
        if (direction.lengthSquared() < 1.0E-4D) {
            return false;
        }

        Vec3d normalized = direction.normalize().multiply(0.7D);
        BlockPos aheadFeet = BlockPos.ofFloored(player.getX() + normalized.x, player.getY(), player.getZ() + normalized.z);
        BlockPos aheadHead = aheadFeet.up();
        if (isSpaceClear(world, aheadFeet) && isSpaceClear(world, aheadHead)) {
            return false;
        }

        BlockPos stepFeet = aheadFeet.up();
        return isSolidGround(world, aheadFeet)
            && isSpaceClear(world, stepFeet)
            && isSpaceClear(world, stepFeet.up());
    }

    private boolean isObstructedAhead(ClientPlayerEntity player, ClientWorld world, Vec3d target) {
        Vec3d direction = new Vec3d(target.x - player.getX(), 0.0D, target.z - player.getZ());
        if (direction.lengthSquared() < 1.0E-4D) {
            return false;
        }

        Vec3d normalized = direction.normalize().multiply(0.65D);
        BlockPos aheadFeet = BlockPos.ofFloored(player.getX() + normalized.x, player.getY(), player.getZ() + normalized.z);
        BlockPos aheadHead = aheadFeet.up();
        return !isSpaceClear(world, aheadFeet) || !isSpaceClear(world, aheadHead);
    }

    private boolean chooseRecoveryLeft(ClientPlayerEntity player, ClientWorld world, Vec3d target, boolean fallbackLeft) {
        Vec3d direction = new Vec3d(target.x - player.getX(), 0.0D, target.z - player.getZ());
        if (direction.lengthSquared() < 1.0E-4D) {
            return fallbackLeft;
        }

        Vec3d forward = direction.normalize();
        Vec3d left = new Vec3d(forward.z, 0.0D, -forward.x);
        Vec3d right = left.multiply(-1.0D);
        double leftScore = scoreRecoveryDirection(player, world, forward, left);
        double rightScore = scoreRecoveryDirection(player, world, forward, right);
        return preferLeftRecovery(leftScore, rightScore, fallbackLeft);
    }

    private double scoreRecoveryDirection(ClientPlayerEntity player, ClientWorld world, Vec3d forward, Vec3d side) {
        Vec3d origin = new Vec3d(player.getX(), player.getY(), player.getZ());
        double[] forwardOffsets = new double[] { 0.25D, 0.55D, 0.9D };
        double[] sideOffsets = new double[] { 0.3D, 0.6D };
        double score = 0.0D;

        for (double forwardOffset : forwardOffsets) {
            Vec3d forwardStep = forward.multiply(forwardOffset);
            for (double sideOffset : sideOffsets) {
                Vec3d sample = origin.add(forwardStep).add(side.multiply(sideOffset));
                BlockPos feet = BlockPos.ofFloored(sample);
                boolean hasBodySpace = isSpaceClear(world, feet) && isSpaceClear(world, feet.up());
                if (!hasBodySpace) {
                    score -= 1.25D;
                    continue;
                }

                score += isSolidGround(world, feet.down()) ? 1.0D : 0.2D;
            }
        }

        return score;
    }

    private BlockPos findClearableObstructionAhead(ClientPlayerEntity player, ClientWorld world, Vec3d target) {
        Vec3d direction = new Vec3d(target.x - player.getX(), 0.0D, target.z - player.getZ());
        if (direction.lengthSquared() < 1.0E-4D) {
            return null;
        }

        Vec3d forward = direction.normalize();
        Vec3d lateral = new Vec3d(-forward.z, 0.0D, forward.x);
        double[] distances = new double[] { 0.35D, 0.65D, 0.95D, 1.25D };
        double[] heights = new double[] { 0.0D, 1.0D, 2.0D };
        double[] lateralOffsets = new double[] { 0.0D, -0.35D, 0.35D };

        for (double distance : distances) {
            for (double height : heights) {
                for (double sideOffset : lateralOffsets) {
                    Vec3d sample = new Vec3d(player.getX(), player.getY() + height, player.getZ())
                        .add(forward.multiply(distance))
                        .add(lateral.multiply(sideOffset));
                    BlockPos candidate = BlockPos.ofFloored(sample);
                    if (hooks.isClearableFoliage(world, candidate) && hooks.isBlockReachable(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    private boolean isSpaceClear(ClientWorld world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private boolean isSolidGround(ClientWorld world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    static double effectiveArrivalRange(double requestedRange) {
        return Math.max(requestedRange, MIN_ARRIVAL_RANGE);
    }

    static boolean preferLeftRecovery(double leftScore, double rightScore, boolean fallbackLeft) {
        if (Math.abs(leftScore - rightScore) <= RECOVERY_DIRECTION_TIE_EPSILON) {
            return fallbackLeft;
        }

        return leftScore > rightScore;
    }

    private void stopMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    private <T> T callOnClientThread(ClientThreadExecutor.ClientTask<T> task) throws Exception {
        return clientThreadExecutor.call(task);
    }

    private enum NavigationState {
        MOVING_DIRECT,
        STEP_UP_ATTEMPT,
        STUCK_RECOVERY,
        DIGGING,
        HAZARD
    }

    private static final class SafetyTracker {
        private static final int MAX_WATER_TICKS = 30;
        private static final int LOW_AIR_THRESHOLD = 80;
        private static final float DAMAGE_ABORT_THRESHOLD = 2.0F;

        private Float lastHealth;
        private float baselineHealth;
        private int damageEvents;
        private int waterTicks;

        NavigationSafety.HazardAssessment evaluate(ClientWorld world, ClientPlayerEntity player, Vec3d current, Vec3d target) {
            if (lastHealth == null) {
                lastHealth = player.getHealth();
                baselineHealth = player.getHealth();
            }

            float health = player.getHealth();
            if (health + 0.01F < lastHealth) {
                damageEvents++;
            }
            lastHealth = health;

            if ((baselineHealth - health) >= DAMAGE_ABORT_THRESHOLD || damageEvents >= 2) {
                return NavigationSafety.HazardAssessment.hazard(
                    "damage",
                    "Player took repeated damage while moving. Health dropped from " + baselineHealth + " to " + health
                );
            }

            if (player.isSubmergedInWater() || player.isTouchingWater()) {
                waterTicks++;
            } else {
                waterTicks = 0;
            }

            if ((player.isSubmergedInWater() && waterTicks >= 10) || waterTicks >= MAX_WATER_TICKS || player.getAir() <= LOW_AIR_THRESHOLD) {
                return NavigationSafety.HazardAssessment.hazard(
                    "drowning",
                    "Dangerous water exposure detected while moving. Air=" + player.getAir() + ", waterTicks=" + waterTicks
                );
            }

            return NavigationSafety.assessPath(world, current, target);
        }
    }

    private static final class StuckTracker {
        private static final long STUCK_THRESHOLD_MS = 900L;
        private static final int RECOVERY_TICKS = 8;
        private static final double MIN_PROGRESS_DISTANCE = 0.12D;

        private Vec3d lastPosition;
        private double bestDistance = Double.MAX_VALUE;
        private long lastProgressAt = System.currentTimeMillis();
        private int recoveryTicksRemaining;
        private int recoveryAttempts;
        private boolean recoverLeft = true;
        private BlockPos activeObstruction;

        void record(Vec3d current, double distanceToTarget) {
            long now = System.currentTimeMillis();
            boolean moved = lastPosition == null || current.distanceTo(lastPosition) >= MIN_PROGRESS_DISTANCE;
            boolean gotCloser = distanceToTarget < (bestDistance - 0.08D);
            if (moved || gotCloser) {
                lastPosition = current;
                bestDistance = Math.min(bestDistance, distanceToTarget);
                lastProgressAt = now;
            }
        }

        boolean shouldStartRecovery(boolean blocked) {
            return blocked && !isRecovering() && (System.currentTimeMillis() - lastProgressAt) >= STUCK_THRESHOLD_MS;
        }

        void startRecovery(boolean recoverLeft) {
            recoveryTicksRemaining = RECOVERY_TICKS;
            recoveryAttempts++;
            this.recoverLeft = recoverLeft;
            lastProgressAt = System.currentTimeMillis();
        }

        void advanceRecovery() {
            if (recoveryTicksRemaining > 0) {
                recoveryTicksRemaining--;
            }
        }

        boolean isRecovering() {
            return recoveryTicksRemaining > 0;
        }

        boolean recoverLeft() {
            return recoverLeft;
        }

        int recoveryAttempts() {
            return recoveryAttempts;
        }

        BlockPos activeObstruction() {
            return activeObstruction;
        }

        void setObstruction(BlockPos obstruction) {
            activeObstruction = obstruction;
        }

        void clearObstruction() {
            activeObstruction = null;
        }
    }

    private record MoveTickResult(boolean arrived, Vec3d position, NavigationState state, String status) {
    }
}
