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
    private final ActionCoordinator actionCoordinator = new ActionCoordinator();
    private final ActionDispatcher actionDispatcher = new ActionDispatcher(new BridgeActionHandler());
    private final NavigationController navigationController = new NavigationController(clientThreadExecutor, new NavigationHooks());
    private final TaskServices taskServices = new TaskServices(new TaskHooks());
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

        actionCoordinator.dispatch(
            executionModeFor(action),
            () -> executeAction(requestId, action, args),
            (error) -> sendError(
                requestId,
                "Unexpected failure while executing '" + action + "': " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
            )
        );
    }

    private void executeAction(String requestId, String action, JsonObject args) {
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
            case "move-to-position" -> handleMoveToPosition(requestId, args);
            case "dig-block" -> handleDigBlock(requestId, args);
            case "harvest-wood" -> handleHarvestWood(requestId, args);
            case "mine-cobblestone" -> handleMineCobblestone(requestId, args);
            case "get-block-info" -> handleGetBlockInfo(requestId, world, args);
            case "find-block" -> handleFindBlock(requestId, player, world, args);
            case "detect-gamemode" -> handleDetectGamemode(requestId, client);
            case "send-chat" -> handleSendChat(requestId, player, args);
            default -> sendError(requestId, "Action '" + action + "' is not yet implemented.");
        }
    }

    private ActionCoordinator.Mode executionModeFor(String action) {
        return switch (action) {
            case "get-position",
                "list-inventory",
                "find-item",
                "get-block-info",
                "find-block",
                "detect-gamemode" -> ActionCoordinator.Mode.READ_ONLY;
            case "equip-item",
                "move-to-position",
                "dig-block",
                "harvest-wood",
                "mine-cobblestone",
                "send-chat" -> ActionCoordinator.Mode.EXCLUSIVE;
            default -> ActionCoordinator.Mode.READ_ONLY;
        };
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
            NavigationController.MoveResult result = navigationController.moveToPositionSync(new Vec3d(x + 0.5D, y, z + 0.5D), range, timeoutMs);
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
            TaskServices.HarvestWoodResult result = taskServices.harvestWoodSync(amount, preferredType, maxRadius, reportEvery);
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
            TaskServices.MineCobblestoneResult result = taskServices.mineCobblestoneSync(amount, maxRadius, reportEvery);
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
            navigationController.moveToPositionSync(candidate.standPosition(), candidate.range(), 15000L, true, true);
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
                    navigationController.moveToPositionSync(candidate.standPosition(), candidate.range(), 8000L, true, true);
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

    private record DigTickResult(boolean blockPresent, boolean needsReposition) {
    }

    private record DigCandidate(BlockPos feetPos, Vec3d standPosition, double score, double range) {
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

    private final class NavigationHooks implements NavigationController.Hooks {
        @Override
        public boolean isBlockReachable(BlockPos pos) {
            return isBlockReachableNow(pos).reachable();
        }

        @Override
        public boolean tickClearObstruction(BlockPos pos) {
            return updateDigTick(pos).blockPresent();
        }

        @Override
        public boolean isClearableFoliage(ClientWorld world, BlockPos pos) {
            return MinecraftMcpBridgeMod.this.isClearableFoliage(world, pos);
        }

        @Override
        public void stopDigging(MinecraftClient client) {
            MinecraftMcpBridgeMod.this.stopDigging(client);
        }
    }

    private final class TaskHooks implements TaskServices.Hooks {
        @Override
        public void digBlockSync(BlockPos pos) throws Exception {
            MinecraftMcpBridgeMod.this.digBlockSync(pos);
        }

        @Override
        public void moveToPosition(Vec3d target, double range, long timeoutMs, boolean allowJump, boolean clearSoftObstructions) throws Exception {
            navigationController.moveToPositionSync(target, range, timeoutMs, allowJump, clearSoftObstructions);
        }

        @Override
        public boolean isDigReachable(BlockPos pos) {
            return isBlockReachableNow(pos).reachable();
        }

        @Override
        public boolean canRepositionForDig(BlockPos pos) {
            return selectBestDigCandidate(findDigCandidates(pos)) != null;
        }

        @Override
        public boolean hasClearableDigObstruction(BlockPos pos) {
            return findClearableDigObstruction(pos) != null;
        }

        @Override
        public void sendJobProgress(String channel, String message) {
            MinecraftMcpBridgeMod.this.sendJobProgress(channel, message);
        }
    }
}
