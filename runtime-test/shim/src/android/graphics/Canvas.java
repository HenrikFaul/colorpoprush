package android.graphics;
public class Canvas {
  public int draws=0;
  public Canvas(){}
  public void drawRect(float l,float t,float r,float b,Paint p){chk(l);chk(t);chk(r);chk(b);draws++;}
  public void drawRoundRect(RectF r,float rx,float ry,Paint p){if(r==null){Faults.record("drawRoundRect null RectF");throw new NullPointerException("RectF");}chk(r.left);chk(r.top);draws++;}
  public void drawCircle(float cx,float cy,float rad,Paint p){chk(cx);chk(cy);chk(rad);draws++;}
  public void drawLine(float x0,float y0,float x1,float y1,Paint p){chk(x0);chk(y0);chk(x1);chk(y1);draws++;}
  public void drawText(String s,float x,float y,Paint p){if(s==null){Faults.record("drawText null text");throw new NullPointerException("text");}chk(x);chk(y);draws++;}
  public void drawPath(Path path,Paint p){if(path==null){Faults.record("drawPath null Path");throw new NullPointerException("Path");}draws++;}
  public void drawArc(RectF o,float a,float sw,boolean uc,Paint p){if(o==null){Faults.record("drawArc null oval");throw new NullPointerException("oval");}draws++;}
  public void save(){}
  public void restore(){}
  public void translate(float x,float y){}
  public void scale(float x,float y){}
  public void rotate(float d){}
  public void rotate(float d,float px,float py){}
  private static void chk(float v){ if(Float.isNaN(v)||Float.isInfinite(v)){ Faults.record("draw NaN/Inf coord"); throw new IllegalArgumentException("NaN/Inf draw coord"); } }
}
