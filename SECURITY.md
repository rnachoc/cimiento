# Security policy

Cimiento is a portfolio project that runs on synthetic data only. It is not meant for production use.

## Reporting a vulnerability
Please open a private security advisory on the GitHub repository (Security tab, "Report a vulnerability"). Do not open a public issue for security problems.

## Scope
Relevant reports include:
- Secrets committed to the repository.
- Ways for an agent or a document to bypass the core rules or human review.
- Prompt injection through document text that changes agent output or triggers actions.
- Role key bypass (an AGENT key approving, an ANALYST key writing).
- Document text leaking into telemetry or logs.

## Practices
- No secrets in code; gitleaks runs in pre-commit and CI over the full history.
- Document text is treated as data, never as instructions.
- Role-based keys per service; the browser never holds credentials.
