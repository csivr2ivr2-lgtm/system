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
            // Step 1: Text Cleansing
            val cleaned = cleanText(processedQuery)
            Log.d(TAG, "Step 1 - Cleaned query: \"$cleaned\" (original processed was: \"$processedQuery\")")

            // Step 2: MusicBrainz Recording Search
            val encodedQuery = URLEncoder.encode(cleaned, "UTF-8")
            val searchUrl = "https://musicbrainz.org/ws/2/recording?query=$encodedQuery&fmt=json&limit=1"

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "VoiceAssistantApp/1.0 (yemot770100@gmail.com)")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Step 2 - MusicBrainz search failed: ${response.code}. Falling back to search.")
                    launchForegroundActivityIntent(processedQuery, context)
                    return
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val recordings = json.optJSONArray("recordings")
                if (recordings == null || recordings.length() == 0) {
                    Log.w(TAG, "Step 2 - No recordings found on MusicBrainz for \"$cleaned\". Falling back to search.")
                    launchForegroundActivityIntent(processedQuery, context)
                    return
                }

                // Step 3: Extract MusicBrainz Artist ID
                val recording = recordings.getJSONObject(0)
                val artistCredit = recording.optJSONArray("artist-credit")
                if (artistCredit == null || artistCredit.length() == 0) {
                    Log.w(TAG, "Step 3 - No artist-credit found in MusicBrainz response. Falling back.")
                    launchForegroundActivityIntent(processedQuery, context)
                    return
                }

                val credit = artistCredit.getJSONObject(0)
                val artistObj = credit.optJSONObject("artist")
                if (artistObj == null) {
                    Log.w(TAG, "Step 3 - No artist object found inside credit. Falling back.")
                    launchForegroundActivityIntent(processedQuery, context)
                    return
                }

                val mbid = artistObj.optString("id") ?: ""
                if (mbid.isEmpty()) {
                    Log.w(TAG, "Step 3 - MBID extracted is empty. Falling back.")
                    launchForegroundActivityIntent(processedQuery, context)
                    return
                }

                Log.d(TAG, "Step 3 - Successfully extracted MusicBrainz Artist ID (MBID): $mbid")

                // Step 4: Retrieve Artist External Connections
                val artistUrlStr = "https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json"
                val artistRequest = Request.Builder()
                    .url(artistUrlStr)
                    .header("User-Agent", "VoiceAssistantApp/1.0 (yemot770100@gmail.com)")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(artistRequest).execute().use { artistResponse ->
                    if (!artistResponse.isSuccessful) {
                        Log.e(TAG, "Step 4 - Artist details request failed: ${artistResponse.code}. Falling back.")
                        launchForegroundActivityIntent(processedQuery, context)
                        return
                    }

                    val artistBodyStr = artistResponse.body?.string() ?: ""
                    val artistJson = JSONObject(artistBodyStr)
                    val relations = artistJson.optJSONArray("relations")
                    if (relations == null || relations.length() == 0) {
                        Log.w(TAG, "Step 4 - No relation data found for artist $mbid. Falling back.")
                        launchForegroundActivityIntent(processedQuery, context)
                        return
                    }

                    // Step 5: Find Spotify URL
                    var spotifyUrl: String? = null
                    for (i in 0 until relations.length()) {
                        val rel = relations.getJSONObject(i)
                        val type = rel.optString("type")
                        if (type == "spotify") {
                            val urlRelObj = rel.optJSONObject("url")
                            if (urlRelObj != null) {
                                val resourceUrl = urlRelObj.optString("resource")
                                if (resourceUrl.isNotEmpty()) {
                                    spotifyUrl = resourceUrl
                                    break
                                }
                            }
                        }
                    }

                    if (spotifyUrl.isNullOrEmpty()) {
                        Log.w(TAG, "Step 5 - No Spotify URL listed in artist relations. Falling back.")
                        launchForegroundActivityIntent(processedQuery, context)
                        return
                    }

                    Log.d(TAG, "Step 5 - Found Spotify URL resource link: $spotifyUrl")

                    // Step 6: Extract Spotify Artist ID
                    var cleanUrl = spotifyUrl.split("?")[0]
                    if (cleanUrl.endsWith("/")) {
                        cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
                    }
                    val spotifyArtistId = cleanUrl.substringAfterLast("/")
                    if (spotifyArtistId.isEmpty() || spotifyArtistId == "spotify" || spotifyArtistId.contains("open.spotify")) {
                        Log.w(TAG, "Step 6 - Extracted Artist ID \"$spotifyArtistId\" appears invalid. Falling back.")
                        launchForegroundActivityIntent(processedQuery, context)
                        return
                    }

                    Log.d(TAG, "Step 6 - Extracted Spotify Artist ID: $spotifyArtistId")

                    // Step 7: Build Android Deep Link URI
                    val androidUri = "spotify:artist:$spotifyArtistId"
                    Log.d(TAG, "Step 7 - Final target Android URI built: $androidUri")

                    // Step 8: Open in Android directly to artist page
                    openSpotifyArtistUri(androidUri, spotifyUrl, context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during MusicBrainz sequence calculation, falling back to search", e)
            launchForegroundActivityIntent(processedQuery, context)
        }
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
