# Retro-LSP

Language Server Protocol implementation and Model Context Protocol (MCP) server for SuperFX and SNES development.

## Language

**Workspace Index**:
The persistent in-memory graph of tokenized source files and symbol scopes maintained across the server runtime.
_Avoid_: Project cache, file store

**Buffer Precedence**:
The invariant ensuring active, open in-memory editor buffers transparently supersede on-disk file state for all parsing, analysis, and symbol resolution queries.
_Avoid_: Dirty cache override, buffer shadow

**Symbol Service**:
The query engine that resolves lexical scopes, symbol definitions, and docstrings across the workspace.
_Avoid_: Symbol table, symbol dictionary

**File Watcher**:
The background filesystem monitor that synchronizes disk modifications into the workspace index.
_Avoid_: Directory poller, file listener

**Workspace Sandbox**:
The filesystem boundary enforced around the required workspace path preventing tool access to external directories.
_Avoid_: Chroot, jail, restricted folder

**Diagnostic Coordinator**:
The subsystem coordinating lexical syntax validation, hardware invariant verification, and static stack depth tracking to publish LSP diagnostics and respond to MCP audits.
_Avoid_: Error reporter, problem manager

**Stack Displacement Invariant**:
The invariant requiring net stack pointer alteration (Δ_SP) across a function invocation to equal zero upon return and match across all control-flow join points.
_Avoid_: Stack leak, frame discrepancy

