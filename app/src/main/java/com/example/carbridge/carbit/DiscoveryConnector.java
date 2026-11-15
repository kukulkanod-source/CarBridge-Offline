package com.example.carbridge.carbit;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.*;
public final class DiscoveryConnector {
 public interface Binder { void bind(Socket socket) throws IOException; }
 public record Route(String name, Binder binder) {}
 public interface Tracker { void track(Socket socket) throws IOException; }
 public static Socket connect(List<Route> routes, InetSocketAddress target, BooleanSupplier running,
     Tracker track, Consumer<Socket> forget, Consumer<String> log) throws IOException {
   IOException failures=new IOException("No TCP route to "+target);
   for(Route route:routes){
     if(!running.getAsBoolean())throw new InterruptedIOException("Session stopped");
     Socket socket=new Socket();String phase="track";boolean connected=false;
     try{
       track.track(socket);log.accept("ROUTE TRY "+route.name());
       phase="bind";route.binder().bind(socket);
       phase="connect";socket.connect(target,5000);socket.setSoTimeout(8000);
       if(!running.getAsBoolean())throw new InterruptedIOException("Session stopped");
       connected=true;log.accept("ROUTE CONNECTED "+route.name()+" local="+socket.getLocalAddress().getHostAddress());return socket;
     }catch(IOException|SecurityException error){
       failures.addSuppressed(error);log.accept("ROUTE FAILED "+route.name()+" phase="+phase+" "+error.getClass().getSimpleName()+": "+error.getMessage());
     }finally{if(!connected){try{socket.close();}finally{forget.accept(socket);}}}
   }
   throw failures;
 }
}
