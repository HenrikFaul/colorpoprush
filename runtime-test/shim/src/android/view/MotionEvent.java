package android.view;
public class MotionEvent {
  public static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3;
  private int action; private float x,y;
  public static MotionEvent obtain(long dt,long et,int action,float x,float y,int meta){
    MotionEvent e=new MotionEvent(); e.action=action; e.x=x; e.y=y; return e;
  }
  public int getActionMasked(){return action;}
  public int getAction(){return action;}
  public float getX(){return x;}
  public float getY(){return y;}
  public void recycle(){}
}
