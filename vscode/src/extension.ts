/* --------------------------------------------------------------------------------------------
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 * ------------------------------------------------------------------------------------------ */

import * as fs from 'fs';
import * as path from 'path';
import { workspace, ExtensionContext } from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
    RevealOutputChannelOn
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;

function getLauncherPath(context: ExtensionContext): string {
    const config = workspace.getConfiguration('retroca65');
    const configuredPath = config.get<string>('serverPath');
    if (configuredPath && fs.existsSync(configuredPath)) {
        return configuredPath;
    }

    const platformSubdir = process.platform === 'win32'
        ? path.join('dist', 'windows', 'bin', 'launcher.bat')
        : process.platform === 'darwin'
            ? path.join('dist', 'mac', 'bin', 'launcher')
            : path.join('dist', 'linux', 'bin', 'launcher');

    // Check sibling of extension directory (project root)
    const projectRootCandidate = path.resolve(context.extensionPath, '..', platformSubdir);
    if (fs.existsSync(projectRootCandidate)) {
        return projectRootCandidate;
    }

    // Fall back to extension-local dist if bundled
    const bundledCandidate = path.resolve(context.extensionPath, platformSubdir);
    if (fs.existsSync(bundledCandidate)) {
        return bundledCandidate;
    }

    return projectRootCandidate;
}

export function activate(context: ExtensionContext) {
    console.log('Activating retroca65');

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

    const launcher = getLauncherPath(context);
    console.log(`retroca65 launcher path: ${launcher}`);

    const serverOptions: ServerOptions = {
        run: {
            command: launcher,
            transport: TransportKind.stdio,
            options: { cwd: context.extensionPath, shell: true }
        },
        debug: {
            command: launcher,
            transport: TransportKind.stdio,
            options: { cwd: context.extensionPath, shell: true }
        }
    };

    client = new LanguageClient('retroca65', 'retroca65 Language Server', serverOptions, clientOptions);
    try {
        client.start();
        context.subscriptions.push(client);
    } catch (error) {
        console.error('Failed to start retroca65 Language Client:', error);
    }
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    return client.stop();
}
