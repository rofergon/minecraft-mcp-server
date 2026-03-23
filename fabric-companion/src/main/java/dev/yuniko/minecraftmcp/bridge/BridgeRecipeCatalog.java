package dev.yuniko.minecraftmcp.bridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class BridgeRecipeCatalog {
    private static final int GRID_CENTER = 1;
    private static final List<String> LOG_VARIANTS = List.of(
        "oak_log",
        "spruce_log",
        "birch_log",
        "jungle_log",
        "acacia_log",
        "dark_oak_log",
        "mangrove_log",
        "cherry_log"
    );

    private final List<RecipeDefinition> recipes;

    private BridgeRecipeCatalog(List<RecipeDefinition> recipes) {
        this.recipes = List.copyOf(recipes);
    }

    static BridgeRecipeCatalog createDefault() {
        List<RecipeDefinition> definitions = new ArrayList<>();
        for (String logVariant : LOG_VARIANTS) {
            definitions.add(new RecipeDefinition(
                logVariant.replace("_log", "_planks"),
                4,
                2,
                false,
                List.of(new PatternRequirement(0, IngredientMatcher.exact(logVariant, logVariant, logVariant)))
            ));
        }

        definitions.add(new RecipeDefinition(
            "sticks",
            4,
            2,
            false,
            List.of(
                new PatternRequirement(0, IngredientMatcher.family("planks", "planks", IngredientFamily.PLANKS)),
                new PatternRequirement(2, IngredientMatcher.family("planks", "planks", IngredientFamily.PLANKS))
            )
        ));

        definitions.add(new RecipeDefinition(
            "crafting_table",
            1,
            2,
            false,
            List.of(
                new PatternRequirement(0, IngredientMatcher.family("planks", "planks", IngredientFamily.PLANKS)),
                new PatternRequirement(1, IngredientMatcher.family("planks", "planks", IngredientFamily.PLANKS)),
                new PatternRequirement(2, IngredientMatcher.family("planks", "planks", IngredientFamily.PLANKS)),
                new PatternRequirement(3, IngredientMatcher.family("planks", "planks", IngredientFamily.PLANKS))
            )
        ));

        addToolSet(definitions, "wooden", IngredientMatcher.family("planks", "planks", IngredientFamily.PLANKS));
        addToolSet(definitions, "stone", IngredientMatcher.exact("cobblestone", "cobblestone", "cobblestone"));
        addToolSet(definitions, "copper", IngredientMatcher.exact("copper_ingot", "copper_ingot", "copper_ingot"));
        addToolSet(definitions, "iron", IngredientMatcher.exact("iron_ingot", "iron_ingot", "iron_ingot"));
        addToolSet(definitions, "diamond", IngredientMatcher.exact("diamond", "diamond", "diamond"));

        return new BridgeRecipeCatalog(definitions);
    }

    private static void addToolSet(List<RecipeDefinition> definitions, String tier, IngredientMatcher material) {
        definitions.add(createPickaxeRecipe(tier, material));
        definitions.add(createAxeRecipe(tier, material));
        definitions.add(createShovelRecipe(tier, material));
        definitions.add(createSwordRecipe(tier, material));
        definitions.add(createHoeRecipe(tier, material));
    }

    private static RecipeDefinition createPickaxeRecipe(String tier, IngredientMatcher material) {
        return new RecipeDefinition(
            tier + "_pickaxe",
            1,
            3,
            true,
            List.of(
                new PatternRequirement(0, material),
                new PatternRequirement(1, material),
                new PatternRequirement(2, material),
                new PatternRequirement(4, stickMatcher()),
                new PatternRequirement(7, stickMatcher())
            )
        );
    }

    private static RecipeDefinition createAxeRecipe(String tier, IngredientMatcher material) {
        return new RecipeDefinition(
            tier + "_axe",
            1,
            3,
            true,
            List.of(
                new PatternRequirement(0, material),
                new PatternRequirement(1, material),
                new PatternRequirement(3, material),
                new PatternRequirement(4, stickMatcher()),
                new PatternRequirement(7, stickMatcher())
            )
        );
    }

    private static RecipeDefinition createShovelRecipe(String tier, IngredientMatcher material) {
        return new RecipeDefinition(
            tier + "_shovel",
            1,
            3,
            true,
            List.of(
                new PatternRequirement(GRID_CENTER, material),
                new PatternRequirement(4, stickMatcher()),
                new PatternRequirement(7, stickMatcher())
            )
        );
    }

    private static RecipeDefinition createSwordRecipe(String tier, IngredientMatcher material) {
        return new RecipeDefinition(
            tier + "_sword",
            1,
            3,
            true,
            List.of(
                new PatternRequirement(GRID_CENTER, material),
                new PatternRequirement(4, material),
                new PatternRequirement(7, stickMatcher())
            )
        );
    }

    private static RecipeDefinition createHoeRecipe(String tier, IngredientMatcher material) {
        return new RecipeDefinition(
            tier + "_hoe",
            1,
            3,
            true,
            List.of(
                new PatternRequirement(0, material),
                new PatternRequirement(1, material),
                new PatternRequirement(4, stickMatcher()),
                new PatternRequirement(7, stickMatcher())
            )
        );
    }

    private static IngredientMatcher stickMatcher() {
        return IngredientMatcher.exact("sticks", "sticks", "sticks");
    }

    List<RecipeCandidate> listCraftableRecipes(Collection<InventoryItem> inventory, StationAccess stationAccess) {
        List<RecipeCandidate> candidates = new ArrayList<>();
        for (RecipeDefinition recipe : recipes) {
            RecipeEvaluation evaluation = evaluate(recipe, inventory, stationAccess);
            if (evaluation.canCraft()) {
                candidates.add(new RecipeCandidate(recipe, evaluation));
            }
        }

        candidates.sort(Comparator.comparing(candidate -> candidate.recipe().outputItem()));
        return List.copyOf(candidates);
    }

    List<RecipeCandidate> findRecipes(String query, Collection<InventoryItem> inventory, StationAccess stationAccess) {
        String normalized = normalize(query);
        if (normalized == null) {
            return List.of();
        }

        List<RecipeCandidate> exact = new ArrayList<>();
        List<RecipeCandidate> partial = new ArrayList<>();
        for (RecipeDefinition recipe : recipes) {
            String output = recipe.outputItem();
            RecipeCandidate candidate = new RecipeCandidate(recipe, evaluate(recipe, inventory, stationAccess));
            if (output.equals(normalized)) {
                exact.add(candidate);
                continue;
            }

            if (output.contains(normalized)) {
                partial.add(candidate);
            }
        }

        List<RecipeCandidate> selected = !exact.isEmpty() ? exact : partial;
        return selected.stream()
            .sorted(Comparator
                .comparing((RecipeCandidate candidate) -> !candidate.evaluation().canCraft())
                .thenComparing(candidate -> candidate.evaluation().missingTotal())
                .thenComparing(candidate -> candidate.recipe().outputItem()))
            .toList();
    }

    RecipeCandidate selectRecipe(String query, Collection<InventoryItem> inventory, StationAccess stationAccess) {
        List<RecipeCandidate> matches = findRecipes(query, inventory, stationAccess);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    RecipeEvaluation evaluate(RecipeDefinition recipe, Collection<InventoryItem> inventory, StationAccess stationAccess) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (InventoryItem item : inventory) {
            if (item == null || item.count() <= 0) {
                continue;
            }
            counts.merge(normalize(item.itemId()), item.count(), Integer::sum);
        }

        List<MissingRequirement> missing = new ArrayList<>();
        int missingTotal = 0;
        for (IngredientSummary summary : recipe.summarizeIngredients()) {
            int have = counts.entrySet().stream()
                .filter(entry -> summary.matcher().matches(entry.getKey()))
                .mapToInt(Map.Entry::getValue)
                .sum();
            if (have < summary.count()) {
                int deficit = summary.count() - have;
                missing.add(new MissingRequirement(summary.displayName(), deficit));
                missingTotal += deficit;
            }
        }

        boolean needsCraftingTable = recipe.requiresCraftingTable();
        boolean canUseCraftingTable = !needsCraftingTable || stationAccess.canUseCraftingTable();
        if (needsCraftingTable && !canUseCraftingTable) {
            missing.add(new MissingRequirement("crafting_table", 1));
            missingTotal += 1;
        }

        return new RecipeEvaluation(
            missing.isEmpty(),
            missingTotal,
            List.copyOf(missing),
            needsCraftingTable,
            stationAccess.craftingTableNearby(),
            stationAccess.craftingTableInInventory()
        );
    }

    record InventoryItem(String itemId, int count) {
        InventoryItem {
            itemId = normalize(itemId);
        }
    }

    record InventorySlot(int slot, String itemId, int count) {
        InventorySlot {
            itemId = normalize(itemId);
        }
    }

    record StationAccess(boolean craftingTableNearby, boolean craftingTableInInventory) {
        boolean canUseCraftingTable() {
            return craftingTableNearby || craftingTableInInventory;
        }
    }

    record MissingRequirement(String name, int count) {
    }

    record RecipeEvaluation(
        boolean canCraft,
        int missingTotal,
        List<MissingRequirement> missing,
        boolean requiresCraftingTable,
        boolean craftingTableNearby,
        boolean craftingTableInInventory
    ) {
    }

    record RecipeCandidate(RecipeDefinition recipe, RecipeEvaluation evaluation) {
    }

    record RecipeDefinition(String outputItem, int outputCount, int gridSize, boolean requiresCraftingTable, List<PatternRequirement> pattern) {
        RecipeDefinition {
            outputItem = normalize(outputItem);
            pattern = List.copyOf(pattern);
        }

        List<IngredientSummary> summarizeIngredients() {
            LinkedHashMap<String, IngredientSummary> grouped = new LinkedHashMap<>();
            for (PatternRequirement requirement : pattern) {
                IngredientMatcher matcher = requirement.matcher();
                grouped.compute(
                    matcher.key(),
                    (key, existing) -> existing == null
                        ? new IngredientSummary(matcher.key(), matcher.displayName(), matcher, 1)
                        : new IngredientSummary(existing.key(), existing.displayName(), existing.matcher(), existing.count() + 1)
                );
            }
            return List.copyOf(grouped.values());
        }

        GridAssignment resolveGridAssignment(Collection<InventorySlot> slots) {
            List<AssignedPatternSlot> assignments = new ArrayList<>();
            Map<Integer, Integer> remaining = new LinkedHashMap<>();
            for (InventorySlot slot : slots) {
                if (slot.count() > 0) {
                    remaining.put(slot.slot(), slot.count());
                }
            }

            for (PatternRequirement requirement : pattern) {
                InventorySlot chosen = null;
                for (InventorySlot slot : slots) {
                    Integer available = remaining.get(slot.slot());
                    if (available == null || available <= 0) {
                        continue;
                    }
                    if (requirement.matcher().matches(slot.itemId())) {
                        chosen = slot;
                        break;
                    }
                }

                if (chosen == null) {
                    return null;
                }

                remaining.put(chosen.slot(), remaining.get(chosen.slot()) - 1);
                assignments.add(new AssignedPatternSlot(requirement.gridIndex(), chosen.slot(), chosen.itemId()));
            }

            assignments.sort(Comparator.comparingInt(AssignedPatternSlot::gridIndex));
            return new GridAssignment(List.copyOf(assignments));
        }
    }

    record IngredientSummary(String key, String displayName, IngredientMatcher matcher, int count) {
    }

    record PatternRequirement(int gridIndex, IngredientMatcher matcher) {
    }

    record AssignedPatternSlot(int gridIndex, int inventorySlot, String itemId) {
    }

    record GridAssignment(List<AssignedPatternSlot> assignments) {
    }

    record IngredientMatcher(String key, String displayName, IngredientFamily family, String exactItemId) {
        static IngredientMatcher exact(String key, String displayName, String exactItemId) {
            return new IngredientMatcher(key, displayName, IngredientFamily.EXACT, normalize(exactItemId));
        }

        static IngredientMatcher family(String key, String displayName, IngredientFamily family) {
            return new IngredientMatcher(key, displayName, family, null);
        }

        boolean matches(String itemId) {
            String normalized = normalize(itemId);
            return switch (family) {
                case EXACT -> Objects.equals(normalized, exactItemId);
                case PLANKS -> normalized != null && normalized.endsWith("_planks");
            };
        }
    }

    enum IngredientFamily {
        EXACT,
        PLANKS
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }

        int separatorIndex = normalized.indexOf(':');
        String localId = separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
        if (localId.equals("sticks")) {
            return "stick";
        }
        return localId;
    }
}
