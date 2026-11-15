package com.example.carbridge.carbit;
public interface BridgeCallbacks {
 int[] dimensions(int requestedWidth,int requestedHeight);
 FrameSource configure(int width,int height,int encoder) throws Exception;
 void touch(int event,int x,int y,int slot,int key);
 default boolean rawTouch(int event,int x,int y,int slot){return false;}
 void disconnected(String reason);
}
