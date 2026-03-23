package dev.yuniko.minecraftmcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.Direction;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MinecraftMcpBridgeMod implements ClientModInitializer {
    private static final String PROTOCOL_VERSION = "1.0.0";
    private static final String BRIDGE_VERSION = "0.1.0";
    private static final Set<String> CLEARABLE_SOFT_BLOCKS = Set.of(
        "dirt",
        "grass_block",
        "coarse_dirt",
        "podzol",
        "rooted_dirt",
        "mud",
        "muddy_mangrove_roots",
        "gravel",
        "sand",
        "red_sand",
        "short_grass",
        "tall_grass",
        "fern",
        "large_fern",
        "moss_block",
        "moss_carpet"
    );
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
        "get-position",
        "list-inventory",
        "find-item",
        "equip-item",
        "move-to-position",
        "dig-block",
        "harvest-wood",
        "mine-cobblestone",
        "get-block-info",
        "find-block",
        "detect-gamemode",
        "send-chat"
    );

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private final ClientThreadExecutor clientThreadExecutor = new ClientThreadExecutor();
    private final ActionDispatcher actionDispatcher = new ActionDispatcher(new BridgeActionHandler());
    private BridgeConfig config;
    private BridgeServer bridgeServer;
    private volatile boolean lastWorldReady = false;

    @Override
    public void onInitializeClient() {
        this.config = BridgeConfig.load();
        System.out.println("[minecraft-mcp-bridge] Initializing bridge on " + config.host() + ":" + config.port());
        this.bridgeServer = new BridgeServer(config, executor, new BridgeTransportListener());
        bridgeServer.start();
        monitor.scheduleAtFixedRate(this::monitorWorldState, 0, 500, TimeUnit.MILLISECONDS);
    }

    private void handleActionRequest(String requestId, String action, JsonObject args) {
        if (!SUPPORTED_ACTIONS.contains(action)) {
            sendError(requestId, "Action '" + action + "' is not yet supported by the Fabric companion.");
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;

        if (player == null || world == null) {
            sendError(requestId, "Minecraft world is not ready yet. Join a world or server first.");
            return;
        }

        switch (action) {
            case "get-position" -> handleGetPosition(requestId, player);
            case "list-inventory" -> handleListInventory(requestId, player);
            case "find-item" -> handleFindItem(requestId, player, args);
            case "equip-item" -> handleEquipItem(requestId, player, args);
            case "move-to-position" -> executor.submit(() -> handleMoveToPosition(requestId, args));
            case "dig-block" -> executor.submit(() -> handleDigBlock(requestId, args));
            case "harvest-wood" -> executor.submit(() -> handleHarvestWood(requestId, args));
            case "mine-cobblestone" -> executor.submit(() -> handleMineCobblestone(requestId, args));
            case "get-block-info" -> handleGetBlockInfo(requestId, world, args);
            case "find-block" -> handleFindBlock(requestId, player, world, args);
            case "detect-gamemode" -> handleDetectGamemode(requestId, client);
            case "send-chat" -> handleSendChat(requestId, player, args);
            default -> sendError(requestId, "Action '" + action + "' is not yet implemented.");
        }
    }

    private void handleGetPosition(String requestId, ClientPlayerEntity player) {
        JsonObject data = new JsonObject();
        data.addProperty("x", (int) Math.floor(player.getX()));
        data.addProperty("y", (int) Math.floor(player.getY()));
        data.addProperty("z", (int) Math.floor(player.getZ()));
        sendActionResult(requestId, "Current position: (" + data.get("x").getAsInt() + ", " + data.get("y").getAsInt() + ", " + data.get("z").getAsInt() + ")", data);
    }

    private void handleListInventory(String requestId, ClientPlayerEntity player) {
        JsonArray items = new JsonArray();
        StringBuilder output = new StringBuilder();
        int count = 0;

        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            Identifier id = Registries.ITEM.getId(stack.getItem());
            JsonObject item = new JsonObject();
            item.addProperty("id", id.toString());
            item.addProperty("displayName", stack.getName().getString());
            item.addProperty("count", stack.getCount());
            item.addProperty("slot", slot);
            items.add(item);

            output.append("- ")
                .append(id)
                .append(" (")
                .append(stack.getName().getString())
                .append(", x")
                .append(stack.getCount())
                .append(") in slot ")
                .append(slot)
                .append('\n');
            count++;
        }

        if (count == 0) {
            sendActionResult(requestId, "Inventory is empty", items);
            return;
        }

        sendActionResult(requestId, "Found " + count + " items in inventory:\n\n" + output, items);
    }

    private void handleFindItem(String requestId, ClientPlayerEntity player, JsonObject args) {
        String query = lower(getAsString(args, "nameOrType"));
        if (query == null || query.isBlank()) {
            sendError(requestId, "nameOrType is required.");
            return;
        }

        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            String id = Registries.ITEM.getId(stack.getItem()).toString();
            String displayName = stack.getName().getString();
            if (id.toLowerCase().contains(query) || displayName.toLowerCase().contains(query)) {
                JsonObject data = new JsonObject();
                data.addProperty("id", id);
                data.addProperty("displayName", displayName);
                data.addProperty("count", stack.getCount());
                data.addProperty("slot", slot);
                sendActionResult(requestId, "Found " + id + " (" + displayName + ") x" + stack.getCount() + " in slot " + slot, data);
                return;
            }
        }

        sendActionResult(requestId, "Couldn't find any item matching '" + query + "' in inventory", new JsonObject());
    }

    private void handleEquipItem(String requestId, ClientPlayerEntity player, JsonObject args) {
        String query = lower(getAsString(args, "itemName"));
        if (query == null || query.isBlank()) {
            sendError(requestId, "itemName is required.");
            return;
        }

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            String id = Registries.ITEM.getId(stack.getItem()).toString();
            String displayName = stack.getName().getString();
            if (id.toLowerCase().contains(query) || displayName.toLowerCase().contains(query)) {
                if (player.networkHandler == null) {
                    sendError(requestId, "Cannot change hotbar slot right now.");
                    return;
                }

                player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
                sendActionResult(requestId, "Equipped " + id + " to hand from hotbar slot " + slot, new JsonObject());
                return;
            }
        }

        sendError(requestId, "Could not equip '" + query + "'. Only hotbar items are supported in the MVP bridge.");
    }

    private void handleGetBlockInfo(String requestId, ClientWorld world, JsonObject args) {
        Integer x = getAsInt(args, "x");
        Integer y = getAsInt(args, "y");
        Integer z = getAsInt(args, "z");
        if (x == null || y == null || z == null) {
            sendError(requestId, "x, y and z are required.");
            return;
        }

        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = world.getBlockState(pos);
        Identifier id = Registries.BLOCK.getId(state.getBlock());

        JsonObject data = new JsonObject();
        data.addProperty("id", id.toString());
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        data.addProperty("displayName", state.getBlock().getName().getString());
        sendActionResult(requestId, "Found " + id + " at position (" + x + ", " + y + ", " + z + ")", data);
    }

    private void handleMoveToPosition(String requestId, JsonObject args) {
        Integer x = getAsInt(args, "x");
        Integer y = getAsInt(args, "y");
        Integer z = getAsInt(args, "z");
        double range = getAsDouble(args, "range") != null ? Objects.requireNonNull(getAsDouble(args, "range")) : 1.0D;
        long timeoutMs = getAsInt(args, "timeoutMs") != null ? Objects.requireNonNull(getAsInt(args, "timeoutMs")) : 15000L;

        if (x == null || y == null || z == null) {
            sendError(requestId, "x, y and z are required.");
            return;
        }

        try {
            MoveResult result = moveToPositionSync(new Vec3d(x + 0.5D, y, z + 0.5D), range, timeoutMs);
            JsonObject data = new JsonObject();
            data.addProperty("x", result.position().x);
            data.addProperty("y", result.position().y);
            data.addProperty("z", result.position().z);
            sendActionResult(requestId, "Successfully moved to position near (" + x + ", " + y + ", " + z + ")", data);
        } catch (Exception error) {
            sendError(requestId, error.getMessage());
        }
    }

    private void handleDigBlock(String requestId, JsonObject args) {
        Integer x = getAsInt(args, "x");
        Integer y = getAsInt(args, "y");
        Integer z = getAsInt(args, "z");

        if (x == null || y == null || z == null) {
            sendError(requestId, "x, y and z are required.");
            return;
        }

        try {
            String blockName = digBlockSync(new BlockPos(x, y, z));
            JsonObject data = new JsonObject();
            data.addProperty("x", x);
            data.addProperty("y", y);
            data.addProperty("z", z);
            data.addProperty("block", blockName);
            sendActionResult(requestId, "Dug " + blockName + " at (" + x + ", " + y + ", " + z + ")", data);
        } catch (Exception error) {
            sendError(requestId, error.getMessage());
        }
    }

    private void handleHarvestWood(String requestId, JsonObject args) {
        Integer amount = getAsInt(args, "amount");
        String preferredType = lower(getAsString(args, "preferredType"));
        int maxRadius = getAsInt(args, "maxRadius") != null ? Objects.requireNonNull(getAsInt(args, "maxRadius")) : 48;
        int reportEvery = getAsInt(args, "reportEvery") != null ? Objects.requireNonNull(getAsInt(args, "reportEvery")) : 15;

        if (amount == null || amount < 1) {
            sendError(requestId, "amount must be a positive integer.");
            return;
        }

        try {
            HarvestWoodResult result = harvestWoodSync(amount, preferredType, maxRadius, reportEvery);
            JsonObject data = new JsonObject();
            data.addProperty("processed", result.processed());
            data.addProperty("collected", result.collected());
            data.addProperty("requested", result.requested());
            data.addProperty("filter", result.filter() == null ? "any_log" : result.filter());
            data.addProperty("x", result.position().getX());
            data.addProperty("y", result.position().getY());
            data.addProperty("z", result.position().getZ());
            sendActionResult(
                requestId,
                "Harvested " + result.collected() + " log(s) after processing " + result.processed() + " block(s).",
                data
            );
        } catch (Exception error) {
            sendError(requestId, error.getMessage());
        }
    }

    private void handleMineCobblestone(String requestId, JsonObject args) {
        Integer amount = getAsInt(args, "amount");
        int maxRadius = getAsInt(args, "maxRadius") != null ? Objects.requireNonNull(getAsInt(args, "maxRadius")) : 32;
        int reportEvery = getAsInt(args, "reportEvery") != null ? Objects.requireNonNull(getAsInt(args, "reportEvery")) : 15;

        if (amount == null || amount < 1) {
            sendError(requestId, "amount must be a positive integer.");
            return;
        }

        try {
            MineCobblestoneResult result = mineCobblestoneSync(amount, maxRadius, reportEvery);
            JsonObject data = new JsonObject();
            data.addProperty("processed", result.processed());
            data.addProperty("collected", result.collected());
            data.addProperty("requested", result.requested());
            data.addProperty("block", result.block());
            data.addProperty("x", result.position().getX());
            data.addProperty("y", result.position().getY());
            data.addProperty("z", result.position().getZ());
            sendActionResult(
                requestId,
                "Mined " + result.collected() + " cobblestone after processing " + result.processed() + " block(s).",
                data
            );
        } catch (Exception error) {
            sendError(requestId, error.getMessage());
        }
    }

    private void handleFindBlock(String requestId, ClientPlayerEntity player, ClientWorld world, JsonObject args) {
        String query = lower(getAsString(args, "blockType"));
        int maxDistance = getAsInt(args, "maxDistance") != null ? Objects.requireNonNull(getAsInt(args, "maxDistance")) : 16;
        if (query == null || query.isBlank()) {
            sendError(requestId, "blockType is required.");
            return;
        }

        BlockPos origin = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
        BlockPos bestPos = null;
        Identifier bestId = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -maxDistance; dx <= maxDistance; dx++) {
            for (int dy = -maxDistance; dy <= maxDistance; dy++) {
                for (int dz = -maxDistance; dz <= maxDistance; dz++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    BlockState state = world.getBlockState(candidate);
                    Identifier id = Registries.BLOCK.getId(state.getBlock());
                    if (!id.toString().toLowerCase().contains(query)) {
                        continue;
                    }

                    double distance = candidate.getSquaredDistance(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestPos = candidate;
                        bestId = id;
                    }
                }
            }
        }

        if (bestPos == null || bestId == null) {
            sendActionResult(requestId, "No " + query + " found within " + maxDistance + " blocks", new JsonObject());
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("id", bestId.toString());
        data.addProperty("x", bestPos.getX());
        data.addProperty("y", bestPos.getY());
        data.addProperty("z", bestPos.getZ());
        sendActionResult(requestId, "Found " + bestId + " at position (" + bestPos.getX() + ", " + bestPos.getY() + ", " + bestPos.getZ() + ")", data);
    }

    private void handleDetectGamemode(String requestId, MinecraftClient client) {
        if (client.interactionManager == null || client.interactionManager.getCurrentGameMode() == null) {
            sendError(requestId, "Current gamemode is not available.");
            return;
        }

        String gameMode = client.interactionManager.getCurrentGameMode().name();
        JsonObject data = new JsonObject();
        data.addProperty("gameMode", gameMode);
        sendActionResult(requestId, "Bot gamemode: \"" + gameMode + "\"", data);
    }

    private void handleSendChat(String requestId, ClientPlayerEntity player, JsonObject args) {
        String message = getAsString(args, "message");
        if (message == null || message.isBlank()) {
            sendError(requestId, "message is required.");
            return;
        }

        if (player.networkHandler == null) {
            sendError(requestId, "Chat is not available right now.");
            return;
        }

        player.networkHandler.sendChatMessage(message);
        sendActionResult(requestId, "Sent message: \"" + message + "\"", new JsonObject());
    }

    private void sendHello() {
        send(BridgeProtocolMessages.hello(PROTOCOL_VERSION, BRIDGE_VERSION));
    }

    private void sendCapabilities() {
        send(BridgeProtocolMessages.capabilities(
            PROTOCOL_VERSION,
            BRIDGE_VERSION,
            "1.21.11",
            "fabric",
            FabricLoader.getInstance().getModContainer("fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown"),
            isWorldReady(),
            SUPPORTED_ACTIONS,
            List.of(
                "Fabric MVP bridge: read-only registry access plus a small set of player actions.",
                "Unsupported actions return explicit errors for MCP capability negotiation."
            )
        ));
    }

    private void sendSessionState() {
        MinecraftClient client = MinecraftClient.getInstance();
        send(BridgeProtocolMessages.sessionState(
            isWorldReady(),
            client.player != null,
            client.player != null ? client.player.getName().getString() : null,
            client.world != null && client.world.getRegistryKey() != null
                ? client.world.getRegistryKey().getValue().toString()
                : null
        ));
    }

    private void sendRegistrySnapshot() {
        send(BridgeProtocolMessages.registrySnapshot(
            Registries.BLOCK.getIds(),
            Registries.ITEM.getIds(),
            Registries.ENTITY_TYPE.getIds()
        ));
    }

    private void sendActionResult(String requestId, String message, JsonElement data) {
        send(BridgeProtocolMessages.actionResult(requestId, message, data));
    }

    private void sendError(String requestId, String message) {
        send(BridgeProtocolMessages.error(requestId, message));
    }

    private void sendJobProgress(String channel, String message) {
        send(BridgeProtocolMessages.chatEvent(channel, message));
    }

    private void send(JsonObject payload) {
        if (bridgeServer == null) {
            return;
        }

        ProtocolSession session = bridgeServer.getActiveSession();
        if (session == null) {
            return;
        }

        session.send(payload);
    }

    private void broadcastSessionState() {
        sendSessionState();
    }

    private void monitorWorldState() {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean worldReady = client.player != null && client.world != null;
        if (worldReady != lastWorldReady) {
            lastWorldReady = worldReady;
            broadcastSessionState();
        }
    }

    private boolean isWorldReady() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && client.world != null;
    }

    private MoveResult moveToPositionSync(Vec3d target, double range, long timeoutMs) throws Exception {
        return moveToPositionSync(target, range, timeoutMs, true, false);
    }

    private MoveResult moveToPositionSync(Vec3d target, double range, long timeoutMs, boolean allowJump) throws Exception {
        return moveToPositionSync(target, range, timeoutMs, allowJump, false);
    }

    private MoveResult moveToPositionSync(Vec3d target, double range, long timeoutMs, boolean allowJump, boolean clearSoftObstructions) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StuckTracker stuckTracker = new StuckTracker();

        try {
            while (System.currentTimeMillis() < deadline) {
                MoveTickResult tick = callOnClientThread(() -> updateNavigationTick(target, range, allowJump, clearSoftObstructions, stuckTracker));
                if (tick.arrived()) {
                    return new MoveResult(tick.position());
                }

                if (tick.state() == NavigationState.STUCK_RECOVERY && stuckTracker.recoveryAttempts() > 4) {
                    throw new IllegalStateException(
                        "Move failed near (" +
                            Math.floor(tick.position().x) + ", " +
                            Math.floor(tick.position().y) + ", " +
                            Math.floor(tick.position().z) + "): " + tick.status()
                    );
                }

                Thread.sleep(50L);
            }

            MoveTickResult current = callOnClientThread(() -> currentMoveTick(target, range));
            throw new IllegalStateException(
                "Move timed out after " + timeoutMs + "ms. Current position: (" +
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

    private String digBlockSync(BlockPos pos) throws Exception {
        MinecraftClient client = MinecraftClient.getInstance();
        String initialBlock = callOnClientThread(() -> {
            ClientWorld world = client.world;
            if (world == null) {
                throw new IllegalStateException("Minecraft world is not ready yet.");
            }

            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                throw new IllegalStateException("No block found at position (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
            }

            return Registries.BLOCK.getId(state.getBlock()).toString();
        });

        int clearedObstructions = 0;
        while (true) {
            ReachabilityResult initialReachability = callOnClientThread(() -> isBlockReachableNow(pos));
            if (initialReachability.reachable()) {
                break;
            }

            BlockPos obstruction = callOnClientThread(() -> findClearableDigObstruction(pos));
            if (obstruction != null) {
                if (clearedObstructions++ >= 4) {
                    throw new IllegalStateException(
                        "Too many obstructing blocks while trying to dig target at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"
                    );
                }
                digBlockSync(obstruction);
                continue;
            }

            DigCandidate candidate = callOnClientThread(() -> selectBestDigCandidate(findDigCandidates(pos)));
            if (candidate == null) {
                throw new IllegalStateException(
                    "No valid digging position found for block at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"
                );
            }
            moveToPositionSync(candidate.standPosition(), candidate.range(), 15000L, true, true);
            break;
        }

        try {
            long deadline = System.currentTimeMillis() + 12000L;
            while (System.currentTimeMillis() < deadline) {
                DigTickResult tick = callOnClientThread(() -> updateDigTick(pos));
                if (!tick.blockPresent()) {
                    return initialBlock;
                }

                if (tick.needsReposition()) {
                    BlockPos obstruction = callOnClientThread(() -> findClearableDigObstruction(pos));
                    if (obstruction != null) {
                        digBlockSync(obstruction);
                        continue;
                    }

                    DigCandidate candidate = callOnClientThread(() -> selectBestDigCandidate(findDigCandidates(pos)));
                    if (candidate == null) {
                        throw new IllegalStateException(
                            "Lost reachability to block at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ") and found no alternative position"
                        );
                    }
                    moveToPositionSync(candidate.standPosition(), candidate.range(), 8000L, true, true);
                    continue;
                }

                Thread.sleep(50L);
            }

            throw new IllegalStateException("Dig timed out for block at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
        } finally {
            callOnClientThread(() -> {
                stopDigging(client);
                stopMovement(client);
                return null;
            });
        }
    }

    private HarvestWoodResult harvestWoodSync(int amount, String preferredType, int maxRadius, int reportEvery) throws Exception {
        String normalizedType = normalizeHarvestType(preferredType);
        int startingCount = callOnClientThread(() -> countMatchingLogsInInventory(normalizedType));
        int processed = 0;
        int lastCollected = 0;
        Set<BlockPos> ignoredTargets = new HashSet<>();

        while (lastCollected < amount) {
            HarvestTarget target = callOnClientThread(() -> findNearestHarvestTarget(normalizedType, maxRadius, ignoredTargets));
            if (target == null) {
                break;
            }

            try {
                digBlockSync(target.position());
                collectDropsNearSync(target.position());
                processed++;
                lastCollected = Math.max(0, callOnClientThread(() -> countMatchingLogsInInventory(normalizedType)) - startingCount);
                ignoredTargets.clear();

                if (processed % reportEvery == 0 || lastCollected >= amount) {
                    sendJobProgress("harvest-wood", "Progress: " + lastCollected + "/" + amount + " logs collected after processing " + processed + " block(s).");
                }
            } catch (Exception error) {
                ignoredTargets.add(target.position());
                if (ignoredTargets.size() >= 24) {
                    break;
                }
            }
        }

        int collected = Math.max(0, callOnClientThread(() -> countMatchingLogsInInventory(normalizedType)) - startingCount);
        BlockPos finalPosition = callOnClientThread(this::currentPlayerBlockPos);
        if (collected < amount) {
            throw new IllegalStateException(
                "Stopped after collecting " + collected + "/" + amount + " log(s). No more valid targets were found within " + maxRadius + " blocks."
            );
        }

        return new HarvestWoodResult(amount, collected, processed, normalizedType, finalPosition);
    }

    private MineCobblestoneResult mineCobblestoneSync(int amount, int maxRadius, int reportEvery) throws Exception {
        Set<String> targetBlocks = Set.of("stone", "cobblestone");
        String targetItem = "cobblestone";
        int startingCount = callOnClientThread(() -> countMatchingInventoryItems(targetItem));
        int processed = 0;
        int lastCollected = 0;
        Set<BlockPos> ignoredTargets = new HashSet<>();

        while (lastCollected < amount) {
            MineTarget target = callOnClientThread(() -> findNearestMineTarget(targetBlocks, maxRadius, ignoredTargets));
            if (target == null) {
                break;
            }

            try {
                digBlockSync(target.position());
                collectDropsNearSync(target.position());
                processed++;
                lastCollected = Math.max(0, callOnClientThread(() -> countMatchingInventoryItems(targetItem)) - startingCount);
                ignoredTargets.clear();

                if (processed % reportEvery == 0 || lastCollected >= amount) {
                    sendJobProgress(
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

        int collected = Math.max(0, callOnClientThread(() -> countMatchingInventoryItems(targetItem)) - startingCount);
        BlockPos finalPosition = callOnClientThread(this::currentPlayerBlockPos);
        if (collected < amount) {
            throw new IllegalStateException(
                "Stopped after collecting " + collected + "/" + amount + " cobblestone. No more valid stone or cobblestone targets were found within " + maxRadius + " blocks."
            );
        }

        return new MineCobblestoneResult(amount, collected, processed, targetItem, finalPosition);
    }

    private void collectDropsNearSync(BlockPos pos) throws Exception {
        int targetY = Math.max(0, pos.getY() - 1);
        moveToPositionSync(new Vec3d(pos.getX() + 0.5D, targetY, pos.getZ() + 0.5D), 1.35D, 8000L, true, true);
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

                    boolean reachableNow = isBlockReachableNow(candidate).reachable();
                    boolean canReposition = selectBestDigCandidate(findDigCandidates(candidate)) != null;
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

                    boolean reachableNow = isBlockReachableNow(candidate).reachable();
                    boolean canReposition = selectBestDigCandidate(findDigCandidates(candidate)) != null;
                    BlockPos obstruction = (!reachableNow && !canReposition) ? findClearableDigObstruction(candidate) : null;
                    if (!reachableNow && !canReposition && obstruction == null) {
                        continue;
                    }

                    double horizontalDistance = Math.pow(candidate.getX() - origin.getX(), 2) + Math.pow(candidate.getZ() - origin.getZ(), 2);
                    double verticalPenalty = Math.max(0, candidate.getY() - origin.getY()) * 6.0D + Math.abs(candidate.getY() - origin.getY()) * 2.0D;
                    double obstructionPenalty = obstruction != null ? 3.0D : 0.0D;
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

    private MoveTickResult updateNavigationTick(Vec3d target, double range, boolean allowJump, boolean clearSoftObstructions, StuckTracker stuckTracker) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return new MoveTickResult(true, Vec3d.ZERO, NavigationState.MOVING_DIRECT, "World not ready");
        }

        Vec3d current = new Vec3d(player.getX(), player.getY(), player.getZ());
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        boolean arrived = horizontalDistance <= range && Math.abs(target.y - current.y) <= 1.5D;
        if (arrived) {
            stopMovement(client);
            return new MoveTickResult(true, current, NavigationState.MOVING_DIRECT, "Arrived");
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
            if (!isClearableFoliage(world, persistentObstruction)) {
                stuckTracker.clearObstruction();
                stopDigging(client);
            } else {
                stopMovement(client);
                DigTickResult clearTick = updateDigTick(persistentObstruction);
                if (!clearTick.blockPresent()) {
                    stuckTracker.clearObstruction();
                    stopDigging(client);
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
            DigTickResult clearTick = updateDigTick(clearableObstruction);
            if (!clearTick.blockPresent()) {
                stuckTracker.clearObstruction();
                stopDigging(client);
            }
            return new MoveTickResult(false, current, NavigationState.DIGGING, "Clearing obstructing block");
        }

        if (stuckTracker.shouldStartRecovery(blocked)) {
            stopMovement(client);
            stuckTracker.startRecovery();
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
        boolean arrived = horizontalDistance <= range && Math.abs(target.y - current.y) <= 1.5D;
        return new MoveTickResult(arrived, current, NavigationState.MOVING_DIRECT, arrived ? "Arrived" : "Awaiting progress");
    }

    private DigTickResult updateDigTick(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.interactionManager == null) {
            return new DigTickResult(true, false);
        }

        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            stopDigging(client);
            return new DigTickResult(false, false);
        }

        ReachabilityResult reachability = isBlockReachableNow(pos);
        if (!reachability.reachable()) {
            stopDigging(client);
            return new DigTickResult(true, true);
        }

        lookAt(player, reachability.aimPoint());

        client.options.attackKey.setPressed(true);
        client.interactionManager.updateBlockBreakingProgress(pos, reachability.face());
        player.swingHand(Hand.MAIN_HAND);
        return new DigTickResult(true, false);
    }

    private ReachabilityResult isBlockReachableNow(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return ReachabilityResult.unreachable("World not ready");
        }

        return findReachability(world, player.getEyePos(), pos, getInteractionReach(player));
    }

    private List<DigCandidate> findDigCandidates(BlockPos targetPos) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        List<DigCandidate> candidates = new ArrayList<>();
        if (player == null || world == null) {
            return candidates;
        }

        int[][] offsets = new int[][] {
            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
            { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 }
        };

        for (int dy = -1; dy <= 1; dy++) {
            for (int[] offset : offsets) {
                BlockPos feetPos = new BlockPos(targetPos.getX() + offset[0], targetPos.getY() + dy, targetPos.getZ() + offset[1]);
                if (!isStandable(world, feetPos)) {
                    continue;
                }

                Vec3d standPosition = new Vec3d(feetPos.getX() + 0.5D, feetPos.getY(), feetPos.getZ() + 0.5D);
                Vec3d eyePosition = standPosition.add(0.0D, player.getEyeHeight(player.getPose()), 0.0D);
                ReachabilityResult reachability = findReachability(world, eyePosition, targetPos, getInteractionReach(player));
                if (!reachability.reachable()) {
                    continue;
                }

                double distanceScore = standPosition.squaredDistanceTo(player.getX(), player.getY(), player.getZ());
                double verticalPenalty = Math.abs(standPosition.y - player.getY()) * 4.0D;
                double diagonalPenalty = (Math.abs(offset[0]) + Math.abs(offset[1]) == 2) ? 0.35D : 0.0D;
                double score = distanceScore + verticalPenalty + (reachability.distanceSq() * 0.25D) + diagonalPenalty;
                candidates.add(new DigCandidate(feetPos, standPosition, score, Math.max(0.7D, Math.sqrt(reachability.distanceSq()) - 0.6D)));
            }
        }

        return candidates;
    }

    private DigCandidate selectBestDigCandidate(List<DigCandidate> candidates) {
        return candidates.stream()
            .min(Comparator.comparingDouble(DigCandidate::score))
            .orElse(null);
    }

    private ReachabilityResult findReachability(ClientWorld world, Vec3d eyePosition, BlockPos pos, double reachDistance) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return ReachabilityResult.unreachable("Target block is air");
        }

        ReachabilityResult best = ReachabilityResult.unreachable("No visible block face");
        for (Direction face : Direction.values()) {
            Vec3d aimPoint = Vec3d.ofCenter(pos).add(
                face.getOffsetX() * 0.499D,
                face.getOffsetY() * 0.499D,
                face.getOffsetZ() * 0.499D
            );
            double distanceSq = eyePosition.squaredDistanceTo(aimPoint);
            if (distanceSq > reachDistance * reachDistance) {
                continue;
            }

            if (!hasLineOfSightToBlock(world, eyePosition, aimPoint, pos)) {
                continue;
            }

            if (!best.reachable() || distanceSq < best.distanceSq()) {
                best = new ReachabilityResult(true, face, aimPoint, distanceSq, "Visible");
            }
        }

        return best;
    }

    private boolean hasLineOfSightToBlock(ClientWorld world, Vec3d from, Vec3d to, BlockPos targetPos) {
        BlockHitResult hit = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, ShapeContext.absent()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(targetPos);
    }

    private boolean isStandable(ClientWorld world, BlockPos feetPos) {
        return isSolidGround(world, feetPos.down()) && isSpaceClear(world, feetPos) && isSpaceClear(world, feetPos.up());
    }

    private boolean isSpaceClear(ClientWorld world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private boolean isSolidGround(ClientWorld world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
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
                    if (isClearableFoliage(world, candidate) && isBlockReachableNow(candidate).reachable()) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    private boolean isClearableFoliage(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && state.isIn(BlockTags.LEAVES);
    }

    private BlockPos findClearableDigObstruction(BlockPos targetPos) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return null;
        }

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            BlockPos candidate = targetPos.offset(face);
            if (!isClearableMiningObstruction(world, candidate)) {
                continue;
            }

            boolean reachableNow = isBlockReachableNow(candidate).reachable();
            boolean canReposition = selectBestDigCandidate(findDigCandidates(candidate)) != null;
            if (!reachableNow && !canReposition) {
                continue;
            }

            double distance = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best;
    }

    private boolean isClearableMiningObstruction(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        String localId = localBlockId(Registries.BLOCK.getId(state.getBlock()).toString());
        return CLEARABLE_SOFT_BLOCKS.contains(localId) || state.isIn(BlockTags.LEAVES);
    }

    private double getInteractionReach(ClientPlayerEntity player) {
        return player.isCreative() ? 5.0D : 4.5D;
    }

    private void stopDigging(MinecraftClient client) {
        client.options.attackKey.setPressed(false);
        if (client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
    }

    private void stopMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eyes = player.getEyePos();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
    }

    private <T> T callOnClientThread(ClientThreadExecutor.ClientTask<T> task) throws Exception {
        return clientThreadExecutor.call(task);
    }

    private boolean isTokenValid(JsonObject message) {
        if (config.token() == null || config.token().isBlank()) {
            return true;
        }

        String incoming = getAsString(message, "token");
        return config.token().equals(incoming);
    }

    private static String getAsString(JsonObject object, String memberName) {
        if (!object.has(memberName) || object.get(memberName).isJsonNull()) {
            return null;
        }
        return object.get(memberName).getAsString();
    }

    private static Integer getAsInt(JsonObject object, String memberName) {
        if (!object.has(memberName) || object.get(memberName).isJsonNull()) {
            return null;
        }
        return (int) Math.floor(object.get(memberName).getAsDouble());
    }

    private static Double getAsDouble(JsonObject object, String memberName) {
        if (!object.has(memberName) || object.get(memberName).isJsonNull()) {
            return null;
        }
        return object.get(memberName).getAsDouble();
    }

    private static String lower(String input) {
        return input == null ? null : input.toLowerCase();
    }

    private static String localBlockId(String id) {
        String normalized = id.toLowerCase();
        int separatorIndex = normalized.indexOf(':');
        return separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
    }

    private enum NavigationState {
        MOVING_DIRECT,
        STEP_UP_ATTEMPT,
        STUCK_RECOVERY,
        DIGGING
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

        void startRecovery() {
            recoveryTicksRemaining = RECOVERY_TICKS;
            recoveryAttempts++;
            recoverLeft = !recoverLeft;
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

    private record MoveResult(Vec3d position) {
    }

    private record DigTickResult(boolean blockPresent, boolean needsReposition) {
    }

    private record DigCandidate(BlockPos feetPos, Vec3d standPosition, double score, double range) {
    }

    private record HarvestTarget(BlockPos position, String blockId) {
    }

    private record HarvestWoodResult(int requested, int collected, int processed, String filter, BlockPos position) {
    }

    private record MineTarget(BlockPos position, String blockId) {
    }

    private record MineCobblestoneResult(int requested, int collected, int processed, String block, BlockPos position) {
    }

    private record ReachabilityResult(boolean reachable, Direction face, Vec3d aimPoint, double distanceSq, String reason) {
        private static ReachabilityResult unreachable(String reason) {
            return new ReachabilityResult(false, Direction.UP, Vec3d.ZERO, Double.MAX_VALUE, reason);
        }
    }

    private final class BridgeTransportListener implements BridgeServer.Listener {
        @Override
        public void onMessage(ProtocolSession session, JsonObject message) {
            actionDispatcher.dispatch(session, message);
        }

        @Override
        public void onSessionClosed(ProtocolSession session) {
            // Reserved for future per-session cleanup hooks.
        }

        @Override
        public void onSessionOpened(ProtocolSession session) {
            // Reserved for future per-session initialization hooks.
        }

        @Override
        public void onTransportError(String context, Exception error) {
            System.err.println("[minecraft-mcp-bridge] " + context);
            error.printStackTrace();
        }
    }

    private final class BridgeActionHandler implements ActionDispatcher.Handler {
        @Override
        public void onActionRequest(ProtocolSession session, String requestId, String action, JsonObject args) {
            handleActionRequest(requestId, action, args);
        }

        @Override
        public void onHello(ProtocolSession session) {
            sendHello();
            sendCapabilities();
            sendSessionState();
            sendRegistrySnapshot();
        }

        @Override
        public void onProtocolError(ProtocolSession session, String requestId, String message) {
            sendError(requestId, message);
        }

        @Override
        public boolean isTokenValid(JsonObject helloMessage) {
            return MinecraftMcpBridgeMod.this.isTokenValid(helloMessage);
        }
    }
}
