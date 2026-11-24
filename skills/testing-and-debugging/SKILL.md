# Testing And Debugging Skill

## Purpose
Create truthful tests and diagnose behavior from observed failures.

## Required knowledge
pytest, JUnit, Android lint, contract tests, failure isolation, deterministic tests.

## Supported tools
pytest, Android Gradle tasks, TestClient, SQLite test databases.

## Development rules
No fabricated test status; no deleting or skipping failing tests just to green CI.

## Implementation procedure
Reproduce; capture exact error; isolate; write regression test; fix root; rerun focused and full affected tests.

## Security requirements
Use synthetic credentials only; ensure tests cannot target production services.

## Testing procedure
Record executed commands, count, environment limits, and failures accurately.

## Troubleshooting
Separate infrastructure/toolchain failures from product defects; preserve actionable logs.

## References
https://github.com/microsoft/playwright ; https://docs.pytest.org/
