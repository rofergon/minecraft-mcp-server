package dev.yuniko.minecraftmcp.bridge;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

final class NavigationSafety {
    private static final int MAX_SAFE_DROP = 3;
    private static final int DROP_SCAN_DEPTH = 6;

    private NavigationSafety() {
    }

    static HazardAssessment assessPath(ClientWorld world, Vec3d start, Vec3d target) {
        Vec3d delta = target.subtract(start);
        double horizontalDistance = Math.sqrt((delta.x * delta.x) + (delta.z * delta.z));
        int samples = Math.max(1, (int) Math.ceil(horizontalDistance / 0.45D));

        for (int step = 1; step <= samples; step++) {
            double progress = step / (double) samples;
            Vec3d sample = start.lerp(target, progress);
            BlockPos feetPos = BlockPos.ofFloored(sample.x, sample.y, sample.z);
            HazardAssessment assessment = assessFooting(world, feetPos);
            if (assessment.hazardous()) {
                return assessment;
            }
        }

        return HazardAssessment.safe();
    }

    static HazardAssessment assessFooting(ClientWorld world, BlockPos feetPos) {
        if (hasImmediateLava(world, feetPos)) {
            return HazardAssessment.hazard("lava", "Immediate lava detected near (" + feetPos.getX() + ", " + feetPos.getY() + ", " + feetPos.getZ() + ")");
        }

        int dropDepth = findDropDepth(world, feetPos);
        if (dropDepth > MAX_SAFE_DROP) {
            return HazardAssessment.hazard(
                "fall",
                "Unsafe drop of " + dropDepth + " block(s) detected near (" + feetPos.getX() + ", " + feetPos.getY() + ", " + feetPos.getZ() + ")"
            );
        }

        return HazardAssessment.safe();
    }

    private static boolean hasImmediateLava(ClientWorld world, BlockPos feetPos) {
        BlockPos[] samples = new BlockPos[] {
            feetPos.down(),
            feetPos,
            feetPos.up(),
            feetPos.north(),
            feetPos.south(),
            feetPos.east(),
            feetPos.west()
        };

        for (BlockPos sample : samples) {
            if (world.getFluidState(sample).isIn(FluidTags.LAVA)) {
                return true;
            }
        }

        return false;
    }

    private static int findDropDepth(ClientWorld world, BlockPos feetPos) {
        for (int drop = 0; drop <= DROP_SCAN_DEPTH; drop++) {
            BlockPos supportPos = feetPos.down(drop + 1);
            if (world.getFluidState(supportPos).isIn(FluidTags.WATER)) {
                return 0;
            }

            if (!world.getBlockState(supportPos).getCollisionShape(world, supportPos).isEmpty()) {
                return drop;
            }
        }

        return DROP_SCAN_DEPTH + 1;
    }

    record HazardAssessment(boolean hazardous, String code, String detail) {
        static HazardAssessment safe() {
            return new HazardAssessment(false, null, "safe");
        }

        static HazardAssessment hazard(String code, String detail) {
            return new HazardAssessment(true, code, detail);
        }
    }
}
