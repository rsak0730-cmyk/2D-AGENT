# Multi Agent Orchestration Skill

## Purpose
Design durable, bounded agent workflows that preserve checkpoints and explicit state.

## Required knowledge
Task graphs, queues, durable execution, cancellation, idempotency, quota/error taxonomy.

## Supported tools
NOVA task state API, database checkpoint store, later LangGraph/Temporal adapters.

## Development rules
Never imply an unimplemented worker is executing; bound parallelism, retries, budgets, and approval gates.

## Implementation procedure
Define state transitions; persist before side effects; use idempotency keys; resume only from verified checkpoint; emit events.

## Security requirements
Pause on credential failures and non-retryable billing errors; human approve high-impact actions.

## Testing procedure
Test transition validity, restart recovery, duplicate events, retry exhaustion, quota handling.

## Troubleshooting
Inspect persisted state and provider error class; never blind-loop on 429/402.

## References
https://github.com/langchain-ai/langgraph ; https://github.com/temporalio/sdk-python
