package dev.yuniko.minecraftmcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.Direction;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MinecraftMcpBridgeMod implements ClientModInitializer {
    private static final String PROTOCOL_VERSION = "1.0.0";
    private static final String BRIDGE_VERSION = "0.1.0";
    private static final int SCAN_BATCH_SIZE = 2048;
    private static final double PRECISE_DIG_APPROACH_RANGE = 0.8D;
    private static final BridgeRecipeCatalog RECIPE_CATALOG = BridgeRecipeCatalog.createDefault();
    private static final Set<String> CLEARABLE_FOLIAGE_BLOCKS = Set.of(
        "dead_bush",
        "sweet_berry_bush",
        "azalea",
        "flowering_azalea",
        "mangrove_roots",
        "mangrove_propagule",
        "vine",
        "cave_vines",
        "cave_vines_plant",
        "weeping_vines",
        "weeping_vines_plant",
        "twisting_vines",
        "twisting_vines_plant",
        "hanging_roots"
    );
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
        "place-block",
        "list-recipes",
        "get-recipe",
        "can-craft",
        "craft-item",
        "detect-gamemode",
        "send-chat"
    );

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private final ClientThreadExecutor clientThreadExecutor = new ClientThreadExecutor();
    private final ActionCoordinator actionCoordinator = new ActionCoordinator();
    private final ActionDispatcher actionDispatcher = new ActionDispatcher(new BridgeActionHandler());
    private final NavigationController navigationController = new NavigationController(clientThreadExecutor, new NavigationHooks());
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

    private void handleActionRequest(ProtocolSession session, String requestId, String action, JsonObject args) {
        ActionContext context = new ActionContext(session, requestId);
        if (!SUPPORTED_ACTIONS.contains(action)) {
            context.sendError("Action '" + action + "' is not yet supported by the Fabric companion.");
            return;
        }

        actionCoordinator.dispatch(
            executionModeFor(action),
            () -> executeAction(context, action, args),
            (error) -> context.sendError(
                "Unexpected failure while executing '" + action + "': " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
            )
        );
    }

    private void executeAction(ActionContext context, String action, JsonObject args) {
        SessionStateSnapshot sessionState = readSessionStateSnapshotSafely();
        if (!sessionState.worldReady()) {
            context.sendError("Minecraft world is not ready yet. Join a world or server first.");
            return;
        }

        switch (action) {
            case "get-position" -> handleGetPosition(context);
            case "list-inventory" -> handleListInventory(context);
            case "find-item" -> handleFindItem(context, args);
            case "equip-item" -> handleEquipItem(context, args);
            case "move-to-position" -> handleMoveToPosition(context, args);
            case "dig-block" -> handleDigBlock(context, args);
            case "harvest-wood" -> handleHarvestWood(context, args);
            case "mine-cobblestone" -> handleMineCobblestone(context, args);
            case "get-block-info" -> handleGetBlockInfo(context, args);
            case "find-block" -> handleFindBlock(context, args);
            case "place-block" -> handlePlaceBlock(context, args);
            case "list-recipes" -> handleListRecipes(context, args);
            case "get-recipe" -> handleGetRecipe(context, args);
            case "can-craft" -> handleCanCraft(context, args);
            case "craft-item" -> handleCraftItem(context, args);
            case "detect-gamemode" -> handleDetectGamemode(context);
            case "send-chat" -> handleSendChat(context, args);
            default -> context.sendError("Action '" + action + "' is not yet implemented.");
        }
    }

    private ActionCoordinator.Mode executionModeFor(String action) {
        return switch (action) {
            case "get-position",
                "list-inventory",
                "find-item",
                "get-block-info",
                "find-block",
                "list-recipes",
                "get-recipe",
                "can-craft",
                "detect-gamemode" -> ActionCoordinator.Mode.READ_ONLY;
            case "equip-item",
                "move-to-position",
                "dig-block",
                "harvest-wood",
                "mine-cobblestone",
                "place-block",
                "craft-item",
                "send-chat" -> ActionCoordinator.Mode.EXCLUSIVE;
            default -> ActionCoordinator.Mode.READ_ONLY;
        };
    }

    private void handleGetPosition(ActionContext context) {
        try {
            PositionSnapshot position = callOnClientThread(this::readPlayerPosition);
            JsonObject data = new JsonObject();
            data.addProperty("x", position.x());
            data.addProperty("y", position.y());
            data.addProperty("z", position.z());
            context.sendActionResult("Current position: (" + position.x() + ", " + position.y() + ", " + position.z() + ")", data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleListInventory(ActionContext context) {
        try {
            List<InventoryItemSnapshot> inventory = callOnClientThread(this::readInventorySnapshot);
            JsonArray items = new JsonArray();
            StringBuilder output = new StringBuilder();

            for (InventoryItemSnapshot item : inventory) {
                JsonObject jsonItem = new JsonObject();
                jsonItem.addProperty("id", item.id());
                jsonItem.addProperty("displayName", item.displayName());
                jsonItem.addProperty("count", item.count());
                jsonItem.addProperty("slot", item.slot());
                items.add(jsonItem);

                output.append("- ")
                    .append(item.id())
                    .append(" (")
                    .append(item.displayName())
                    .append(", x")
                    .append(item.count())
                    .append(") in slot ")
                    .append(item.slot())
                    .append('\n');
            }

            if (inventory.isEmpty()) {
                context.sendActionResult("Inventory is empty", items);
                return;
            }

            context.sendActionResult("Found " + inventory.size() + " items in inventory:\n\n" + output, items);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleFindItem(ActionContext context, JsonObject args) {
        String query = lower(getAsString(args, "nameOrType"));
        if (query == null || query.isBlank()) {
            context.sendError("nameOrType is required.");
            return;
        }

        try {
            InventoryItemSnapshot item = callOnClientThread(() -> findMatchingInventoryItem(query));
            if (item != null) {
                JsonObject data = new JsonObject();
                data.addProperty("id", item.id());
                data.addProperty("displayName", item.displayName());
                data.addProperty("count", item.count());
                data.addProperty("slot", item.slot());
                context.sendActionResult("Found " + item.id() + " (" + item.displayName() + ") x" + item.count() + " in slot " + item.slot(), data);
                return;
            }

            context.sendActionResult("Couldn't find any item matching '" + query + "' in inventory", new JsonObject());
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleEquipItem(ActionContext context, JsonObject args) {
        String query = lower(getAsString(args, "itemName"));
        if (query == null || query.isBlank()) {
            context.sendError("itemName is required.");
            return;
        }

        try {
            InventoryItemSnapshot equippedItem = callOnClientThread(() -> equipMatchingHotbarItem(query));
            JsonObject data = new JsonObject();
            data.addProperty("slot", equippedItem.slot());
            context.sendActionResult("Equipped " + equippedItem.id() + " to hand from hotbar slot " + equippedItem.slot(), data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleGetBlockInfo(ActionContext context, JsonObject args) {
        Integer x = getAsInt(args, "x");
        Integer y = getAsInt(args, "y");
        Integer z = getAsInt(args, "z");
        if (x == null || y == null || z == null) {
            context.sendError("x, y and z are required.");
            return;
        }

        try {
            BlockInfoSnapshot block = callOnClientThread(() -> readBlockInfo(new BlockPos(x, y, z)));
            JsonObject data = new JsonObject();
            data.addProperty("id", block.id());
            data.addProperty("x", block.x());
            data.addProperty("y", block.y());
            data.addProperty("z", block.z());
            data.addProperty("displayName", block.displayName());
            context.sendActionResult("Found " + block.id() + " at position (" + block.x() + ", " + block.y() + ", " + block.z() + ")", data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleMoveToPosition(ActionContext context, JsonObject args) {
        Integer x = getAsInt(args, "x");
        Integer y = getAsInt(args, "y");
        Integer z = getAsInt(args, "z");
        double range = getAsDouble(args, "range") != null ? Objects.requireNonNull(getAsDouble(args, "range")) : 1.0D;
        long timeoutMs = getAsInt(args, "timeoutMs") != null ? Objects.requireNonNull(getAsInt(args, "timeoutMs")) : 15000L;

        if (x == null || y == null || z == null) {
            context.sendError("x, y and z are required.");
            return;
        }

        try {
            NavigationController.MoveResult result = navigationController.moveToPositionSync(new Vec3d(x + 0.5D, y, z + 0.5D), range, timeoutMs);
            JsonObject data = new JsonObject();
            data.addProperty("x", result.position().x);
            data.addProperty("y", result.position().y);
            data.addProperty("z", result.position().z);
            context.sendActionResult("Successfully moved to position near (" + x + ", " + y + ", " + z + ")", data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleDigBlock(ActionContext context, JsonObject args) {
        Integer x = getAsInt(args, "x");
        Integer y = getAsInt(args, "y");
        Integer z = getAsInt(args, "z");

        if (x == null || y == null || z == null) {
            context.sendError("x, y and z are required.");
            return;
        }

        try {
            String blockName = digBlockSync(new BlockPos(x, y, z));
            JsonObject data = new JsonObject();
            data.addProperty("x", x);
            data.addProperty("y", y);
            data.addProperty("z", z);
            data.addProperty("block", blockName);
            context.sendActionResult("Dug " + blockName + " at (" + x + ", " + y + ", " + z + ")", data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleHarvestWood(ActionContext context, JsonObject args) {
        Integer amount = getAsInt(args, "amount");
        String preferredType = lower(getAsString(args, "preferredType"));
        int maxRadius = getAsInt(args, "maxRadius") != null ? Objects.requireNonNull(getAsInt(args, "maxRadius")) : 48;
        int reportEvery = getAsInt(args, "reportEvery") != null ? Objects.requireNonNull(getAsInt(args, "reportEvery")) : 15;

        if (amount == null || amount < 1) {
            context.sendError("amount must be a positive integer.");
            return;
        }

        try {
            TaskServices.HarvestWoodResult result = taskServicesFor(context).harvestWoodSync(amount, preferredType, maxRadius, reportEvery);
            JsonObject data = new JsonObject();
            data.addProperty("processed", result.processed());
            data.addProperty("collected", result.collected());
            data.addProperty("requested", result.requested());
            data.addProperty("filter", result.filter() == null ? "any_log" : result.filter());
            data.addProperty("x", result.position().getX());
            data.addProperty("y", result.position().getY());
            data.addProperty("z", result.position().getZ());
            context.sendActionResult(
                "Harvested " + result.collected() + " log(s) after processing " + result.processed() + " block(s).",
                data
            );
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleMineCobblestone(ActionContext context, JsonObject args) {
        Integer amount = getAsInt(args, "amount");
        int maxRadius = getAsInt(args, "maxRadius") != null ? Objects.requireNonNull(getAsInt(args, "maxRadius")) : 32;
        int reportEvery = getAsInt(args, "reportEvery") != null ? Objects.requireNonNull(getAsInt(args, "reportEvery")) : 15;

        if (amount == null || amount < 1) {
            context.sendError("amount must be a positive integer.");
            return;
        }

        try {
            TaskServices.MineCobblestoneResult result = taskServicesFor(context).mineCobblestoneSync(amount, maxRadius, reportEvery);
            JsonObject data = new JsonObject();
            data.addProperty("processed", result.processed());
            data.addProperty("collected", result.collected());
            data.addProperty("requested", result.requested());
            data.addProperty("block", result.block());
            data.addProperty("x", result.position().getX());
            data.addProperty("y", result.position().getY());
            data.addProperty("z", result.position().getZ());
            context.sendActionResult(
                "Mined " + result.collected() + " cobblestone after processing " + result.processed() + " block(s).",
                data
            );
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleFindBlock(ActionContext context, JsonObject args) {
        String query = lower(getAsString(args, "blockType"));
        int maxDistance = getAsInt(args, "maxDistance") != null ? Objects.requireNonNull(getAsInt(args, "maxDistance")) : 16;
        if (query == null || query.isBlank()) {
            context.sendError("blockType is required.");
            return;
        }

        try {
            FindBlockMatch match = findNearestBlockMatch(query, maxDistance);
            if (match == null) {
                context.sendActionResult("No " + query + " found within " + maxDistance + " blocks", new JsonObject());
                return;
            }

            JsonObject data = new JsonObject();
            data.addProperty("id", match.id());
            data.addProperty("x", match.position().getX());
            data.addProperty("y", match.position().getY());
            data.addProperty("z", match.position().getZ());
            context.sendActionResult(
                "Found " + match.id() + " at position (" + match.position().getX() + ", " + match.position().getY() + ", " + match.position().getZ() + ")",
                data
            );
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handlePlaceBlock(ActionContext context, JsonObject args) {
        Integer x = getAsInt(args, "x");
        Integer y = getAsInt(args, "y");
        Integer z = getAsInt(args, "z");
        String faceDirection = lower(getAsString(args, "faceDirection"));
        if (x == null || y == null || z == null) {
            context.sendError("x, y and z are required.");
            return;
        }

        try {
            PlacementResult result = placeEquippedBlockSync(new BlockPos(x, y, z), faceDirection);
            JsonObject data = new JsonObject();
            data.addProperty("placed", result.placed());
            data.addProperty("x", x);
            data.addProperty("y", y);
            data.addProperty("z", z);
            if (result.face() != null) {
                data.addProperty("face", result.face());
            }
            if (result.reason() != null) {
                data.addProperty("reason", result.reason());
            }
            if (result.blockId() != null) {
                data.addProperty("block", result.blockId());
            }
            context.sendActionResult(result.message(), data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleListRecipes(ActionContext context, JsonObject args) {
        String outputItem = lower(getAsString(args, "outputItem"));
        try {
            BridgeRecipeCatalog.StationAccess stationAccess = callOnClientThread(this::readRecipeStationAccessOnClientThread);
            List<BridgeRecipeCatalog.InventoryItem> inventory = callOnClientThread(this::readRecipeInventoryOnClientThread);
            List<BridgeRecipeCatalog.RecipeCandidate> recipes = outputItem == null
                ? RECIPE_CATALOG.listCraftableRecipes(inventory, stationAccess)
                : RECIPE_CATALOG.findRecipes(outputItem, inventory, stationAccess).stream()
                    .filter(candidate -> candidate.evaluation().canCraft())
                    .toList();

            JsonObject data = new JsonObject();
            JsonArray items = new JsonArray();
            for (BridgeRecipeCatalog.RecipeCandidate candidate : recipes) {
                items.add(recipeCandidateToJson(candidate));
            }
            data.add("recipes", items);
            data.addProperty("count", recipes.size());
            if (outputItem != null) {
                data.addProperty("outputItem", outputItem);
            }

            if (recipes.isEmpty()) {
                context.sendActionResult(
                    "No craftable recipes found" + (outputItem != null ? " for " + outputItem : "") + " with current inventory",
                    data
                );
                return;
            }

            StringBuilder output = new StringBuilder("Found " + recipes.size() + " craftable recipe(s):\n\n");
            for (int index = 0; index < recipes.size(); index++) {
                BridgeRecipeCatalog.RecipeCandidate candidate = recipes.get(index);
                output.append(index + 1)
                    .append(". ")
                    .append(candidate.recipe().outputItem())
                    .append(" (x")
                    .append(candidate.recipe().outputCount())
                    .append(")\n");
                output.append("   Ingredients: ")
                    .append(formatIngredientSummaries(candidate.recipe()))
                    .append("\n\n");
            }

            context.sendActionResult(output.toString(), data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleGetRecipe(ActionContext context, JsonObject args) {
        String itemName = lower(getAsString(args, "itemName"));
        if (itemName == null || itemName.isBlank()) {
            context.sendError("itemName is required.");
            return;
        }

        try {
            BridgeRecipeCatalog.StationAccess stationAccess = callOnClientThread(this::readRecipeStationAccessOnClientThread);
            List<BridgeRecipeCatalog.InventoryItem> inventory = callOnClientThread(this::readRecipeInventoryOnClientThread);
            List<BridgeRecipeCatalog.RecipeCandidate> recipes = RECIPE_CATALOG.findRecipes(itemName, inventory, stationAccess);

            JsonObject data = new JsonObject();
            JsonArray items = new JsonArray();
            for (BridgeRecipeCatalog.RecipeCandidate candidate : recipes) {
                items.add(recipeCandidateToJson(candidate));
            }
            data.add("recipes", items);
            data.addProperty("count", recipes.size());
            data.addProperty("itemName", itemName);

            if (recipes.isEmpty()) {
                context.sendActionResult("No recipes found for " + itemName, data);
                return;
            }

            StringBuilder output = new StringBuilder("Recipe(s) for " + itemName + ":\n\n");
            for (int index = 0; index < recipes.size(); index++) {
                BridgeRecipeCatalog.RecipeCandidate candidate = recipes.get(index);
                output.append(index + 1)
                    .append(". Output: ")
                    .append(candidate.recipe().outputItem())
                    .append(" (x")
                    .append(candidate.recipe().outputCount())
                    .append(")");
                output.append(candidate.evaluation().canCraft() ? " [craftable]\n" : " [missing: " + candidate.evaluation().missingTotal() + "]\n");
                output.append("   Ingredients: ")
                    .append(formatIngredientSummaries(candidate.recipe()))
                    .append("\n\n");
            }

            context.sendActionResult(output.toString(), data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleCanCraft(ActionContext context, JsonObject args) {
        String itemName = lower(getAsString(args, "itemName"));
        if (itemName == null || itemName.isBlank()) {
            context.sendError("itemName is required.");
            return;
        }

        try {
            BridgeRecipeCatalog.StationAccess stationAccess = callOnClientThread(this::readRecipeStationAccessOnClientThread);
            List<BridgeRecipeCatalog.InventoryItem> inventory = callOnClientThread(this::readRecipeInventoryOnClientThread);
            List<BridgeRecipeCatalog.RecipeCandidate> recipes = RECIPE_CATALOG.findRecipes(itemName, inventory, stationAccess);
            JsonObject data = new JsonObject();
            data.addProperty("itemName", itemName);

            if (recipes.isEmpty()) {
                data.addProperty("canCraft", false);
                data.add("missing", new JsonArray());
                context.sendActionResult("No recipe found for " + itemName, data);
                return;
            }

            BridgeRecipeCatalog.RecipeCandidate best = recipes.getFirst();
            data.addProperty("canCraft", best.evaluation().canCraft());
            data.addProperty("resultName", best.recipe().outputItem());
            data.add("missing", missingRequirementsToJson(best.evaluation().missing()));
            if (best.evaluation().canCraft()) {
                context.sendActionResult("Yes, can craft " + best.recipe().outputItem() + ". Have all required ingredients.", data);
                return;
            }

            context.sendActionResult(formatMissingRequirements(best), data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleCraftItem(ActionContext context, JsonObject args) {
        String outputItem = lower(getAsString(args, "outputItem"));
        int amount = getAsInt(args, "amount") != null ? Objects.requireNonNull(getAsInt(args, "amount")) : 1;
        if (outputItem == null || outputItem.isBlank()) {
            context.sendError("outputItem is required.");
            return;
        }
        if (amount < 1) {
            context.sendError("amount must be a positive integer.");
            return;
        }

        try {
            CraftResult result = craftItemSync(outputItem, amount);
            JsonObject data = new JsonObject();
            data.addProperty("crafted", result.craftedCount() > 0);
            data.addProperty("outputItem", result.outputItem());
            data.addProperty("craftedCount", result.craftedCount());
            context.sendActionResult("Successfully crafted " + result.outputItem() + " " + result.craftedCount() + " time(s)", data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleDetectGamemode(ActionContext context) {
        try {
            String gameMode = callOnClientThread(this::readCurrentGamemode);
            JsonObject data = new JsonObject();
            data.addProperty("gameMode", gameMode);
            context.sendActionResult("Bot gamemode: \"" + gameMode + "\"", data);
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private void handleSendChat(ActionContext context, JsonObject args) {
        String message = getAsString(args, "message");
        if (message == null || message.isBlank()) {
            context.sendError("message is required.");
            return;
        }

        try {
            callOnClientThread(() -> {
                sendChatMessage(message);
                return null;
            });
            context.sendActionResult("Sent message: \"" + message + "\"", new JsonObject());
        } catch (Exception error) {
            context.sendError(error.getMessage());
        }
    }

    private TaskServices taskServicesFor(ActionContext context) {
        return new TaskServices(new TaskHooks(context));
    }

    private void sendHello(ProtocolSession session) {
        session.send(BridgeProtocolMessages.hello(PROTOCOL_VERSION, BRIDGE_VERSION));
    }

    private void sendCapabilities(ProtocolSession session) {
        session.send(BridgeProtocolMessages.capabilities(
            PROTOCOL_VERSION,
            BRIDGE_VERSION,
            "1.21.11",
            "fabric",
            FabricLoader.getInstance().getModContainer("fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown"),
            readSessionStateSnapshotSafely().worldReady(),
            SUPPORTED_ACTIONS,
            List.of(
                "Fabric MVP bridge: read-only registry access plus a small set of player actions.",
                "Unsupported actions return explicit errors for MCP capability negotiation."
            )
        ));
    }

    private void sendSessionState(ProtocolSession session) {
        SessionStateSnapshot snapshot = readSessionStateSnapshotSafely();
        session.send(BridgeProtocolMessages.sessionState(
            snapshot.worldReady(),
            snapshot.connected(),
            snapshot.playerName(),
            snapshot.dimension()
        ));
    }

    private void sendRegistrySnapshot(ProtocolSession session) {
        session.send(BridgeProtocolMessages.registrySnapshot(
            Registries.BLOCK.getIds(),
            Registries.ITEM.getIds(),
            Registries.ENTITY_TYPE.getIds()
        ));
    }

    private void sendToActive(JsonObject payload) {
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
        SessionStateSnapshot snapshot = readSessionStateSnapshotSafely();
        sendToActive(BridgeProtocolMessages.sessionState(
            snapshot.worldReady(),
            snapshot.connected(),
            snapshot.playerName(),
            snapshot.dimension()
        ));
    }

    private void monitorWorldState() {
        boolean worldReady = readSessionStateSnapshotSafely().worldReady();
        if (worldReady != lastWorldReady) {
            lastWorldReady = worldReady;
            broadcastSessionState();
        }
    }

    private boolean isWorldReady() {
        return readSessionStateSnapshotSafely().worldReady();
    }

    private SessionStateSnapshot readSessionStateSnapshotSafely() {
        try {
            return readSessionStateSnapshot();
        } catch (Exception error) {
            return new SessionStateSnapshot(false, false, null, null);
        }
    }

    private SessionStateSnapshot readSessionStateSnapshot() throws Exception {
        return callOnClientThread(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            ClientWorld world = client.world;
            String dimension = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString()
                : null;
            return new SessionStateSnapshot(player != null && world != null, player != null, player != null ? player.getName().getString() : null, dimension);
        });
    }

    private PositionSnapshot readPlayerPosition() {
        ClientPlayerEntity player = requirePlayer();
        return new PositionSnapshot(
            (int) Math.floor(player.getX()),
            (int) Math.floor(player.getY()),
            (int) Math.floor(player.getZ())
        );
    }

    private List<InventoryItemSnapshot> readInventorySnapshot() {
        ClientPlayerEntity player = requirePlayer();
        List<InventoryItemSnapshot> items = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            items.add(new InventoryItemSnapshot(
                Registries.ITEM.getId(stack.getItem()).toString(),
                stack.getName().getString(),
                stack.getCount(),
                slot
            ));
        }
        return List.copyOf(items);
    }

    private InventoryItemSnapshot findMatchingInventoryItem(String query) {
        return findMatchingInventoryItemSnapshot(query);
    }

    private InventoryItemSnapshot findMatchingInventoryItemSnapshot(String query) {
        ClientPlayerEntity player = requirePlayer();
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            String id = Registries.ITEM.getId(stack.getItem()).toString();
            String displayName = stack.getName().getString();
            if (id.toLowerCase().contains(query) || displayName.toLowerCase().contains(query)) {
                return new InventoryItemSnapshot(id, displayName, stack.getCount(), slot);
            }
        }

        return null;
    }

    private InventoryItemSnapshot equipMatchingHotbarItem(String query) {
        try {
            return equipMatchingInventoryItem(query);
        } catch (Exception error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private boolean ensureInventoryItemSelectedInHotbarOnClientThread(String query, int hotbarSlot) throws Exception {
        ClientPlayerEntity player = requirePlayer();
        InventoryItemSnapshot match = findMatchingInventoryItemSnapshot(query);
        if (match == null) {
            return false;
        }

        int targetHotbarSlot = resolveHotbarSlot(player, match.slot(), hotbarSlot);
        if (match.slot() != targetHotbarSlot) {
            swapInventorySlotIntoHotbar(player, match.slot(), targetHotbarSlot);
        }

        selectHotbarSlot(player, targetHotbarSlot);
        return true;
    }

    private InventoryItemSnapshot equipMatchingInventoryItem(String query) throws Exception {
        ClientPlayerEntity player = requirePlayer();
        InventoryItemSnapshot match = findMatchingInventoryItemSnapshot(query);
        if (match == null) {
            throw new IllegalStateException("Could not equip '" + query + "'. Item not found in inventory.");
        }

        int hotbarSlot = resolveHotbarSlot(player, match.slot(), null);
        if (match.slot() != hotbarSlot) {
            swapInventorySlotIntoHotbar(player, match.slot(), hotbarSlot);
        }

        selectHotbarSlot(player, hotbarSlot);
        ItemStack equipped = player.getInventory().getStack(hotbarSlot);
        return new InventoryItemSnapshot(
            Registries.ITEM.getId(equipped.getItem()).toString(),
            equipped.getName().getString(),
            equipped.getCount(),
            hotbarSlot
        );
    }

    private int resolveHotbarSlot(ClientPlayerEntity player, int sourceSlot, Integer preferredHotbarSlot) {
        if (preferredHotbarSlot != null && preferredHotbarSlot >= 0 && preferredHotbarSlot <= 8) {
            return preferredHotbarSlot;
        }

        return sourceSlot < 9 ? sourceSlot : chooseHotbarSlotForSwap(player, sourceSlot);
    }

    private int chooseHotbarSlotForSwap(ClientPlayerEntity player, int sourceSlot) {
        int selected = player.getInventory().getSelectedSlot();
        if (sourceSlot == selected) {
            return selected;
        }

        ItemStack selectedStack = player.getInventory().getStack(selected);
        if (selectedStack.isEmpty()) {
            return selected;
        }

        for (int slot = 0; slot < 9; slot++) {
            if (slot == sourceSlot) {
                return slot;
            }
            if (player.getInventory().getStack(slot).isEmpty()) {
                return slot;
            }
        }

        return selected;
    }

    private void swapInventorySlotIntoHotbar(ClientPlayerEntity player, int inventorySlot, int hotbarSlot) {
        if (player.currentScreenHandler != player.playerScreenHandler) {
            throw new IllegalStateException("Cannot rearrange inventory while another screen is open.");
        }

        int screenSlot = inventorySlotToPlayerScreenSlot(inventorySlot);
        if (screenSlot < 0) {
            throw new IllegalStateException("Inventory slot " + inventorySlot + " cannot be moved to the hotbar in the MVP bridge.");
        }

        requireInteractionManager().clickSlot(
            player.playerScreenHandler.syncId,
            screenSlot,
            hotbarSlot,
            SlotActionType.SWAP,
            player
        );
    }

    private void selectHotbarSlot(ClientPlayerEntity player, int hotbarSlot) {
        if (player.networkHandler == null) {
            throw new IllegalStateException("Cannot change hotbar slot right now.");
        }

        player.getInventory().setSelectedSlot(hotbarSlot);
        player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
    }

    private int inventorySlotToPlayerScreenSlot(int inventorySlot) {
        if (inventorySlot >= 9 && inventorySlot <= 35) {
            return inventorySlot;
        }
        if (inventorySlot >= 0 && inventorySlot <= 8) {
            return PlayerScreenHandler.HOTBAR_START + inventorySlot;
        }
        if (inventorySlot == 40) {
            return PlayerScreenHandler.OFFHAND_ID;
        }
        return -1;
    }

    private List<BridgeRecipeCatalog.InventoryItem> readRecipeInventoryOnClientThread() {
        List<BridgeRecipeCatalog.InventoryItem> items = new ArrayList<>();
        for (InventoryItemSnapshot snapshot : readInventorySnapshot()) {
            items.add(new BridgeRecipeCatalog.InventoryItem(snapshot.id(), snapshot.count()));
        }
        return List.copyOf(items);
    }

    private List<BridgeRecipeCatalog.InventorySlot> readRecipeInventorySlotsOnClientThread() {
        List<BridgeRecipeCatalog.InventorySlot> items = new ArrayList<>();
        ClientPlayerEntity player = requirePlayer();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            items.add(new BridgeRecipeCatalog.InventorySlot(slot, Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount()));
        }
        return List.copyOf(items);
    }

    private BridgeRecipeCatalog.StationAccess readRecipeStationAccessOnClientThread() {
        boolean nearby = findNearestExactBlockMatchOnClientThread("crafting_table", 16) != null;
        boolean inInventory = countMatchingInventoryItemsOnClientThread("crafting_table") > 0;
        return new BridgeRecipeCatalog.StationAccess(nearby, inInventory);
    }

    private BlockInfoSnapshot readBlockInfo(BlockPos pos) {
        ClientWorld world = requireWorld();
        BlockState state = world.getBlockState(pos);
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        return new BlockInfoSnapshot(id.toString(), state.getBlock().getName().getString(), pos.getX(), pos.getY(), pos.getZ());
    }

    private String readCurrentGamemode() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager == null || client.interactionManager.getCurrentGameMode() == null) {
            throw new IllegalStateException("Current gamemode is not available.");
        }

        return client.interactionManager.getCurrentGameMode().name();
    }

    private void sendChatMessage(String message) {
        ClientPlayerEntity player = requirePlayer();
        if (player.networkHandler == null) {
            throw new IllegalStateException("Chat is not available right now.");
        }

        player.networkHandler.sendChatMessage(message);
    }

    private FindBlockMatch findNearestBlockMatch(String query, int maxDistance) throws Exception {
        SearchOrigin origin = callOnClientThread(this::readSearchOrigin);
        if (origin == null) {
            return null;
        }

        BlockBoxBatchCursor cursor = new BlockBoxBatchCursor(origin.position(), -maxDistance, maxDistance, -maxDistance, maxDistance, -maxDistance, maxDistance);
        FindBlockMatch bestMatch = null;

        while (cursor.hasRemaining()) {
            List<BlockPos> batch = cursor.nextBatch(SCAN_BATCH_SIZE);
            FindBlockMatch batchMatch = callOnClientThread(() -> evaluateFindBlockBatch(origin, query, batch));
            if (batchMatch != null && (bestMatch == null || batchMatch.distanceSq() < bestMatch.distanceSq())) {
                bestMatch = batchMatch;
            }
        }

        return bestMatch;
    }

    private FindBlockMatch findNearestExactBlockMatchOnClientThread(String targetType, int maxDistance) {
        SearchOrigin origin = readSearchOrigin();
        if (origin == null) {
            return null;
        }

        ClientWorld world = requireWorld();
        FindBlockMatch bestMatch = null;
        int verticalRadius = Math.min(maxDistance, 12);
        for (int dx = -maxDistance; dx <= maxDistance; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -maxDistance; dz <= maxDistance; dz++) {
                    BlockPos candidate = origin.position().add(dx, dy, dz);
                    BlockState state = world.getBlockState(candidate);
                    String localId = BridgePlacementSupport.localId(Registries.BLOCK.getId(state.getBlock()).toString());
                    if (!localId.equals(targetType)) {
                        continue;
                    }

                    double distanceSq = candidate.getSquaredDistance(origin.position());
                    if (bestMatch == null || distanceSq < bestMatch.distanceSq()) {
                        bestMatch = new FindBlockMatch(localId, candidate.toImmutable(), distanceSq);
                    }
                }
            }
        }

        return bestMatch;
    }

    private FindBlockMatch evaluateFindBlockBatch(SearchOrigin origin, String query, List<BlockPos> batch) {
        ClientWorld world = requireWorld();
        FindBlockMatch bestMatch = null;
        for (BlockPos candidate : batch) {
            BlockState state = world.getBlockState(candidate);
            Identifier id = Registries.BLOCK.getId(state.getBlock());
            if (!id.toString().toLowerCase().contains(query)) {
                continue;
            }

            double distanceSq = candidate.getSquaredDistance(origin.position());
            if (bestMatch == null || distanceSq < bestMatch.distanceSq()) {
                bestMatch = new FindBlockMatch(id.toString(), candidate.toImmutable(), distanceSq);
            }
        }

        return bestMatch;
    }

    private int countMatchingLogsInInventoryOnClientThread(String preferredType) {
        ClientPlayerEntity player = requirePlayer();
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

    private int countMatchingInventoryItemsOnClientThread(String targetItem) {
        ClientPlayerEntity player = requirePlayer();
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

    private PlacementResult placeEquippedBlockSync(BlockPos targetPos, String faceDirection) throws Exception {
        PlacementPlan plan = callOnClientThread(() -> buildPlacementPlanOnClientThread(targetPos, faceDirection));
        if (plan.failure() != null) {
            return plan.failure();
        }

        boolean reachedCandidate = false;
        for (PlacementCandidate candidate : plan.candidates()) {
            boolean reachable = callOnClientThread(() -> isPlacementCandidateReachableOnClientThread(candidate));
            if (!reachable) {
                DigCandidate digCandidate = callOnClientThread(() -> selectBestDigCandidate(findDigCandidates(candidate.supportPos())));
                if (digCandidate == null) {
                    continue;
                }
                navigationController.moveToPositionSync(digCandidate.standPosition(), Math.max(1.0D, digCandidate.range()), 8000L, true, true);
                reachable = callOnClientThread(() -> isPlacementCandidateReachableOnClientThread(candidate));
            }

            if (!reachable) {
                continue;
            }

            reachedCandidate = true;
            PlacementAttempt attempt = callOnClientThread(() -> performPlacementAttemptOnClientThread(targetPos, plan.itemId(), candidate));
            if (attempt.confirmed()) {
                return new PlacementResult(
                    true,
                    "Placed block at (" + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + ") using " + attempt.face() + " face",
                    null,
                    attempt.face(),
                    attempt.blockId()
                );
            }
        }

        return new PlacementResult(
            false,
            reachedCandidate
                ? "Failed to place block at (" + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + "): placement was not confirmed"
                : "Failed to place block at (" + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + "): target remained out of reach",
            reachedCandidate ? "placement_not_confirmed" : "out_of_reach",
            null,
            null
        );
    }

    private PlacementPlan buildPlacementPlanOnClientThread(BlockPos targetPos, String faceDirection) {
        ClientPlayerEntity player = requirePlayer();
        ClientWorld world = requireWorld();
        ItemStack handStack = player.getMainHandStack();
        if (handStack.isEmpty() || !(handStack.getItem() instanceof BlockItem)) {
            return new PlacementPlan(
                null,
                List.of(),
                new PlacementResult(false, "Failed to place block at (" + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + "): equipped item is not placeable", "not_placeable", null, null)
            );
        }

        BlockState targetState = world.getBlockState(targetPos);
        if (!targetState.isAir() && !targetState.isReplaceable()) {
            String occupiedBy = Registries.BLOCK.getId(targetState.getBlock()).toString();
            return new PlacementPlan(
                Registries.ITEM.getId(handStack.getItem()).toString(),
                List.of(),
                new PlacementResult(
                    false,
                    "There's already a block (" + occupiedBy + ") at (" + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + ")",
                    "occupied",
                    null,
                    occupiedBy
                )
            );
        }

        List<PlacementCandidate> candidates = new ArrayList<>();
        for (String faceLabel : BridgePlacementSupport.orderedFaceDirections(faceDirection)) {
            Direction supportOffset = directionByName(faceLabel);
            if (supportOffset == null) {
                continue;
            }

            BlockPos supportPos = targetPos.offset(supportOffset);
            BlockState supportState = world.getBlockState(supportPos);
            if (supportState.isAir() || supportState.isReplaceable()) {
                continue;
            }

            Direction clickedFace = supportOffset.getOpposite();
            Vec3d aimPoint = Vec3d.ofCenter(supportPos).add(
                clickedFace.getOffsetX() * 0.499D,
                clickedFace.getOffsetY() * 0.499D,
                clickedFace.getOffsetZ() * 0.499D
            );
            candidates.add(new PlacementCandidate(faceLabel, supportPos.toImmutable(), clickedFace, aimPoint));
        }

        if (candidates.isEmpty()) {
            return new PlacementPlan(
                Registries.ITEM.getId(handStack.getItem()).toString(),
                List.of(),
                new PlacementResult(
                    false,
                    "Failed to place block at (" + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + "): no suitable support block found",
                    "no_support",
                    null,
                    null
                )
            );
        }

        return new PlacementPlan(Registries.ITEM.getId(handStack.getItem()).toString(), List.copyOf(candidates), null);
    }

    private boolean isPlacementCandidateReachableOnClientThread(PlacementCandidate candidate) {
        ClientPlayerEntity player = requirePlayer();
        ClientWorld world = requireWorld();
        double reach = getInteractionReach(player);
        if (player.getEyePos().squaredDistanceTo(candidate.aimPoint()) > reach * reach) {
            return false;
        }
        return hasLineOfSightToBlock(world, player.getEyePos(), candidate.aimPoint(), candidate.supportPos());
    }

    private PlacementAttempt performPlacementAttemptOnClientThread(BlockPos targetPos, String itemId, PlacementCandidate candidate) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = requirePlayer();
        ClientWorld world = requireWorld();
        if (client.interactionManager == null) {
            throw new IllegalStateException("Block interaction is not available right now.");
        }

        lookAt(player, candidate.aimPoint());
        ActionResult actionResult = client.interactionManager.interactBlock(
            player,
            Hand.MAIN_HAND,
            new BlockHitResult(candidate.aimPoint(), candidate.clickedFace(), candidate.supportPos(), false)
        );
        player.swingHand(Hand.MAIN_HAND);

        BlockState placedState = world.getBlockState(targetPos);
        String placedBlockId = Registries.BLOCK.getId(placedState.getBlock()).toString();
        boolean confirmed = !placedState.isAir() && actionResult.isAccepted() && BridgePlacementSupport.placementConfirmed(itemId, placedBlockId);
        return new PlacementAttempt(candidate.faceLabel(), confirmed, placedBlockId);
    }

    private CraftResult craftItemSync(String outputItem, int amount) throws Exception {
        int craftedCount = 0;

        for (int attempt = 0; attempt < amount; attempt++) {
            BridgeRecipeCatalog.RecipeCandidate recipeCandidate = callOnClientThread(() -> {
                BridgeRecipeCatalog.StationAccess stationAccess = readRecipeStationAccessOnClientThread();
                List<BridgeRecipeCatalog.InventoryItem> inventory = readRecipeInventoryOnClientThread();
                return RECIPE_CATALOG.selectRecipe(outputItem, inventory, stationAccess);
            });

            if (recipeCandidate == null) {
                throw new IllegalStateException("Recipe '" + outputItem + "' is not supported by the Fabric MVP crafting bridge.");
            }

            if (!recipeCandidate.evaluation().canCraft()) {
                if (craftedCount > 0) {
                    break;
                }
                throw new IllegalStateException(formatMissingRequirements(recipeCandidate));
            }

            Map<String, Integer> before = callOnClientThread(this::readInventoryCountMapOnClientThread);
            if (recipeCandidate.recipe().requiresCraftingTable()) {
                BlockPos tablePos = ensureCraftingTableAvailableSync();
                CraftingScreenHandler handler = openCraftingTableScreenSync(tablePos);
                try {
                    craftRecipeOnceSync(handler, recipeCandidate.recipe());
                } finally {
                    closeHandledScreenSync();
                }
            } else {
                PlayerScreenHandler handler = callOnClientThread(() -> requirePlayer().playerScreenHandler);
                craftRecipeOnceSync(handler, recipeCandidate.recipe());
            }

            Map<String, Integer> after = callOnClientThread(this::readInventoryCountMapOnClientThread);
            int delta = after.getOrDefault(recipeCandidate.recipe().outputItem(), 0) - before.getOrDefault(recipeCandidate.recipe().outputItem(), 0);
            if (delta < recipeCandidate.recipe().outputCount()) {
                throw new IllegalStateException("Crafting " + recipeCandidate.recipe().outputItem() + " did not produce the expected output.");
            }
            craftedCount++;
        }

        if (craftedCount == 0) {
            throw new IllegalStateException("Failed to craft " + outputItem + ": missing ingredients or recipe not found");
        }

        return new CraftResult(outputItem, craftedCount);
    }

    private BlockPos ensureCraftingTableAvailableSync() throws Exception {
        FindBlockMatch nearby = callOnClientThread(() -> findNearestExactBlockMatchOnClientThread("crafting_table", 16));
        if (nearby != null) {
            return nearby.position();
        }

        callOnClientThread(() -> {
            equipMatchingInventoryItem("crafting_table");
            return null;
        });
        BlockPos targetPos = callOnClientThread(this::findPlacementTargetNearPlayerOnClientThread);
        if (targetPos == null) {
            throw new IllegalStateException("Could not find a nearby valid spot to place the crafting_table.");
        }

        PlacementResult result = placeEquippedBlockSync(targetPos, null);
        if (!result.placed()) {
            throw new IllegalStateException(result.message());
        }
        return targetPos;
    }

    private BlockPos findPlacementTargetNearPlayerOnClientThread() {
        ClientPlayerEntity player = requirePlayer();
        ClientWorld world = requireWorld();
        BlockPos origin = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
        BlockPos playerFeet = currentPlayerBlockPosOnClientThread();
        BlockPos playerHead = playerFeet.up();
        int[] verticalOffsets = new int[] {0, 1, -1};

        for (int radius = 1; radius <= 2; radius++) {
            for (int dy : verticalOffsets) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                            continue;
                        }
                        BlockPos candidate = origin.add(dx, dy, dz);
                        if (candidate.equals(playerFeet) || candidate.equals(playerHead)) {
                            continue;
                        }

                        BlockState state = world.getBlockState(candidate);
                        if (!state.isAir() && !state.isReplaceable()) {
                            continue;
                        }
                        if (!isSolidGround(world, candidate.down())) {
                            continue;
                        }

                        PlacementPlan plan = buildPlacementPlanOnClientThread(candidate, null);
                        if (plan.failure() == null && canReachOrRepositionForPlacementOnClientThread(candidate, plan)) {
                            return candidate.toImmutable();
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean canReachOrRepositionForPlacementOnClientThread(BlockPos targetPos, PlacementPlan plan) {
        for (PlacementCandidate candidate : plan.candidates()) {
            if (isPlacementCandidateReachableOnClientThread(candidate)) {
                return true;
            }
            if (selectBestDigCandidate(findDigCandidates(candidate.supportPos())) != null) {
                return true;
            }
        }

        return selectBestDigCandidate(findDigCandidates(targetPos)) != null;
    }

    private CraftingScreenHandler openCraftingTableScreenSync(BlockPos tablePos) throws Exception {
        ReachabilityResult reachability = callOnClientThread(() -> isBlockReachableNow(tablePos));
        if (!reachability.reachable()) {
            DigCandidate candidate = callOnClientThread(() -> selectBestDigCandidate(findDigCandidates(tablePos)));
            if (candidate == null) {
                throw new IllegalStateException("Could not find a valid position to use the crafting_table at (" + tablePos.getX() + ", " + tablePos.getY() + ", " + tablePos.getZ() + ")");
            }
            navigationController.moveToPositionSync(candidate.standPosition(), Math.max(1.0D, candidate.range()), 8000L, true, true);
        }

        callOnClientThread(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = requirePlayer();
            ReachabilityResult currentReachability = isBlockReachableNow(tablePos);
            if (!currentReachability.reachable()) {
                throw new IllegalStateException("Crafting table is still out of reach.");
            }
            lookAt(player, currentReachability.aimPoint());
            ActionResult result = requireInteractionManager().interactBlock(
                player,
                Hand.MAIN_HAND,
                new BlockHitResult(currentReachability.aimPoint(), currentReachability.face(), tablePos, false)
            );
            if (!result.isAccepted()) {
                throw new IllegalStateException("Could not open the crafting_table screen.");
            }
            return client.player.currentScreenHandler;
        });

        long deadline = System.currentTimeMillis() + 1500L;
        while (System.currentTimeMillis() < deadline) {
            ScreenHandler handler = callOnClientThread(() -> requirePlayer().currentScreenHandler);
            if (handler instanceof CraftingScreenHandler craftingScreenHandler) {
                return craftingScreenHandler;
            }
            Thread.sleep(50L);
        }

        throw new IllegalStateException("Timed out while waiting for the crafting_table screen to open.");
    }

    private void closeHandledScreenSync() throws Exception {
        callOnClientThread(() -> {
            ClientPlayerEntity player = requirePlayer();
            if (player.currentScreenHandler != player.playerScreenHandler) {
                player.closeHandledScreen();
            }
            return null;
        });

        long deadline = System.currentTimeMillis() + 1000L;
        while (System.currentTimeMillis() < deadline) {
            boolean closed = callOnClientThread(() -> {
                ClientPlayerEntity player = requirePlayer();
                return player.currentScreenHandler == player.playerScreenHandler;
            });
            if (closed) {
                return;
            }
            Thread.sleep(25L);
        }
    }

    private void craftRecipeOnceSync(ScreenHandler handler, BridgeRecipeCatalog.RecipeDefinition recipe) throws Exception {
        callOnClientThread(() -> {
            clearCraftingSlotsOnClientThread(handler);
            if (!canAcceptCraftOutputOnClientThread(recipe.outputItem(), recipe.outputCount())) {
                throw new IllegalStateException("Not enough inventory space to receive crafted output for " + recipe.outputItem() + ".");
            }

            List<BridgeRecipeCatalog.InventorySlot> inventorySlots = readRecipeInventorySlotsOnClientThread();
            BridgeRecipeCatalog.GridAssignment assignment = recipe.resolveGridAssignment(inventorySlots);
            if (assignment == null) {
                throw new IllegalStateException("Missing ingredients for " + recipe.outputItem() + ".");
            }
            if (!handler.getCursorStack().isEmpty()) {
                throw new IllegalStateException("Cannot craft while holding an item on the cursor.");
            }

            for (BridgeRecipeCatalog.AssignedPatternSlot slot : assignment.assignments()) {
                int sourceScreenSlot = findScreenSlotIdForInventorySlot(handler, slot.inventorySlot());
                int inputScreenSlot = getCraftingInputSlots(handler).get(slot.gridIndex()).id;
                placeSingleItemIntoCraftingSlotOnClientThread(handler, sourceScreenSlot, inputScreenSlot);
            }
            return null;
        });

        waitForCraftResultSync(handler, recipe.outputItem(), recipe.outputCount());

        callOnClientThread(() -> {
            Slot resultSlot = getCraftingOutputSlot(handler);
            requireInteractionManager().clickSlot(handler.syncId, resultSlot.id, 0, SlotActionType.QUICK_MOVE, requirePlayer());
            return null;
        });
    }

    private void clearCraftingSlotsOnClientThread(ScreenHandler handler) {
        ClientPlayerEntity player = requirePlayer();
        for (Slot inputSlot : getCraftingInputSlots(handler)) {
            if (inputSlot.hasStack()) {
                requireInteractionManager().clickSlot(handler.syncId, inputSlot.id, 0, SlotActionType.QUICK_MOVE, player);
            }
        }
        Slot resultSlot = getCraftingOutputSlot(handler);
        if (resultSlot.hasStack()) {
            requireInteractionManager().clickSlot(handler.syncId, resultSlot.id, 0, SlotActionType.QUICK_MOVE, player);
        }
    }

    private void placeSingleItemIntoCraftingSlotOnClientThread(ScreenHandler handler, int sourceScreenSlot, int targetScreenSlot) {
        ClientPlayerEntity player = requirePlayer();
        requireInteractionManager().clickSlot(handler.syncId, sourceScreenSlot, 0, SlotActionType.PICKUP, player);
        requireInteractionManager().clickSlot(handler.syncId, targetScreenSlot, 1, SlotActionType.PICKUP, player);
        if (!handler.getCursorStack().isEmpty()) {
            requireInteractionManager().clickSlot(handler.syncId, sourceScreenSlot, 0, SlotActionType.PICKUP, player);
        }
        if (!handler.getCursorStack().isEmpty()) {
            throw new IllegalStateException("Failed to restore the cursor stack after filling the crafting grid.");
        }
    }

    private void waitForCraftResultSync(ScreenHandler handler, String outputItem, int outputCount) throws Exception {
        long deadline = System.currentTimeMillis() + 1000L;
        while (System.currentTimeMillis() < deadline) {
            boolean ready = callOnClientThread(() -> {
                Slot resultSlot = getCraftingOutputSlot(handler);
                if (!resultSlot.hasStack()) {
                    return false;
                }
                ItemStack stack = resultSlot.getStack();
                String itemId = BridgeRecipeCatalog.normalize(Registries.ITEM.getId(stack.getItem()).toString());
                return Objects.equals(itemId, outputItem) && stack.getCount() >= outputCount;
            });
            if (ready) {
                return;
            }
            Thread.sleep(25L);
        }

        throw new IllegalStateException("Craft result for " + outputItem + " did not appear in time.");
    }

    private boolean canAcceptCraftOutputOnClientThread(String outputItem, int outputCount) {
        ClientPlayerEntity player = requirePlayer();
        int remaining = outputCount;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                return true;
            }

            String itemId = BridgeRecipeCatalog.normalize(Registries.ITEM.getId(stack.getItem()).toString());
            if (Objects.equals(itemId, outputItem)) {
                remaining -= Math.max(0, stack.getMaxCount() - stack.getCount());
                if (remaining <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private int findScreenSlotIdForInventorySlot(ScreenHandler handler, int inventorySlot) {
        ClientPlayerEntity player = requirePlayer();
        for (Slot slot : handler.slots) {
            if (slot.inventory == player.getInventory() && slot.getIndex() == inventorySlot) {
                return slot.id;
            }
        }
        throw new IllegalStateException("Could not resolve screen slot for inventory slot " + inventorySlot + ".");
    }

    private List<Slot> getCraftingInputSlots(ScreenHandler handler) {
        if (handler instanceof PlayerScreenHandler playerScreenHandler) {
            return playerScreenHandler.getInputSlots();
        }
        if (handler instanceof CraftingScreenHandler craftingScreenHandler) {
            return craftingScreenHandler.getInputSlots();
        }
        throw new IllegalStateException("Current screen is not a crafting screen.");
    }

    private Slot getCraftingOutputSlot(ScreenHandler handler) {
        if (handler instanceof PlayerScreenHandler playerScreenHandler) {
            return playerScreenHandler.getOutputSlot();
        }
        if (handler instanceof CraftingScreenHandler craftingScreenHandler) {
            return craftingScreenHandler.getOutputSlot();
        }
        throw new IllegalStateException("Current screen is not a crafting screen.");
    }

    private Map<String, Integer> readInventoryCountMapOnClientThread() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (InventoryItemSnapshot item : readInventorySnapshot()) {
            counts.merge(BridgeRecipeCatalog.normalize(item.id()), item.count(), Integer::sum);
        }
        return counts;
    }

    private TaskServices.HarvestTarget findNearestHarvestTargetBatched(String preferredType, int maxRadius, Set<BlockPos> ignoredTargets) throws Exception {
        SearchOrigin origin = callOnClientThread(this::readSearchOrigin);
        if (origin == null) {
            return null;
        }

        int verticalRadius = Math.min(12, maxRadius);
        BlockBoxBatchCursor cursor = new BlockBoxBatchCursor(origin.position(), -maxRadius, maxRadius, -verticalRadius, verticalRadius, -maxRadius, maxRadius);
        ScoredHarvestTarget bestTarget = null;

        while (cursor.hasRemaining()) {
            List<BlockPos> batch = cursor.nextBatch(SCAN_BATCH_SIZE);
            ScoredHarvestTarget batchTarget = callOnClientThread(() -> evaluateHarvestBatch(origin, preferredType, ignoredTargets, batch));
            if (batchTarget != null && (bestTarget == null || batchTarget.score() < bestTarget.score())) {
                bestTarget = batchTarget;
            }
        }

        return bestTarget == null ? null : bestTarget.target();
    }

    private ScoredHarvestTarget evaluateHarvestBatch(SearchOrigin origin, String preferredType, Set<BlockPos> ignoredTargets, List<BlockPos> batch) {
        ClientWorld world = requireWorld();
        BlockPos playerFeet = currentPlayerBlockPosOnClientThread();
        ScoredHarvestTarget bestTarget = null;

        for (BlockPos candidate : batch) {
            if (ignoredTargets.contains(candidate)) {
                continue;
            }

            BlockState state = world.getBlockState(candidate);
            Identifier id = Registries.BLOCK.getId(state.getBlock());
            if (!matchesHarvestType(id.toString(), preferredType)) {
                continue;
            }

            boolean reachableNow = isBlockReachableNow(candidate).reachable();
            DigCandidate digCandidate = selectBestDigCandidate(findDigCandidates(candidate));
            boolean canReposition = digCandidate != null;
            if (!reachableNow && !canReposition) {
                continue;
            }

            NavigationSafety.HazardAssessment collectionSafety = NavigationSafety.assessFooting(
                world,
                new BlockPos(candidate.getX(), Math.max(world.getBottomY(), candidate.getY() - 1), candidate.getZ())
            );
            if (collectionSafety.hazardous()) {
                continue;
            }

            if (!reachableNow && isDigCandidateHazardous(world, digCandidate)) {
                continue;
            }

            NavigationSafety.HazardAssessment pathSafety = NavigationSafety.assessPath(world, Vec3d.ofCenter(playerFeet), Vec3d.ofCenter(candidate));
            if (pathSafety.hazardous()) {
                continue;
            }

            double horizontalDistance = Math.pow(candidate.getX() - origin.position().getX(), 2) + Math.pow(candidate.getZ() - origin.position().getZ(), 2);
            double verticalPenalty = Math.max(0, candidate.getY() - origin.position().getY()) * 5.0D + Math.abs(candidate.getY() - origin.position().getY()) * 1.5D;
            double score = horizontalDistance + verticalPenalty;
            if (bestTarget == null || score < bestTarget.score()) {
                bestTarget = new ScoredHarvestTarget(new TaskServices.HarvestTarget(candidate.toImmutable(), id.toString()), score);
            }
        }

        return bestTarget;
    }

    private TaskServices.MineTarget findNearestMineTargetBatched(Set<String> blockTypes, int maxRadius, Set<BlockPos> ignoredTargets) throws Exception {
        SearchOrigin origin = callOnClientThread(this::readSearchOrigin);
        if (origin == null) {
            return null;
        }

        int verticalRadius = Math.min(16, maxRadius);
        BlockBoxBatchCursor cursor = new BlockBoxBatchCursor(origin.position(), -maxRadius, maxRadius, -verticalRadius, verticalRadius, -maxRadius, maxRadius);
        ScoredMineTarget bestTarget = null;

        while (cursor.hasRemaining()) {
            List<BlockPos> batch = cursor.nextBatch(SCAN_BATCH_SIZE);
            ScoredMineTarget batchTarget = callOnClientThread(() -> evaluateMineBatch(origin, blockTypes, ignoredTargets, batch));
            if (batchTarget != null && (bestTarget == null || batchTarget.score() < bestTarget.score())) {
                bestTarget = batchTarget;
            }
        }

        return bestTarget == null ? null : bestTarget.target();
    }

    private ScoredMineTarget evaluateMineBatch(SearchOrigin origin, Set<String> blockTypes, Set<BlockPos> ignoredTargets, List<BlockPos> batch) {
        ClientWorld world = requireWorld();
        BlockPos playerFeet = currentPlayerBlockPosOnClientThread();
        ScoredMineTarget bestTarget = null;

        for (BlockPos candidate : batch) {
            if (ignoredTargets.contains(candidate)) {
                continue;
            }

            BlockState state = world.getBlockState(candidate);
            Identifier id = Registries.BLOCK.getId(state.getBlock());
            if (!matchesAnyExactBlockType(id.toString(), blockTypes)) {
                continue;
            }

            boolean reachableNow = isBlockReachableNow(candidate).reachable();
            DigCandidate digCandidate = selectBestDigCandidate(findDigCandidates(candidate));
            boolean canReposition = digCandidate != null;
            boolean obstruction = !reachableNow && !canReposition && findClearableDigObstruction(candidate) != null;
            if (!reachableNow && !canReposition && !obstruction) {
                continue;
            }

            NavigationSafety.HazardAssessment collectionSafety = NavigationSafety.assessFooting(
                world,
                new BlockPos(candidate.getX(), Math.max(world.getBottomY(), candidate.getY() - 1), candidate.getZ())
            );
            if (collectionSafety.hazardous()) {
                continue;
            }

            if (!reachableNow && digCandidate != null && isDigCandidateHazardous(world, digCandidate)) {
                continue;
            }

            NavigationSafety.HazardAssessment pathSafety = NavigationSafety.assessPath(world, Vec3d.ofCenter(playerFeet), Vec3d.ofCenter(candidate));
            if (pathSafety.hazardous()) {
                continue;
            }

            double horizontalDistance = Math.pow(candidate.getX() - origin.position().getX(), 2) + Math.pow(candidate.getZ() - origin.position().getZ(), 2);
            double verticalPenalty = Math.max(0, candidate.getY() - origin.position().getY()) * 6.0D + Math.abs(candidate.getY() - origin.position().getY()) * 2.0D;
            double obstructionPenalty = obstruction ? 3.0D : 0.0D;
            double score = horizontalDistance + verticalPenalty + obstructionPenalty;
            if (bestTarget == null || score < bestTarget.score()) {
                bestTarget = new ScoredMineTarget(new TaskServices.MineTarget(candidate.toImmutable(), id.toString()), score);
            }
        }

        return bestTarget;
    }

    private SearchOrigin readSearchOrigin() {
        ClientPlayerEntity player = requirePlayer();
        return new SearchOrigin(BlockPos.ofFloored(player.getX(), player.getY(), player.getZ()));
    }

    private BlockPos currentPlayerBlockPosOnClientThread() {
        ClientPlayerEntity player = requirePlayer();
        return BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
    }

    private boolean isDigCandidateHazardous(ClientWorld world, DigCandidate candidate) {
        return candidate == null || NavigationSafety.assessFooting(world, candidate.feetPos()).hazardous();
    }

    private ClientPlayerEntity requirePlayer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            throw new IllegalStateException("Minecraft player is not ready yet.");
        }
        return client.player;
    }

    private ClientWorld requireWorld() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            throw new IllegalStateException("Minecraft world is not ready yet.");
        }
        return client.world;
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
            NavigationSafety.HazardAssessment movementSafety = callOnClientThread(
                () -> NavigationSafety.assessFooting(requireWorld(), candidate.feetPos())
            );
            if (movementSafety.hazardous()) {
                throw new IllegalStateException("Dig failed [hazard]: " + movementSafety.detail());
            }
            navigationController.moveToPositionSync(candidate.standPosition(), preciseDigApproachRange(candidate.range()), 15000L, true, true);
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
                    NavigationSafety.HazardAssessment movementSafety = callOnClientThread(
                        () -> NavigationSafety.assessFooting(requireWorld(), candidate.feetPos())
                    );
                    if (movementSafety.hazardous()) {
                        throw new IllegalStateException("Dig failed [hazard]: " + movementSafety.detail());
                    }
                    navigationController.moveToPositionSync(candidate.standPosition(), preciseDigApproachRange(candidate.range()), 8000L, true, true);
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

        double eyeHeight = player.getEyeHeight(player.getPose());
        double reachDistance = getInteractionReach(player);
        int maxVerticalDrop = maxDigCandidateVerticalDrop(reachDistance, eyeHeight);

        for (BlockPos feetPos : enumerateDigCandidateFeetPositions(targetPos, maxVerticalDrop)) {
            if (!isStandable(world, feetPos)) {
                continue;
            }

            Vec3d standPosition = new Vec3d(feetPos.getX() + 0.5D, feetPos.getY(), feetPos.getZ() + 0.5D);
            Vec3d eyePosition = standPosition.add(0.0D, eyeHeight, 0.0D);
            ReachabilityResult reachability = findReachability(world, eyePosition, targetPos, reachDistance);
            if (!reachability.reachable()) {
                continue;
            }

            int offsetX = feetPos.getX() - targetPos.getX();
            int offsetZ = feetPos.getZ() - targetPos.getZ();
            double distanceScore = standPosition.squaredDistanceTo(player.getX(), player.getY(), player.getZ());
            double verticalPenalty = Math.abs(standPosition.y - player.getY()) * 4.0D;
            double diagonalPenalty = (Math.abs(offsetX) + Math.abs(offsetZ) == 2) ? 0.35D : 0.0D;
            double score = distanceScore + verticalPenalty + (reachability.distanceSq() * 0.25D) + diagonalPenalty;
            candidates.add(new DigCandidate(feetPos, standPosition, score, Math.max(0.7D, Math.sqrt(reachability.distanceSq()) - 0.6D)));
        }

        return candidates;
    }

    static int maxDigCandidateVerticalDrop(double reachDistance, double eyeHeight) {
        return Math.max(2, (int) Math.ceil(reachDistance + eyeHeight - 0.5D));
    }

    static List<BlockPos> enumerateDigCandidateFeetPositions(BlockPos targetPos, int maxVerticalDrop) {
        int[][] offsets = new int[][] {
            { 0, 0 },
            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
            { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 }
        };
        List<BlockPos> candidates = new ArrayList<>();

        for (int dy = -Math.max(1, maxVerticalDrop); dy <= 1; dy++) {
            for (int[] offset : offsets) {
                candidates.add(new BlockPos(targetPos.getX() + offset[0], targetPos.getY() + dy, targetPos.getZ() + offset[1]));
            }
        }

        return candidates;
    }

    private DigCandidate selectBestDigCandidate(List<DigCandidate> candidates) {
        return candidates.stream()
            .min(Comparator.comparingDouble(DigCandidate::score))
            .orElse(null);
    }

    static double preciseDigApproachRange(double candidateRange) {
        return Math.max(NavigationController.effectiveArrivalRange(0.0D), Math.min(candidateRange, PRECISE_DIG_APPROACH_RANGE));
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
        if (state.isAir()) {
            return false;
        }

        String localId = localBlockId(Registries.BLOCK.getId(state.getBlock()).toString());
        return state.isIn(BlockTags.LEAVES) || isClearableFoliageId(localId);
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
        return state.isIn(BlockTags.LEAVES) || isClearableMiningObstructionId(localId);
    }

    static boolean isClearableFoliageId(String localId) {
        if (localId == null || localId.isBlank()) {
            return false;
        }

        return CLEARABLE_FOLIAGE_BLOCKS.contains(localId)
            || localId.endsWith("_sapling")
            || localId.endsWith("_propagule");
    }

    static boolean isClearableMiningObstructionId(String localId) {
        return CLEARABLE_SOFT_BLOCKS.contains(localId) || isClearableFoliageId(localId);
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
        return BridgePlacementSupport.localId(id);
    }

    private Direction directionByName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "down" -> Direction.DOWN;
            case "up" -> Direction.UP;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    private JsonObject recipeCandidateToJson(BridgeRecipeCatalog.RecipeCandidate candidate) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("result", candidate.recipe().outputItem());
        recipe.addProperty("resultCount", candidate.recipe().outputCount());
        recipe.addProperty("requiresCraftingTable", candidate.recipe().requiresCraftingTable());
        recipe.addProperty("canCraft", candidate.evaluation().canCraft());
        recipe.addProperty("missingTotal", candidate.evaluation().missingTotal());
        recipe.add("ingredients", ingredientSummariesToJson(candidate.recipe()));
        recipe.add("missing", missingRequirementsToJson(candidate.evaluation().missing()));
        return recipe;
    }

    private JsonArray ingredientSummariesToJson(BridgeRecipeCatalog.RecipeDefinition recipe) {
        JsonArray ingredients = new JsonArray();
        for (BridgeRecipeCatalog.IngredientSummary ingredient : recipe.summarizeIngredients()) {
            JsonObject jsonIngredient = new JsonObject();
            jsonIngredient.addProperty("name", ingredient.displayName());
            jsonIngredient.addProperty("count", ingredient.count());
            ingredients.add(jsonIngredient);
        }
        return ingredients;
    }

    private JsonArray missingRequirementsToJson(List<BridgeRecipeCatalog.MissingRequirement> missingRequirements) {
        JsonArray missing = new JsonArray();
        for (BridgeRecipeCatalog.MissingRequirement requirement : missingRequirements) {
            JsonObject jsonRequirement = new JsonObject();
            jsonRequirement.addProperty("name", requirement.name());
            jsonRequirement.addProperty("count", requirement.count());
            missing.add(jsonRequirement);
        }
        return missing;
    }

    private String formatIngredientSummaries(BridgeRecipeCatalog.RecipeDefinition recipe) {
        return recipe.summarizeIngredients().stream()
            .map(ingredient -> ingredient.displayName() + " x" + ingredient.count())
            .reduce((left, right) -> left + ", " + right)
            .orElse("(none)");
    }

    private String formatMissingRequirements(BridgeRecipeCatalog.RecipeCandidate candidate) {
        StringBuilder output = new StringBuilder("Cannot craft " + candidate.recipe().outputItem() + ". Missing:\n");
        for (BridgeRecipeCatalog.MissingRequirement requirement : candidate.evaluation().missing()) {
            output.append("- ").append(requirement.name()).append(" x").append(requirement.count()).append('\n');
        }
        return output.toString().trim();
    }

    private net.minecraft.client.network.ClientPlayerInteractionManager requireInteractionManager() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager == null) {
            throw new IllegalStateException("Minecraft interaction manager is not ready yet.");
        }
        return client.interactionManager;
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

    private record PositionSnapshot(int x, int y, int z) {
    }

    private record InventoryItemSnapshot(String id, String displayName, int count, int slot) {
    }

    private record BlockInfoSnapshot(String id, String displayName, int x, int y, int z) {
    }

    private record SessionStateSnapshot(boolean worldReady, boolean connected, String playerName, String dimension) {
    }

    private record SearchOrigin(BlockPos position) {
    }

    private record FindBlockMatch(String id, BlockPos position, double distanceSq) {
    }

    private record ScoredHarvestTarget(TaskServices.HarvestTarget target, double score) {
    }

    private record ScoredMineTarget(TaskServices.MineTarget target, double score) {
    }

    private record PlacementPlan(String itemId, List<PlacementCandidate> candidates, PlacementResult failure) {
    }

    private record PlacementCandidate(String faceLabel, BlockPos supportPos, Direction clickedFace, Vec3d aimPoint) {
    }

    private record PlacementAttempt(String face, boolean confirmed, String blockId) {
    }

    private record PlacementResult(boolean placed, String message, String reason, String face, String blockId) {
    }

    private record CraftResult(String outputItem, int craftedCount) {
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
            handleActionRequest(session, requestId, action, args);
        }

        @Override
        public void onHello(ProtocolSession session) {
            sendHello(session);
            sendCapabilities(session);
            sendSessionState(session);
            sendRegistrySnapshot(session);
        }

        @Override
        public void onProtocolError(ProtocolSession session, String requestId, String message) {
            session.send(BridgeProtocolMessages.error(requestId, message));
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
        private final ActionContext context;

        private TaskHooks(ActionContext context) {
            this.context = context;
        }

        @Override
        public void digBlockSync(BlockPos pos) throws Exception {
            MinecraftMcpBridgeMod.this.digBlockSync(pos);
        }

        @Override
        public void moveToPosition(Vec3d target, double range, long timeoutMs, boolean allowJump, boolean clearSoftObstructions) throws Exception {
            navigationController.moveToPositionSync(target, range, timeoutMs, allowJump, clearSoftObstructions);
        }

        @Override
        public int countMatchingLogsInInventory(String preferredType) throws Exception {
            return callOnClientThread(() -> countMatchingLogsInInventoryOnClientThread(preferredType));
        }

        @Override
        public int countMatchingInventoryItems(String targetItem) throws Exception {
            return callOnClientThread(() -> countMatchingInventoryItemsOnClientThread(targetItem));
        }

        @Override
        public boolean ensureItemSelectedInHotbar(String targetItem, int hotbarSlot) throws Exception {
            return callOnClientThread(() -> ensureInventoryItemSelectedInHotbarOnClientThread(targetItem, hotbarSlot));
        }

        @Override
        public TaskServices.HarvestTarget findNearestHarvestTarget(String preferredType, int maxRadius, Set<BlockPos> ignoredTargets) throws Exception {
            return findNearestHarvestTargetBatched(preferredType, maxRadius, ignoredTargets);
        }

        @Override
        public TaskServices.MineTarget findNearestMineTarget(Set<String> blockTypes, int maxRadius, Set<BlockPos> ignoredTargets) throws Exception {
            return findNearestMineTargetBatched(blockTypes, maxRadius, ignoredTargets);
        }

        @Override
        public BlockPos currentPlayerBlockPos() throws Exception {
            return callOnClientThread(MinecraftMcpBridgeMod.this::currentPlayerBlockPosOnClientThread);
        }

        @Override
        public void sendJobProgress(String channel, String message) {
            context.sendJobProgress(channel, message);
        }
    }
}
