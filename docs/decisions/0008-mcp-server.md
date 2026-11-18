# ADR 0008: Read-only MCP server

Status: accepted
Date: 2026-11-18

## Context
An assistant should be able to look into projects, schedules, proposals, the audit log and the documents, without any way to change them.

## Decisions
- `services/mcp` uses the official MCP Python SDK (`mcp` 2.x, `MCPServer`), stdio transport.
- Tools: `list_projects`, `list_tenders`, `get_schedule`, `list_proposals`, `get_proposal`, `read_audit`, `verify_audit`, and `search_documents` when the agents' search URL is configured. Every tool is annotated read-only and non-destructive. There is no write tool.
- Two independent locks on writes: the server's core client only sends GET, and it uses the ANALYST key, which the core refuses for any write (verified against the running core: 403 on every POST).
- Masking before a model sees the output: company prices and productivities (`unitPrice`, `amount`, `ratePerDay`) are removed, and text that looks like an instruction planted in a document is replaced by a marker while its document and page stay visible.

## Consequences
- The masking patterns are a copy of the generic patterns of the agents' injection detector; a planted wording they miss is shown as text (still clearly data, never executed).
