# Windows Gradle / KSP: same-drive `GRADLE_USER_HOME`

## Symptom

```
e: [ksp] java.lang.IllegalArgumentException: this and base files have different roots:
C:\Users\…\.gradle\caches\…\some.jar!… and E:\Projects\legado\app
```

## Cause

This clone lives on `E:`. The default Gradle user home is `%USERPROFILE%\.gradle` on `C:`.
KSP uses `Path.relativize`, which cannot cross Windows drive roots
([google/ksp#1079](https://github.com/google/ksp/issues/1079)).

`.gitignore` already reserved `.gradle-home/` for a same-drive cache, but that alone
does not set `GRADLE_USER_HOME`. Without the wrapper / env / IDE setting, agents and
shells still used `C:` and hit the error.

This is **not** a wrong `--tests` class name.

## Fix (CLI)

1. Use `./gradlew` / `gradlew.bat` from the repo root.
2. On Windows, the wrapper sets `GRADLE_USER_HOME` to `<repo>/.gradle-home` when unset.
3. Never export `GRADLE_USER_HOME` to `C:\Users\…\.gradle` for this project.

## Fix (Android Studio)

**Project only** — do not change the IDE global “Gradle user home” (that affects every project).

When this repo is open, `.idea/gradle.xml` sets:

`serviceDirectoryPath` → `$PROJECT_DIR$/.gradle-home`

(`.idea/` is gitignored; recreate locally if missing.)

Confirm under **Settings → Build Tools → Gradle** while this project is focused; other projects should still use the default `%USERPROFILE%\.gradle`.

## Enforcement

| Layer | What |
|---|---|
| `gradlew` / `gradlew.bat` | Auto-set `.gradle-home` on Windows |
| `.cursor/rules/windows-gradle-ksp.mdc` | Always-on agent guidance |
| Hook `legado_windows_gradle_home_on_c` | DENY shell cmds that set home on `C:` while running Gradle |
| Hook `legado_ksp_different_roots_output` | ASK after shell if output shows `different roots` |
| `gradle.properties` comment | Human-visible reminder |

## Verify

```bash
unset GRADLE_USER_HOME
./gradlew :app:testAppDebugUnitTest --tests 'io.legado.app.model.checkalgo.ChangeChapterVerifyTest'
# Expect BUILD SUCCESSFUL; daemon under .gradle-home/
```
