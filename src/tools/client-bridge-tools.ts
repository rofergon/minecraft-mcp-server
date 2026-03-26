import { z } from 'zod';
import type { RegisteredTool } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { MessageStore } from '../message-store.js';
import type { ToolFactory } from '../tool-factory.js';
import type { ClientBridgeBackend } from '../backends/client-bridge-backend.js';

export type ClientBridgeToolSpec = {
  description: string;
  name: string;
  schema: Record<string, unknown>;
};

export const CLIENT_BRIDGE_PASSTHROUGH_TOOLS: ClientBridgeToolSpec[] = [
  {
    name: 'get-position',
    description: 'Get the current position of the bot',
    schema: {}
  },
  {
    name: 'move-to-position',
    description: 'Move the bot to a specific position',
    schema: {
      x: z.coerce.number().describe('X coordinate'),
      y: z.coerce.number().describe('Y coordinate'),
      z: z.coerce.number().describe('Z coordinate'),
      range: z.coerce.number().finite().optional().describe('How close to get to the target (default: 1)'),
      timeoutMs: z.number().int().min(50).optional().describe('Timeout in milliseconds before cancelling (min: 50, default: no timeout)')
    }
  },
  {
    name: 'look-at',
    description: 'Make the bot look at a specific position',
    schema: {
      x: z.coerce.number().describe('X coordinate'),
      y: z.coerce.number().describe('Y coordinate'),
      z: z.coerce.number().describe('Z coordinate')
    }
  },
  {
    name: 'jump',
    description: 'Make the bot jump',
    schema: {}
  },
  {
    name: 'move-in-direction',
    description: 'Move the bot in a specific direction for a duration',
    schema: {
      direction: z.enum(['forward', 'back', 'left', 'right']).describe('Direction to move'),
      duration: z.number().optional().describe('Duration in milliseconds (default: 1000)')
    }
  },
  {
    name: 'fly-to',
    description: 'Make the bot fly to a specific position',
    schema: {
      x: z.coerce.number().describe('X coordinate'),
      y: z.coerce.number().describe('Y coordinate'),
      z: z.coerce.number().describe('Z coordinate')
    }
  },
  {
    name: 'list-inventory',
    description: "List all items in the bot's inventory",
    schema: {}
  },
  {
    name: 'find-item',
    description: "Find a specific item in the bot's inventory",
    schema: {
      nameOrType: z.string().describe('Name or type of item to find')
    }
  },
  {
    name: 'equip-item',
    description: 'Equip a specific item',
    schema: {
      itemName: z.string().describe('Name of the item to equip'),
      destination: z.string().optional().describe("Where to equip the item (default: 'hand')")
    }
  },
  {
    name: 'place-block',
    description: 'Place a block at the specified position',
    schema: {
      x: z.coerce.number().describe('X coordinate'),
      y: z.coerce.number().describe('Y coordinate'),
      z: z.coerce.number().describe('Z coordinate'),
      faceDirection: z.enum(['up', 'down', 'north', 'south', 'east', 'west']).optional().describe("Direction to place against (default: 'down')")
    }
  },
  {
    name: 'dig-block',
    description: 'Dig a block at the specified position',
    schema: {
      x: z.coerce.number().describe('X coordinate'),
      y: z.coerce.number().describe('Y coordinate'),
      z: z.coerce.number().describe('Z coordinate')
    }
  },
  {
    name: 'get-block-info',
    description: 'Get information about a block at the specified position',
    schema: {
      x: z.coerce.number().describe('X coordinate'),
      y: z.coerce.number().describe('Y coordinate'),
      z: z.coerce.number().describe('Z coordinate')
    }
  },
  {
    name: 'find-block',
    description: 'Find the nearest block of a specific type',
    schema: {
      blockType: z.string().describe('Type of block to find'),
      maxDistance: z.coerce.number().finite().optional().describe('Maximum search distance (default: 16)')
    }
  },
  {
    name: 'find-entity',
    description: 'Find the nearest entity of a specific type',
    schema: {
      type: z.string().optional().describe('Type of entity to find (empty for any entity)'),
      maxDistance: z.coerce.number().finite().optional().describe('Maximum search distance (default: 16)')
    }
  },
  {
    name: 'send-chat',
    description: 'Send a chat message in-game',
    schema: {
      message: z.string().describe('Message to send in chat')
    }
  },
  {
    name: 'detect-gamemode',
    description: 'Detect the gamemode on game',
    schema: {}
  },
  {
    name: 'list-recipes',
    description: 'List all available crafting recipes the bot can make with current inventory',
    schema: {
      outputItem: z.string().trim().min(1).optional().describe('Optional: filter recipes by output item name')
    }
  },
  {
    name: 'craft-item',
    description: 'Craft an item using a crafting recipe',
    schema: {
      outputItem: z.string().trim().min(1).describe('Name of the item to craft'),
      amount: z.number().int().min(1).optional().describe('Number of times to craft (default: 1)')
    }
  },
  {
    name: 'get-recipe',
    description: 'Get detailed information about a specific recipe',
    schema: {
      itemName: z.string().trim().min(1).describe('Name of the item to get recipe for')
    }
  },
  {
    name: 'can-craft',
    description: 'Check if the bot can craft a specific item with current inventory',
    schema: {
      itemName: z.string().trim().min(1).describe('Name of the item to check')
    }
  },
  {
    name: 'harvest-wood',
    description: 'Autonomously harvest wood until the requested amount is collected',
    schema: {
      amount: z.number().int().min(1).describe('Target number of log items to collect'),
      preferredType: z.string().trim().min(1).optional().describe('Preferred log type, for example oak_log'),
      maxRadius: z.coerce.number().int().min(4).max(96).optional().describe('Maximum horizontal search radius (default: 48)'),
      reportEvery: z.coerce.number().int().min(1).max(64).optional().describe('Send a progress update every N processed logs (default: 15)')
    }
  },
  {
    name: 'mine-cobblestone',
    description: 'Autonomously mine cobblestone until the requested amount is collected. Requires a wooden_pickaxe in inventory and will ask the agent to craft/select it in hotbar slot 1 first when missing.',
    schema: {
      amount: z.number().int().min(1).describe('Target number of cobblestone items to collect'),
      maxRadius: z.coerce.number().int().min(4).max(96).optional().describe('Maximum horizontal search radius (default: 32)'),
      reportEvery: z.coerce.number().int().min(1).max(64).optional().describe('Send a progress update every N processed blocks (default: 15)')
    }
  },
  {
    name: 'smelt-item',
    description: 'Smelt items using a furnace-like block',
    schema: {
      x: z.coerce.number().describe('X coordinate'),
      y: z.coerce.number().describe('Y coordinate'),
      z: z.coerce.number().describe('Z coordinate'),
      inputItem: z.string().trim().min(1).describe('Name of item to smelt'),
      inputCount: z.number().int().positive().optional().describe('Amount of input to smelt (default: 1)'),
      fuelItem: z.string().trim().min(1).describe('Name of fuel item'),
      fuelCount: z.number().int().positive().optional().describe('Amount of fuel to use (default: 1)'),
      takeOutput: z.boolean().optional().describe('Whether to take output when ready (default: true)'),
      timeoutMs: z.number().int().positive().optional().describe('Timeout waiting for output in ms (default: 60000)')
    }
  }
];

export function registerClientBridgePassthroughTool(
  factory: ToolFactory,
  backend: ClientBridgeBackend,
  tool: ClientBridgeToolSpec
): RegisteredTool {
  return factory.registerTool(tool.name, tool.description, tool.schema, async (args) => {
    const result = await backend.performAction(tool.name, (args as Record<string, unknown>) ?? {});
    if (result.isError) {
      return factory.createErrorResponse(result.message);
    }

    return factory.createStructuredResponse(result.message, result.data);
  });
}

export function registerClientBridgeReadChatTool(
  factory: ToolFactory,
  messageStore: MessageStore
): RegisteredTool {
  return factory.registerTool(
    'read-chat',
    'Get recent chat messages from players',
    {
      count: z.number().optional().describe('Number of recent messages to retrieve (default: 10, max: 100)')
    },
    async ({ count = 10 }) => {
      const maxCount = Math.min(count, messageStore.getMaxMessages());
      const messages = messageStore.getRecentMessages(maxCount);

      if (messages.length === 0) {
        return factory.createStructuredResponse('No chat messages found', { messages: [] });
      }

      let output = `Found ${messages.length} chat message(s):\n\n`;
      messages.forEach((msg, index) => {
        const timestamp = new Date(msg.timestamp).toISOString();
        output += `${index + 1}. ${timestamp} - ${msg.username}: ${msg.content}\n`;
      });

      return factory.createStructuredResponse(output, {
        messages,
        count: messages.length
      });
    }
  );
}
