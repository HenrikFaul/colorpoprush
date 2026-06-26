package android.graphics;
public class RadialGradient extends Shader {
  public RadialGradient(float cx,float cy,float radius,int[] colors,float[] stops,Shader.TileMode m){
    if(!(radius>0)){ Faults.record("RadialGradient radius="+radius); throw new IllegalArgumentException("radius must be > 0"); }
    if(colors==null||colors.length<2){ Faults.record("RadialGradient colors"); throw new IllegalArgumentException("needs >= 2 colors"); }
    if(stops!=null && stops.length!=colors.length){ Faults.record("RadialGradient stops"); throw new IllegalArgumentException("color and stop arrays must be of equal length"); }
  }
}
