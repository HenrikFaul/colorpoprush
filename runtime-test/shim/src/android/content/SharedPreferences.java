package android.content;
import java.util.HashMap;
public class SharedPreferences {
  public final HashMap<String,Object> map=new HashMap<String,Object>();
  public int getInt(String k,int d){Object v=map.get(k);return v instanceof Integer?(Integer)v:d;}
  public boolean getBoolean(String k,boolean d){Object v=map.get(k);return v instanceof Boolean?(Boolean)v:d;}
  public long getLong(String k,long d){Object v=map.get(k);return v instanceof Long?(Long)v:d;}
  public String getString(String k,String d){Object v=map.get(k);return v instanceof String?(String)v:d;}
  public Editor edit(){return new Editor(this);}
  public static class Editor {
    private final SharedPreferences p;
    Editor(SharedPreferences p){this.p=p;}
    public Editor putInt(String k,int v){p.map.put(k,v);return this;}
    public Editor putBoolean(String k,boolean v){p.map.put(k,v);return this;}
    public Editor putLong(String k,long v){p.map.put(k,v);return this;}
    public Editor putString(String k,String v){p.map.put(k,v);return this;}
    public Editor remove(String k){p.map.remove(k);return this;}
    public Editor clear(){p.map.clear();return this;}
    public void apply(){}
    public boolean commit(){return true;}
  }
}
