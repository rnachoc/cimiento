# ADR 0018: Tender intake from the console

## Context
Tenders reached the agents only through a script or the Kafka bridge, so a company could not bring in a new tender without a developer. A person needs to upload the documents from the console and see the result in the review inbox, without giving any model a way to write.

## Decision
1. **The core keeps the documents and a queue.** `POST /api/intakes` (multipart: code, title, planned start, `pliego`, `anexo`, `mediciones`) is a person's act: estimating or management (ADR 0013); site managers do not bring in tenders. The core checks a new code, a Monday start, the PDF header and 20 MB per file, stores each PDF as received with its SHA-256 (`intake_document`, a trigger refuses UPDATE and DELETE) and audits `INTAKE_RECEIVED` with counts and bytes, never text (V10).
2. **The agent worker pulls.** `cimiento-agents worker` claims the oldest job with the AGENT key (`POST /api/intakes/claim`, `FOR UPDATE SKIP LOCKED`, so two workers never take the same job; a job running for 15 minutes is presumed abandoned and can be taken again), downloads the documents, checks each checksum, runs the usual analysis in a temporary folder and reports with `POST /api/intakes/{id}/result`. The report carries counts, the recommendation or the name of an error. A successful report must name the tender with the intake's code; the core audits `INTAKE_ANALYSED`.
3. **Nothing new can write.** The worker only has the AGENT key: it registers the tender and submits proposals like any agent, and every proposal goes through the rules and waits for a person. Polling instead of Kafka keeps the intake working where Kafka is not deployed; the Kafka bridge stays for event flows.
4. **Console:** "Nueva licitación" (from the bid pipeline): form, three PDF slots, the reasons when the core refuses, and the recent uploads with their status, refreshed every 3 seconds while a job is queued or running, with links to the tender file and the inbox.
5. **Packaging:** `services/agents/Dockerfile` runs the worker. Compose starts it under the `worker` profile; `make demo` uses it. Without `ANTHROPIC_API_KEY` it reads with the keyless rule-based reader.

## Consequences
- No new dependency. Uploads are limited to 64 MB per request in nginx and Spring.
- The keyless reader is written for the synthetic document format: real tenders need a model, whose quality on real documents is not measured.
- Out of scope: other document types, editing an upload, deleting stored documents and antivirus scanning (the core only stores the files and the worker only reads them as PDF text).
