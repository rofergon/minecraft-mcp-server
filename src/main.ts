#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { setupStdioFiltering } from './stdio-filter.js';
import { log } from './logger.js';
import { parseConfig } from './config.js';
import type { BackendCallbacks, GameBackend } from './backend.js';
import { ToolFactory } from './tool-factory.js';
import { MessageStore } from './message-store.js';
import { MineflayerBackend } from './backends/mineflayer-backend.js';
import { ClientBridgeBackend } from './backends/client-bridge-backend.js';
import { registerRuntimeCapabilitiesTool } from './tools/runtime-capabilities-tool.js';

setupStdioFiltering();

process.on('unhandledRejection', (reason) => {
  log('error', `Unhandled rejection: ${reason}`);
});

process.on('uncaughtException', (error) => {
  log('error', `Uncaught exception: ${error}`);
});

async function main() {
  const config = parseConfig();
  const messageStore = new MessageStore();

  const callbacks: BackendCallbacks = {
    onLog: log,
    onChatMessage: (username, message) => messageStore.addMessage(username, message)
  };

  const backend = await selectBackend(config, callbacks);

  const server = new McpServer({
    name: "minecraft-mcp-server",
    version: "2.0.4"
  });

  const factory = new ToolFactory(server, backend);
  backend.registerTools(factory, messageStore);
  registerRuntimeCapabilitiesTool(factory, () => backend);

  process.stdin.on('end', () => {
    backend.cleanup();
    log('info', 'MCP Client has disconnected. Shutting down...');
    process.exit(0);
  });

  const transport = new StdioServerTransport();
  await server.connect(transport);
}

async function selectBackend(config: ReturnType<typeof parseConfig>, callbacks: BackendCallbacks): Promise<GameBackend> {
  if (config.backend === 'mineflayer') {
    callbacks.onLog('info', 'Using Mineflayer backend');
    return new MineflayerBackend(config, callbacks);
  }

  if (config.backend === 'client-bridge') {
    callbacks.onLog('info', 'Using client-bridge backend');
    return new ClientBridgeBackend(config, callbacks);
  }

  const bridgeBackend = new ClientBridgeBackend(config, callbacks);
  if (await bridgeBackend.probe()) {
    callbacks.onLog('info', 'Auto-selected client-bridge backend');
    return bridgeBackend;
  }

  callbacks.onLog('info', 'Auto-selected mineflayer backend');
  return new MineflayerBackend(config, callbacks);
}

main().catch((error) => {
  log('error', `Fatal error in main(): ${error}`);
  process.exit(1);
});
