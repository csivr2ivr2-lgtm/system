package com.example.actions

import android.content.Context
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.example.BuildConfig

object SpotifyRemoteControl {
    private const val TAG = "SpotifyRemoteControl"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    /**
     * Connect to Spotify App Remote and execute the given action.
     */
    fun connectAndExecute(context: Context, action: (SpotifyAppRemote) -> Unit) {
        connectAndExecute(context, action, null)
    }

    fun connectAndExecute(
        context: Context, 
        action: (SpotifyAppRemote) -> Unit, 
        onFailure: ((Throwable) -> Unit)?
    ) {
        val remote = spotifyAppRemote
        if (remote != null && remote.isConnected) {
            action(remote)
            return
        }

        val clientId = try { BuildConfig.SPOTIFY_CLIENT_ID } catch (e: Exception) { "" }
            .ifEmpty { "8ae488cd9fe44caa9f0ad9fa1586b251" }
            .let { if (it == "MY_SPOTIFY_CLIENT_ID") "8ae488cd9fe44caa9f0ad9fa1586b251" else it }
        val redirectUri = try { BuildConfig.SPOTIFY_REDIRECT_URI } catch (e: Exception) { "" }
            .ifEmpty { "jarvisassistant://spotify-callback" }
            .let { if (it == "MY_SPOTIFY_REDIRECT_URI") "jarvisassistant://spotify-callback" else it }

        Log.d(TAG, "Connecting to Spotify App Remote with client_id: $clientId, redirect: $redirectUri")

        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(false) // Disable auth popup on device to avoid blocking flow
            .build()

        SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                Log.d(TAG, "Spotify App Remote Connected!")
                spotifyAppRemote = appRemote
                action(appRemote)
            }

            override fun onFailure(throwable: Throwable) {
                Log.e(TAG, "Spotify App Remote connection failed: " + throwable.message, throwable)
                onFailure?.invoke(throwable)
            }
        })
    }

    /**
     * Pauses the music.
     */
    fun pause(context: Context): String {
        connectAndExecute(context) { remote ->
            remote.playerApi.pause()
            Log.d(TAG, "Paused playback via App Remote")
        }
        return "משהה את המוזיקה בספוטיפיי"
    }

    /**
     * Resumes the music.
     */
    fun resume(context: Context): String {
        connectAndExecute(context) { remote ->
            remote.playerApi.resume()
            Log.d(TAG, "Resumed playback via App Remote")
        }
        return "ממשיך את המוזיקה בספוטיפיי"
    }

    /**
     * Skips to the next track.
     */
    fun skipNext(context: Context): String {
        connectAndExecute(context) { remote ->
            remote.playerApi.skipNext()
            Log.d(TAG, "Skipped to next track via App Remote")
        }
        return "מדלג לשיר הבא"
    }

    /**
     * Skips to the previous track.
     */
    fun skipPrevious(context: Context): String {
        connectAndExecute(context) { remote ->
            remote.playerApi.skipPrevious()
            Log.d(TAG, "חוזר לשיר הקודם")
        }
        return "חוזר לשיר הקודם"
    }

    /**
     * Disconnects from App Remote.
     */
    fun disconnect() {
        spotifyAppRemote?.let {
            if (it.isConnected) {
                SpotifyAppRemote.disconnect(it)
                Log.d(TAG, "Spotify App Remote disconnected")
            }
        }
        spotifyAppRemote = null
    }
}
