import type { MessageStore } from './message-store.js';
import type { ToolFactory } from './tool-factory.js';

export type BackendKind = 'mineflayer' | 'client-bridge';
export type BackendMode = BackendKind | 'auto';
export type ConnectionState = 'connected' | 'connecting' | 'disconnected';

export interface ConnectionCheckResult {
  connected: boolean;
  message?: string;
}

export interface RegistrySummary {
  blocks: number;
  items: number;
  entities: number;
  namespaces: string[];
}

export interface RuntimeCapabilities {
  mode: BackendMode;
  selectedBackend: BackendKind;
  protocolVersion: string;
  minecraftVersion?: string;
  loader?: string;
  loaderVersion?: string;
  worldReady: boolean;
  supportedActions: string[];
  registries?: RegistrySummary;
  notes?: string[];
}

export interface BackendCallbacks {
  onLog: (level: string, message: string) => void;
  onChatMessage: (username: string, message: string) => void;
}

export interface BridgeActionResult {
  message: string;
  data?: unknown;
  isError?: boolean;
}

export interface GameBackend {
  readonly kind: BackendKind;

  registerTools(factory: ToolFactory, messageStore: MessageStore): void;
  checkConnectionAndReconnect(): Promise<ConnectionCheckResult>;
  cleanup(): void;
  getRuntimeCapabilities(): Promise<RuntimeCapabilities>;
}
