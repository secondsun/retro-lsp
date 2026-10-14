/* --------------------------------------------------------------------------------------------
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 * ------------------------------------------------------------------------------------------ */

import * as fs from 'fs';
import * as path from 'path';
import { workspace, ExtensionContext, window, commands } from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
    RevealOutputChannelOn
} from 'vscode-languageclient/node';
import { buildServerArgs, resolveEnableMcp } from './serverConfig.js';

export { buildServerArgs, resolveEnableMcp } from './serverConfig.js';

let client: LanguageClient | undefined;

function getLauncherPath(context: ExtensionContext): string | undefined {
    const config = workspace.getConfiguration('retroca65');
    const configuredPath = config.get<string>('serverPath');
    if (configuredPath && configuredPath.trim().length > 0) {
        if (fs.existsSync(configuredPath)) {
            return configuredPath;
        }
        console.warn(`Configured retroca65.serverPath does not exist: ${configuredPath}`);
    }

    const launcherBinary = process.platform === 'win32' ? 'launcher.bat' : 'launcher';
    const platformFolder = process.platform === 'win32'
        ? 'windows'
        : process.platform === 'darwin'
            ? 'mac'
            : 'linux';

    // 1. Platform-specific bundled server in extension root: <extension>/server/bin/<launcher>
    const bundledServer = path.join(context.extensionPath, 'server', 'bin', launcherBinary);
    if (fs.existsSync(bundledServer)) {
        return bundledServer;
    }

    // 2. Bundled server with platform subdir: <extension>/server/<platform>/bin/<launcher>
    const bundledPlatformServer = path.join(context.extensionPath, 'server', platformFolder, 'bin', launcherBinary);
    if (fs.existsSync(bundledPlatformServer)) {
        return bundledPlatformServer;
    }

    // 3. Bundled server in dist subdir: <extension>/dist/<platform>/bin/<launcher>
    const bundledDistServer = path.join(context.extensionPath, 'dist', platformFolder, 'bin', launcherBinary);
    if (fs.existsSync(bundledDistServer)) {
        return bundledDistServer;
    }

    // 4. Project root / local development fallback: <extension>/../dist/<platform>/bin/<launcher>
    const projectRootCandidate = path.resolve(context.extensionPath, '..', 'dist', platformFolder, 'bin', launcherBinary);
    if (fs.existsSync(projectRootCandidate)) {
        return projectRootCandidate;
    }

    return undefined;
}

export function buildServerOptions(
    launcher: string,
    extensionPath: string,
    enableMcp: boolean = false
): ServerOptions {
    const args = buildServerArgs(enableMcp);

    return {
        run: {
            command: launcher,
            transport: TransportKind.stdio,
            args,
            options: { cwd: extensionPath, shell: true }
        },
        debug: {
            command: launcher,
            transport: TransportKind.stdio,
            args,
            options: { cwd: extensionPath, shell: true }
        }
    };
}

export async function startServer(context: ExtensionContext, mcpOverride?: boolean): Promise<void> {
    const launcher = getLauncherPath(context);
    if (!launcher) {
        const errorMsg = 'retroca65 Language Server binary was not found. Please verify the extension installation or configure "retroca65.serverPath" in settings.';
        console.error(errorMsg);
        window.showErrorMessage(errorMsg, 'Open Settings').then(selection => {
            if (selection === 'Open Settings') {
                commands.executeCommand('workbench.action.openSettings', 'retroca65.serverPath');
            }
        });
        return;
    }

    if (process.platform !== 'win32') {
        try {
            fs.chmodSync(launcher, 0o755);
        } catch (e) {
            // Ignore if file permissions cannot be modified
        }
    }

    const config = workspace.getConfiguration('retroca65');
    const configuredEnableMcp = config.get<boolean>('enableMcp', false);
    const enableMcp = resolveEnableMcp(configuredEnableMcp, mcpOverride);

    console.log(`retroca65 launcher path: ${launcher} (enableMcp: ${enableMcp})`);

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: 'retroca65' }],
        synchronize: {
            configurationSection: 'retroca65',
            fileEvents: [
                workspace.createFileSystemWatcher('**/Makefile'),
                workspace.createFileSystemWatcher('**/*.s'),
                workspace.createFileSystemWatcher('**/*.i'),
                workspace.createFileSystemWatcher('**/*.inc'),
                workspace.createFileSystemWatcher('**/*.sgs')
            ]
        },
        outputChannelName: enableMcp ? 'retroca65 (MCP)' : 'retroca65',
        revealOutputChannelOn: RevealOutputChannelOn.Info
    };

    const serverOptions = buildServerOptions(launcher, context.extensionPath, enableMcp);

    client = new LanguageClient(
        'retroca65',
        enableMcp ? 'retroca65 Language Server (MCP)' : 'retroca65 Language Server',
        serverOptions,
        clientOptions
    );

    try {
        await client.start();
        context.subscriptions.push(client);
    } catch (error) {
        console.error('Failed to start retroca65 Language Client:', error);
    }
}

export async function restartServer(context: ExtensionContext, mcpOverride?: boolean): Promise<void> {
    if (client) {
        try {
            await client.stop();
        } catch (error) {
            console.error('Failed to stop retroca65 Language Client during restart:', error);
        }
        client = undefined;
    }
    await startServer(context, mcpOverride);
}

export function activate(context: ExtensionContext) {
    console.log('Activating retroca65');

    context.subscriptions.push(
        commands.registerCommand('retroca65.restartServer', async () => {
            window.showInformationMessage('Restarting retroca65 language server...');
            await restartServer(context);
        })
    );

    context.subscriptions.push(
        commands.registerCommand('retroca65.restartServerWithMcp', async () => {
            try {
                const config = workspace.getConfiguration('retroca65');
                await config.update('enableMcp', true);
            } catch (err) {
                console.warn('Failed to persist retroca65.enableMcp setting:', err);
            }
            window.showInformationMessage('Restarting retroca65 language server with MCP enabled...');
            await restartServer(context, true);
        })
    );

    startServer(context);
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    return client.stop();
}

export function getClient(): LanguageClient | undefined {
    return client;
}
