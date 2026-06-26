package android.view;
public class Choreographer {
  private static final Choreographer I=new Choreographer();
  public static Choreographer getInstance(){return I;}
  public interface FrameCallback { void doFrame(long frameTimeNanos); }
  // Store only; the test harness drives doFrame() manually (like a paused looper).
  public void postFrameCallback(FrameCallback cb){}
  public void removeFrameCallback(FrameCallback cb){}
}
