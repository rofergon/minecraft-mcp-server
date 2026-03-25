import net from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';
import type { RegisteredTool } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { BackendCallbacks, BridgeActionResult, ConnectionCheckResult, ConnectionState, GameBackend, RegistrySummary, RuntimeCapabilities } from '../backend.js';
import type { ServerConfig } from '../config.js';
import type { MessageStore } from '../message-store.js';
import type { ToolFactory } from '../tool-factory.js';
import { CLIENT_BRIDGE_PASSTHROUGH_TOOLS, registerClientBridgePassthroughTool, registerClientBridgeReadChatTool } from '../tools/client-bridge-tools.js';

type BridgeMessage =
  | {
      type: 'hello';
      protocolVersion?: string;
      bridgeVersion?: string;
    }
  | {
      type: 'capabilities';
      protocolVersion?: string;
      bridgeVersion?: string;
      minecraftVersion?: string;
      loader?: string;
      loaderVersion?: string;
      supportedActions?: string[];
      notes?: string[];
      worldReady?: boolean;
    }
  | {
      type: 'session_state';
      worldReady?: boolean;
      connected?: boolean;
      dimension?: string;
      playerName?: string;
    }
  | {
      type: 'registry_snapshot';
      blocks?: string[];
      items?: string[];
      entities?: string[];
      namespaces?: string[];
    }
  | {
      type: 'chat_event';
      username?: string;
      message?: string;
    }
  | {
      type: 'action_result';
      requestId: string;
      message?: string;
      data?: unknown;
      isError?: boolean;
    }
  | {
      type: 'error';
      requestId?: string;
      message?: string;
    };

interface PendingRequest {
  reject: (error: Error) => void;
  resolve: (result: BridgeActionResult) => void;
  timeoutId: ReturnType<typeof setTimeout>;
}

interface BridgeHelloPayload {
  type: 'hello';
  clientName: string;
  clientVersion: string;
  protocolVersion: string;
  token?: string;
}

interface BridgeActionRequestPayload {
  type: 'action_request';
  requestId: string;
  action: string;
  args: Record<string, unknown>;
}

const CLIENT_BRIDGE_PROTOCOL_VERSION = '1.0.0';
const DEFAULT_CONNECT_TIMEOUT_MS = 4000;
const DEFAULT_ACTION_TIMEOUT_MS = 15000;
const ACTION_TIMEOUT_MS_BY_ACTION: Record<string, number> = {
  'harvest-wood': 10 * 60 * 1000,
  'mine-cobblestone': 10 * 60 * 1000
};

export class ClientBridgeBackend implements GameBackend {
  readonly kind = 'client-bridge' as const;

  private state: ConnectionState = 'disconnected';
  private socket: net.Socket | null = null;
  private connectPromise: Promise<void> | null = null;
  private readonly callbacks: BackendCallbacks;
  private readonly config: ServerConfig;
  private readonly pendingRequests = new Map<string, PendingRequest>();
  private nextRequestId = 0;
  private buffer = '';
  private protocolVersion = CLIENT_BRIDGE_PROTOCOL_VERSION;
  private bridgeVersion?: string;
  private minecraftVersion?: string;
  private loader?: string;
  private loaderVersion?: string;
  private supportedActions: string[] = [];
  private notes: string[] = [];
  private worldReady = false;
  private registrySummary?: RegistrySummary;
  private lastError?: string;
  private toolFactory?: ToolFactory;
  private messageStore?: MessageStore;
  private readChatTool?: RegisteredTool;
  private readonly registeredActionTools = new Map<string, RegisteredTool>();

  constructor(config: ServerConfig, callbacks: BackendCallbacks) {
    this.config = config;
    this.callbacks = callbacks;
  }

  registerTools(factory: ToolFactory, messageStore: MessageStore): void {
    this.toolFactory = factory;
    this.messageStore = messageStore;

    if (!this.readChatTool) {
      this.readChatTool = registerClientBridgeReadChatTool(factory, messageStore);
    }

    this.syncRegisteredTools();
    void this.probeAndSyncTools();
  }

  async probe(timeoutMs = 750): Promise<boolean> {
    try {
      await this.ensureConnected(timeoutMs);
      return true;
    } catch {
      return false;
    }
  }

  async checkConnectionAndReconnect(): Promise<ConnectionCheckResult> {
    try {
      await this.ensureConnected(DEFAULT_CONNECT_TIMEOUT_MS);
      return { connected: true };
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      return {
        connected: false,
        message:
          `Cannot connect to the Minecraft client bridge at ${this.config.bridgeHost}:${this.config.bridgePort}\n\n` +
          `Please ensure:\n` +
          `1. The Fabric companion mod is installed in the dedicated client instance\n` +
          `2. The client is open and joined to a world or server\n` +
          `3. The bridge is listening on ${this.config.bridgeHost}:${this.config.bridgePort}\n\n` +
          `Last error: ${detail}`
      };
    }
  }

  async performAction(action: string, args: Record<string, unknown> = {}): Promise<BridgeActionResult> {
    await this.ensureConnected(DEFAULT_CONNECT_TIMEOUT_MS);

    const requestId = `req-${Date.now()}-${++this.nextRequestId}`;
    const payload: BridgeActionRequestPayload = {
      type: 'action_request',
      requestId,
      action,
      args
    };

    return new Promise<BridgeActionResult>((resolve, reject) => {
      const actionTimeoutMs = ACTION_TIMEOUT_MS_BY_ACTION[action] ?? DEFAULT_ACTION_TIMEOUT_MS;
      const timeoutId = setTimeout(() => {
        this.pendingRequests.delete(requestId);
        reject(new Error(`Timed out waiting for bridge action '${action}'`));
      }, actionTimeoutMs);

      this.pendingRequests.set(requestId, {
        resolve,
        reject,
        timeoutId
      });

      try {
        this.sendMessage(payload);
      } catch (error) {
        clearTimeout(timeoutId);
        this.pendingRequests.delete(requestId);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }

  cleanup(): void {
    this.rejectAllPending(new Error('Client bridge shutting down'));
    this.state = 'disconnected';
    this.buffer = '';
    this.connectPromise = null;

    if (this.socket) {
      this.socket.removeAllListeners();
      this.socket.destroy();
      this.socket = null;
    }
  }

  async getRuntimeCapabilities(): Promise<RuntimeCapabilities> {
    try {
      await this.ensureConnected(DEFAULT_CONNECT_TIMEOUT_MS);
    } catch {
      // Return the best-known cached state when the bridge is unavailable.
    }

    return {
      mode: 'client-bridge',
      selectedBackend: this.kind,
      protocolVersion: this.protocolVersion,
      minecraftVersion: this.minecraftVersion,
      loader: this.loader,
      loaderVersion: this.loaderVersion,
      worldReady: this.worldReady,
      supportedActions: [...this.supportedActions],
      registries: this.registrySummary,
      notes: this.notes.length > 0 ? [...this.notes] : undefined
    };
  }

  private async ensureConnected(timeoutMs: number): Promise<void> {
    if (this.state === 'connected' && this.socket && !this.socket.destroyed) {
      return;
    }

    if (this.connectPromise) {
      return this.connectPromise;
    }

    this.connectPromise = this.connect(timeoutMs)
      .finally(() => {
        this.connectPromise = null;
      });

    return this.connectPromise;
  }

  private async connect(timeoutMs: number): Promise<void> {
    this.state = 'connecting';

    await new Promise<void>((resolve, reject) => {
      const socket = net.createConnection({
        host: this.config.bridgeHost,
        port: this.config.bridgePort
      });

      let settled = false;
      const timeoutId = setTimeout(() => {
        if (settled) return;
        settled = true;
        socket.destroy(new Error(`Connection timed out after ${timeoutMs}ms`));
        reject(new Error(`Connection timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      const markReady = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timeoutId);
        this.state = 'connected';
        resolve();
      };

      socket.setEncoding('utf8');

      socket.on('connect', () => {
        this.socket = socket;
        this.lastError = undefined;
        this.sendMessage({
          type: 'hello',
          clientName: 'minecraft-mcp-server',
          clientVersion: '2.0.4',
          protocolVersion: CLIENT_BRIDGE_PROTOCOL_VERSION,
          token: this.config.bridgeToken
        } satisfies BridgeHelloPayload);
      });

      socket.on('data', (chunk) => {
        this.buffer += chunk;

        let newlineIndex = this.buffer.indexOf('\n');
        while (newlineIndex !== -1) {
          const line = this.buffer.slice(0, newlineIndex).trim();
          this.buffer = this.buffer.slice(newlineIndex + 1);
          if (line) {
            this.handleIncomingLine(line, markReady);
          }
          newlineIndex = this.buffer.indexOf('\n');
        }
      });

      socket.on('error', (error) => {
        this.lastError = error.message;
        this.callbacks.onLog('error', `Client bridge socket error: ${error.message}`);
        if (!settled) {
          settled = true;
          clearTimeout(timeoutId);
          reject(error);
        }
      });

      socket.on('close', () => {
        this.handleDisconnect(this.lastError ?? 'Socket closed');
      });
    });
  }

  private handleIncomingLine(line: string, onReady: () => void): void {
    try {
      const message = JSON.parse(line) as BridgeMessage;
      this.handleMessage(message);

      if (message.type === 'hello' || message.type === 'capabilities' || message.type === 'session_state') {
        onReady();
      }
    } catch (error) {
      this.callbacks.onLog('warn', `Failed to parse bridge message: ${line} (${error})`);
    }
  }

  private handleMessage(message: BridgeMessage): void {
    switch (message.type) {
      case 'hello':
        if (message.protocolVersion) this.protocolVersion = message.protocolVersion;
        if (message.bridgeVersion) this.bridgeVersion = message.bridgeVersion;
        break;
      case 'capabilities':
        this.protocolVersion = message.protocolVersion ?? this.protocolVersion;
        this.bridgeVersion = message.bridgeVersion ?? this.bridgeVersion;
        this.minecraftVersion = message.minecraftVersion ?? this.minecraftVersion;
        this.loader = message.loader ?? this.loader;
        this.loaderVersion = message.loaderVersion ?? this.loaderVersion;
        this.supportedActions = message.supportedActions ?? this.supportedActions;
        this.notes = message.notes ?? this.notes;
        this.worldReady = message.worldReady ?? this.worldReady;
        this.syncRegisteredTools();
        break;
      case 'session_state':
        this.worldReady = message.worldReady ?? this.worldReady;
        break;
      case 'registry_snapshot':
        this.registrySummary = {
          blocks: message.blocks?.length ?? 0,
          items: message.items?.length ?? 0,
          entities: message.entities?.length ?? 0,
          namespaces: message.namespaces ?? []
        };
        break;
      case 'chat_event':
        this.callbacks.onChatMessage(message.username ?? 'system', message.message ?? '');
        break;
      case 'action_result': {
        const pending = this.pendingRequests.get(message.requestId);
        if (!pending) return;
        clearTimeout(pending.timeoutId);
        this.pendingRequests.delete(message.requestId);
        pending.resolve({
          message: message.message ?? 'Action completed successfully.',
          data: message.data,
          isError: message.isError
        });
        break;
      }
      case 'error': {
        if (message.requestId) {
          const pending = this.pendingRequests.get(message.requestId);
          if (!pending) return;
          clearTimeout(pending.timeoutId);
          this.pendingRequests.delete(message.requestId);
          pending.reject(new Error(message.message ?? 'Client bridge returned an error'));
          return;
        }

        this.lastError = message.message ?? 'Unknown client bridge error';
        this.callbacks.onLog('error', this.lastError);
        break;
      }
    }
  }

  private handleDisconnect(reason: string): void {
    this.state = 'disconnected';
    this.lastError = reason;

    if (this.socket) {
      this.socket.removeAllListeners();
      this.socket = null;
    }

    this.rejectAllPending(new Error(`Client bridge disconnected: ${reason}`));

    if (this.config.autoReconnect) {
      void delay(250).then(() => {
        if (this.state === 'disconnected') {
          this.callbacks.onLog('info', 'Client bridge disconnected; it will reconnect on the next tool call.');
        }
      });
    }
  }

  private rejectAllPending(error: Error): void {
    for (const pending of this.pendingRequests.values()) {
      clearTimeout(pending.timeoutId);
      pending.reject(error);
    }
    this.pendingRequests.clear();
  }

  private sendMessage(payload: BridgeHelloPayload | BridgeActionRequestPayload): void {
    if (!this.socket || this.socket.destroyed) {
      throw new Error('Client bridge socket is not connected');
    }

    this.socket.write(`${JSON.stringify(payload)}\n`);
  }

  private async probeAndSyncTools(): Promise<void> {
    try {
      await this.probe();
    } finally {
      this.syncRegisteredTools();
    }
  }

  private syncRegisteredTools(): void {
    if (!this.toolFactory || !this.messageStore) {
      return;
    }

    const supported = new Set(this.supportedActions);

    for (const [name, registeredTool] of this.registeredActionTools) {
      if (supported.has(name)) {
        continue;
      }

      registeredTool.remove();
      this.registeredActionTools.delete(name);
    }

    for (const tool of CLIENT_BRIDGE_PASSTHROUGH_TOOLS) {
      if (!supported.has(tool.name) || this.registeredActionTools.has(tool.name)) {
        continue;
      }

      const registeredTool = registerClientBridgePassthroughTool(this.toolFactory, this, tool);
      this.registeredActionTools.set(tool.name, registeredTool);
    }
  }
}
