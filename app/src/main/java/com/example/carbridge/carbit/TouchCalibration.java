package com.example.carbridge.carbit;
/** Affine fit from three non-collinear targets; other targets are independent validation. */
public final class TouchCalibration {
 public static double[] fit(double[][] raw,double[][] target,double tolerance) {
  if(raw.length<5||raw.length!=target.length)throw new IllegalArgumentException("Need five targets");
  double x0=raw[0][0],y0=raw[0][1],dx1=raw[1][0]-x0,dy1=raw[1][1]-y0,dx2=raw[2][0]-x0,dy2=raw[2][1]-y0;
  double det=dx1*dy2-dx2*dy1;
  if(Math.abs(det)<100)throw new IllegalArgumentException("Targets too close");
  double[] c=new double[6];
  for(int axis=0;axis<2;axis++) {
   double a=target[1][axis]-target[0][axis],b=target[2][axis]-target[0][axis];int k=axis*3;
   c[k]=(a*dy2-b*dy1)/det;c[k+1]=(dx1*b-dx2*a)/det;c[k+2]=target[0][axis]-c[k]*x0-c[k+1]*y0;
  }
  if(!valid(c))throw new IllegalArgumentException("Invalid transform");
  for(int i=3;i<raw.length;i++) if(Math.hypot(c[0]*raw[i][0]+c[1]*raw[i][1]+c[2]-target[i][0],c[3]*raw[i][0]+c[4]*raw[i][1]+c[5]-target[i][1])>tolerance)
   throw new IllegalArgumentException("Check targets did not match");
  return c;
 }
 public static boolean valid(double[] c) {
  if(c==null||c.length!=6)return false;
  for(double v:c)if(!Double.isFinite(v))return false;
  return Math.abs(c[0]*c[4]-c[1]*c[3])>1e-8;
 }
}
