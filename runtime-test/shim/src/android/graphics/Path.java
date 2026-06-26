package android.graphics;
public class Path {
  public int ops=0;
  public Path(){}
  public void moveTo(float x,float y){ops++;}
  public void lineTo(float x,float y){ops++;}
  public void close(){ops++;}
  public void reset(){ops=0;}
}
