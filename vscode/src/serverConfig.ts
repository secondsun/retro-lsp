/**
 * Configuration and argument utilities for RetroCA65 language server process launcher.
 */

export interface ServerExecOptions {
    command: string;
    args?: string[];
    options: { cwd: string; shell: boolean };
}

export interface RetroServerOptions {
    run: ServerExecOptions;
    debug: ServerExecOptions;
}

/**
 * Returns command-line arguments to pass to the retro-lsp server process.
 * When enableMcp is true, returns ['--mcp'].
 */
export function buildServerArgs(enableMcp: boolean): string[] {
    return enableMcp ? ['--mcp'] : [];
}

/**
 * Resolves whether MCP mode is enabled given a workspace configuration value
 * and an optional explicit runtime override.
 */
export function resolveEnableMcp(configValue?: boolean, overrideValue?: boolean): boolean {
    if (overrideValue !== undefined) {
        return overrideValue;
    }
    return Boolean(configValue);
}

/**
 * Builds the server configuration options for launching retro-lsp.
 */
export function buildServerConfig(
    launcher: string,
    extensionPath: string,
    enableMcp: boolean
): RetroServerOptions {
    const args = buildServerArgs(enableMcp);
    return {
        run: {
            command: launcher,
            args,
            options: { cwd: extensionPath, shell: true }
        },
        debug: {
            command: launcher,
            args,
            options: { cwd: extensionPath, shell: true }
        }
    };
}
