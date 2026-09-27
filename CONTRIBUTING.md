# Contributing

Thanks for your interest in improving Life Dashboard Companion!

## Getting Started

1. Fork and clone the repository
2. Open the project in Android Studio, or build from the command line:

   ```bash
   ./gradlew assembleDebug
   ./gradlew installDebug   # install on a connected device
   ```

3. Health Connect features need a real device (or emulator) with the Health Connect app installed

## Development Guidelines

- **Run the tests** before opening a PR:

  ```bash
  ./gradlew testDebugUnitTest
  ```

- **Changes to the sync path need the instrumented suite.** `scripts/instrumented.sh` runs the real sync on an emulator of its own: Health Connect, the webhook, the outbox, MQTT and Receive (see [docs/building.md](docs/building.md#instrumented-tests)). CI runs it on every pull request, leaving out the slow `@LargeTest` tests, which run on main. A bug fix comes with a test that fails without it.
- **Never swallow a cancellation.** In suspend code, a `catch (e: Exception)` or `runCatching` must let `CancellationException` through first; `scripts/check-cancellation.sh` runs in CI and fails otherwise.
- **Payload compatibility matters.** The JSON payload format is shared with the [iOS companion app](https://github.com/owen282000/life-dashboard-companion-app-ios); both apps feed the same backends. Changes to payload keys or value formats need a very good reason and matching updates to [docs/webhook.md](docs/webhook.md) and [docs/webhook-schema.json](docs/webhook-schema.json).
- **UI text belongs in `strings.xml`.** Use `stringResource(R.string.…)` and positional format arguments rather than concatenating sentences; `scripts/check-hardcoded-strings.sh` runs in CI and fails on hardcoded text. Translations are welcome as a new `values-<locale>/strings.xml`.
- **Keep pure logic testable.** Sync logic that does not need Health Connect lives in small, dependency-free types (see `ResilientReadLogic`, `WebhookSupport`); follow that pattern so it stays unit-testable on the JVM.
- **AI assistance is fine; say so in the PR.** The rules are in [AI_POLICY.md](AI_POLICY.md): disclose the tool, understand and test what you submit, no unreviewed agent output.
- **Commit messages** follow the conventional style used in the history: `feat:`, `fix:`, `docs:`, `ci:`, `build:`, `test:`, `chore:`.

## Version tags

If you push version tags, enable the repo's git hooks once:

```bash
git config core.hooksPath .githooks
```

Tags must be strict semver (X.Y.Z) and higher than the previous tag; the app version is derived from them at build time.

## Opening a Pull Request

1. Create a feature branch (`git checkout -b feature/amazing-feature`)
2. Make your changes, with tests where it makes sense
3. Make sure the build and tests pass
4. Open a PR describing what changed and why

Small, focused PRs are much easier to review than big ones. When in doubt, open an issue first to discuss the direction.
