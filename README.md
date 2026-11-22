# AI Spend

Android app with a home-screen widget that shows, for each of your AI accounts, the subscription
limits in use and the API spend and balance.

You create one **profile** per account in Settings, give it a token or key, and the app refreshes
it in the background (every 15 min to 3 h) and on demand.

## What each provider shows

| Provider | Credential | Shows | Source |
| --- | --- | --- | --- |
| Claude Pro / Max | In-app sign-in (or tokens pasted from Claude Code) | 5-hour, weekly and model-only weekly limits (e.g. Fable), extra-usage spend | Undocumented endpoint used by Claude Code |
| ChatGPT / Codex | In-app sign-in with a one-time code (or tokens pasted from Codex CLI) | 5-hour and weekly limits, plan, credits | Undocumented endpoint used by Codex CLI |
| Anthropic API | Admin key and/or in-app Console sign-in | Cost today and this month (UTC), prepaid credit balance | Admin API cost report; Console billing endpoints (unofficial) for the balance |
| OpenAI API | Admin key | Cost today and this month (UTC) | Organization Costs API |
| OpenRouter | API key | Spend today and this month, credit balance, key limit | `/api/v1/key`, `/api/v1/credits` |
| DeepSeek | API key | Balance | `/user/balance` |

Things to know:

- Anthropic has no API for the prepaid credit balance. Signing in to the Console inside the app lets it
  read the balance the way the Console billing page does; the session is a full Console login, kept
  encrypted on the device and used only for read requests.
- OpenAI has no way to read the prepaid credit balance with an Admin key, so only spend is shown.
- The two subscription providers rely on endpoints that are not public APIs. They can change or
  stop working without notice, and Anthropic's terms reserve subscription OAuth tokens for its own
  apps — use them with your own account at your own discretion.
- Signing in inside the app gives it its own tokens, which it renews itself; Claude Code and Codex
  on your computers stay signed in. Pasting tokens copied from a computer also works, but if you
  include the refresh token, renewing it here signs that computer out (refresh tokens are single-use).
- Credentials are encrypted with an Android Keystore key, excluded from backups, and sent only to
  the provider they belong to. There is no server component.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # response parsers and token refresh
```

Every push also builds the APK in GitHub Actions and attaches it to the run as an artifact.

## Adding a provider

1. Add an entry to `ProviderType` (`app/src/main/java/app/aispend/data/ProviderType.kt`) with its credential fields.
2. Implement `UsageProvider` in `providers/` and return meters (percent of a limit) and/or money (spend, balance).
3. Register it in `Providers.of` and add a parser test with a sample response.
