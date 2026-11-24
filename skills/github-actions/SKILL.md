# Github Actions Skill

## Purpose
Build, test, and publish reproducible non-release NOVA artifacts in CI.

## Required knowledge
GitHub Actions, Gradle, Android SDK/JDK compatibility, Python dependencies, artifact provenance.

## Supported tools
GitHub hosted runner, Gradle, setup-java, upload-artifact.

## Development rules
Never add signing credentials or API secrets to debug CI; pin and review actions; least permission.

## Implementation procedure
Checkout; install pinned runtime; validate; run tests/lint; build; assert output; publish artifacts and readable summary.

## Security requirements
Use repository secrets only for trusted release workflows; do not expose secrets to fork PRs.

## Testing procedure
Run workflows manually and verify artifact nonzero size and summary.

## Troubleshooting
Read full failure logs; keep APK failure visible; avoid `continue-on-error`.

## References
https://github.com/android/architecture-samples ; https://docs.github.com/actions
