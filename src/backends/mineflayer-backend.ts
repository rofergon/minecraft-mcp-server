import { BotConnection } from '../bot-connection.js';
import type { BackendCallbacks, GameBackend, RuntimeCapabilities } from '../backend.js';
import type { ServerConfig } from '../config.js';
import type { MessageStore } from '../message-store.js';
import type { ToolFactory } from '../tool-factory.js';
import { registerBlockTools } from '../tools/block-tools.js';
import { registerChatTools } from '../tools/chat-tools.js';
import { registerCraftingTools } from '../tools/crafting-tools.js';
import { registerEntityTools } from '../tools/entity-tools.js';
import { registerFlightTools } from '../tools/flight-tools.js';
import { registerFurnaceTools } from '../tools/furnace-tools.js';
import { registerGameStateTools } from '../tools/gamestate-tools.js';
import { registerInventoryTools } from '../tools/inventory-tools.js';
import { registerPositionTools } from '../tools/position-tools.js';

const MINEFLAYER_SUPPORTED_ACTIONS = [
  'get-position',
  'move-to-position',
  'look-at',
  'jump',
  'move-in-direction',
  'fly-to',
  'list-inventory',
  'find-item',
  'equip-item',
  'place-block',
  'dig-block',
  'get-block-info',
  'find-block',
  'find-entity',
  'send-chat',
  'read-chat',
  'detect-gamemode',
  'list-recipes',
  'craft-item',
  'get-recipe',
  'can-craft',
  'smelt-item'
] as const;

export class MineflayerBackend implements GameBackend {
  readonly kind = 'mineflayer' as const;

  private readonly connection: BotConnection;

  constructor(config: ServerConfig, callbacks: BackendCallbacks) {
    this.connection = new BotConnection(
      {
        host: config.host,
        port: config.port,
        username: config.username
      },
      callbacks
    );
  }

  registerTools(factory: ToolFactory, messageStore: MessageStore): void {
    const getBot = () => this.connection.getBot()!;

    registerPositionTools(factory, getBot);
    registerInventoryTools(factory, getBot);
    registerBlockTools(factory, getBot);
    registerEntityTools(factory, getBot);
    registerChatTools(factory, getBot, messageStore);
    registerFlightTools(factory, getBot);
    registerGameStateTools(factory, getBot);
    registerCraftingTools(factory, getBot);
    registerFurnaceTools(factory, getBot);
  }

  checkConnectionAndReconnect() {
    return this.connection.checkConnectionAndReconnect();
  }

  cleanup(): void {
    this.connection.cleanup();
  }

  async getRuntimeCapabilities(): Promise<RuntimeCapabilities> {
    const state = this.connection.getState();
    const bot = this.connection.getBot();

    return {
      mode: 'mineflayer',
      selectedBackend: this.kind,
      protocolVersion: 'mineflayer/1',
      minecraftVersion: bot?.version,
      loader: 'vanilla-protocol',
      worldReady: state === 'connected',
      supportedActions: [...MINEFLAYER_SUPPORTED_ACTIONS],
      notes: [
        'Uses Mineflayer over the vanilla protocol.',
        'Registry-backed Fabric/Forge compatibility requires the client-bridge backend.'
      ]
    };
  }
}
