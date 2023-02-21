/* --------------------------------------------------------------------------------------------
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 * ------------------------------------------------------------------------------------------ */

import * as Path from 'path';
import { workspace, ExtensionContext, IndentAction, languages } from 'vscode';
import {LanguageClient,
	ServerOptions,
	TransportKind} from 'vscode-languageclient/node';

import {
	LanguageClientOptions,
    RevealOutputChannelOn
} from 'vscode-languageclient';

let client: LanguageClient;

export function activate(context: ExtensionContext) {
	console.log('Activating retroca65');

    // Options to control the language client
    let clientOptions: LanguageClientOptions = {
        documentSelector: ['retroca65'],
        synchronize: {
            configurationSection: 'retroca65',
            fileEvents: [
                workspace.createFileSystemWatcher('Makefile'),
                workspace.createFileSystemWatcher('**/*.s'),
                workspace.createFileSystemWatcher('**/*.sgs')
            ]
        },
        outputChannelName: 'retroca65',
        revealOutputChannelOn: RevealOutputChannelOn.Info // never
    }

    let launcher = Path.resolve('C:\\Users\\secon\\Projects\\retro-lsp\\target\\tm4e4lsp.exe'); //graalvm (build in 51s - 7 MB)
     //let launcher = Path.resolve('C:\\Users\\secon\\Projects\\retro-lsp\\dist\\windows\\bin\\launcher.bat'); //jlink  (build in 10s? - 40mb)
    console.log(launcher);
    
    // Start the child java process
    let serverOptions: ServerOptions = {
            run : { command: launcher, transport: TransportKind.stdio,
                    options: { cwd: context.extensionPath,  }
            },
            debug : { command: launcher, transport: TransportKind.stdio,
                options: { cwd: context.extensionPath }
        }
    }
    


    // Create the language client and start the client.
    let client = new LanguageClient('retroca65', 'retroca65 Language Server', serverOptions, clientOptions);
    try {
        client.start();
        context.subscriptions.push(client);

    } catch (error) {
        console.log(error)
    }

}

export function deactivate(): Thenable<void> | undefined {
	if (!client) {
		return undefined;
	}
	return client.stop();
}
function platformSpecificLauncher(): string[] {
	switch (process.platform) {
		case 'win32':
            return ['C:\Users\secon\Projects\retro-lsp\scripts\launchgraalagent.bat'];

		case 'darwin':
			return ['dist', 'mac', 'bin', 'launcher'];

		case 'linux':
            return ['dist', 'linux', 'bin', 'launcher'];			
	}

	throw `unsupported platform: ${process.platform}`;
}
