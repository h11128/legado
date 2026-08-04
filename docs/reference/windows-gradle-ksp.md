# Windows Gradle / KSP: same-drive `GRADLE_USER_HOME`

## Symptom

```
e: [ksp] java.lang.IllegalArgumentException: this and base files have different roots:
C:\Users\…\.gradle\caches\…\some.jar!… and E:\Projects\legado\app
```

## Cause

Projects on this machine live on `E:`. The default Gradle user home is
`%USERPROFILE%\.gradle` on `C:`. KSP uses `Path.relativize`, which cannot cross
Windows drive roots ([google/ksp#1079](https://github.com/google/ksp/issues/1079)).

This is **not** a wrong `--tests` class name.

## Fix (machine — preferred)

All E: projects share one home:

| Knob | Value |
|---|---|
| User env `GRADLE_USER_HOME` | `E:\.gradle` |
| Android Studio → Gradle user home / service directory | `E:/.gradle` |

## Fix (CLI fallback)

`./gradlew` / `gradlew.bat`: if `GRADLE_USER_HOME` is unset, prefer `E:/.gradle`,
else `<repo>/.gradle-home`.

Never export `GRADLE_USER_HOME` to `C:\Users\…\.gradle`.

## Enforcement

| Layer | What |
|---|---|
| User env + AS global | `E:\.gradle` |
| `gradlew` / `gradlew.bat` | Fallback to `E:/.gradle` when unset |
| `.cursor/rules/windows-gradle-ksp.mdc` | Always-on agent guidance |
| Hook `legado_windows_gradle_home_on_c` | DENY shell cmds that set home on `C:` while running Gradle |
| Hook `legado_ksp_different_roots_output` | ASK after shell if output shows `different roots` |

## Verify

```bash
echo "$GRADLE_USER_HOME"   # expect E:\.gradle or /e/.gradle
./gradlew :app:testAppDebugUnitTest --tests 'io.legado.app.model.checkalgo.ChangeChapterVerifyTest'
```
