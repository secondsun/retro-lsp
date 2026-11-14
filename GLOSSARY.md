# Retro-LSP

Language Server Protocol implementation and Model Context Protocol (MCP) server for SuperFX and SNES development.

## Language

**Workspace Index**:
The deep module boundary (`WorkspaceIndex`) managing workspace source file indexing, open editor buffer precedence, sandbox confinement, lazy on-demand directory indexing, and publication of immutable `WorkspaceSnapshot`s.
_Avoid_: ProjectService, FileService, SymbolService, project cache, file store

**Workspace Snapshot**:
An immutable point-in-time view (`WorkspaceSnapshot`) of effective workspace files (`diskFiles ⊕ openBuffers`) used for lock-free symbol, scope, documentation, macro, include, and reference queries.
_Avoid_: Live symbol map, mutable symbol table

**Buffer Precedence**:
The invariant ensuring active, open in-memory editor buffers transparently supersede on-disk file state for all parsing, analysis, and symbol resolution queries until closed via `closeBuffer`.
_Avoid_: Dirty cache override, buffer shadow

**Transient Snippet**:
An ephemeral overlay snapshot (`WorkspaceIndex.snapshotFor`) created when inline `code` is passed to MCP or diagnostic tools, resolving both snippet-local and workspace symbols/macros with zero mutation of persistent workspace state.
_Avoid_: Scratch file, temporary buffer write

**File Watcher**:
The background filesystem monitor (`WorkspaceFileWatcher`) registering directories lazily as `WorkspaceIndex` indexes them on demand and synchronizing disk creations, modifications, and deletions via `notifyDiskEvent`.
_Avoid_: Eager tree walker, directory poller

**Workspace Sandbox**:
The filesystem boundary enforced around the required workspace path preventing tool access to external directories.
_Avoid_: Chroot, jail, restricted folder

**Audit Finding**:
The sealed domain union (`AuditFinding.Syntax`, `AuditFinding.Hardware`, `AuditFinding.Stack`, `AuditFinding.Performance`) carrying typed diagnostic category, 1-based source coordinates, severity, rule code, and message from each validation pass without string-prefix guessing.
_Avoid_: Untyped diagnostic string, rule-prefix heuristic

**Diagnostic Coordinator**:
The subsystem coordinating lexical/block syntax validation, dialect-gated FX3 hardware invariant verification, SuperFX performance heuristics, and static stack depth tracking to publish LSP diagnostics and respond to MCP audits.
_Avoid_: Error reporter, problem manager

**Stack Displacement Invariant**:
The invariant requiring net stack pointer alteration ($\Delta\text{SP}$) across a function invocation to equal zero upon return and match across all control-flow join points.
_Avoid_: Stack leak, frame discrepancy
