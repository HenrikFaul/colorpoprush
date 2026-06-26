package android.graphics;
public class RectF {
  public float left,top,right,bottom;
  public RectF(){}
  public RectF(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
  public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
  public float centerX(){return (left+right)*0.5f;}
  public float centerY(){return (top+bottom)*0.5f;}
  public float width(){return right-left;}
  public float height(){return bottom-top;}
  public boolean contains(float x,float y){return x>=left&&x<right&&y>=top&&y<bottom;}
}
