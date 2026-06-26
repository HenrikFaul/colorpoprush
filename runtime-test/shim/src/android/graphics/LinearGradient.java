package android.graphics;
public class LinearGradient extends Shader {
  public LinearGradient(float x0,float y0,float x1,float y1,int c0,int c1,Shader.TileMode m){
    if(Float.isNaN(x0)||Float.isNaN(y0)||Float.isNaN(x1)||Float.isNaN(y1)){
      Faults.record("LinearGradient NaN coord");
      throw new IllegalArgumentException("NaN coordinate");
    }
  }
}
