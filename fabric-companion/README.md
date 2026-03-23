# Fabric Companion Mod

Companion mod for the `client-bridge` backend in `minecraft-mcp-server`.

This mod runs inside a Fabric Minecraft client and exposes a local TCP bridge so the MCP server can control the player from the desktop tooling layer.

## Current behavior

- Opens a local TCP bridge on `127.0.0.1:25570` by default
- Performs a `hello` handshake with the MCP backend
- Sends session state, runtime capabilities, and registry snapshots
- Executes supported player actions directly in the Minecraft client
- Returns explicit bridge errors when an action is not implemented in the Fabric side

## Actions currently implemented in the Fabric mod

The mod currently supports these actions end-to-end:

- `get-position`
- `list-inventory`
- `find-item`
- `equip-item`
- `move-to-position`
- `dig-block`
- `harvest-wood`
- `mine-cobblestone`
- `get-block-info`
- `find-block`
- `detect-gamemode`
- `send-chat`

## Notes on automation

`harvest-wood` is the first higher-level autonomous job implemented in the Fabric bridge.

`mine-cobblestone` follows the same pattern for short-range automated mining.

Current behavior:

- Searches nearby logs, optionally filtered by type such as `oak_log`
- Moves to a valid working position before digging
- Avoids the previous infinite-jump behavior on elevated logs
- Clears obstructing leaves in front of the player when they block access to the trunk
- Sends periodic progress updates through `chat_event`
- Mines nearby `stone` or `cobblestone`, counts collected `cobblestone`, and can clear soft obstructions such as dirt or leaves when they block access

This keeps long harvesting runs inside the mod so the MCP agent does not need to spend tokens micromanaging every single block.

## Build

From the repository root:

```bash
cd fabric-companion
./gradlew build
```

On this workstation we have also built it with:

```powershell
& 'C:\Users\sebas\.codex\tmp\gradle-8.14\bin\gradle.bat' build
```

The built jar is generated at:

`fabric-companion/build/libs/minecraft-mcp-bridge-fabric-0.1.0.jar`

## Installation in the Minecraft instance

Target instance used in this setup:

`C:\Users\sebas\curseforge\minecraft\Instances\codexworld`

Install steps:

1. Build the mod jar.
2. Copy `fabric-companion/build/libs/minecraft-mcp-bridge-fabric-0.1.0.jar` into:
   `C:\Users\sebas\curseforge\minecraft\Instances\codexworld\mods\`
3. Start the `codexworld` Fabric instance.
4. Join a world or server with the client.
5. Start `minecraft-mcp-server` with `--backend client-bridge` or `--backend auto`.

PowerShell example:

```powershell
Copy-Item `
  'C:\Users\sebas\tools\minecraft-mcp-server\fabric-companion\build\libs\minecraft-mcp-bridge-fabric-0.1.0.jar' `
  'C:\Users\sebas\curseforge\minecraft\Instances\codexworld\mods\minecraft-mcp-bridge-fabric-0.1.0.jar' `
  -Force
```

Important:

- Restart the Minecraft instance after replacing the jar.
- If the MCP server was already running, restart it too when you want tool/schema changes to be picked up.

## Optional config

Create this file inside the Minecraft instance:

`C:\Users\sebas\curseforge\minecraft\Instances\codexworld\config\minecraft-mcp-bridge.json`

Example:

```json
{
  "host": "127.0.0.1",
  "port": 25570,
  "token": null
}
```

## MCP integration

The Fabric mod is only one side of the bridge. The MCP server backend is responsible for:

- opening the client-bridge connection
- exposing tools to Codex
- relaying `action_request` / `action_result`
- surfacing `chat_event` progress updates from autonomous jobs

Relevant backend files in this repository:

- [client-bridge-tools.ts](C:/Users/sebas/tools/minecraft-mcp-server/src/tools/client-bridge-tools.ts)
- [client-bridge-backend.ts](C:/Users/sebas/tools/minecraft-mcp-server/src/backends/client-bridge-backend.ts)

## Limitations

- Not every passthrough tool declared on the MCP side is implemented in the Fabric mod yet.
- The current navigation is intentionally short-range and task-focused; it is not global pathfinding like Baritone.
- `harvest-wood` is implemented; other higher-level jobs such as vein mining or excavation are still future work.
