package android.os;
public class Handler {
  public Handler(){}
  public Handler(Looper l){}
  public boolean post(Runnable r){ try{ if(r!=null) r.run(); }catch(Throwable t){} return true; }
  public boolean postDelayed(Runnable r,long d){ return true; } // delayed teardown is a no-op in tests
  public void removeCallbacksAndMessages(Object t){}
}
