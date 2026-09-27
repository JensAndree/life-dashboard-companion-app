# Release checklist

The instrumented suite (see [building.md](building.md#instrumented-tests)) runs the sync path on an emulator, and CI runs it on every push and pull request. Some things it cannot reach: a phone that dozes, a release build shrunk by R8, an update over the settings and watermarks of an earlier version, and a vendor's battery management. The background delivery bug of 1.18.0 was of that kind, and it reached users. This list covers that ground by hand.

**When it is required.** For every release that touches the sync path: `HealthSyncManager`, `HealthConnectManager`, `WebhookManager`, `PendingDrainer`, `SyncScheduler`, the workers, `WriteBackApplier`, or a dependency they use (OkHttp, WorkManager, Health Connect). A release that only changes text, docs or unrelated screens can skip steps 3 to 5.

It takes about 15 minutes of work and one night of waiting.

1. **The suite is green on the commit that gets the tag.** `scripts/instrumented.sh` locally, or the `instrumented` job on the push to main. A test that was skipped or retried counts as not green until it is understood.
2. **A second pair of eyes on the sync diff.** Every change in the files above gets a review that tries to break it: what happens when the worker is stopped halfway, when Health Connect does not answer, when the webhook is down for a day. Green tests prove the cases someone thought of.
3. **Update over the previous release, with the release build.** Install the previous release from GitHub on a real phone and let it sync. Then install the new release APK over it, not a debug build, since R8 only runs on release builds. Check that the settings survived, run Sync Now, and look in Logs for a webhook row and, when MQTT is set up, a broker row.
4. **One night in the background.** Sync interval 15 minutes, app swiped away from recents, screen off, charger unplugged. In the morning the Logs should show scheduled rows at the expected times, with no gap longer than two intervals. This is the step that would have caught the 1.18.0 bug.
5. **An outage and its recovery.** Stop the receiver for three syncs: the failure notification appears once. Start it again: the next sync empties the outbox, the notification goes away, and the receiver has every record once.
6. **Receive, when it changed.** Let one measurement come in from the dev Home Assistant (see [building.md](building.md#local-test-stack)) and find it in Health Connect and in the app that reads it, such as Samsung Health.
7. **48 hours after the release.** Read new issues and the F-Droid and Obtainium channels before the next feature starts, so a regression is fixed while the change is still fresh.
