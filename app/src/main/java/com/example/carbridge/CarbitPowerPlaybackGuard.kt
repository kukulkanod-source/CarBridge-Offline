package com.example.carbridge

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.carbridge.adb.AdbSession

/** Main-thread lease on one already-playing session, never a global volume change. */
internal class CarbitPowerPlaybackGuard(private val context:Context) {
    private val main=Handler(Looper.getMainLooper())
    private var held:MediaController?=null
    private var callback:MediaController.Callback?=null
    private var notBefore=0L
    private var pausedAck=false
    private var revision=0L
    @Volatile var pending=false
        private set
    private fun sessions():List<MediaController> = context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(ComponentName(context,HuMediaNotificationListener::class.java))
    fun pauseForTransition() {
        if(pending) { notBefore=SystemClock.elapsedRealtime()+1500L;return }
        if(context.getSystemService(AudioManager::class.java).mode!=AudioManager.MODE_NORMAL)return
        val target=runCatching { sessions().firstOrNull { it.playbackState?.state==PlaybackState.STATE_PLAYING } }
            .onFailure { SessionDiagnostics.event("CarbitLink POWER_AUDIO media session access unavailable: ${it.javaClass.simpleName}") }
            .getOrNull() ?: run { SessionDiagnostics.event("CarbitLink POWER_AUDIO no accessible playing session to pause");return }
        val actions=target.playbackState?.actions ?: return
        if(actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)==0L)return
        held=target;pending=true;pausedAck=false;notBefore=SystemClock.elapsedRealtime()+1500L
        val lease=++revision
        val observer=object:MediaController.Callback() {
            override fun onSessionDestroyed() { if(revision==lease && held===target)cancel("player session ended") }
            override fun onPlaybackStateChanged(state:PlaybackState?) {
                if(revision!=lease || held!==target)return
                when(state?.state) {
                    PlaybackState.STATE_PAUSED -> pausedAck=true
                    PlaybackState.STATE_PLAYING -> if(pausedAck)cancel("player resumed independently")
                    PlaybackState.STATE_STOPPED,PlaybackState.STATE_ERROR -> cancel("player stopped")
                }
            }
        }
        callback=observer
        runCatching {
            target.registerCallback(observer,main)
            target.transportControls.pause()
            SessionDiagnostics.event("CarbitLink POWER_AUDIO pause requested; package=${target.packageName}")
        }.onFailure { cancel("pause unavailable") }
        main.postDelayed({ if(revision==lease && held===target)cancel("20s deadline; manual play required") },20_000L)
    }
    /** Invoked only after PCM was accepted by the currently owned HU audio channel. */
    fun resumeIfRouted() {
        val target=held ?: return
        if(SystemClock.elapsedRealtime()<notBefore || !AdbSession.isConnected())return
        if(context.getSystemService(AudioManager::class.java).mode!=AudioManager.MODE_NORMAL) { cancel("call audio mode");return }
        val current=runCatching { sessions() }.getOrNull() ?: run { cancel("session access lost");return }
        if(current.none { it.sessionToken==target.sessionToken } || current.any {
                it.sessionToken!=target.sessionToken && it.playbackState?.state==PlaybackState.STATE_PLAYING
            }) { cancel("active player changed");return }
        val state=target.playbackState ?: return
        if(state.state!=PlaybackState.STATE_PAUSED)return
        if(!pausedAck)pausedAck=true
        if(state.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)==0L) { cancel("play unsupported");return }
        cancel("HU PCM route ready")
        runCatching { target.transportControls.play();SessionDiagnostics.event("CarbitLink POWER_AUDIO resume requested on ready HU route; package=${target.packageName}") }
            .onFailure { SessionDiagnostics.event("CarbitLink POWER_AUDIO resume failed; ${it.javaClass.simpleName}") }
    }
    fun cancel(reason:String) {
        revision++
        val target=held
        held=null;pending=false;pausedAck=false
        callback?.let { observer -> runCatching { target?.unregisterCallback(observer) } }
        callback=null
        if(target!=null)SessionDiagnostics.event("CarbitLink POWER_AUDIO hold ended; reason=$reason")
    }
}
