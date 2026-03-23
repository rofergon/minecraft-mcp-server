import test from 'ava';
import sinon from 'sinon';
import { registerBlockTools } from '../src/tools/block-tools.js';
import { ToolFactory } from '../src/tool-factory.js';
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { BotConnection } from '../src/bot-connection.js';
import type mineflayer from 'mineflayer';
import { Vec3 } from 'vec3';

test('registerBlockTools registers place-block tool', (t) => {
  const mockServer = {
    tool: sinon.stub()
  } as unknown as McpServer;
  const mockConnection = {
    checkConnectionAndReconnect: sinon.stub().resolves({ connected: true })
  } as unknown as BotConnection;
  const factory = new ToolFactory(mockServer, mockConnection);
  const mockBot = {} as Partial<mineflayer.Bot>;
  const getBot = () => mockBot as mineflayer.Bot;

  registerBlockTools(factory, getBot);

  const toolCalls = (mockServer.tool as sinon.SinonStub).getCalls();
  const placeBlockCall = toolCalls.find(call => call.args[0] === 'place-block');

  t.truthy(placeBlockCall);
  t.is(placeBlockCall!.args[1], 'Place a block at the specified position');
});

test('registerBlockTools registers dig-block tool', (t) => {
  const mockServer = {
    tool: sinon.stub()
  } as unknown as McpServer;
  const mockConnection = {
    checkConnectionAndReconnect: sinon.stub().resolves({ connected: true })
  } as unknown as BotConnection;
  const factory = new ToolFactory(mockServer, mockConnection);
  const mockBot = {} as Partial<mineflayer.Bot>;
  const getBot = () => mockBot as mineflayer.Bot;

  registerBlockTools(factory, getBot);

  const toolCalls = (mockServer.tool as sinon.SinonStub).getCalls();
  const digBlockCall = toolCalls.find(call => call.args[0] === 'dig-block');

  t.truthy(digBlockCall);
  t.is(digBlockCall!.args[1], 'Dig a block at the specified position');
});

test('registerBlockTools registers get-block-info tool', (t) => {
  const mockServer = {
    tool: sinon.stub()
  } as unknown as McpServer;
  const mockConnection = {
    checkConnectionAndReconnect: sinon.stub().resolves({ connected: true })
  } as unknown as BotConnection;
  const factory = new ToolFactory(mockServer, mockConnection);
  const mockBot = {} as Partial<mineflayer.Bot>;
  const getBot = () => mockBot as mineflayer.Bot;

  registerBlockTools(factory, getBot);

  const toolCalls = (mockServer.tool as sinon.SinonStub).getCalls();
  const getBlockInfoCall = toolCalls.find(call => call.args[0] === 'get-block-info');

  t.truthy(getBlockInfoCall);
  t.is(getBlockInfoCall!.args[1], 'Get information about a block at the specified position');
});

test('registerBlockTools registers find-block tool', (t) => {
  const mockServer = {
    tool: sinon.stub()
  } as unknown as McpServer;
  const mockConnection = {
    checkConnectionAndReconnect: sinon.stub().resolves({ connected: true })
  } as unknown as BotConnection;
  const factory = new ToolFactory(mockServer, mockConnection);
  const mockBot = {} as Partial<mineflayer.Bot>;
  const getBot = () => mockBot as mineflayer.Bot;

  registerBlockTools(factory, getBot);

  const toolCalls = (mockServer.tool as sinon.SinonStub).getCalls();
  const findBlockCall = toolCalls.find(call => call.args[0] === 'find-block');

  t.truthy(findBlockCall);
  t.is(findBlockCall!.args[1], 'Find the nearest block of a specific type');
});

test('get-block-info returns block information', async (t) => {
  const mockServer = {
    tool: sinon.stub()
  } as unknown as McpServer;
  const mockConnection = {
    checkConnectionAndReconnect: sinon.stub().resolves({ connected: true })
  } as unknown as BotConnection;
  const factory = new ToolFactory(mockServer, mockConnection);
  
  const mockBlock = {
    name: 'stone',
    type: 1,
    position: new Vec3(10, 64, 20)
  };
  const mockBot = {
    blockAt: sinon.stub().returns(mockBlock)
  } as Partial<mineflayer.Bot>;
  const getBot = () => mockBot as mineflayer.Bot;

  registerBlockTools(factory, getBot);

  const toolCalls = (mockServer.tool as sinon.SinonStub).getCalls();
  const getBlockInfoCall = toolCalls.find(call => call.args[0] === 'get-block-info');
  const executor = getBlockInfoCall!.args[3];

  const result = await executor({ x: 10, y: 64, z: 20 });

  t.true(result.content[0].text.includes('stone'));
  t.true(result.content[0].text.includes('10'));
  t.true(result.content[0].text.includes('64'));
  t.true(result.content[0].text.includes('20'));
  t.is(result.structuredContent.found, true);
  t.is(result.structuredContent.block.name, 'stone');
});

test('get-block-info handles missing block', async (t) => {
  const mockServer = {
    tool: sinon.stub()
  } as unknown as McpServer;
  const mockConnection = {
    checkConnectionAndReconnect: sinon.stub().resolves({ connected: true })
  } as unknown as BotConnection;
  const factory = new ToolFactory(mockServer, mockConnection);
  
  const mockBot = {
    blockAt: sinon.stub().returns(null)
  } as Partial<mineflayer.Bot>;
  const getBot = () => mockBot as mineflayer.Bot;

  registerBlockTools(factory, getBot);

  const toolCalls = (mockServer.tool as sinon.SinonStub).getCalls();
  const getBlockInfoCall = toolCalls.find(call => call.args[0] === 'get-block-info');
  const executor = getBlockInfoCall!.args[3];

  const result = await executor({ x: 10, y: 64, z: 20 });

  t.true(result.content[0].text.includes('No block information found'));
  t.is(result.structuredContent.found, false);
});

test('dig-block handles air blocks', async (t) => {
  const mockServer = {
    tool: sinon.stub()
  } as unknown as McpServer;
  const mockConnection = {
    checkConnectionAndReconnect: sinon.stub().resolves({ connected: true })
  } as unknown as BotConnection;
  const factory = new ToolFactory(mockServer, mockConnection);
  
  const mockBlock = {
    name: 'air'
  };
  const mockBot = {
    blockAt: sinon.stub().returns(mockBlock)
  } as Partial<mineflayer.Bot>;
  const getBot = () => mockBot as mineflayer.Bot;

  registerBlockTools(factory, getBot);

  const toolCalls = (mockServer.tool as sinon.SinonStub).getCalls();
  const digBlockCall = toolCalls.find(call => call.args[0] === 'dig-block');
  const executor = digBlockCall!.args[3];

  const result = await executor({ x: 10, y: 64, z: 20 });

  t.true(result.content[0].text.includes('No block found'));
  t.is(result.structuredContent.found, false);
});

test('dig-block uses the bot height as the approach level for tall blocks', async (t) => {
  const mockServer = {
    tool: sinon.stub()
  } as unknown as McpServer;
  const mockConnection = {
    checkConnectionAndReconnect: sinon.stub().resolves({ connected: true })
  } as unknown as BotConnection;
  const factory = new ToolFactory(mockServer, mockConnection);

  const mockBlock = {
    name: 'oak_log',
    position: new Vec3(10, 68, 20)
  };
  const pathfinderGoto = sinon.stub().resolves();
  const mockBot = {
    entity: { position: new Vec3(10, 64, 20) },
    blockAt: sinon.stub().returns(mockBlock),
    canDigBlock: sinon.stub().returns(false),
    canSeeBlock: sinon.stub().returns(false),
    pathfinder: { goto: pathfinderGoto }
  } as unknown as mineflayer.Bot;
  const getBot = () => mockBot;

  registerBlockTools(factory, getBot);

  const toolCalls = (mockServer.tool as sinon.SinonStub).getCalls();
  const digBlockCall = toolCalls.find(call => call.args[0] === 'dig-block');
  const executor = digBlockCall!.args[3];

  await executor({ x: 10, y: 68, z: 20 });

  t.true(pathfinderGoto.calledOnce);
  const goal = pathfinderGoto.firstCall.args[0] as { x: number; y: number; z: number; rangeSq: number };
  t.is(goal.x, 10);
  t.is(goal.y, 64);
  t.is(goal.z, 20);
  t.is(goal.rangeSq, 1);
});

test('find-block returns not found when block not found', async (t) => {
  const mockServer = {
    tool: sinon.stub()
  } as unknown as McpServer;
  const mockConnection = {
    checkConnectionAndReconnect: sinon.stub().resolves({ connected: true })
  } as unknown as BotConnection;
  const factory = new ToolFactory(mockServer, mockConnection);
  
  const mockBot = {
    version: '1.21',
    findBlock: sinon.stub().returns(null)
  } as Partial<mineflayer.Bot>;
  const getBot = () => mockBot as mineflayer.Bot;

  registerBlockTools(factory, getBot);

  const toolCalls = (mockServer.tool as sinon.SinonStub).getCalls();
  const findBlockCall = toolCalls.find(call => call.args[0] === 'find-block');
  const executor = findBlockCall!.args[3];

  const result = await executor({ blockType: 'diamond_ore', maxDistance: 16 });

  t.true(result.content[0].text.includes('No diamond_ore found'));
});
