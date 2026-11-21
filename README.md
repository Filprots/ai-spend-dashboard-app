# AI Spend

Android app with a home-screen widget that shows, for each of your AI accounts, the subscription
limits in use and the API spend and balance.

You create one **profile** per account in Settings, give it a token or key, and the app refreshes
it in the background (every 15 min to 3 h) and on demand.

## What each provider shows

| Provider | Credential | Shows | Source |
| --- | --- | --- | --- |
| Claude Pro / Max | OAuth access token (+ optional refresh token) from Claude Code | 5-hour and weekly limits, extra-usage spend | Undocumented endpoint used by Claude Code |
| ChatGPT / Codex | Access token, account ID (+ optional refresh token) from Codex CLI | 5-hour and weekly limits, plan, credits | Undocumented endpoint used by Codex CLI |
| Anthropic API | Admin key | Cost today and this month (UTC) | Admin API cost report |
| OpenAI API | Admin key | Cost today and this month (UTC) | Organization Costs API |
| OpenRouter | API key | Spend today and this month, credit balance, key limit | `/api/v1/key`, `/api/v1/credits` |
| DeepSeek | API key | Balance | `/user/balance` |

Things to know:

- Anthropic and OpenAI do not expose the prepaid credit balance through any API, so only spend is shown.
- The two subscription providers rely on endpoints that are not public APIs. They can change or
  stop working without notice, and Anthropic's terms reserve subscription OAuth tokens for its own
  apps — use them with your own account at your own discretion.
- Subscription access tokens expire after a few hours. If you also enter the refresh token the app
  renews them itself, but refresh tokens are single-use, so the Claude Code / Codex install you
  copied it from gets signed out and needs a fresh login.
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
