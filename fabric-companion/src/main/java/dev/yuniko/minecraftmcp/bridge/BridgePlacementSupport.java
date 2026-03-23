package dev.yuniko.minecraftmcp.bridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BridgePlacementSupport {
    private static final List<String> DEFAULT_FACE_ORDER = List.of("down", "north", "south", "east", "west", "up");

    private BridgePlacementSupport() {
    }

    static List<String> orderedFaceDirections(String requestedFace) {
        if (requestedFace == null || requestedFace.isBlank()) {
            return DEFAULT_FACE_ORDER;
        }

        String normalized = requestedFace.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (DEFAULT_FACE_ORDER.contains(normalized)) {
            ordered.add(normalized);
        }
        ordered.addAll(DEFAULT_FACE_ORDER);
        return List.copyOf(ordered);
    }

    static Set<String> expectedPlacedBlockIds(String itemId) {
        String localId = localId(itemId);
        if (localId.equals("torch")) {
            return Set.of("torch", "wall_torch");
        }
        if (localId.equals("redstone_torch")) {
            return Set.of("redstone_torch", "redstone_wall_torch");
        }
        if (localId.equals("soul_torch")) {
            return Set.of("soul_torch", "soul_wall_torch");
        }
        return Set.of(localId);
    }

    static boolean placementConfirmed(String itemId, String blockId) {
        return expectedPlacedBlockIds(itemId).contains(localId(blockId));
    }

    static String localId(String id) {
        String normalized = id == null ? "" : id.toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf(':');
        return separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
    }

    static List<String> sortedExpectedBlockIds(String itemId) {
        return new ArrayList<>(expectedPlacedBlockIds(itemId)).stream().sorted().toList();
    }
}
