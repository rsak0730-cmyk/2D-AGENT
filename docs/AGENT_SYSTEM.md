# Agent system status

The API currently supports separately named agent records with provider/model identifiers, specialty labels, instructions, and status fields. CRUD subset: create, list and delete. No model credentials attach to an agent; currently all AI requests use the single server-configured provider/model. These records are metadata, not autonomous workers.

Future execution design must add validated task planning/dependency graph, bounded worker pool, per-agent identity/provider credentials by secret reference, scoped tools, persisted checkpoints, cancellation, idempotency, quota classifications, safe resumption after restart, approvals, event log and provider usage. Use LangGraph only if graph control materially simplifies workflow; evaluate Temporal for durable long-running tasks rather than layering both without a reason. Do not add terminal execution on the API host; use an isolated sandbox and explicit approval.
