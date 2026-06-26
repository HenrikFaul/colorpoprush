package android.media;
public class AudioTrack {
  public static final int STATE_INITIALIZED=1, STATE_UNINITIALIZED=0, MODE_STATIC=0, MODE_STREAM=1;
  public AudioTrack(int s,int rate,int ch,int fmt,int buf,int mode){ if(buf<=0) throw new IllegalArgumentException("bad buffer"); }
  public int getState(){return STATE_INITIALIZED;}
  public int write(short[] data,int off,int n){return n;}
  public void play(){}
  public void stop(){}
  public void flush(){}
  public void release(){}
}
