package android.content;
import java.util.HashMap;
import android.content.res.Resources;
public class Context {
  public static final int MODE_PRIVATE=0;
  private final HashMap<String,SharedPreferences> prefs=new HashMap<String,SharedPreferences>();
  private final Resources res=new Resources();
  public SharedPreferences getSharedPreferences(String n,int m){
    SharedPreferences p=prefs.get(n); if(p==null){p=new SharedPreferences();prefs.put(n,p);} return p;
  }
  public Resources getResources(){return res;}
}
