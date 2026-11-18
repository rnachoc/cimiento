# Security policy

Cimiento is a portfolio project that runs on synthetic data only. It is not meant for production use.

## Reporting a vulnerability
Please open a private security advisory on the GitHub repository (Security tab, "Report a vulnerability") or write to Ignacio Rodriguez Caro at rnachoccontact@gmail.com. Do not open a public issue for security problems.

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
- The core refuses to start while role keys hold the `.env.example` placeholders; `make demo` generates random keys into a git-ignored file.
- Anthropic API keys are never stored in the repository. Each user puts their own key in a local, git-ignored `.env`; without one everything runs keyless. Never paste a key into an issue, a log or a recording.

## Supported versions
Only the latest release on the default branch receives fixes.
