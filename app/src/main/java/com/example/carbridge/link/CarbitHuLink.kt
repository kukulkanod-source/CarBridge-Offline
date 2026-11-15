package com.example.carbridge.link

import android.content.Context
import android.view.MotionEvent
import com.example.carbridge.SessionDiagnostics
import com.example.carbridge.carbit.*
import java.io.IOException
import java.util.ArrayDeque

/** CarbitLink uses the same virtual-display encoder as CarLife. No second capture source. */
class CarbitHuLink(private val context: Context, private val host: String, private val port: Int) : HuLink {
    @Volatile private var listener: HuLink.Listener? = null
    @Volatile private var session: Session? = null
    @Volatile private var source: EncodedFrames? = null
    @Volatile private var running = false
    @Volatile private var csd = ByteArray(0)
    @Volatile private var captureRecoveryUntil = 0L
    fun isCaptureRecovering(): Boolean = captureRecoveryUntil > 0
    fun beginCaptureRecovery() {
        captureRecoveryUntil = android.os.SystemClock.elapsedRealtime()+30_000L
        session?.beginCaptureRecovery()
        csd = ByteArray(0)
        source?.resetForRecovery()
    }
    fun endCaptureRecovery() {
        if(captureRecoveryUntil>0) SessionDiagnostics.event("CarbitLink POWER_RECOVERY video resumed; receiver session retained")
        captureRecoveryUntil=0; session?.endCaptureRecovery()
    }
    @Volatile private var calibrationInput: ((Int,Int,Int,Int)->Unit)? = null
    fun beginCalibration(callback: (Int,Int,Int,Int)->Unit) {
        check(running && source!=null) { "Нет активного видеосеанса CarbitLink" }
        listener?.onTouch(HuLink.TouchEvent(MotionEvent.ACTION_CANCEL,0,0,0))
        calibrationInput=callback
    }
    fun endCalibration() { calibrationInput=null }
    fun saveCalibration(transform: DoubleArray) { checkNotNull(session).saveCalibration(transform) }
    fun clearCalibration() { session?.clearCalibration() }
    override fun start(listener: HuLink.Listener) {
        this.listener = listener
        running = true
        try {
            val journal = Journal(context)
            val s = Session(context, journal, host, port, false, object : BridgeCallbacks {
                override fun dimensions(requestedWidth:Int,requestedHeight:Int):IntArray {
                    val settings=com.example.carbridge.BridgeSettings.read(context)
                    if(settings.logicalDisplayMode==com.example.carbridge.BridgeSettings.DISPLAY_MODE_1920_720) return intArrayOf(1920,720)
                    val size=VideoConfig.size(requestedWidth,requestedHeight)
                    return intArrayOf(size.width(),size.height())
                }
                override fun configure(width: Int, height: Int, encoder: Int): FrameSource {
                    check(encoder == 2) { "CarBridge requires H264" }
                    val frames = EncodedFrames()
                    source = frames
                    listener.onDiagnosticStatus("CarbitLink: виртуальный дисплей "+width+"×"+height)
                    listener.onHuReady(width, height)
                    return frames
                }
                override fun touch(event: Int, x: Int, y: Int, slot: Int, key: Int) {
                    if (!running || slot != 0) {
                        SessionDiagnostics.event("CarbitLink TOUCH_DROP running=$running slot=$slot")
                        return
                    }
                    val action = when(event) {
                        1 -> MotionEvent.ACTION_UP
                        2 -> MotionEvent.ACTION_DOWN
                        3 -> MotionEvent.ACTION_MOVE
                        5 -> MotionEvent.ACTION_CANCEL
                        else -> {
                            SessionDiagnostics.event("CarbitLink TOUCH_UNKNOWN event=$event key=$key")
                            return
                        }
                    }
                    if(action!=MotionEvent.ACTION_MOVE) SessionDiagnostics.event("CarbitLink TOUCH_FORWARD action=$action x=$x y=$y")
                    listener.onTouch(HuLink.TouchEvent(action, 0, x, y))
                }
                override fun rawTouch(event:Int,x:Int,y:Int,slot:Int):Boolean {
                    val callback=calibrationInput ?: return false
                    callback(event,x,y,slot);return true
                }
                override fun disconnected(reason: String) {
                    if (running) {
                        running = false
                        listener.onHuDisconnected("CarbitLink: "+reason)
                    }
                }
            })
            session = s
            s.start()
        } catch (error: Exception) {
            running = false
            listener.onHuDisconnected("CarbitLink: "+error.message)
        }
    }
    override fun stop() {
        running = false
        endCalibration()
        session?.close(); session = null
        source?.close(); source = null
        csd = ByteArray(0)
        listener = null
    }
    override fun sendConfig(csd: ByteArray) { this.csd = csd.copyOf() }
    override fun startMediaAudio(): Boolean = running && session?.startAudio()==true
    override fun sendMediaPcm(pcm: ByteArray): Boolean = running && session?.sendAudio(pcm)==true
    override fun stopMediaAudio() { session?.stopAudio() }
    override fun sendVideoFrame(data: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        if (!running) return
        source?.offer(data, keyFrame)
    }

    private inner class EncodedFrames : FrameSource {
        private val queue = ArrayDeque<ByteArray>()
        private val lock = Object()
        private var queuedBytes = 0
        private var waitingKey = true
        private var closed = false
        private var lastRequest = 0L
        fun resetForRecovery() = synchronized(lock) {
            queue.clear(); queuedBytes=0; waitingKey=true; lock.notifyAll()
        }
        fun offer(data: ByteArray, key: Boolean) {
            var request = false
            synchronized(lock) {
                if (closed) return
                if (queue.size >= 45 || queuedBytes + data.size + csd.size > 4 * 1024 * 1024) {
                    queue.clear(); queuedBytes = 0; waitingKey = true
                    SessionDiagnostics.event("CarbitLink video backlog reset; waiting for IDR")
                }
                if (waitingKey && !key) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now-lastRequest > 1000) { lastRequest=now; request=true }
                } else {
                    val packet = if (key && csd.isNotEmpty()) csd + data else data.copyOf()
                    if(packet.size > 4*1024*1024) return
                    waitingKey = false
                    queue.addLast(packet); queuedBytes += packet.size
                    lock.notifyAll()
                }
            }
            if(request) listener?.onRequestKeyframe()
        }
        override fun next(): ByteArray {
            synchronized(lock) {
                val until=android.os.SystemClock.elapsedRealtime()+20_000
                while(queue.isEmpty() && !closed && running) {
                    val remaining=maxOf(until,captureRecoveryUntil)-android.os.SystemClock.elapsedRealtime()
                    if(remaining<=0) throw IOException("No virtual-display frame in 20 seconds")
                    lock.wait(minOf(remaining,500))
                }
                if(closed || !running) throw IOException("Video source stopped")
                return queue.removeFirst().also { queuedBytes-=it.size }
            }
        }
        override fun close() { synchronized(lock) { closed=true;queue.clear();queuedBytes=0;lock.notifyAll() } }
    }
}
