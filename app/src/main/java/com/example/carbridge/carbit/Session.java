package com.example.carbridge.carbit;
import android.content.*;
import android.net.*;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;

public final class Session implements AutoCloseable {
 final BridgeCallbacks bridge;
 final Context context; final Journal log; final String host; final int port; final boolean jpegOnly;
 final ExecutorService pool=Executors.newCachedThreadPool();
 final List<Closeable> sockets=Collections.synchronizedList(new ArrayList<>());
 final Object lifecycle=new Object();
 volatile boolean running=true, started=false; volatile FrameSource video;
 volatile int fps=10; volatile long frames,bytes,lastWrite;
 volatile long captureRecoveryUntil;
 public void beginCaptureRecovery(){captureRecoveryUntil=SystemClock.elapsedRealtime()+30000;if(touchMapper!=null)touchMapper.resetGesture();}
 public void endCaptureRecovery(){captureRecoveryUntil=0;}
 volatile String status="Запуск"; volatile String huid="";
 volatile OutputStream upstream; KeyPair keys;
 volatile CarbitAudioChannel audio;
 public boolean startAudio(){CarbitAudioChannel a=audio;return a!=null&&a.startPlayback();}
 public boolean sendAudio(byte[] pcm){CarbitAudioChannel a=audio;return a!=null&&a.sendPcm(pcm);}
 public void stopAudio(){CarbitAudioChannel a=audio;if(a!=null)a.stopPlayback();}
 volatile CarbitTouchMapper touchMapper;
 volatile String calibrationKey;
 public void saveCalibration(double[] transform) {
   CarbitTouchMapper mapper=touchMapper;String key=calibrationKey;
   if(mapper==null||key==null||!TouchCalibration.valid(transform))throw new IllegalStateException("No active touch geometry");
   mapper.setCalibration(transform);
   StringJoiner values=new StringJoiner(",");for(double v:transform)values.add(Double.toString(v));
   context.getSharedPreferences("carbit_touch_calibration",0).edit().putString(key,values.toString()).apply();
   log.log("TOUCH_CALIBRATION saved for current peer and video geometry");
 }
 public void clearCalibration() {
   if(calibrationKey!=null)context.getSharedPreferences("carbit_touch_calibration",0).edit().remove(calibrationKey).apply();
   if(touchMapper!=null)touchMapper.setCalibration(null);
 }
 void loadCalibration() {
   try {String saved=context.getSharedPreferences("carbit_touch_calibration",0).getString(calibrationKey,"");
     if(!saved.isEmpty()){String[] fields=saved.split(",");double[] t=new double[fields.length];for(int i=0;i<t.length;i++)t[i]=Double.parseDouble(fields[i]);touchMapper.setCalibration(t);log.log("TOUCH_CALIBRATION loaded");}
   }catch(Exception bad){log.error("Invalid touch calibration ignored",bad);}
 }
 final java.util.concurrent.atomic.AtomicBoolean controlBusy=new java.util.concurrent.atomic.AtomicBoolean(), dataBusy=new java.util.concurrent.atomic.AtomicBoolean();
 public Session(Context c,Journal l,String h,int p,boolean jpeg,BridgeCallbacks callbacks){bridge=callbacks;context=c;log=l;host=h;port=p;jpegOnly=jpeg;}
 interface Work {void run(Socket s)throws Exception;}
 void track(Closeable c)throws IOException{synchronized(sockets){if(!running){c.close();throw new IOException("Session stopped");}if(sockets.size()>=12){c.close();throw new IOException("Too many connections");}sockets.add(c);}}
 public void start(){pool.execute(()->{try{
   initKeys();
   synchronized(lifecycle){if(!running)return;
   audio=new CarbitAudioChannel(log,(command,payload)->{
     OutputStream out=upstream;if(!running||out==null)throw new IOException("PXC upstream unavailable");
     synchronized(out){out.write(Wire.pxc(command,0,payload));out.flush();}
     if(command!=CarbitAudioChannel.MUSIC_DATA)log.log("AUDIO TX "+Integer.toHexString(command));
   },()->running&&upstream!=null);}
   listen(10922,this::pxc);listen(10921,this::control);listen(10920,this::data);
   pool.execute(()->{while(running){try{Thread.sleep(1000);long now=SystemClock.elapsedRealtime();if(lastWrite>0&&now>=captureRecoveryUntil&&now-lastWrite>15000){log.log("ERROR video socket write timeout");close();bridge.disconnected("video socket write timeout");}}catch(InterruptedException e){return;}}});
   status="Ожидание ГУ";log.log("LISTEN 10922 PXC / 10921 control / 10920 video; selected HU="+host+":"+port);
   List<DiscoveryConnector.Route> routes=new ArrayList<>();
   ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
   try {
     for(Network n:cm.getAllNetworks()){
       NetworkCapabilities cap=cm.getNetworkCapabilities(n);
       if(cap==null||!cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
       boolean unrestricted=cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
       LinkProperties lp=cm.getLinkProperties(n);
       log.log("NETWORK wifi="+n+" unrestricted="+unrestricted+" interface="+(lp==null?"?":lp.getInterfaceName())+" addresses="+(lp==null?"?":lp.getLinkAddresses()));
       if(unrestricted)routes.add(new DiscoveryConnector.Route("wifi "+n, n::bindSocket));
       else log.log("NETWORK SKIP restricted "+n);
     }
   }catch(SecurityException denied){log.error("network enumeration",denied);}
   routes.add(new DiscoveryConnector.Route("system-default",socket -> {}));
   Socket s=DiscoveryConnector.connect(routes,new InetSocketAddress(host,port),()->running,this::track,sockets::remove,log::log);
   log.log("DISCOVERY local="+s.getLocalAddress().getHostAddress());
   send(s.getOutputStream(),0x70000010,json("phoneType","Android","packageName",context.getPackageName()));
   Wire.Packet response=Wire.pxc(s.getInputStream());logPacket("DISCOVERY RX",response);
   if(response.command()!=0x70000011)throw new IOException("Unexpected discovery response");
   boolean available=new JSONObject(new String(response.body(),StandardCharsets.UTF_8)).optBoolean("status",false);
   log.log("HU available="+available); if(!available)status="ГУ занят / отклонил запрос";
   s.close();sockets.remove(s);
 }catch(Exception e){log.error("start/discovery",e);status="Ошибка запуска: "+e.getMessage();close();bridge.disconnected(status);}});}
 void listen(int port,Work work)throws Exception{
   ServerSocket server=new ServerSocket();track(server);server.setReuseAddress(true);server.bind(new InetSocketAddress(port));
   pool.execute(()->{while(running)try{Socket s=server.accept();if(!s.getInetAddress().getHostAddress().equals(host)){log.log("REJECT peer "+s.getInetAddress());s.close();continue;}
     java.util.concurrent.atomic.AtomicBoolean busy=port==10921?controlBusy:port==10920?dataBusy:null;
     if(busy!=null&&!busy.compareAndSet(false,true)){log.log("REJECT duplicate channel "+port);s.close();continue;}
     track(s);s.setTcpNoDelay(true);s.setSoTimeout(45000);pool.execute(()->{log.log("ACCEPT port="+port);try{work.run(s);}catch(Exception e){if(running){log.error("port "+port,e);status="Канал "+port+" закрыт: "+e.getClass().getSimpleName();bridge.disconnected(status);}}finally{try{s.close();}catch(Exception ignored){}sockets.remove(s);if(busy!=null)busy.set(false);}});
   }catch(Exception e){if(running)log.error("listener "+port,e);break;}});
 }
 static byte[] json(Object... pairs)throws JSONException{JSONObject j=new JSONObject();for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);return j.toString().getBytes(StandardCharsets.UTF_8);}
 void send(OutputStream out,int cmd,byte[] body)throws IOException {synchronized(out){out.write(Wire.pxc(cmd,0,body));out.flush();}log.log(String.format(Locale.US,"PXC TX %08x bytes=%d",cmd,body.length));}
 void logPacket(String label,Wire.Packet p){log.log(String.format(Locale.US,"%s %08x bytes=%d",label,p.command(),p.body().length));}
 void pxc(Socket s)throws Exception{
   OutputStream out=s.getOutputStream();
   while(running){Wire.Packet p=Wire.pxc(s.getInputStream());logPacket("PXC RX",p);int cmd=p.command();
     if(audio!=null&&audio.onCommand(cmd))continue;
     if(cmd==0x10000||cmd==0x20000){send(out,cmd+1,new byte[0]);if(cmd==0x20000){s.setSoTimeout(0);upstream=out;if(!huid.isEmpty())foreground();}}
     else if(cmd==0x10010){
       JSONObject request=new JSONObject(new String(p.body(),StandardCharsets.UTF_8));
       huid=request.optString("HUID",request.optString("huid",""));if(huid.isEmpty())throw new IOException("ClientInfo missing HUID; keys="+request.names());
       if(huid.length()>100)throw new IOException("HUID too long");
       log.log("CLIENT_INFO keys="+request.names()+" HUID SHA256="+fingerprint(huid)+" channel="+request.optInt("channel")+" flavor="+request.optInt("flavor"));
       Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.ENCRYPT_MODE,keys.getPrivate());
       String echo=Base64.encodeToString(cipher.doFinal(huid.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
       send(out,0x10011,json("pxcVersion","1.0.2","phoneUUID",context.getSharedPreferences("carbit_identity",0).getString("uuid",""),"phoneBrand",Build.BRAND,"phoneModel",Build.MODEL,
         "phoneOsVersion",""+Build.VERSION.SDK_INT,"phoneOs","Android","package",context.getPackageName(),"versionCode",73,"token",0,
         "pubkey",Base64.encodeToString(keys.getPublic().getEncoded(),Base64.NO_WRAP),"encryptedHUID",echo,"bluetoothName","Carbit Probe","supportH264IFrame",true,"supportFunction",0));
       status="Идентификатор передан";foreground();
     }else if(cmd==0x103b0||cmd==0x103c0||cmd==0x103d0||cmd==0x103e0){
       log.log("LICENSE_REQUIRED cmd="+Integer.toHexString(cmd)+" backend NOT implemented; no success response");
       status="ГУ запросил лицензию — см. журнал";send(out,cmd+1,new byte[0]);
     }else if(cmd==0x10770){log.log("HW_AUTH "+safeState(p.body()));send(out,cmd+1,new byte[0]);}
     else if(cmd==0x70000020){send(out,cmd+1,new byte[0]);}
     else if((cmd&1)==1){log.log("PXC reply received");}
     else {log.log("PXC_UNIMPLEMENTED "+Integer.toHexString(cmd));send(out,0x80000010,new byte[0]);}
   }
 }
 String safeState(byte[] b){try{return "state="+new JSONObject(new String(b,StandardCharsets.UTF_8)).optInt("state",-1);}catch(Exception e){return "invalid JSON";}}
 volatile int touchWidth=1280,touchHeight=800;
 void foreground()throws Exception{OutputStream out=upstream;if(out!=null)send(out,0x20020,json("displayRotation",1,"width",touchWidth,"height",touchHeight,"enableAccessibility",true,"enableAOAHid",false));}
 void reply(OutputStream out,int cmd,byte[] body)throws IOException{out.write(Wire.mirror(cmd,body));out.flush();log.log("MIRROR TX "+Integer.toHexString(cmd)+" bytes="+body.length);}
 void control(Socket s)throws Exception{
   OutputStream out=s.getOutputStream();
   while(running){Wire.Packet p=Wire.mirror(s.getInputStream());int cmd=p.command();if(cmd!=0x40&&cmd!=0x20)logPacket("MIRROR RX",p);
     switch(cmd){
       case 0x30:reply(out,0x31,Wire.ints(5002,1));break;
       case 0x40:reply(out,0x41,new byte[0]);break;
       case 0x10:configure(out,p.body());break;
       case 0x60:reply(out,0x61,json("supportFunction",0));break;
       case 0x70:if(video==null){reply(out,0x7f04,new byte[0]);break;}started=true;reply(out,0x71,new byte[0]);status="ГУ запустил видео";break;
       case 0x20:if(p.body().length<10)log.log("TOUCH_BAD_LENGTH bytes="+p.body().length);if(p.body().length>=10){
         ByteBuffer b=Wire.le(p.body());int event=b.getShort()&65535,x=b.getShort(),y=b.getShort(),slot=b.getShort()&65535,key=b.getShort()&65535;
         if(bridge.rawTouch(event,x,y,slot))break;
         CarbitTouchMapper mapper=touchMapper;CarbitTouchMapper.Touch mapped=mapper==null?null:mapper.accept(event,x,y,slot);
         if(event!=3)log.log("TOUCH_RX event="+event+" x="+x+" y="+y+" slot="+slot+" mapped="+(mapped==null?"drop":mapped.x()+","+mapped.y()));
         if(mapped!=null)bridge.touch(mapped.event(),mapped.x(),mapped.y(),slot,key);
       }break;
       default:log.log("MIRROR_UNIMPLEMENTED "+Integer.toHexString(cmd));reply(out,0x7f02,new byte[0]);
     }
   }
 }
 synchronized void configure(OutputStream out,byte[] body)throws Exception{
   if(video!=null){reply(out,0x7f02,new byte[0]);log.log("Reconfigure: stop and reconnect; active touch geometry retained");return;}
   if(body.length<32){reply(out,0x7f04,new byte[0]);return;}
   ByteBuffer b=Wire.le(body);int w=b.getShort()&65535,h=b.getShort()&65535,requestedFps=b.getInt(),wanted=b.getInt(),supported=b.getInt();
   b.getShort();b.getShort();int bitrate=b.getInt();
   log.log("CONFIG "+w+"x"+h+" fps="+requestedFps+" encoder="+wanted+" supported="+supported+" bitrate="+bitrate+" flags="+Arrays.toString(Arrays.copyOfRange(body,24,32)));
   String encrypted=new String(body,32,body.length-32,StandardCharsets.UTF_8).replace("\u0000","").trim();
   try{Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.DECRYPT_MODE,keys.getPublic());String got=new String(cipher.doFinal(Base64.decode(encrypted,Base64.DEFAULT)),StandardCharsets.UTF_8);
     if(huid.isEmpty()||!huid.equals(got))throw new GeneralSecurityException("HUID mismatch");
   }catch(Exception e){log.error("HUID verification",e);reply(out,0x7f03,new byte[0]);return;}
   log.log("HUID_ECHO verified selected peer");
   int enc=VideoConfig.encoder(wanted,supported,false);
   if(enc!=2){reply(out,0x7f02,new byte[0]);log.log("Unsupported encoder request wanted="+wanted+" supported="+supported);return;}
   if(jpegOnly&&enc!=1)log.log("JPEG preference unavailable; using requested H264");
   try{int[] selected=bridge.dimensions(w,h);
     if(selected.length!=2||selected[0]<96||selected[1]<96||selected[0]>1920||selected[1]>1920||(long)selected[0]*selected[1]>2073600||selected[0]%16!=0||selected[1]%16!=0)throw new IllegalArgumentException("Invalid capture dimensions");
     VideoConfig.Size size=new VideoConfig.Size(selected[0],selected[1]);
     log.log("VIDEO_NEGOTIATED requested="+w+"x"+h+" actual="+size.width()+"x"+size.height()+" encoder="+enc+" preferJPEG="+jpegOnly);
     touchMapper=new CarbitTouchMapper(w,h,size.width(),size.height());
     calibrationKey=fingerprint(huid)+"_"+w+"x"+h+"_"+size.width()+"x"+size.height();loadCalibration();
     log.log("TOUCH_GEOMETRY input="+touchMapper.inputWidth+"x"+touchMapper.inputHeight+" output="+size.width()+"x"+size.height());
     w=size.width();h=size.height();touchWidth=w;touchHeight=h;foreground();
   }catch(IllegalArgumentException invalid){log.error("video dimensions",invalid);reply(out,0x7f04,new byte[0]);return;}
   if(requestedFps<0){log.log("Invalid requested fps="+requestedFps);reply(out,0x7f04,new byte[0]);return;}
   if(video!=null){reply(out,0x7f02,new byte[0]);log.log("Reconfigure: stop and reconnect");return;}
   try{video=bridge.configure(w,h,enc);}catch(Exception e){log.error("encoder init",e);reply(out,0x7f05,new byte[0]);return;}
   fps=requestedFps==0?0:Math.min(10,requestedFps);
   byte[] response=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(enc).putShort((short)w).putShort((short)h).array();
   reply(out,0x11,response);status="Видео настроено "+w+"×"+h;
 }
 void data(Socket s)throws Exception{
   Wire.Packet first=Wire.mirror(s.getInputStream());logPacket("DATA RX",first);if(first.command()!=0x72)throw new IOException("Expected data request 0x72");
   long deadline=SystemClock.elapsedRealtime()+20000;
   while(running&&(!started||video==null)&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
   if(video==null||!started)throw new IOException("No capture config/start within 20s");
   if(fps>0){s.setSoTimeout(0);}
   if(fps>0)pool.execute(()->{try{while(running){Wire.Packet p=Wire.mirror(s.getInputStream());if(p.command()!=0x72)logPacket("DATA extra",p);}}catch(Exception e){try{s.close();}catch(Exception ignored){}}});
   OutputStream out=s.getOutputStream();
   do{
     byte[] frame=video.next();lastWrite=SystemClock.elapsedRealtime();out.write(Wire.ints(frame.length));out.write(frame);out.flush();lastWrite=0;frames++;bytes+=frame.length;
     if(frames==1||frames%50==0)log.log("VIDEO_SENT frames="+frames+" bytes="+bytes+" latest="+frame.length+" (display unconfirmed)");
     status="Отправлено кадров: "+frames+" • "+(bytes/1024)+" КБ (показ не подтверждён)";
     if(fps==0){Wire.Packet p=Wire.mirror(s.getInputStream());if(p.command()!=0x72)throw new IOException("Expected next-frame request");}
     else Thread.sleep(1000/fps);
   }while(running);
 }
 void initKeys()throws Exception{
   SharedPreferences p=context.getSharedPreferences("identity",0);String priv=p.getString("private",null),pub=p.getString("public",null);
   if(priv!=null&&pub!=null){KeyFactory f=KeyFactory.getInstance("RSA");keys=new KeyPair(f.generatePublic(new X509EncodedKeySpec(Base64.decode(pub,0))),f.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv,0))));}
   else{KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(1024);keys=g.generateKeyPair();p.edit().putString("private",Base64.encodeToString(keys.getPrivate().getEncoded(),2)).putString("public",Base64.encodeToString(keys.getPublic().getEncoded(),2)).putString("uuid",UUID.randomUUID().toString()).apply();}
 }
 static String fingerprint(String text)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(int i=0;i<6;i++)s.append(String.format("%02x",hash[i]));return s.toString();}
 @Override public void close(){CarbitAudioChannel a;synchronized(lifecycle){if(!running)return;running=false;a=audio;audio=null;}if(a!=null)a.close();log.log("STOP frames="+frames+" bytes="+bytes);synchronized(sockets){for(Closeable c:sockets)try{c.close();}catch(Exception ignored){}sockets.clear();}pool.shutdownNow();FrameSource v=video;video=null;if(v!=null)v.close();}
}
