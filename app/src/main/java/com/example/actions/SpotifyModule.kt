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

        return "מאתר את האמן של $trimmedSong בספוטיפיי"
    }

    /**
     * Pipeline to clean text, search MusicBrainz, extract Artist MBID, lookup links,
     * find Spotify URL, extract Artist ID, and launch Android Intent.
     */
    private fun executeMusicBrainzPipeline(processedQuery: String, originalQuery: String, context: Context) {
        try {
            var resolvedSpotifyArtistId: String? = null

            // Strategy 1: Search recording using fully cleaned query (keeping text from parenthesized parts)
            val cleanedFull = cleanText(processedQuery)
            Log.d(TAG, "Strategy 1 - Cleaned query (full): \"$cleanedFull\" (original processed was: \"$processedQuery\")")
            resolvedSpotifyArtistId = searchRecordingAndGetArtistSpotifyId(cleanedFull)

            // Strategy 2: Search recording using cleaned query WITHOUT parenthesized contents
            if (resolvedSpotifyArtistId == null) {
                val cleanedWithoutParens = cleanTextWithoutParentheses(processedQuery)
                if (cleanedWithoutParens != cleanedFull && cleanedWithoutParens.isNotEmpty()) {
                    Log.d(TAG, "Strategy 2 - Cleaned query without parentheses: \"$cleanedWithoutParens\"")
                    resolvedSpotifyArtistId = searchRecordingAndGetArtistSpotifyId(cleanedWithoutParens)
                }
            }

            // Strategy 3: Search for primary artist directly by splitting input on delimiters (e.g. '-')
            if (resolvedSpotifyArtistId == null) {
                val segments = processedQuery.split(Regex("[-|/_]"))
                if (segments.isNotEmpty()) {
                    val primaryArtist = segments[0].trim()
                    val cleanedPrimaryArtist = cleanText(primaryArtist)
                    if (cleanedPrimaryArtist.isNotEmpty() && cleanedPrimaryArtist.lowercase() != cleanedFull.lowercase()) {
                        Log.d(TAG, "Strategy 3 - Searching artist directly: \"$cleanedPrimaryArtist\"")
                        resolvedSpotifyArtistId = searchArtistAndGetSpotifyId(cleanedPrimaryArtist)
                    }
                }
            }

            // Strategy 4: Search for secondary artist (e.g. guest artist inside parenthesized expression)
            if (resolvedSpotifyArtistId == null) {
                val secondaryArtist = extractSecondaryArtist(processedQuery)
                if (secondaryArtist != null && secondaryArtist.isNotEmpty()) {
                    val cleanedSecondary = cleanText(secondaryArtist)
                    if (cleanedSecondary.isNotEmpty()) {
                        Log.d(TAG, "Strategy 4 - Searching guest artist directly: \"$cleanedSecondary\"")
                        resolvedSpotifyArtistId = searchArtistAndGetSpotifyId(cleanedSecondary)
                    }
                }
            }

            // Strategy 5: Generic query lookup for any matching artist directly using the entire cleaned text
            if (resolvedSpotifyArtistId == null) {
                if (cleanedFull.isNotEmpty()) {
                    Log.d(TAG, "Strategy 5 - Searching entire cleaned query as artist directly: \"$cleanedFull\"")
                    resolvedSpotifyArtistId = searchArtistAndGetSpotifyId(cleanedFull)
                }
            }

            // Strategy 6: Search using original unresolved query as artist directly
            if (resolvedSpotifyArtistId == null && originalQuery != processedQuery) {
                val cleanedOrig = cleanText(originalQuery)
                if (cleanedOrig.isNotEmpty()) {
                    Log.d(TAG, "Strategy 6 - Searching original query as artist directly: \"$cleanedOrig\"")
                    resolvedSpotifyArtistId = searchArtistAndGetSpotifyId(cleanedOrig)
                }
            }

            if (resolvedSpotifyArtistId != null) {
                val androidUri = "spotify:artist:$resolvedSpotifyArtistId"
                val spotifyUrl = "https://open.spotify.com/artist/$resolvedSpotifyArtistId"
                Log.d(TAG, "Successfully resolved target Spotify Artist ID: $resolvedSpotifyArtistId")
                openSpotifyArtistUri(androidUri, spotifyUrl, context)
            } else {
                Log.w(TAG, "All MusicBrainz strategies failed to find a Spotify Artist ID. Launching fallback generic search.")
                launchForegroundActivityIntent(processedQuery, context)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during MusicBrainz sequence calculation, falling back to search", e)
            launchForegroundActivityIntent(processedQuery, context)
        }
    }

    /**
     * Executes MusicBrainz recording search and queries the primary artist relationships to extract the Spotify ID.
     */
    private fun searchRecordingAndGetArtistSpotifyId(query: String): String? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://musicbrainz.org/ws/2/recording?query=$encodedQuery&fmt=json&limit=3"

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "MyApp/1.0 (cs.ivr2ivr2@gmail.com)")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Recording search failed with response code: ${response.code}")
                    return null
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val recordings = json.optJSONArray("recordings")
                if (recordings != null && recordings.length() > 0) {
                    val limit = minOf(recordings.length(), 3)
                    for (i in 0 until limit) {
                        val recording = recordings.getJSONObject(i)
                        val artistCredit = recording.optJSONArray("artist-credit")
                        if (artistCredit != null && artistCredit.length() > 0) {
                            val credit = artistCredit.getJSONObject(0)
                            val artistObj = credit.optJSONObject("artist")
                            if (artistObj != null) {
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
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in searchRecordingAndGetArtistSpotifyId for query: $query", e)
        }
        return null
    }

    /**
     * Executes MusicBrainz artist search and queries the matching artist's relationships to extract the Spotify ID.
     */
    private fun searchArtistAndGetSpotifyId(artistName: String): String? {
        try {
            val encodedQuery = URLEncoder.encode(artistName, "UTF-8")
            val searchUrl = "https://musicbrainz.org/ws/2/artist?query=$encodedQuery&fmt=json&limit=3"

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "MyApp/1.0 (cs.ivr2ivr2@gmail.com)")
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
            Log.e(TAG, "Error in searchArtistAndGetSpotifyId for artistName: $artistName", e)
        }
        return null
    }

    /**
     * Retrieves internal MusicBrainz relationships for specified MBID and extracts Spotify ID.
     */
    private fun getSpotifyIdFromMbid(mbid: String): String? {
        try {
            val artistUrlStr = "https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json"
            val artistRequest = Request.Builder()
                .url(artistUrlStr)
                .header("User-Agent", "MyApp/1.0 (cs.ivr2ivr2@gmail.com)")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(artistRequest).execute().use { artistResponse ->
                if (!artistResponse.isSuccessful) {
                    Log.e(TAG, "Artist relations lookup failed with response code: ${artistResponse.code}")
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
     * Cleans up raw query string by removing parentheses (keeping internal terms),
     * transforming separators to spaces, stripping speech patterns, and collapsing spaces.
     */
    fun cleanText(input: String): String {
        // Strip out individual brace characters to keep terms inside them (matches user example)
        var text = input.replace("(", " ")
                        .replace(")", " ")
                        .replace("[", " ")
                        .replace("]", " ")

        // Replace typical separation marks with a single blank space
        text = text.replace("-", " ")
                   .replace("|", " ")
                   .replace("/", " ")
                   .replace("_", " ")

        // Pattern matching for typical audio/collaboration tags as distinct words or phrases
        val regex = Regex("(?i)(?:\\s|^)(?:feat\\.?|ft\\.?|featuring|עם)(?:\\s|$)")
        var previousText = ""
        while (text != previousText) {
            previousText = text
            text = text.replace(regex, " ")
        }

        // Merge repeating white spaces to a single blank space and trim bounds
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Cleans up raw query string by fully removing parentheses and parenthesized content.
     */
    fun cleanTextWithoutParentheses(input: String): String {
        val withoutParens = input.replace(Regex("\\([^)]*\\)"), " ")
                                 .replace(Regex("\\[[^]]*\\]"), " ")
        return cleanText(withoutParens)
    }

    /**
     * Attempts to find a secondary / guest artist name from guest symbols or parenthesized texts.
     */
    private fun extractSecondaryArtist(input: String): String? {
        val regex = Regex("\\(([^)]*)\\)")
        val match = regex.find(input)
        if (match != null) {
            val content = match.groupValues[1]
            val cleanedContent = content.replace(Regex("(?i)(?:feat\\.?|ft\\.?|featuring|עם)"), " ").trim()
            if (cleanedContent.isNotEmpty()) {
                return cleanedContent
            }
        }

        val splitWords = listOf(" feat. ", " feat ", " ft. ", " ft ", " featuring ", " עם ")
        for (delimiter in splitWords) {
            if (input.lowercase().contains(delimiter)) {
                val parts = input.split(Regex("(?i)$delimiter"))
                if (parts.size > 1) {
                    val candidate = parts[1].trim()
                    if (candidate.isNotEmpty()) {
                        return candidate
                    }
                }
            }
        }
        return null
    }

    /**
     * Action Intent launch targeting Spotify application to open the specific artist directly.
     */
    private fun openSpotifyArtistUri(targetUri: String, fallbackUrl: String, context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(targetUri)).apply {
                setPackage("com.spotify.music")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Step 8 - Successfully triggered view ACTION_VIEW for Spotify Artist Uri: $targetUri")
        } catch (e: Exception) {
            Log.e(TAG, "Step 8 - Failing direct package launch. Retrying with generic view...", e)
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(targetUri)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
                Log.d(TAG, "Step 8 - Successfully dispatched default view intent for: $targetUri")
            } catch (ex: Exception) {
                Log.e(TAG, "Step 8 - Fully failing custom URI launch, displaying fallback browser link", ex)
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fallbackUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(browserIntent)
                } catch (browserEx: Exception) {
                    Log.e(TAG, "Step 8 - Web browser fallback failed", browserEx)
                }
            }
        }
    }

    /**
     * Standard background/foreground fallback player using search intent (MediaStore Voice Actions API)
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
            Log.d(TAG, "Successfully launched Spotify Media Intent in fallback for: $query")
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching fallback media search intent", e)
        }
    }

    /**
     * Corrects music title queries with Gemini 3.5 Flash API if key is set
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
