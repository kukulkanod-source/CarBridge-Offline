package com.example.carbridge.carbit;

import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** PXC music audio: start 0x20090/91, raw S16_LE 0x60000030, stop 0x200D0/D1.
 * Values recovered from P2CMainService and the receiver's ECAudioInfo, not mirror-video guesses. */
public final class CarbitAudioChannel implements AutoCloseable {
    public static final int MUSIC_DATA=0x60000030;
    private static final int MUSIC_TYPE=4; // EPAudioType MUSIC=8 is serialized as wire type 4.
    public interface Sink { void send(int command, byte[] payload) throws IOException; }
    private record Packet(int command, byte[] payload, long epoch) {}
    private final LinkedBlockingDeque<Packet> queue=new LinkedBlockingDeque<>(16);
    private final Object state=new Object();
    private final Journal log;
    private final Sink sink;
    private final BooleanSupplier connected;
    private final Thread worker;
    private volatile boolean closed, wanted, ready, starting;
    private long epoch, bytes, dropped, lastStats;
    public CarbitAudioChannel(Journal log,Sink sink,BooleanSupplier connected) {
        this.log=log;this.sink=sink;this.connected=connected;
        worker=new Thread(this::writeLoop,"carbit-audio-writer");worker.start();
    }
    public boolean startPlayback() {
        synchronized(state) {
            if(closed||!connected.getAsBoolean())return false;
            if(ready&&wanted)return true;
            if(!starting) {
                wanted=true;starting=true;ready=false;epoch++;
                try {
                    JSONObject config=new JSONObject().put("type",MUSIC_TYPE).put("sampleRate",48000).put("channel",2).put("format",3);
                    if(!control(new Packet(0x20090,config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),epoch))) {
                        wanted=false;starting=false;return false;
                    }
                }catch(Exception e){wanted=false;starting=false;return false;}
            }
            long until=SystemClock.elapsedRealtime()+2000;
            while(starting&&!closed&&connected.getAsBoolean()) {
                long wait=until-SystemClock.elapsedRealtime();if(wait<=0)break;
                try{state.wait(wait);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
            if(ready&&wanted&&!closed)return true;
            wanted=false;starting=false;ready=false;
            log.log("AUDIO_START not acknowledged; video retained; PCM not sent");
            control(new Packet(0x200d0,"{\"type\":4}".getBytes(java.nio.charset.StandardCharsets.UTF_8),epoch));
            return false;
        }
    }
    public boolean onCommand(int command) {
        if(command!=0x20091&&command!=0x200d1)return false;
        synchronized(state) {
            if(command==0x20091&&wanted&&starting){ready=true;starting=false;log.log("AUDIO_START acknowledged; pcm=48000/stereo/s16le; channel=PXC");}
            state.notifyAll();
        }
        return true;
    }
    public boolean sendPcm(byte[] pcm) {
        if(closed||!wanted||!ready||pcm.length==0||pcm.length>10240||pcm.length%4!=0)return false;
        if(!queue.offerLast(new Packet(MUSIC_DATA,pcm.clone(),epoch))) { dropped+=pcm.length;return false; }
        return true;
    }
    public void stopPlayback() {
        synchronized(state) {
            boolean active=wanted||ready||starting;
            wanted=false;ready=false;starting=false;epoch++;
            queue.removeIf(p->p.command()==MUSIC_DATA);
            if(active&&!closed)control(new Packet(0x200d0,"{\"type\":4}".getBytes(java.nio.charset.StandardCharsets.UTF_8),epoch));
            state.notifyAll();
        }
    }
    private boolean control(Packet p) {
        queue.removeIf(old->old.command()==MUSIC_DATA);
        return queue.offerLast(p);
    }
    private void writeLoop() {
        while(!closed)try {
            Packet p=queue.pollFirst(500,TimeUnit.MILLISECONDS);if(p==null)continue;
            if(p.command()==MUSIC_DATA&&(!wanted||!ready||p.epoch()!=epoch))continue;
            if(p.command()==0x20090&&(!wanted||p.epoch()!=epoch))continue;
            sink.send(p.command(),p.payload());
            if(p.command()==MUSIC_DATA) {
                bytes+=p.payload().length;long now=SystemClock.elapsedRealtime();
                if(now-lastStats>=10000){lastStats=now;log.log("AUDIO_SENT pcmBytes="+bytes+" droppedBytes="+dropped+" queue="+queue.size());}
            }
        }catch(InterruptedException e){return;}
        catch(IOException e){
            synchronized(state){wanted=false;ready=false;starting=false;queue.clear();state.notifyAll();}
            log.error("audio PXC write; video retained",e);
        }
    }
    @Override public void close(){synchronized(state){closed=true;wanted=false;ready=false;starting=false;queue.clear();state.notifyAll();}worker.interrupt();}
}
