package android.content;
public interface SharedPreferences {
    String getString(String k, String d); long getLong(String k, long d); boolean getBoolean(String k, boolean d);
    Editor edit();
    interface Editor { Editor putString(String k,String v); Editor putLong(String k,long v); Editor putBoolean(String k,boolean v); Editor remove(String k); void apply(); }
}
