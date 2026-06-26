package android.graphics;
public class Paint {
  public static final int ANTI_ALIAS_FLAG=1;
  public enum Style { FILL, STROKE, FILL_AND_STROKE }
  public enum Align { LEFT, CENTER, RIGHT }
  public enum Cap { BUTT, ROUND, SQUARE }
  public enum Join { MITER, ROUND, BEVEL }
  private float textSize=12f; private boolean bold;
  public Paint(){}
  public Paint(int flags){}
  public void setStyle(Style s){}
  public void setColor(int c){}
  public void setAlpha(int a){}
  public void setStrokeWidth(float w){}
  public void setTextSize(float s){textSize=s;}
  public void setTextAlign(Align a){}
  public void setFakeBoldText(boolean b){bold=b;}
  public void setStrokeCap(Cap c){}
  public void setStrokeJoin(Join j){}
  public Shader setShader(Shader sh){return sh;}
  public float measureText(String t){return t==null?0:t.length()*textSize*0.55f;}
}
