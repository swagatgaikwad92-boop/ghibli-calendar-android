package org.json;
import java.util.*;
public class JSONObject {
    public static final Object NULL = new Object(){ public String toString(){return "null";} public boolean equals(Object o){return o==this||o==null;} };
    final Map<String,Object> map = new LinkedHashMap<>();
    public JSONObject() {}
    public JSONObject(String s) throws JSONException { Object o=new Parser(s).value(); if(!(o instanceof JSONObject)) throw new JSONException("not object"); map.putAll(((JSONObject)o).map); }
    public Object opt(String k){ return map.get(k); }
    public boolean has(String k){ return map.containsKey(k); }
    public boolean isNull(String k){ Object v=map.get(k); return v==null||v==NULL; }
    public JSONObject remove0(String k){ map.remove(k); return this; }
    public Object remove(String k){ return map.remove(k); }
    public boolean optBoolean(String k,boolean d){ Object v=map.get(k); return v instanceof Boolean?(Boolean)v:d; }
    public int optInt(String k,int d){ Object v=map.get(k); return v instanceof Number?((Number)v).intValue():d; }
    public double optDouble(String k,double d){ Object v=map.get(k); return v instanceof Number?((Number)v).doubleValue():d; }
    public JSONArray optJSONArray(String k){ Object v=map.get(k); return v instanceof JSONArray?(JSONArray)v:null; }
    public JSONObject optJSONObject(String k){ Object v=map.get(k); return v instanceof JSONObject?(JSONObject)v:null; }
    public JSONObject put(String k,Object v) throws JSONException { map.put(k,v); return this; }
    public JSONObject put(String k,int v) throws JSONException { map.put(k,v); return this; }
    public JSONObject put(String k,boolean v) throws JSONException { map.put(k,v); return this; }
    static String write(Object o){
        if(o==null||o==NULL) return "null";
        if(o instanceof String){ StringBuilder b=new StringBuilder("\""); for(char c:((String)o).toCharArray()){ if(c=='"'||c=='\\') b.append('\\').append(c); else if(c=='\n') b.append("\\n"); else if(c<0x20) b.append(String.format("\\u%04x",(int)c)); else b.append(c);} return b.append('"').toString(); }
        if(o instanceof Double){ double d=(Double)o; return d==Math.floor(d)&&!Double.isInfinite(d)?String.valueOf((long)d):String.valueOf(d); }
        return o.toString();
    }
    public String toString(){ StringBuilder b=new StringBuilder("{"); boolean f=true; for(Map.Entry<String,Object> e:map.entrySet()){ if(!f)b.append(','); f=false; b.append(write(e.getKey())).append(':').append(write(e.getValue())); } return b.append('}').toString(); }
}
