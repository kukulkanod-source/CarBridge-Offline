package com.example.carbridge.carbit;

/** Mirror coordinates are relative to the fitted video area in the HU request size.
 * Matches the stock client's resize-video step (m1.n/t), then its input scale (m1.r).
 * The HU has already removed the letterbox origin; subtracting it again is wrong.
 */
public final class CarbitTouchMapper {
    public record Touch(int event, int x, int y) {}
    public final int inputWidth, inputHeight, outputWidth, outputHeight;
    private boolean pressed;
    private volatile double[] calibration;
    public synchronized void setCalibration(double[] c) { if(c!=null&&!TouchCalibration.valid(c))throw new IllegalArgumentException("Calibration"); calibration=c==null?null:c.clone(); pressed=false; }
    private int lastX, lastY;
    public synchronized void resetGesture() { pressed=false; }
    public CarbitTouchMapper(int requestedWidth, int requestedHeight, int width, int height) {
        int rw = requestedWidth & ~15, rh = requestedHeight & ~15;
        if (rw <= 0 || rh <= 0 || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Invalid touch dimensions");
        outputWidth = width; outputHeight = height;
        if ((long) rw * height >= (long) rh * width) {
            // Match the stock integer Rect edges, including odd margins.
            double fitted = (double) width * rh / height;
            inputWidth = (int)((rw + fitted) / 2) - (int)((rw - fitted) / 2);
            inputHeight = rh;
        } else {
            double fitted = (double) height * rw / width;
            inputWidth = rw;
            inputHeight = (int)((rh + fitted) / 2) - (int)((rh - fitted) / 2);
        }
    }
    public synchronized Touch accept(int event, int x, int y, int slot) {
        if (slot != 0 || event < 1 || event > 3) return null;
        double[] c=calibration;
        double mx=c==null?(double)x*outputWidth/inputWidth:c[0]*x+c[1]*y+c[2];
        double my=c==null?(double)y*outputHeight/inputHeight:c[3]*x+c[4]*y+c[5];
        boolean inside = mx >= 0 && my >= 0 && mx < outputWidth && my < outputHeight;
        if (!inside) {
            if (!pressed) return null;
            pressed = false;
            return new Touch(5, lastX, lastY); // Internal cancel, not a wire event.
        }
        if (event == 2) pressed = true;
        else if (!pressed) return null;
        lastX = Math.min(outputWidth - 1, (int)mx);
        lastY = Math.min(outputHeight - 1, (int)my);
        if (event == 1) pressed = false;
        return new Touch(event, lastX, lastY);
    }
}
