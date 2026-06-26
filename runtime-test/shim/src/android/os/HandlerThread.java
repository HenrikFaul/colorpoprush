package android.os;
public class HandlerThread extends Thread {
  private final Looper looper=new Looper();
  public HandlerThread(String name){super(name);}
  @Override public void start(){}
  public Looper getLooper(){return looper;}
  public boolean quitSafely(){return true;}
  public boolean quit(){return true;}
}
