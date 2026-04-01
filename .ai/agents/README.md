# Agent Definitions

This folder contains role definitions for multi-agent coding workflows on πChess. They are **tool-agnostic** — any coding agent (Claude Code, Cursor, Copilot Workspace, Aider, etc.) can use them as system prompts or role instructions.

## Agents

| Agent | File | Purpose |
|-------|------|---------|
| Spec Writer | [`spec-writer.md`](spec-writer.md) | Writes/modifies test specs — always runs first (TDD) |
| Coder | [`coder.md`](coder.md) | Implements minimum code to pass specs + formats |
| Verifier | [`verifier.md`](verifier.md) | Runs tests, coverage, diagnostics — the green-light gate |
| Curator | [`curator.md`](curator.md) | Keeps docs, README, `.ai/`, and HelpView in sync |
| Architect | [`architect.md`](architect.md) | Reviews for style compliance and future-phase compatibility |

## Intended Workflow

```
1. Architect   → reviews the task, flags risks to future phases
2. Spec Writer → writes or updates test specs
3. Coder       → implements code to pass specs, runs scalafmtAll
4. Verifier    → runs sbt coverage test coverageReport + diagnostics
5. Curator     → updates any affected docs
```

Steps 3–4 loop until green. Step 5 can run in parallel with step 4 if the change scope is clear.

## How to Use

Each file starts with a role summary, then lists the agent's scope, required reading, inputs, outputs, and constraints. Feed the relevant file as a system prompt or prepend it to your task prompt depending on your tool.
