package android.content;
import java.io.File; import java.util.*;
public class Context {
    public static final int MODE_PRIVATE = 0;
    final Map<String,Object> store = new HashMap<>();
    File dir = new File(System.getProperty("java.io.tmpdir"), "gf-test-" + System.nanoTime());
    public Context getApplicationContext() { return this; }
    public File getFilesDir() { dir.mkdirs(); return dir; }
    public int getColor(int r) { return r; }
    public SharedPreferences getSharedPreferences(String n, int m) {
        return new SharedPreferences() {
            public String getString(String k,String d){ Object v=store.get(n+k); return v==null?d:(String)v; }
            public long getLong(String k,long d){ Object v=store.get(n+k); return v==null?d:(Long)v; }
            public boolean getBoolean(String k,boolean d){ Object v=store.get(n+k); return v==null?d:(Boolean)v; }
            public Editor edit(){ return new Editor(){
                public Editor putString(String k,String v){ store.put(n+k,v); return this; }
                public Editor putLong(String k,long v){ store.put(n+k,v); return this; }
                public Editor putBoolean(String k,boolean v){ store.put(n+k,v); return this; }
                public Editor remove(String k){ store.remove(n+k); return this; }
                public void apply(){} }; }
        };
    }
}
