# Retro-LSP

Language Server Protocol implementation and Model Context Protocol (MCP) server for SuperFX and SNES development.

## Language

**Workspace Index**:
The persistent in-memory graph of tokenized source files and symbol scopes maintained across the server runtime.
_Avoid_: Project cache, file store

**Symbol Service**:
The query engine that resolves lexical scopes, symbol definitions, and docstrings across the workspace.
_Avoid_: Symbol table, symbol dictionary

**File Watcher**:
The background filesystem monitor that synchronizes disk modifications into the workspace index.
_Avoid_: Directory poller, file listener

**Workspace Sandbox**:
The filesystem boundary enforced around the required workspace path preventing tool access to external directories.
_Avoid_: Chroot, jail, restricted folder
