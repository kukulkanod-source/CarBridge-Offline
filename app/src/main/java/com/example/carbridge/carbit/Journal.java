package com.example.carbridge.carbit;
import android.content.Context;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class Journal {
    public final File file;
    private final StringBuilder tail=new StringBuilder();
    private final SimpleDateFormat time=new SimpleDateFormat("HH:mm:ss.SSS",Locale.US);
    public Journal(Context c) throws IOException {
        File dir=new File(c.getFilesDir(),"logs"); dir.mkdirs();
        File[] old=dir.listFiles(); if(old!=null) { Arrays.sort(old,Comparator.comparingLong(File::lastModified).reversed()); for(int i=19;i<old.length;i++) old[i].delete(); }
        file=new File(dir,"carbit-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".log");
        file.createNewFile();
    }
    public synchronized void log(String text) {
        String line=time.format(new Date())+" "+text+"\n";
        com.example.carbridge.SessionDiagnostics.INSTANCE.event("CarbitLink "+text);
        tail.append(line); if(tail.length()>18000) tail.delete(0,tail.length()-14000);
        if(file.length()<8*1024*1024) try(FileOutputStream o=new FileOutputStream(file,true)){o.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(IOException ignored){}
    }
    public synchronized String tail(){return tail.toString();}
    public synchronized byte[] snapshot() throws IOException { return java.nio.file.Files.readAllBytes(file.toPath()); }
    public void error(String stage,Throwable e){log("ERROR "+stage+": "+e.getClass().getSimpleName()+" "+e.getMessage());}
}
