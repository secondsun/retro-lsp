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
    _enableMcp: boolean = false
): ServerOptions {
    const isWin = process.platform === 'win32';
    return {
        run: {
            command: launcher,
            transport: TransportKind.stdio,
            args: [],
            options: { cwd: extensionPath, shell: isWin }
        },
        debug: {
            command: launcher,
            transport: TransportKind.stdio,
            args: [],
            options: { cwd: extensionPath, shell: isWin }
        }
    };
}

export async function startServer(context: ExtensionContext, _mcpOverride?: boolean): Promise<void> {
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

    console.log(`retroca65 launcher path: ${launcher}`);

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
        outputChannelName: 'retroca65',
        revealOutputChannelOn: RevealOutputChannelOn.Info
    };

    const serverOptions = buildServerOptions(launcher, context.extensionPath);

    const newClient = new LanguageClient(
        'retroca65',
        'retroca65 Language Server',
        serverOptions,
        clientOptions
    );

    try {
        await newClient.start();
        client = newClient;
        context.subscriptions.push(client);
    } catch (error) {
        console.error('Failed to start retroca65 Language Client:', error);
        client = undefined;
    }
}

export async function restartServer(context: ExtensionContext, _mcpOverride?: boolean): Promise<void> {
    if (client) {
        try {
            if (client.isRunning()) {
                await client.stop();
            }
        } catch (error) {
            console.error('Failed to stop retroca65 Language Client during restart:', error);
        }
        client = undefined;
    }
    await startServer(context);
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
            window.showInformationMessage('Restarting retroca65 language server...');
            await restartServer(context);
        })
    );

    startServer(context);
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    const currentClient = client;
    client = undefined;
    try {
        if (currentClient.isRunning()) {
            return currentClient.stop();
        }
    } catch (error) {
        console.error('Error stopping client during deactivate:', error);
    }
    return undefined;
}

export function getClient(): LanguageClient | undefined {
    return client;
}
