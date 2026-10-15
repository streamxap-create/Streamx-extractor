package com.streamx.extractor.model

data class PlexLibrary(val key: String, val title: String, val type: String)

/** type: movie, show, season, episode. streamUrl khali ho to browse karo (children), warna chala sakte ho. */
data class PlexItem(
    val ratingKey: String,
    val title: String,
    val type: String,
    val year: Int = 0,
    val summary: String = "",
    val thumbUrl: String = "",
    val durationMs: Long = 0L,
    val streamUrl: String = "",
    val childCount: Int = 0,
    val parentTitle: String = "",
    val index: Int = 0,
    val videoResolution: String = "",
    val container: String = "",
    // PlexDiscoverClient (Plex's free Movies & TV catalog) ke liye: hub/search se mile
    // item ka full-metadata path — abhi streamUrl khali ho to isi key se
    // resolvePlayable() asli stream URL nikal sakta hai. Apne-server wale PlexClient
    // items ke liye ye hamesha khali rehta hai (unka streamUrl pehle se hi bhara hota hai).
    val key: String = "",
    // Free catalog ka content aksar DASH + Widevine DRM se serve hota hai (apna server
    // kabhi DRM use nahi karta — isliye ye dono sirf PlexDiscoverClient items par bharte
    // hain). isDash sach ho to streamUrl ek DASH manifest hai, player ko wahi batana
    // padta hai (extension se guess nahi hoga). licenseUrl khali na ho to Widevine
    // chahiye — player ko DRM config ke saath hi play karna hoga, warna fail hoga.
    val isDash: Boolean = false,
    val licenseUrl: String = ""
) {
    val isPlayable: Boolean get() = streamUrl.isNotEmpty()
    val needsResolve: Boolean get() = streamUrl.isEmpty() && key.isNotEmpty()
}
