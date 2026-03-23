import { z } from "zod";
import mineflayer from 'mineflayer';
import { ToolFactory } from '../tool-factory.js';

interface InventoryItem {
  name: string;
  count: number;
  slot: number;
}

export function registerInventoryTools(factory: ToolFactory, getBot: () => mineflayer.Bot): void {
  factory.registerTool(
    "list-inventory",
    "List all items in the bot's inventory",
    {},
    async () => {
      const bot = getBot();
      const items = bot.inventory.items();
      const itemList: InventoryItem[] = items.map((item) => ({
        name: item.name,
        count: item.count,
        slot: item.slot
      }));

      if (items.length === 0) {
        return factory.createStructuredResponse("Inventory is empty", {
          items: [],
          count: 0
        });
      }

      let inventoryText = `Found ${items.length} items in inventory:\n\n`;
      itemList.forEach(item => {
        inventoryText += `- ${item.name} (x${item.count}) in slot ${item.slot}\n`;
      });

      return factory.createStructuredResponse(inventoryText, {
        items: itemList,
        count: itemList.length
      });
    }
  );

  factory.registerTool(
    "find-item",
    "Find a specific item in the bot's inventory",
    {
      nameOrType: z.string().describe("Name or type of item to find")
    },
    async ({ nameOrType }) => {
      const bot = getBot();
      const items = bot.inventory.items();
      const item = items.find((item) =>
        item.name.includes(nameOrType.toLowerCase())
      );

      if (item) {
        return factory.createStructuredResponse(`Found ${item.count} ${item.name} in inventory (slot ${item.slot})`, {
          found: true,
          item: {
            name: item.name,
            count: item.count,
            slot: item.slot
          }
        });
      } else {
        return factory.createStructuredResponse(`Couldn't find any item matching '${nameOrType}' in inventory`, {
          found: false,
          query: nameOrType
        });
      }
    }
  );

  factory.registerTool(
    "equip-item",
    "Equip a specific item",
    {
      itemName: z.string().describe("Name of the item to equip"),
      destination: z.string().optional().describe("Where to equip the item (default: 'hand')")
    },
    async ({ itemName, destination = 'hand' }) => {
      const bot = getBot();
      const items = bot.inventory.items();
      const item = items.find((item) =>
        item.name.includes(itemName.toLowerCase())
      );

      if (!item) {
        return factory.createStructuredResponse(`Couldn't find any item matching '${itemName}' in inventory`, {
          equipped: false,
          query: itemName,
          destination
        });
      }

      await bot.equip(item, destination as mineflayer.EquipmentDestination);
      return factory.createStructuredResponse(`Equipped ${item.name} to ${destination}`, {
        equipped: true,
        item: {
          name: item.name,
          count: item.count,
          slot: item.slot
        },
        destination
      });
    }
  );
}
