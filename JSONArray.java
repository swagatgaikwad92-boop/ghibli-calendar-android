package org.json;
import java.util.*;
public class JSONArray {
    final List<Object> list = new ArrayList<>();
    public JSONArray() {}
    public JSONArray(String s) throws JSONException { Object o = new Parser(s).value(); if(!(o instanceof JSONArray)) throw new JSONException("not array"); list.addAll(((JSONArray)o).list); }
    public int length(){ return list.size(); }
    public Object opt(int i){ return i>=0&&i<list.size()?list.get(i):null; }
    public JSONObject optJSONObject(int i){ Object o=opt(i); return o instanceof JSONObject?(JSONObject)o:null; }
    public JSONObject getJSONObject(int i) throws JSONException { JSONObject o=optJSONObject(i); if(o==null) throw new JSONException("no obj"); return o; }
    public JSONArray put(Object o){ list.add(o); return this; }
    public String toString(){ StringBuilder b=new StringBuilder("["); for(int i=0;i<list.size();i++){ if(i>0)b.append(','); b.append(JSONObject.write(list.get(i))); } return b.append(']').toString(); }
}
