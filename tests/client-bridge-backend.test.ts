import test from 'ava';
import net from 'node:net';
import { ClientBridgeBackend } from '../src/backends/client-bridge-backend.js';
import type { ServerConfig } from '../src/config.js';

function listen(server: net.Server, port: number): Promise<void> {
  return new Promise((resolve, reject) => {
    server.listen(port, '127.0.0.1', () => resolve());
    server.once('error', reject);
  });
}

function close(server: net.Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((error) => {
      if (error) reject(error);
      else resolve();
    });
  });
}

test.serial('client bridge backend completes handshake and performs action', async (t) => {
  const server = net.createServer();
  const port = 25571;
  t.teardown(async () => {
    await close(server);
  });

  await listen(server, port);

  let sawHello = false;

  server.on('connection', (socket) => {
    socket.setEncoding('utf8');
    let buffer = '';

    socket.on('data', (chunk) => {
      buffer += chunk;
      let newlineIndex = buffer.indexOf('\n');

      while (newlineIndex !== -1) {
        const line = buffer.slice(0, newlineIndex).trim();
        buffer = buffer.slice(newlineIndex + 1);
        if (!line) {
          newlineIndex = buffer.indexOf('\n');
          continue;
        }

        const message = JSON.parse(line) as { type: string; requestId?: string };

        if (message.type === 'hello') {
          sawHello = true;
          socket.write(`${JSON.stringify({ type: 'hello', protocolVersion: '1.0.0', bridgeVersion: '0.1.0' })}\n`);
          socket.write(`${JSON.stringify({
            type: 'capabilities',
            protocolVersion: '1.0.0',
            bridgeVersion: '0.1.0',
            minecraftVersion: '1.21.11',
            loader: 'fabric',
            loaderVersion: '0.18.4',
            supportedActions: ['get-position'],
            worldReady: true
          })}\n`);
          socket.write(`${JSON.stringify({
            type: 'registry_snapshot',
            blocks: ['minecraft:stone'],
            items: ['minecraft:stick'],
            entities: ['minecraft:pig'],
            namespaces: ['minecraft']
          })}\n`);
        }

        if (message.type === 'action_request') {
          socket.write(`${JSON.stringify({
            type: 'action_result',
            requestId: message.requestId,
            message: 'Current position: (1, 2, 3)',
            data: { x: 1, y: 2, z: 3 }
          })}\n`);
        }

        newlineIndex = buffer.indexOf('\n');
      }
    });
  });

  const config: ServerConfig = {
    backend: 'client-bridge',
    host: 'localhost',
    port: 25565,
    username: 'LLMBot',
    bridgeHost: '127.0.0.1',
    bridgePort: port,
    bridgeToken: undefined,
    autoReconnect: false
  };

  const backend = new ClientBridgeBackend(config, {
    onLog: () => undefined,
    onChatMessage: () => undefined
  });
  t.teardown(() => backend.cleanup());

  const connection = await backend.checkConnectionAndReconnect();
  t.true(connection.connected);
  t.true(sawHello);

  const result = await backend.performAction('get-position');
  t.is(result.message, 'Current position: (1, 2, 3)');

  const capabilities = await backend.getRuntimeCapabilities();
  t.is(capabilities.loader, 'fabric');
  t.deepEqual(capabilities.registries, {
    blocks: 1,
    items: 1,
    entities: 1,
    namespaces: ['minecraft']
  });
});
