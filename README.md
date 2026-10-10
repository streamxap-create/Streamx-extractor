Streamx Extractor

Kotlin library to extract YouTube video info and streams.

---

Installation

Teen files mein code add karna hai.

File 1: settings.gradle.kts (Project Root)

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

File 2: build.gradle.kts (App Module)

dependencies {
    implementation("com.github.streamxap-create:Streamx-extractor:V1.0")
}

File 3: Main.kt

import com.streamx.extractor.StreamxExtractor

fun main() {
    val video = StreamxExtractor.getVideo("dQw4w9WgXcQ")

    println("Title: ${video.title}")
    println("Channel: ${video.channelName}")
    println("Duration: ${video.duration}s")
    println("Views: ${video.viewCount}")
}

---

Features

- Video info (title, description, duration, channel, views)
- Quality: 144p se 1440p (2K), 2160p (4K) aur 4320p (8K) tak, jo bhi video mein ho (video-only + audio-only, merge karke chalao), 360p muxed backup
- Har (height, codec) group alag test hota hai, isliye ek quality fail ho to baaki rehti hain
- Playlist, channel videos, related videos, search (page-wise)
- Link se ID nikalna: `StreamxExtractor.extractVideoId(url)`
- `ExtractorConfig` mein API keys / client versions badal sakte ho
- Har URL pehle test hota hai (403 wali stream list se hat jati hai)
- video.log mein har client ka report
- Thumbnails
- Built with Kotlin

---

License

MIT License

---

Naye functions

val best = v.pickBest()                 // sabse high (4K/8K tak)
val q2k  = v.pickVideo(1440)            // max 2K
v.qualityOptions                         // menu: "4K 60fps", "2K", "1080p"...
StreamxExtractor.getPlaylist(id)         // ItemPage, nextToken se agla page
StreamxExtractor.getChannelVideos("UC...")
StreamxExtractor.getRelated(videoId)

Music / Spotify-jaise features

StreamxExtractor.searchMusic("coldplay")              // YouTube Music: songs/albums/artists (MusicFilter)
StreamxExtractor.suggestions("cold")                  // autocomplete
StreamxExtractor.getRadio(videoId)                    // autoplay / "is jaisa aur"
StreamxExtractor.findLyrics(title, artist, seconds)   // synced lyrics (LRCLIB), LrcParser.lineAt(...)
val q = PlayQueue(tracks); q.next(); q.toggleShuffle(); q.cycleRepeat(); q.peekNext()
video.pickAudio(preferOpus = true, maxBitrate = 128_000)   // data saver
video.audioOptions                                    // "Opus 160kbps", "AAC 128kbps"
item.toTrack()                                        // VideoItem / Video / MusicItem -> Track (artist - title saaf)
StreamxExtractor.getChannelInfo("UC...")
StreamxExtractor.getComments(videoId)                 // experimental
StreamxExtractor.searchPage(q, null, SearchFilter.NEWEST)
video.subtitles / .keywords / .isLive / .publishDate / .likeCount / .hasHdr
getVideo() ab cache karta hai (ExtractorConfig.cacheSeconds), prefetch(id) agle gaane ke liye
Coroutines: getVideoAsync(), getRadioAsync(), suggestionsAsync(), findLyricsAsync()

Note: 1440p+ par H.264 nahi hota, wahan VP9/AV1 aati hai. Phone ka hardware decoder check karo.

---

Limitations

- signatureCipher wali streams abhi skip hoti hain.
- Comments, YouTube Music search, channel subscribers best-effort hain (YouTube ka layout badalta hai).
- Spotify ke gaane extract nahi ho sakte (DRM); yeh library YouTube / YouTube Music use karti hai.
- YouTube kabhi bhi format badal sakta hai; keys/versions `ExtractorConfig` mein update karo.
- Playlist/channel/related ka parsing YouTube ke renderer names par chalta hai; layout badle to update chahiye.

Credits: VISIONOS client ka idea NewPipe project se liya gaya hai.

---

Player mein kaise lagayen (Media3)

val v = StreamxExtractor.getVideo(id)
val vs = v.pickVideo(1080)     // video-only
val au = v.pickAudio()         // audio-only
// MergingMediaSource(progressive(vs), progressive(au))
// har stream ka apna stream.userAgent hota hai
// URL par open-ended Range 403 deta hai, isliye 10MB chunks mein maango (ChunkedHttpDataSource)


---

## JAR kaise banayein / use karein

GitHub Actions: Actions > "Build library JAR" > Run workflow > Artifacts > `streamx-extractor-jars`.
Tag `V1.0` push karo to Releases mein JAR khud lag jata hai.

Teen files milti hain:
- `streamx-extractor-1.0.jar`: sirf library (okhttp, moshi, coroutines alag se chahiye)
- `streamx-extractor-1.0-all.jar`: sab kuch ek JAR mein
- `streamx-extractor-1.0-sources.jar`: source

Local: `./gradlew build fatJar` -> `build/libs/`.
Android project mein: `libs/` mein rakho aur `implementation(files("libs/streamx-extractor-1.0-all.jar"))`.

---

## JitPack se use karna

1. GitHub par release/tag banao (jaise `V1.0`): repo > Releases > Create a new release.
2. https://jitpack.io par repo ka link daalo > "Look up" > us tag ke saamne "Get it". Build log wahin dikhta hai.
3. Project mein:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.streamxap-create:Streamx-extractor:V1.0")
}
```

`jitpack.yml` mein JDK 17 set hai. Pehli baar tag banane par JitPack khud build karta hai; fail ho to "Look up" page par log dekho.
