package com.example.carbridge.carbit;

import java.io.*;
import java.nio.*;

/** Independently implemented from the observed PXC and mirror layouts. */
public final class Wire {
    public static final int MAX_PAYLOAD = 1024 * 1024;
    public record Packet(int command, int token, byte[] body) {}
    public static ByteBuffer le(byte[] b) { return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); }
    public static byte[] read(InputStream in, int n) throws IOException {
        if (n < 0 || n > MAX_PAYLOAD) throw new IOException("Invalid payload length " + n);
        byte[] b = new byte[n]; int p = 0;
        while (p < n) { int c = in.read(b, p, n-p); if (c < 0) throw new EOFException("EOF " + p + "/" + n); if(c==0) continue; p+=c; }
        return b;
    }
    public static Packet pxc(InputStream in) throws IOException {
        ByteBuffer h=le(read(in,16)); int c=h.getInt(), size=h.getInt(), magic=h.getInt(), r=h.getInt();
        if(size<16 || size>MAX_PAYLOAD+16 || magic!=(c^size)) throw new IOException("Bad PXC header");
        return new Packet(c,r,read(in,size-16));
    }
    public static byte[] pxc(int c,int r,byte[] body) {
        if(body.length>MAX_PAYLOAD) throw new IllegalArgumentException("PXC too large");
        return ByteBuffer.allocate(16+body.length).order(ByteOrder.LITTLE_ENDIAN).putInt(c).putInt(16+body.length).putInt(c^(16+body.length)).putInt(r).put(body).array();
    }
    public static Packet mirror(InputStream in) throws IOException {
        ByteBuffer h=le(read(in,8)); int c=h.getShort()&65535, len=h.getShort()&65535, t=h.getInt();
        return new Packet(c,t,read(in,len));
    }
    public static byte[] mirror(int c, byte[] b) {
        if(b.length>65535) throw new IllegalArgumentException("Mirror payload too large");
        return ByteBuffer.allocate(8+b.length).order(ByteOrder.LITTLE_ENDIAN).putShort((short)c).putShort((short)b.length).putInt(0).put(b).array();
    }
    public static byte[] ints(int... n) { ByteBuffer b=ByteBuffer.allocate(n.length*4).order(ByteOrder.LITTLE_ENDIAN); for(int v:n)b.putInt(v); return b.array(); }
}
