import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import {
    buildServerArgs,
    resolveEnableMcp,
    buildServerConfig
} from '../../serverConfig.js';

describe('VS Code Extension MCP Toggle & Server Launcher Integration', () => {
    describe('buildServerArgs', () => {
        it('should return ["--mcp"] when enableMcp is true', () => {
            const args = buildServerArgs(true);
            assert.deepStrictEqual(args, ['--mcp']);
        });

        it('should return [] when enableMcp is false', () => {
            const args = buildServerArgs(false);
            assert.deepStrictEqual(args, []);
        });
    });

    describe('resolveEnableMcp', () => {
        it('should return configValue when overrideValue is undefined', () => {
            assert.strictEqual(resolveEnableMcp(true, undefined), true);
            assert.strictEqual(resolveEnableMcp(false, undefined), false);
            assert.strictEqual(resolveEnableMcp(undefined, undefined), false);
        });

        it('should prioritize overrideValue over configValue', () => {
            assert.strictEqual(resolveEnableMcp(false, true), true);
            assert.strictEqual(resolveEnableMcp(true, false), false);
        });
    });

    describe('buildServerConfig', () => {
        it('should configure launcher with --mcp flag when enableMcp is true', () => {
            const config = buildServerConfig('/usr/bin/launcher', '/home/user/ext', true);
            assert.strictEqual(config.run.command, '/usr/bin/launcher');
            assert.deepStrictEqual(config.run.args, ['--mcp']);
            assert.strictEqual(config.run.options.cwd, '/home/user/ext');
            assert.strictEqual(config.run.options.shell, true);

            assert.strictEqual(config.debug.command, '/usr/bin/launcher');
            assert.deepStrictEqual(config.debug.args, ['--mcp']);
            assert.strictEqual(config.debug.options.cwd, '/home/user/ext');
            assert.strictEqual(config.debug.options.shell, true);
        });

        it('should configure launcher without --mcp flag when enableMcp is false', () => {
            const config = buildServerConfig('/usr/bin/launcher', '/home/user/ext', false);
            assert.deepStrictEqual(config.run.args, []);
            assert.deepStrictEqual(config.debug.args, []);
        });
    });

    describe('package.json Manifest Configuration', () => {
        const packageJsonPath = path.resolve(__dirname, '../../../package.json');
        const pkg = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));

        it('should declare retroca65.enableMcp configuration property', () => {
            const configProps = pkg.contributes?.configuration?.properties;
            assert.ok(configProps, 'contributes.configuration.properties must exist');

            const mcpProp = configProps['retroca65.enableMcp'];
            assert.ok(mcpProp, 'retroca65.enableMcp property must be defined');
            assert.strictEqual(mcpProp.type, 'boolean');
            assert.strictEqual(mcpProp.default, false);
            assert.strictEqual(
                mcpProp.description,
                'Enable Model Context Protocol (MCP) mode on server launch'
            );
        });

        it('should declare retroca65.restartServer and retroca65.restartServerWithMcp commands', () => {
            const commands = pkg.contributes?.commands;
            assert.ok(Array.isArray(commands), 'contributes.commands must be an array');

            const restartCmd = commands.find((c: any) => c.command === 'retroca65.restartServer');
            assert.ok(restartCmd, 'retroca65.restartServer command must be registered');
            assert.strictEqual(restartCmd.title, 'RetroCA65: Restart Server');

            const restartMcpCmd = commands.find((c: any) => c.command === 'retroca65.restartServerWithMcp');
            assert.ok(restartMcpCmd, 'retroca65.restartServerWithMcp command must be registered');
            assert.strictEqual(restartMcpCmd.title, 'RetroCA65: Restart Server with MCP');
        });

        it('should include restart commands in activationEvents', () => {
            const activationEvents = pkg.activationEvents;
            assert.ok(Array.isArray(activationEvents), 'activationEvents must be an array');
            assert.ok(activationEvents.includes('onCommand:retroca65.restartServer'));
            assert.ok(activationEvents.includes('onCommand:retroca65.restartServerWithMcp'));
        });
    });
});
