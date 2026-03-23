import { McpServer, type RegisteredTool } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z, ZodError, ZodRawShape, ZodType } from "zod";
import type { ConnectionCheckResult } from './backend.js';

interface ConnectionLike {
  checkConnectionAndReconnect: () => Promise<ConnectionCheckResult>;
}

type McpResponse = {
  content: { type: "text"; text: string }[];
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
  [key: string]: unknown;
};

export class ToolFactory {
  constructor(
    private server: McpServer,
    private connection: ConnectionLike
  ) {}

  registerTool(
    name: string,
    description: string,
    schema: Record<string, unknown>,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    executor: (args: any) => Promise<McpResponse>
  ): RegisteredTool {
    return this.server.tool(name, description, schema, async (args: unknown): Promise<McpResponse> => {
      const connectionCheck = await this.connection.checkConnectionAndReconnect();

      if (!connectionCheck.connected) {
        return {
          content: [{ type: "text", text: connectionCheck.message! }],
          isError: true
        };
      }

      try {
        const parsedArgs = this.shouldValidateSchema(schema)
          ? this.parseArgs(schema as ZodRawShape, args)
          : args;
        return await executor(parsedArgs);
      } catch (error) {
        return this.createErrorResponse(error as Error);
      }
    });
  }

  createResponse(text: string): McpResponse {
    return {
      content: [{ type: "text", text }]
    };
  }

  createStructuredResponse(text: string, data: unknown): McpResponse {
    const structuredContent = this.normalizeStructuredContent(data);
    if (!structuredContent) {
      return this.createResponse(text);
    }

    return {
      content: [{ type: "text", text }],
      structuredContent
    };
  }

  createErrorResponse(error: Error | string): McpResponse {
    const errorMessage = error instanceof Error ? error.message : error;
    return {
      content: [{ type: "text", text: `Failed: ${errorMessage}` }],
      isError: true
    };
  }

  private shouldValidateSchema(schema: Record<string, unknown>): boolean {
    const values = Object.values(schema);
    if (values.length === 0) {
      return true;
    }

    return values.every((value) => value instanceof ZodType);
  }

  private parseArgs(schema: ZodRawShape, args: unknown): unknown {
    try {
      return z.object(schema).passthrough().parse(args ?? {});
    } catch (error) {
      if (error instanceof ZodError) {
        throw new Error(this.formatZodError(error));
      }
      throw error;
    }
  }

  private formatZodError(error: ZodError): string {
    const details = error.issues
      .map((issue) => {
        const path = issue.path.length > 0 ? `${issue.path.join('.')}: ` : '';
        return `${path}${issue.message}`;
      })
      .join('; ');

    return `Invalid tool arguments: ${details}`;
  }

  private normalizeStructuredContent(data: unknown): Record<string, unknown> | undefined {
    if (data === null || typeof data === 'undefined') {
      return undefined;
    }

    if (Array.isArray(data)) {
      return { data };
    }

    if (typeof data === 'object') {
      return data as Record<string, unknown>;
    }

    return { value: data };
  }
}
