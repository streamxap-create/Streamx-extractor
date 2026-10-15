# Streamx-extractor

Kotlin/JVM library: YouTube / YouTube Music / Plex data extraction (streams, search,
playlists, channels, comments, lyrics, queue). Pure JVM - works in Android apps too.

## Use via JitPack

1. Push this project to GitHub and create a release/tag, e.g. `v1.1.0`.
2. In the app's `settings.gradle.kts` make sure `maven { url = uri("https://jitpack.io") }`
   is in `dependencyResolutionManagement.repositories`.
3. In the app's `build.gradle.kts`:

```kotlin
implementation("com.github.<github-user>:<repo-name>:v1.1.0")
```

(Open `https://jitpack.io/#<github-user>/<repo-name>` to see the exact coordinate and the build log.)

JitPack builds with `jitpack.yml` (JDK 17, `./gradlew publishToMavenLocal -x test`).

## Build locally

```
./gradlew build            # compile + tests
./gradlew fatJar           # one jar with all dependencies
./gradlew publishToMavenLocal
```

## Notes
- Uses YouTube's unofficial internal APIs: they can change without notice.
- Config (keys, client versions, language, cache) lives in `ExtractorConfig` and can be
  overridden at app start.

## Install (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven { url = uri("https://jitpack.io") } }
}

// app/build.gradle.kts
implementation("com.github.zk5740764-cmyk:Streamx-extractor:v1.4.0")
```

## Notice

This library uses unofficial YouTube endpoints. They can change or stop working at any time,
and using them may conflict with YouTube's Terms of Service. Use at your own risk.

## License

MIT - see [LICENSE](LICENSE).
