package com.example.actions

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URLEncoder

object SpotifyModule {
    private const val TAG = "SpotifyModule"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Entry point for music voice command processing.
     * Starts an asynchronous background coroutine to resolve and play the target track using Spotify.
     */
    fun play(song: String, context: Context): String {
        val trimmedSong = song.trim()

        scope.launch {
            try {
                Log.d(TAG, "Processing Spotify voice action for query: $trimmedSong")

                // Step 1: Query correction using Gemini LLM API (if configured)
                val correctedSongAI = correctSongWithAI(trimmedSong)
                Log.d(TAG, "Gemini correction output: $correctedSongAI")

                // Step 2: Extract canon metadata using FuzzyMatcher
                val correctedSong = FuzzyMatcher.findBestCanonicalSong(correctedSongAI)
                Log.d(TAG, "Fuzzy Matcher resolved canonical name: $correctedSong")

                val finalQuery = if (correctedSong.isNotEmpty()) correctedSong else if (correctedSongAI.isNotEmpty()) correctedSongAI else trimmedSong

                // Start MusicBrainz lookup and intent dispatch pipeline
                executeMusicBrainzPipeline(finalQuery, trimmedSong, context)

            } catch (e: Exception) {
                Log.e(TAG, "Error in Spotify play module pipeline, falling back to foreground search", e)
                launchForegroundActivityIntent(trimmedSong, context)
            }
        }

        return "מאתר את השיר $trimmedSong בספוטיפיי"
    }

    /**
     * Pipeline to clean text, search MusicBrainz recordings, retrieve external relations,
     * extract Spotify URL, parse Spotify URI type & ID, and launch Android Intent.
     */
    private fun executeMusicBrainzPipeline(processedQuery: String, originalQuery: String, context: Context) {
        try {
            Log.d(TAG, "Starting MusicBrainz Recording pipeline. input processedQuery: \"$processedQuery\"")

            // Clean the text using the exact spec rule (keeps parenthesis content for better query resolution)
            val cleanedQuery = cleanTextAccordingToSpec(processedQuery)
            Log.d(TAG, "Cleaned query (keeping parenthetical content): \"$cleanedQuery\"")
            val targetSearchQuery = if (cleanedQuery.isNotEmpty()) cleanedQuery else processedQuery

            // Strategy 1: Search recording using the cleaned query
            var success = false
            var recordings = searchRecordingsOnMusicBrainz(targetSearchQuery)
            if (recordings.isNotEmpty()) {
                success = processRecordings(recordings, targetSearchQuery, context)
            }

            // Strategy 2: Fallback - Search using query with stripped parentheses completely
            if (!success) {
                val cleanedStripped = cleanTextStrippingParentheses(processedQuery)
                if (cleanedStripped != targetSearchQuery && cleanedStripped.isNotEmpty()) {
                    Log.d(TAG, "Strategy 2 - Trying with stripped parentheses query: \"$cleanedStripped\"")
                    recordings = searchRecordingsOnMusicBrainz(cleanedStripped)
                    if (recordings.isNotEmpty()) {
                        success = processRecordings(recordings, cleanedStripped, context)
                    }
                }
            }

            // Strategy 3: Fallback - Search with first segment split via standard separators
            if (!success) {
                val segments = processedQuery.split(Regex("[-|/_]"))
                if (segments.isNotEmpty()) {
                    val primaryPart = cleanTextAccordingToSpec(segments[0])
                    if (primaryPart.isNotEmpty() && primaryPart != targetSearchQuery) {
                        Log.d(TAG, "Strategy 3 - Trying primary segment: \"$primaryPart\"")
                        recordings = searchRecordingsOnMusicBrainz(primaryPart)
                        if (recordings.isNotEmpty()) {
                            success = processRecordings(recordings, primaryPart, context)
                        }
                    }
                }
            }

            // Strategy 4: Fallback - Search directly with original unresolved query
            if (!success && originalQuery != processedQuery) {
                val cleanedOrig = cleanTextAccordingToSpec(originalQuery)
                if (cleanedOrig.isNotEmpty()) {
                    Log.d(TAG, "Strategy 4 - Trying original raw query cleaned: \"$cleanedOrig\"")
                    recordings = searchRecordingsOnMusicBrainz(cleanedOrig)
                    if (recordings.isNotEmpty()) {
                        success = processRecordings(recordings, cleanedOrig, context)
                    }
                }
            }

            // Strategy 5: Ultimate Fallback - Use Artist direct Search if recording search results yielded nothing
            if (!success) {
                Log.d(TAG, "Strategy 5 - Searching Artist directly for: \"$targetSearchQuery\"")
                val artistSpotifyId = searchArtistAndGetSpotifyId(targetSearchQuery)
                if (artistSpotifyId != null) {
                    val artistUri = "spotify:artist:$artistSpotifyId"
                    val artistUrl = "https://open.spotify.com/artist/$artistSpotifyId"
                    Log.d(TAG, "Resolved Spotify Artist ID from direct artist search: $artistSpotifyId")
                    openSpotifyUri(artistUri, artistUrl, context)
                    success = true
                }
            }

            if (!success) {
                Log.w(TAG, "All MusicBrainz lookup strategies failed. Dispatched generic Search/Media player fallbacks.")
                launchForegroundActivityIntent(processedQuery, context)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception in executeMusicBrainzPipeline, falling back...", e)
            launchForegroundActivityIntent(processedQuery, context)
        }
    }

    /**
     * Executes Free Search on MusicBrainz recordings endpoint.
     */
    private fun searchRecordingsOnMusicBrainz(query: String): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://musicbrainz.org/ws/2/recording?query=$encodedQuery&fmt=json&limit=10"

            val request = Request.Builder()
                .url(url)
                // Use the precise User-Agent specifying the email
                .header("User-Agent", "SmartAssistant/1.0 (cs.ivr2ivr2@gmail.com)")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Recording search failed with response code: ${response.code}")
                    return list
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val recordings = json.optJSONArray("recordings")
                if (recordings != null) {
                    for (i in 0 until recordings.length()) {
                        list.add(recordings.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in searchRecordingsOnMusicBrainz of query: $query", e)
        }
        return list
    }

    /**
     * Evaluates recordings in order to look up direct connection links, then launches direct or fallback Spotify URI.
     */
    private fun processRecordings(recordingsList: List<JSONObject>, query: String, context: Context): Boolean {
        // Iterate over the top 5 recording candidates
        val limit = minOf(recordingsList.size, 5)
        for (i in 0 until limit) {
            val record = recordingsList[i]
            val mbid = record.optString("id") ?: ""
            if (mbid.isEmpty()) continue

            Log.d(TAG, "Evaluating Recording MBID: $mbid (Index: $i)")

            // Step 3 & 4 - Get External relations of the Recording itself and find Spotify URLs
            val spotifyInfo = getSpotifyInfoFromRecordingMbid(mbid)
            if (spotifyInfo != null) {
                Log.d(TAG, "Found target Spotify URL relation directly on Recording: ${spotifyInfo.url}")
                openSpotifyUri(spotifyInfo.uri, spotifyInfo.url, context)
                return true
            }

            // Fallback - Retrieve primary Artist relationships from this recording's credits
            val artistCredit = record.optJSONArray("artist-credit")
            if (artistCredit != null && artistCredit.length() > 0) {
                val credit = artistCredit.getJSONObject(0)
                val artistObj = credit.optJSONObject("artist")
                if (artistObj != null) {
                    val artistMbid = artistObj.optString("id") ?: ""
                    if (artistMbid.isNotEmpty()) {
                        Log.d(TAG, "Recording direct link not found. Querying artist credits profiles for MBID: $artistMbid")
                        val artistSpotifyId = getSpotifyIdFromMbid(artistMbid)
                        if (artistSpotifyId != null) {
                            val artistUri = "spotify:artist:$artistSpotifyId"
                            val artistUrl = "https://open.spotify.com/artist/$artistSpotifyId"
                            Log.d(TAG, "Successfully resolved fallback to Spotify Artist ID: $artistSpotifyId")
                            openSpotifyUri(artistUri, artistUrl, context)
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    /**
     * Step 3, 4, & 5 - Query MusicBrainz Recording relationships to find a direct open.spotify.com link.
     */
    private fun getSpotifyInfoFromRecordingMbid(mbid: String): SpotifyInfo? {
        try {
            val url = "https://musicbrainz.org/ws/2/recording/$mbid?inc=url-rels&fmt=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SmartAssistant/1.0 (cs.ivr2ivr2@gmail.com)")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Recording relation lookup failed with code ${response.code}")
                    return null
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val relations = json.optJSONArray("relations")
                if (relations != null) {
                    for (i in 0 until relations.length()) {
                        val rel = relations.getJSONObject(i)
                        val urlObj = rel.optJSONObject("url")
                        if (urlObj != null) {
                            val resourceUrl = urlObj.optString("resource") ?: ""
                            if (resourceUrl.contains("open.spotify.com")) {
                                val info = parseSpotifyUrl(resourceUrl)
                                if (info != null) {
                                    return info
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getSpotifyInfoFromRecordingMbid for recording MBID $mbid", e)
        }
        return null
    }

    /**
     * Executes MusicBrainz artist search and queries matching artist relationships to find Spotify ID.
     */
    private fun searchArtistAndGetSpotifyId(artistName: String): String? {
        try {
            val encodedQuery = URLEncoder.encode(artistName, "UTF-8")
            val searchUrl = "https://musicbrainz.org/ws/2/artist?query=$encodedQuery&fmt=json&limit=3"

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "SmartAssistant/1.0 (cs.ivr2ivr2@gmail.com)")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Artist search failed with response code: ${response.code}")
                    return null
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val artists = json.optJSONArray("artists")
                if (artists != null && artists.length() > 0) {
                    val limit = minOf(artists.length(), 3)
                    for (i in 0 until limit) {
                        val artistObj = artists.getJSONObject(i)
                        val mbid = artistObj.optString("id") ?: ""
                        if (mbid.isNotEmpty()) {
                            val spotifyId = getSpotifyIdFromMbid(mbid)
                            if (spotifyId != null) {
                                return spotifyId
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in searchArtistAndGetSpotifyId for $artistName", e)
        }
        return null
    }

    /**
     * Retrieves artist relations for a specified MBID and extracts Spotify Artist ID.
     */
    private fun getSpotifyIdFromMbid(mbid: String): String? {
        try {
            val artistUrlStr = "https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json"
            val artistRequest = Request.Builder()
                .url(artistUrlStr)
                .header("User-Agent", "SmartAssistant/1.0 (cs.ivr2ivr2@gmail.com)")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(artistRequest).execute().use { artistResponse ->
                if (!artistResponse.isSuccessful) {
                    Log.e(TAG, "Artist relations lookup failed with code ${artistResponse.code}")
                    return null
                }

                val artistBodyStr = artistResponse.body?.string() ?: ""
                val artistJson = JSONObject(artistBodyStr)
                val relations = artistJson.optJSONArray("relations")
                if (relations != null) {
                    for (i in 0 until relations.length()) {
                        val rel = relations.getJSONObject(i)
                        val type = rel.optString("type")
                        if (type == "spotify") {
                            val urlRelObj = rel.optJSONObject("url")
                            if (urlRelObj != null) {
                                val resourceUrl = urlRelObj.optString("resource")
                                if (resourceUrl.isNotEmpty()) {
                                    var cleanUrl = resourceUrl.split("?")[0]
                                    if (cleanUrl.endsWith("/")) {
                                        cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
                                    }
                                    val spotifyArtistId = cleanUrl.substringAfterLast("/")
                                    if (spotifyArtistId.isNotEmpty() && spotifyArtistId != "spotify" && !spotifyArtistId.contains("open.spotify")) {
                                        return spotifyArtistId
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving Spotify ID from MBID: $mbid", e)
        }
        return null
    }

    /**
     * Cleans up raw query string by removing parentheses characters ONLY (preserving logical content inside)
     * as illustrated in the step 1 spec example.
     */
    fun cleanTextAccordingToSpec(input: String): String {
        // Strip out parenthetical bounds to keep their internal content text
        var text = input.replace("(", " ")
                        .replace(")", " ")
                        .replace("[", " ")
                        .replace("]", " ")

        // Replace typical separation marks with a single space
        text = text.replace("-", " ")
                   .replace("|", " ")
                   .replace("/", " ")
                   .replace("_", " ")

        // Strip out words/conjunctions "feat", "feat.", "ft", "ft.", "featuring", "עם" as distinct tokens
        // We use space padding or word boundaries to match cleanly
        val regex = Regex("(?i)\\b(?:feat\\.?|ft\\.?|featuring)\\b|\\s+עם\\s+|^עם\\s+|\\s+עם$")
        text = text.replace(regex, " ")

        // Collapse duplicate spacing and trim bounds
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Cleans up raw query string by fully removing parentheses AND the content inside them.
     */
    fun cleanTextStrippingParentheses(input: String): String {
        var text = input.replace(Regex("\\([^)]*\\)"), " ")
                        .replace(Regex("\\[[^]]*\\]"), " ")

        // Replace typical separation marks with space
        text = text.replace("-", " ")
                   .replace("|", " ")
                   .replace("/", " ")
                   .replace("_", " ")

        // Strip out words/conjunctions "feat", "feat.", "ft", "ft.", "featuring", "עם" as distinct tokens
        val regex = Regex("(?i)\\b(?:feat\\.?|ft\\.?|featuring)\\b|\\s+עם\\s+|^עם\\s+|\\s+עם$")
        text = text.replace(regex, " ")

        // Collapse spacing and trim
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Data structure holding parsed Spotify resources retrieved from external link relations.
     */
    data class SpotifyInfo(
        val type: String,
        val id: String,
        val url: String,
        val uri: String
    )

    /**
     * Robust parser separating host URL paths to extract type, ID, and build Android Uri.
     */
    private fun parseSpotifyUrl(url: String): SpotifyInfo? {
        try {
            var cleanUrl = url.split("?")[0]
            if (cleanUrl.endsWith("/")) {
                cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
            }

            if (cleanUrl.contains("open.spotify.com/")) {
                val pathSegment = cleanUrl.substringAfter("open.spotify.com/")
                val segments = pathSegment.split("/")
                if (segments.size >= 2) {
                    val type = segments[0]
                    val id = segments[1]
                    if (id.isNotEmpty() && (type == "track" || type == "artist" || type == "album" || type == "playlist")) {
                        return SpotifyInfo(
                            type = type,
                            id = id,
                            url = cleanUrl,
                            uri = "spotify:$type:$id"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Spotify URL: $url", e)
        }
        return null
    }

    /**
     * Launches direct Action Uri in Android targeting Spotify application, with robust system-wide web fallbacks.
     */
    private fun openSpotifyUri(targetUri: String, fallbackUrl: String, context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(targetUri)).apply {
                setPackage("com.spotify.music")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Triggered View Intent directly on Spotify Package for: $targetUri")
        } catch (e: Exception) {
            Log.e(TAG, "Direct package launch failed. Launching default systems handlers fallback URI...", e)
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(targetUri)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
                Log.d(TAG, "Dispatched open default handler for Spotify Uri: $targetUri")
            } catch (ex: Exception) {
                Log.e(TAG, "All direct URI launchers failed, displaying standard HTTPS browser fallback link", ex)
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fallbackUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(browserIntent)
                } catch (browserEx: Exception) {
                    Log.e(TAG, "Web browser fallback failed", browserEx)
                }
            }
        }
    }

    /**
     * Foreground Search Intent using standard system-wide query parameters.
     */
    private fun launchForegroundActivityIntent(query: String, context: Context) {
        try {
            val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage("com.spotify.music")
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                putExtra("android.intent.extra.focus", "vnd.android.cursor.item/audio")
                putExtra(android.app.SearchManager.QUERY, query)
                putExtra("android.intent.extra.playall", true)
                putExtra("android.intent.extra.FROM_SEARCH", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(mediaIntent)
            Log.d(TAG, "Launched Foreground Media Play Search fallback for: $query")
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching fallback media search intent", e)
        }
    }

    /**
     * Corrects music title queries with Gemini 3.5 Flash API if key is set.
     */
    private fun correctSongWithAI(query: String): String {
        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Exception) { "" }
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API key is not configured or is a placeholder. Skipping AI correction.")
            return query
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

            val systemPrompt = "אנא תקן וערוך את פקודת החיפוש הבאה לשיר, אלבום, פלייליסט או אמן בספוטיפיי. " +
                    "הפקודה עשויה להכיל שגיאות כתיב, שגיאות זיהוי דיבור (Speech to Text) או מילים מיותרות ומבלבלות. " +
                    "עליך לחלץ ולתקן את שם השיר והמבצע בצורה הקנונית והמדויקת ביותר כדי שחיפוש בספוטיפיי יחזיר את התוצאה הנכונה ביותר. " +
                    "אם הקלט מכיל רק אמן, החזר רק אמן. " +
                    "החזר אך ורק את מחרוזת החיפוש המטוהרת, המתוקנת והמדויקת ביותר לחיפוש (ללא הקדמות, ללא מירכאות, וללא הסברים)."

            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "הטקסט לתיקון הוא: \"$query\"")
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemPrompt)
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = requestJson.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val jsonResponse = JSONObject(responseBody)
                    val candidates = jsonResponse.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val firstPart = parts.getJSONObject(0)
                            val rawText = firstPart.optString("text") ?: ""
                            val cleanedResult = rawText.trim().replace(Regex("[\"']"), "")
                            if (cleanedResult.isNotEmpty() && cleanedResult.lowercase() != "null") {
                                Log.d(TAG, "Gemini AI correction: \"$query\" -> \"$cleanedResult\"")
                                return cleanedResult
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Gemini API request failed with status code ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error communicating with Gemini API for query correction", e)
        }
        return query
    }
}
