package com.example.carbridge.carbit;
public interface FrameSource extends AutoCloseable {
 byte[] next() throws Exception;
 void close();
}
