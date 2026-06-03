package com.example.actions

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

object FuzzyMatcher {

    // Matches speech-to-text queries against public iTunes and Deezer song indexes.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class TrackCandidate(
        val title: String,
        val artist: String,
        val album: String,
        val score: Double
    )

    /**
     * Resolves complex phonetic queries, speech-to-text mismatches, and multi-lingual
     * inaccuracies by looking up real track repositories and analyzing similarity using local Jaro-Winkler.
     */
    fun findBestCanonicalSong(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return query

        Log.d("FuzzyMatcher", "Applying lightweight matching engine on: $trimmed")
        try {
            val candidates = fetchCandidates(trimmed)
            if (candidates.isEmpty()) {
                Log.d("FuzzyMatcher", "No candidates found via public index lookup. Defaulting to raw query.")
                return trimmed
            }

            // Sort by score descending; if tied, prefer full exact track title matches.
            val sortedCandidates = candidates.sortedWith(
                compareByDescending<TrackCandidate> { it.score }
                    .thenBy {
                        val isExactTitle = it.title.equals(trimmed, ignoreCase = true)
                        val isExactFull = "${it.artist} - ${it.title}".equals(trimmed, ignoreCase = true)
                        if (isExactTitle || isExactFull) 0 else 1
                    }
            )

            val bestCandidate = sortedCandidates.firstOrNull()
            val minThreshold = 0.75

            if (bestCandidate == null || bestCandidate.score < minThreshold) {
                Log.d("FuzzyMatcher", "Best candidate is null or score (${bestCandidate?.score}) is below threshold ($minThreshold). Returning original query: $trimmed")
                return trimmed
            }

            val canonicalName = "${bestCandidate.artist} - ${bestCandidate.title}"
            Log.d("FuzzyMatcher", "Resolved to canonical name: \"$canonicalName\" with score: ${bestCandidate.score}")
            return canonicalName
        } catch (e: Exception) {
            Log.e("FuzzyMatcher", "Error in string similarity resolution pipeline", e)
        }
        return trimmed
    }

    /**
     * Fetches metadata candidates from multiple free open indexes (iTunes & Deezer) to increase robustness
     */
    private fun fetchCandidates(query: String): List<TrackCandidate> {
        val candidatesList = mutableListOf<TrackCandidate>()
        
        // Parallel queries to iTunes and Deezer
        fetchFromITunes(query, candidatesList)
        fetchFromDeezer(query, candidatesList)

        return candidatesList
    }

    private fun fetchFromITunes(query: String, list: MutableList<TrackCandidate>) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encoded&media=music&limit=6"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    val json = JSONObject(bodyString)
                    val results = json.optJSONArray("results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val item = results.getJSONObject(i)
                            val title = item.optString("trackName") ?: ""
                            val artist = item.optString("artistName") ?: ""
                            val album = item.optString("collectionName") ?: ""
                            
                            if (title.isNotEmpty()) {
                                val score = calculateMatchingScore(query, title, artist)
                                list.add(TrackCandidate(title, artist, album, score))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FuzzyMatcher", "iTunes fetch failed", e)
        }
    }

    private fun fetchFromDeezer(query: String, list: MutableList<TrackCandidate>) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.deezer.com/search?q=$encoded&limit=6"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    val json = JSONObject(bodyString)
                    val data = json.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)
                            val title = item.optString("title") ?: ""
                            val artistObj = item.optJSONObject("artist")
                            val artist = artistObj?.optString("name") ?: ""
                            val albumObj = item.optJSONObject("album")
                            val album = albumObj?.optString("title") ?: ""
                            
                            if (title.isNotEmpty()) {
                                val score = calculateMatchingScore(query, title, artist)
                                list.add(TrackCandidate(title, artist, album, score))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FuzzyMatcher", "Deezer fetch failed", e)
        }
    }

    /**
     * Calculates semantic/spelling similarity between the query and track components
     */
    private fun calculateMatchingScore(query: String, title: String, artist: String): Double {
        val q = query.lowercase().trim()
        val t = title.lowercase().trim()
        val a = artist.lowercase().trim()

        val fullCombined = "$a - $t"
        val altCombined = "$t $a"

        // Direct containment checks are extremely strong signals
        if (q == t || q == fullCombined || q == altCombined) return 1.0
        if (t.contains(q) || q.contains(t)) return 0.85

        // Let's compute several similarity configurations using local Jaro-Winkler
        val s1 = jaroWinklerSimilarity(q, t)
        val s2 = jaroWinklerSimilarity(q, a)
        val s3 = jaroWinklerSimilarity(q, fullCombined)
        val s4 = jaroWinklerSimilarity(q, altCombined)

        return max(max(s1, s2), max(s3, s4))
    }

    /**
     * Local high-performance implementation of the Jaro-Winkler distance algorithm.
     * Computes similarity index from 0.0 (unrelated) to 1.0 (exact match).
     */
    fun jaroWinklerSimilarity(s1: String, s2: String): Double {
        val str1 = s1.trim().lowercase()
        val str2 = s2.trim().lowercase()

        if (str1 == str2) return 1.0
        if (str1.isEmpty() || str2.isEmpty()) return 0.0

        val len1 = str1.length
        val len2 = str2.length

        val matchWindow = max(0, (max(len1, len2) / 2) - 1)

        val hashS1 = BooleanArray(len1)
        val hashS2 = BooleanArray(len2)

        var matches = 0.0

        for (i in 0 until len1) {
            val start = max(0, i - matchWindow)
            val end = min(len2, i + matchWindow + 1)
            for (j in start until end) {
                if (!hashS2[j] && str1[i] == str2[j]) {
                    hashS1[i] = true
                    hashS2[j] = true
                    matches++
                    break
                }
            }
        }

        if (matches == 0.0) return 0.0

        var transpositions = 0.0
        var k = 0
        for (i in 0 until len1) {
            if (hashS1[i]) {
                while (k < len2 && !hashS2[k]) {
                    k++
                }
                if (k < len2) {
                    if (str1[i] != str2[k]) {
                        transpositions++
                    }
                    k++
                }
            }
        }

        val jaro = (matches / len1 + matches / len2 + (matches - transpositions / 2.0) / matches) / 3.0

        // Winkler prefix scaling (allows up to 4 prefix char match adjustment)
        var prefixLength = 0
        val maxPrefix = min(4, min(len1, len2))
        for (i in 0 until maxPrefix) {
            if (str1[i] == str2[i]) {
                prefixLength++
            } else {
                break
            }
        }

        return jaro + prefixLength * 0.1 * (1.0 - jaro)
    }
}
