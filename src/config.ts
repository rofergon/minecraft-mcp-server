import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import type { BackendMode } from './backend.js';

export interface ServerConfig {
  backend: BackendMode;
  host: string;
  port: number;
  username: string;
  bridgeHost: string;
  bridgePort: number;
  bridgeToken?: string;
  autoReconnect: boolean;
}

export function parseConfig(): ServerConfig {
  return yargs(hideBin(process.argv))
    .option('backend', {
      type: 'string',
      choices: ['auto', 'mineflayer', 'client-bridge'],
      description: 'Minecraft backend to use',
      default: 'auto'
    })
    .option('host', {
      type: 'string',
      description: 'Minecraft server host',
      default: 'localhost'
    })
    .option('port', {
      type: 'number',
      description: 'Minecraft server port',
      default: 25565
    })
    .option('username', {
      type: 'string',
      description: 'Bot username',
      default: 'LLMBot'
    })
    .option('bridge-host', {
      type: 'string',
      description: 'Client bridge host',
      default: '127.0.0.1'
    })
    .option('bridge-port', {
      type: 'number',
      description: 'Client bridge port',
      default: 25570
    })
    .option('bridge-token', {
      type: 'string',
      description: 'Optional authentication token for the client bridge'
    })
    .option('auto-reconnect', {
      type: 'boolean',
      description: 'Reconnect automatically after a backend disconnect',
      default: true
    })
    .help()
    .alias('help', 'h')
    .parseSync() as ServerConfig;
}
