package android.view;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
public class View {
  protected Context context; private int width,height; private boolean attached;
  public View(Context c){this.context=c;}
  public Resources getResources(){return context.getResources();}
  public void setFocusable(boolean b){}
  public void setClickable(boolean b){}
  public void setKeepScreenOn(boolean b){}
  public int getWidth(){return width;}
  public int getHeight(){return height;}
  public void invalidate(){}
  public boolean performHapticFeedback(int f){return true;}
  public void layout(int l,int t,int r,int b){
    int ow=width,oh=height; width=r-l; height=b-t;
    if(width!=ow||height!=oh) onSizeChanged(width,height,ow,oh);
  }
  /** Test hook: simulate attach so onAttachedToWindow runs. */
  public void attach(){ if(!attached){attached=true; onAttachedToWindow();} }
  public void detach(){ if(attached){attached=false; onDetachedFromWindow();} }
  public final void draw(Canvas c){ onDraw(c); }
  protected void onDraw(Canvas c){}
  protected void onSizeChanged(int w,int h,int ow,int oh){}
  protected void onAttachedToWindow(){}
  protected void onDetachedFromWindow(){}
  public boolean onTouchEvent(MotionEvent e){return false;}
}
