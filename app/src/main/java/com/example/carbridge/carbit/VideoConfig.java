package com.example.carbridge.carbit;
/** Negotiation only: no allocation at the HU's requested resolution. */
public final class VideoConfig {
 public record Size(int width,int height) {}
 public static int encoder(int wanted,int supported,boolean preferJpeg){
   if(preferJpeg&&(wanted==0||wanted==1||(supported&1)!=0))return 1;
   int selected=wanted==0?1:wanted;
   return selected==1||selected==2?selected:0;
 }
 public static Size size(int width,int height){
   if(width<96||height<96||width>8192||height>8192||Math.max(width,height)<160)
     throw new IllegalArgumentException("Invalid requested dimensions "+width+"x"+height);
   double scale=Math.min(1.0,Math.min(1280.0/Math.max(width,height),800.0/Math.min(width,height)));
   int w=(int)(width*scale)/16*16,h=(int)(height*scale)/16*16;
   if(w<96||h<96)throw new IllegalArgumentException("Unsupported aspect ratio");
   return new Size(w,h);
 }
}
