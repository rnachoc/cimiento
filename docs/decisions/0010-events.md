# ADR 0010: Events through Kafka

Status: accepted
Date: 2026-11-18

## Context
Steps of the tender flow should be able to react to each other without calling each other directly, and every change in the core should be observable as an event, without losing events or leaking document text.

## Decisions
- **Transactional outbox in the core**: every audit entry writes an `outbox_event` row in the same transaction. A scheduled publisher sends unpublished rows to `cimiento.audit` (Kafka, KRaft) in id order and marks them published only after the broker acknowledged them. Delivery is therefore at least once. Publishing is off unless `CIMIENTO_OUTBOX_ENABLED=true`; the table fills either way.
- **Key per project**: events of one project go to the same partition, so their order is kept. Payloads hold the audit sequence number, action, actor, entity, key, time, hash and the audit details (identifiers and statuses), never document text.
- **Consumers in Python** (`confluent-kafka`): manual commits after a message was handled or parked; retries with growing waits (0.5 s, 1 s, ...); after three failures the message goes to `<topic>.dlq` with the error, original topic, partition and offset in its headers. `replay` sends dead letters back to their topic.
- **Idempotent consumers**: the bridge's side effects go through the core with idempotency keys (tenders by code, proposals by content hash), so a redelivered intake creates nothing new (tested).
- **Bridge**: `cimiento.intake` (a tender arrived) runs the agents for that tender; their proposals go to the core; a person decides; the decision comes back on `cimiento.audit`. Tender identifiers are validated before any file is read.
- Temporal was not added: the flow is short and the outbox plus idempotent consumers cover it.

## Verification
- Core: the outbox is written and published in order per project; with the broker down nothing is lost and events go out once it is back (Postgres in Testcontainers, producer replaced by a recorder; the Kafka module of Testcontainers 1.21 could not start a broker under Docker Desktop on WSL).
- `make flow` (and CI): real Postgres, Kafka and core in docker compose; 12 checks from intake to decision, dead letters, replay and idempotency.
