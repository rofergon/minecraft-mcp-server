import mineflayer from 'mineflayer';
import { ToolFactory } from '../tool-factory.js';

export function registerGameStateTools(factory: ToolFactory, getBot: () => mineflayer.Bot): void {
  factory.registerTool(
    "detect-gamemode",
    "Detect the gamemode on game",
    {},
    async () => {
      const bot = getBot();
      return factory.createStructuredResponse(`Bot gamemode: "${bot.game.gameMode}"`, {
        gameMode: bot.game.gameMode
      });
    }
  );
}
