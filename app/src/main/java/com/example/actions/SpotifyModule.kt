package com.example.actions

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.FormBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SpotifyModule {
    private const val TAG = "SpotifyModule"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Structured concurrency background scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Entry point for music voice command processing.
     * Starts an asynchronous background coroutine to resolve and play the target track using Spotify's on-device media player integration.
     */
    fun play(song: String, context: Context): String {
        val trimmedSong = song.trim()

        scope.launch {
            try {
                Log.d(TAG, "Parsing voice music command query: $trimmedSong")

                // Step 1: Query correction using Gemini LLM API (if configured)
                val correctedSongAI = correctSongWithAI(trimmedSong)
                Log.d(TAG, "Gemini correction output: $correctedSongAI")

                // Step 2: Extract canon metadata using FuzzyMatcher
                val correctedSong = FuzzyMatcher.findBestCanonicalSong(correctedSongAI)
                Log.d(TAG, "Fuzzy Matcher resolved canonical name: $correctedSong")

                val finalQuery = if (correctedSong.isNotEmpty()) correctedSong else if (correctedSongAI.isNotEmpty()) correctedSongAI else trimmedSong

                // Step 3: Try background URI resolution and remote command play first
                Log.d(TAG, "Attempting background URI resolution for: $finalQuery")
                val resolvedUri = resolveSpotifyUri(finalQuery)

                if (resolvedUri != null) {
                    Log.d(TAG, "Background URI resolved: $resolvedUri. Launching via Spotify App Remote command...")
                    SpotifyRemoteControl.connectAndExecute(context, { remote ->
                        remote.playerApi.play(resolvedUri)
                        Log.d(TAG, "Dispatched play command for $resolvedUri on Spotify App Remote")
                    }, { error ->
                        Log.e(TAG, "Background App Remote playback connection failed, trying fallback to search intent", error)
                        launchStandardSearchIntent(finalQuery, context)
                    })
                } else {
                    Log.w(TAG, "Failed resolving background URI. Falling back to platform media search intent.")
                    launchStandardSearchIntent(finalQuery, context)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed executing Spotify playback flow, falling back to original query", e)
                launchStandardSearchIntent(trimmedSong, context)
            }
        }

        return "שולח בקשת השמעה בספוטיפיי עבור $trimmedSong"
    }

    /**
     * Corrects music title queries with Gemini 3.5 Flash API
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

    /**
     * Launch standard media play action (MediaStore Voice Actions API) targeting Spotify
     */
    private fun launchForegroundActivityIntent(query: String, context: Context, artist: String, title: String) {
        try {
            val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage("com.spotify.music")
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                putExtra("android.intent.extra.focus", "vnd.android.cursor.item/audio")
                putExtra(android.app.SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
                putExtra("android.intent.extra.title", title)
                if (artist.isNotEmpty()) {
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                    putExtra("android.intent.extra.artist", artist)
                }
                putExtra("android.intent.extra.playall", true)
                putExtra("android.intent.extra.FROM_SEARCH", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(mediaIntent)
            Log.d(TAG, "Successfully launched Spotify Media Intent in foreground for: $query")
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching Intent package for Spotify in foreground", e)
        }
    }

    /**
     * Launch search action targeting Spotify: tries MediaBrowser background-playback connection first,
     * so user is not kicked out of WhatsApp / current active foreground application.
     */
    private fun launchStandardSearchIntent(query: String, context: Context) {
        val parts = query.split(" - ", limit = 2)
        val artist = if (parts.size > 1) parts[0].trim() else ""
        val title = if (parts.size > 1) parts[1].trim() else query.trim()

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            try {
                Log.d(TAG, "Attempting background playback via MediaBrowser service connection for: $query")
                val componentName = ComponentName(
                    "com.spotify.music",
                    "com.spotify.music.legacy.media.browser.SpotifyMediaBrowserService"
                )

                var fallbackExecuted = false
                var mediaBrowser: MediaBrowser? = null

                val fallbackRunnable = Runnable {
                    if (!fallbackExecuted) {
                        fallbackExecuted = true
                        Log.d(TAG, "MediaBrowser connection timeout. Falling back to foreground.")
                        try {
                            mediaBrowser?.disconnect()
                        } catch (e: Exception) {}
                        launchForegroundActivityIntent(query, context, artist, title)
                    }
                }

                val connectionCallback = object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        mainHandler.removeCallbacks(fallbackRunnable)
                        if (fallbackExecuted) return
                        fallbackExecuted = true

                        try {
                            val browser = mediaBrowser ?: return
                            val sessionToken = browser.sessionToken
                            val mediaController = MediaController(context, sessionToken)
                            val transportControls = mediaController.transportControls

                            val extras = Bundle().apply {
                                putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                                putString("android.intent.extra.focus", "vnd.android.cursor.item/audio")
                                putString(android.app.SearchManager.QUERY, query)
                                putString(MediaStore.EXTRA_MEDIA_TITLE, title)
                                putString("android.intent.extra.title", title)
                                if (artist.isNotEmpty()) {
                                    putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                                    putString("android.intent.extra.artist", artist)
                                }
                                putBoolean("android.intent.extra.playall", true)
                                putBoolean("android.intent.extra.FROM_SEARCH", true)
                            }

                            // Order background play-from-search
                            transportControls.playFromSearch(query, extras)
                            Log.d(TAG, "Successfully dispatched playFromSearch background command to Spotify MediaBrowserService")

                            // Disconnect cleanly
                            mainHandler.postDelayed({
                                try {
                                    browser.disconnect()
                                } catch (e: Exception) {}
                            }, 500)

                        } catch (e: Exception) {
                            Log.e(TAG, "Exception running background playback; reverting to foreground mode", e)
                            launchForegroundActivityIntent(query, context, artist, title)
                        }
                    }

                    override fun onConnectionFailed() {
                        mainHandler.removeCallbacks(fallbackRunnable)
                        if (fallbackExecuted) return
                        fallbackExecuted = true
                        Log.w(TAG, "Background MediaBrowser connection failed.")
                        launchForegroundActivityIntent(query, context, artist, title)
                    }

                    override fun onConnectionSuspended() {
                        Log.d(TAG, "Background MediaBrowser connection suspended.")
                    }
                }

                mediaBrowser = MediaBrowser(context, componentName, connectionCallback, null)
                mediaBrowser.connect()

                // Set a 1.2-second connection timeout
                mainHandler.postDelayed(fallbackRunnable, 1200)

            } catch (e: Exception) {
                Log.e(TAG, "Failed initiating media browser connection components", e)
                launchForegroundActivityIntent(query, context, artist, title)
            }
        }
    }

    private fun resolveSpotifyUri(query: String): String? {
        val clientId = try { BuildConfig.SPOTIFY_CLIENT_ID } catch (e: Exception) { "" }
            .ifEmpty { "8ae488cd9fe44caa9f0ad9fa1586b251" }
            .let { if (it == "MY_SPOTIFY_CLIENT_ID") "8ae488cd9fe44caa9f0ad9fa1586b251" else it }
        val clientSecret = try { BuildConfig.SPOTIFY_CLIENT_SECRET } catch (e: Exception) { "" }
            .ifEmpty { "6ee5638feb744f88a34681a29094a238" }
            .let { if (it == "MY_SPOTIFY_CLIENT_SECRET") "6ee5638feb744f88a34681a29094a238" else it }

        if (clientId == "MY_SPOTIFY_CLIENT_ID" || clientSecret == "MY_SPOTIFY_CLIENT_SECRET" || clientId.isEmpty() || clientSecret.isEmpty()) {
            Log.w(TAG, "Spotify credentials not fully configured in environment properties. Background search bypassed.")
            return null
        }

        try {
            val tokenUrl = "https://accounts.spotify.com/api/token"
            val tokenBody = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build()

            val tokenRequest = Request.Builder()
                .url(tokenUrl)
                .post(tokenBody)
                .build()

            httpClient.newCall(tokenRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed auth with Spotify accounts server: ${response.code}")
                    return null
                }
                val bodyStr = response.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val token = json.optString("access_token") ?: return null

                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://api.spotify.com/v1/search?q=$encodedQuery&type=track,playlist,album,artist&limit=1"

                val searchRequest = Request.Builder()
                    .url(searchUrl)
                    .get()
                    .header("Authorization", "Bearer $token")
                    .build()

                httpClient.newCall(searchRequest).execute().use { searchResponse ->
                    if (!searchResponse.isSuccessful) {
                        Log.e(TAG, "Failed background Spotify search api: ${searchResponse.code}")
                        return null
                    }
                    val searchStr = searchResponse.body?.string() ?: return null
                    val searchJson = JSONObject(searchStr)

                    // 1. Try Tracks
                    val tracksObj = searchJson.optJSONObject("tracks")
                    val trackItems = tracksObj?.optJSONArray("items")
                    if (trackItems != null && trackItems.length() > 0) {
                        val track = trackItems.getJSONObject(0)
                        val uri = track.optString("uri")
                        if (!uri.isNullOrEmpty()) {
                            Log.d(TAG, "Resolved canonical Track URI via background Web Search: $uri")
                            return uri
                        }
                    }

                    // 2. Try Playlists
                    val playlistsObj = searchJson.optJSONObject("playlists")
                    val playlistItems = playlistsObj?.optJSONArray("items")
                    if (playlistItems != null && playlistItems.length() > 0) {
                        val playlist = playlistItems.getJSONObject(0)
                        val uri = playlist.optString("uri")
                        if (!uri.isNullOrEmpty()) {
                            Log.d(TAG, "Resolved canonical Playlist URI via background Web Search: $uri")
                            return uri
                        }
                    }

                    // 3. Try Albums
                    val albumsObj = searchJson.optJSONObject("albums")
                    val albumItems = albumsObj?.optJSONArray("items")
                    if (albumItems != null && albumItems.length() > 0) {
                        val album = albumItems.getJSONObject(0)
                        val uri = album.optString("uri")
                        if (!uri.isNullOrEmpty()) {
                            Log.d(TAG, "Resolved canonical Album URI via background Web Search: $uri")
                            return uri
                        }
                    }

                    // 4. Try Artists
                    val artistsObj = searchJson.optJSONObject("artists")
                    val artistItems = artistsObj?.optJSONArray("items")
                    if (artistItems != null && artistItems.length() > 0) {
                        val artist = artistItems.getJSONObject(0)
                        val uri = artist.optString("uri")
                        if (!uri.isNullOrEmpty()) {
                            Log.d(TAG, "Resolved canonical Artist URI via background Web Search: $uri")
                            return uri
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background Web API URI search encountered exception", e)
        }
        return null
    }
}
