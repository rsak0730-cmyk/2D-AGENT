# Voice Integration Skill

## Purpose
Add opt-in, permission-respecting speech interfaces and provider adapters.

## Required knowledge
Android speech APIs, audio lifecycle, TTS/STT, consent, interruption and accessibility.

## Supported tools
Android runtime permission APIs; configured external voice provider once added.

## Development rules
Do not claim unrestricted background recording; never record without consent; external voice API keys stay server-side.

## Implementation procedure
Request microphone permission when user starts voice; provide text fallback; stop on lifecycle loss; disclose uploads.

## Security requirements
Use foreground service only for eligible user-visible sessions; apply retention/deletion policy.

## Testing procedure
Test denied/revoked permission, audio interruption, network outage, and playback lifecycle.

## Troubleshooting
Check device capability, permission state, audio focus, and service restrictions.

## References
https://developer.android.com/guide/topics/media/mediaplayer ; https://elevenlabs.io/docs
