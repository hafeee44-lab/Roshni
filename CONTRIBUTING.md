# Contributing

Thanks for helping. The most useful contributions, roughly in order:

1. **A wrong detection, with the data.** If the app called an outage that wasn't one, or missed one, open **Settings → Inverter data**, tap **Copy**, and paste it into an issue along with what actually happened and when. Real dumps become test fixtures; `DeyeDumpTest` is one.
2. **Support for another inverter.** Usually this means teaching `GridAnalyzer` a new key code or name. Add a fixture test next to `DeyeDumpTest` and keep every existing test passing.
3. **Translations.** UI text still lives in the Kotlin code. Moving it into `strings.xml` is the first step, and a very welcome pull request. Urdu is especially wanted after that.
4. **UI polish and accessibility.** Follow [docs/DESIGN.md](docs/DESIGN.md).

## Building and testing

```bash
./gradlew testDebugUnitTest assembleDebug
```

Demo mode (on the welcome screen, or in **Settings → Data source**) runs the full pipeline against a simulated inverter. It's the quickest way to see a UI change, the alarm, or the outage notification without real hardware. **Simulate outage** in the demo controls makes the grid fail on demand.

## Where things go

- **When and how the app alerts** is decided in `domain/`, as pure Kotlin with no Android imports. `AlertPlanner` takes the previous state and one observation and returns the new state, the alerts and the log events. Every rule there has a test in `AlertPlannerTest`. Change a rule, change or add its test.
- **Persistence** is in `data/`: plain `SharedPreferences` and a hand-written `SQLiteOpenHelper`. There's deliberately no annotation processing.
- **Android plumbing** is in `service/`: the foreground service, notifications, the alarm player, the boot receiver and the tile.
- **UI** is in `ui/`, written in Compose and built on the components in `ui/components`.

## Pull requests

- Keep each pull request to one change, and include a test for any logic change.
- Match the surrounding style: small functions, comments that explain *why*, and no dead code.
- Never commit credentials. `local.properties` is gitignored for exactly that reason.
