package org.json;
class Parser {
    final String s; int i=0;
    Parser(String s){ this.s=s; }
    void ws(){ while(i<s.length()&&Character.isWhitespace(s.charAt(i))) i++; }
    Object value() throws JSONException {
        ws(); if(i>=s.length()) throw new JSONException("eof");
        char c=s.charAt(i);
        if(c=='{'){ i++; JSONObject o=new JSONObject(); ws(); if(s.charAt(i)=='}'){i++;return o;}
            while(true){ ws(); String k=str(); ws(); expect(':'); Object v=value(); o.put(k,v); ws(); char d=s.charAt(i++); if(d=='}')break; if(d!=',')throw new JSONException("obj"); } return o; }
        if(c=='['){ i++; JSONArray a=new JSONArray(); ws(); if(s.charAt(i)==']'){i++;return a;}
            while(true){ a.put(value()); ws(); char d=s.charAt(i++); if(d==']')break; if(d!=',')throw new JSONException("arr"); } return a; }
        if(c=='"') return str();
        if(s.startsWith("true",i)){i+=4;return Boolean.TRUE;} if(s.startsWith("false",i)){i+=5;return Boolean.FALSE;}
        if(s.startsWith("null",i)){i+=4;return JSONObject.NULL;}
        int st=i; while(i<s.length()&&"+-0123456789.eE".indexOf(s.charAt(i))>=0) i++;
        String n=s.substring(st,i); if(n.isEmpty()) throw new JSONException("bad"); 
        if(n.contains(".")||n.contains("e")||n.contains("E")) return Double.parseDouble(n);
        long l=Long.parseLong(n); return (l>=Integer.MIN_VALUE&&l<=Integer.MAX_VALUE)?(Object)(int)l:(Object)l;
    }
    void expect(char c) throws JSONException { if(s.charAt(i++)!=c) throw new JSONException("expected "+c); }
    String str() throws JSONException {
        expect('"'); StringBuilder b=new StringBuilder();
        while(true){ char c=s.charAt(i++); if(c=='"')break; if(c=='\\'){ char e=s.charAt(i++);
            switch(e){case 'n':b.append('\n');break;case 't':b.append('\t');break;case 'r':b.append('\r');break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;
                case 'u':b.append((char)Integer.parseInt(s.substring(i,i+4),16));i+=4;break;default:b.append(e);} } else b.append(c); }
        return b.toString();
    }
}
