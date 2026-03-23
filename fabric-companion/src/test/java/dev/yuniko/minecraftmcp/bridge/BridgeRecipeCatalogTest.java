package dev.yuniko.minecraftmcp.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeRecipeCatalogTest {
    private final BridgeRecipeCatalog catalog = BridgeRecipeCatalog.createDefault();

    @Test
    void partialPlanksQueryResolvesToCraftableVariant() {
        BridgeRecipeCatalog.RecipeCandidate recipe = catalog.selectRecipe(
            "planks",
            List.of(new BridgeRecipeCatalog.InventoryItem("oak_log", 1)),
            new BridgeRecipeCatalog.StationAccess(false, false)
        );

        assertNotNull(recipe);
        assertEquals("oak_planks", recipe.recipe().outputItem());
        assertTrue(recipe.evaluation().canCraft());
    }

    @Test
    void exactMatchWinsOverPartialMatch() {
        List<BridgeRecipeCatalog.RecipeCandidate> recipes = catalog.findRecipes(
            "sticks",
            List.of(new BridgeRecipeCatalog.InventoryItem("oak_planks", 2)),
            new BridgeRecipeCatalog.StationAccess(false, false)
        );

        assertEquals(1, recipes.size());
        assertEquals("stick", recipes.getFirst().recipe().outputItem());
    }

    @Test
    void stonePickaxeNeedsCraftingTableAccess() {
        BridgeRecipeCatalog.RecipeCandidate recipe = catalog.selectRecipe(
            "stone_pickaxe",
            List.of(
                new BridgeRecipeCatalog.InventoryItem("cobblestone", 3),
                new BridgeRecipeCatalog.InventoryItem("sticks", 2)
            ),
            new BridgeRecipeCatalog.StationAccess(false, false)
        );

        assertNotNull(recipe);
        assertFalse(recipe.evaluation().canCraft());
        assertEquals(List.of(new BridgeRecipeCatalog.MissingRequirement("crafting_table", 1)), recipe.evaluation().missing());
    }

    @Test
    void stonePickaxeCanCraftWhenCraftingTableIsAvailableFromInventory() {
        BridgeRecipeCatalog.RecipeCandidate recipe = catalog.selectRecipe(
            "stone_pickaxe",
            List.of(
                new BridgeRecipeCatalog.InventoryItem("cobblestone", 3),
                new BridgeRecipeCatalog.InventoryItem("sticks", 2),
                new BridgeRecipeCatalog.InventoryItem("crafting_table", 1)
            ),
            new BridgeRecipeCatalog.StationAccess(false, true)
        );

        assertNotNull(recipe);
        assertTrue(recipe.evaluation().canCraft());
        assertTrue(recipe.evaluation().requiresCraftingTable());
    }

    @Test
    void evaluationReportsMissingMaterials() {
        BridgeRecipeCatalog.RecipeCandidate recipe = catalog.selectRecipe(
            "wooden_pickaxe",
            List.of(new BridgeRecipeCatalog.InventoryItem("sticks", 1)),
            new BridgeRecipeCatalog.StationAccess(true, false)
        );

        assertNotNull(recipe);
        assertFalse(recipe.evaluation().canCraft());
        assertEquals(4, recipe.evaluation().missingTotal());
        assertEquals(
            List.of(
                new BridgeRecipeCatalog.MissingRequirement("planks", 3),
                new BridgeRecipeCatalog.MissingRequirement("sticks", 1)
            ),
            recipe.evaluation().missing()
        );
    }

    @Test
    void expandedToolWhitelistIncludesAllBasicTiers() {
        List<String> expectedRecipes = List.of(
            "wooden_pickaxe", "wooden_axe", "wooden_shovel", "wooden_sword", "wooden_hoe",
            "stone_pickaxe", "stone_axe", "stone_shovel", "stone_sword", "stone_hoe",
            "copper_pickaxe", "copper_axe", "copper_shovel", "copper_sword", "copper_hoe",
            "iron_pickaxe", "iron_axe", "iron_shovel", "iron_sword", "iron_hoe",
            "diamond_pickaxe", "diamond_axe", "diamond_shovel", "diamond_sword", "diamond_hoe"
        );

        for (String recipeName : expectedRecipes) {
            BridgeRecipeCatalog.RecipeCandidate recipe = catalog.selectRecipe(
                recipeName,
                List.of(),
                new BridgeRecipeCatalog.StationAccess(false, false)
            );

            assertNotNull(recipe, "Expected recipe to exist for " + recipeName);
            assertEquals(recipeName, recipe.recipe().outputItem());
            assertTrue(recipe.recipe().requiresCraftingTable());
        }
    }

    @Test
    void copperAxeRequiresCopperAndSticks() {
        BridgeRecipeCatalog.RecipeCandidate recipe = catalog.selectRecipe(
            "copper_axe",
            List.of(
                new BridgeRecipeCatalog.InventoryItem("copper_ingot", 2),
                new BridgeRecipeCatalog.InventoryItem("sticks", 2),
                new BridgeRecipeCatalog.InventoryItem("crafting_table", 1)
            ),
            new BridgeRecipeCatalog.StationAccess(false, true)
        );

        assertNotNull(recipe);
        assertFalse(recipe.evaluation().canCraft());
        assertEquals(
            List.of(new BridgeRecipeCatalog.MissingRequirement("copper_ingot", 1)),
            recipe.evaluation().missing()
        );
    }

    @Test
    void diamondSwordCanCraftWhenMaterialsAndCraftingTableAreAvailable() {
        BridgeRecipeCatalog.RecipeCandidate recipe = catalog.selectRecipe(
            "diamond_sword",
            List.of(
                new BridgeRecipeCatalog.InventoryItem("diamond", 2),
                new BridgeRecipeCatalog.InventoryItem("stick", 1),
                new BridgeRecipeCatalog.InventoryItem("crafting_table", 1)
            ),
            new BridgeRecipeCatalog.StationAccess(false, true)
        );

        assertNotNull(recipe);
        assertTrue(recipe.evaluation().canCraft());
    }

    @Test
    void sticksAliasNormalizesToStickItemId() {
        BridgeRecipeCatalog.RecipeCandidate recipe = catalog.selectRecipe(
            "sticks",
            List.of(new BridgeRecipeCatalog.InventoryItem("oak_planks", 2)),
            new BridgeRecipeCatalog.StationAccess(false, false)
        );

        assertNotNull(recipe);
        assertEquals("stick", recipe.recipe().outputItem());
    }

    @Test
    void unsupportedOutputsReturnNoRecipes() {
        assertTrue(catalog.findRecipes("netherite_pickaxe", List.of(), new BridgeRecipeCatalog.StationAccess(false, false)).isEmpty());
        assertNull(catalog.selectRecipe("netherite_pickaxe", List.of(), new BridgeRecipeCatalog.StationAccess(false, false)));
    }
}
