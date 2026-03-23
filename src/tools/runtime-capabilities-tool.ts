import type { GameBackend } from '../backend.js';
import type { ToolFactory } from '../tool-factory.js';

export function registerRuntimeCapabilitiesTool(factory: ToolFactory, getBackend: () => GameBackend): void {
  factory.registerTool(
    'get-runtime-capabilities',
    'Get diagnostic information about the active Minecraft backend and runtime registries',
    {},
    async () => {
      const capabilities = await getBackend().getRuntimeCapabilities();
      return factory.createStructuredResponse(JSON.stringify(capabilities, null, 2), capabilities);
    }
  );
}
