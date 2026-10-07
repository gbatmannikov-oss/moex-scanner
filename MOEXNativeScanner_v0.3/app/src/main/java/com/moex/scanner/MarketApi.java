package com.moex.scanner;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class MarketApi {
    private static final String HOST="https://iss.moex.com";
    private final String token;
    public MarketApi(String token){this.token=token==null?"":token.trim();}
    private String get(String path) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(HOST+path).openConnection(); c.setRequestMethod("GET"); c.setConnectTimeout(12000); c.setReadTimeout(20000); c.setRequestProperty("Accept","application/json");
        if(!token.isEmpty()) c.setRequestProperty("Authorization",token.startsWith("Bearer ")?token:"Bearer "+token);
        int code=c.getResponseCode(); InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream(); String body=new String(in.readAllBytes(),StandardCharsets.UTF_8); if(code<200||code>=300)throw new IOException("HTTP "+code+": "+body); return body;
    }
    public double lastPrice(String ticker) throws Exception {
        String q="/iss/engines/futures/markets/forts/boards/RFUD/securities/"
                +URLEncoder.encode(ticker,"UTF-8")
                +".json?iss.meta=off&iss.only=marketdata&marketdata.columns=SECID,LAST";
        JSONObject j=new JSONObject(get(q));
        JSONObject md=j.optJSONObject("marketdata");
        if(md==null) return 0;
        JSONArray data=md.optJSONArray("data");
        if(data==null || data.length()==0) return 0;
        JSONArray row=data.optJSONArray(0);
        if(row==null) return 0;
        return row.optDouble(1,0);
    }

    public JSONObject futoi(String ticker) throws Exception{
        // FUTOI использует базовый код инструмента: Si, BR, RI...
        ticker = ticker.replaceFirst("[FGHJKMNQUVXZ][0-9]$", "");java.time.LocalDate d=java.time.LocalDate.now().minusDays(15); String ds=d.toString(); return new JSONObject(get("/iss/analyticalproducts/futoi/securities/"+URLEncoder.encode(ticker,"UTF-8")+".json?from="+ds+"&till="+ds));}
    public double openPosition(String ticker) throws Exception {
        JSONObject j = new JSONObject(get(
            "/iss/engines/futures/markets/forts/securities/"
            + URLEncoder.encode(ticker,"UTF-8")
            + ".json?iss.meta=off"
        ));
        JSONObject md = j.optJSONObject("marketdata");
        if (md == null) return 0;
        JSONArray cols = md.optJSONArray("columns");
        JSONArray data = md.optJSONArray("data");
        if (cols == null || data == null || data.length() == 0) return 0;
        int idx = -1;
        for (int i=0; i<cols.length(); i++) {
            if ("OPENPOSITION".equalsIgnoreCase(cols.optString(i))) {
                idx=i;
                break;
            }
        }
        if (idx < 0) return 0;
        JSONArray row = data.optJSONArray(0);
        if (row == null) return 0;
        return row.optDouble(idx,0);
    }

    public CandleSeries candles(String ticker,int interval,String from) throws Exception{
        String base="/iss/engines/futures/markets/forts/boards/rfud/securities/"
                +URLEncoder.encode(ticker,"UTF-8")
                +"/candles.json?interval="+interval
                +(from==null?"":"&from="+URLEncoder.encode(from,"UTF-8"));

        JSONArray all=new JSONArray();
        JSONArray cols=null;
        int start=0;

        while(true){
            String q=base+"&start="+start;
            JSONObject j=new JSONObject(get(q));
            JSONObject c=j.optJSONObject("candles");
            if(c==null) break;

            if(cols==null) cols=c.optJSONArray("columns");
            JSONArray data=c.optJSONArray("data");
            if(data==null || data.length()==0) break;

            for(int i=0;i<data.length();i++){
                all.put(data.get(i));
            }

            int n=data.length();
            start+=n;

            if(n<500) break;
        }

        return new CandleSeries(
                cols==null?new JSONArray():cols,
                all
        );
    }

    public static final class CandleSeries { public final JSONArray columns,data; public CandleSeries(){this.columns=new JSONArray();this.data=new JSONArray();} public CandleSeries(JSONArray c,JSONArray d){columns=c;data=d;} }
    public static FutoI parseFutoi(JSONObject root){
        FutoI f=new FutoI(); JSONObject t=root.optJSONObject("futoi"); if(t==null)return f; JSONArray cols=t.optJSONArray("columns"), data=t.optJSONArray("data"); if(cols==null||data==null)return f;
        Map<String,Integer> ix=new HashMap<>(); for(int i=0;i<cols.length();i++)ix.put(cols.optString(i).toUpperCase(),i);
        for(int r=0;r<data.length();r++){JSONArray a=data.optJSONArray(r); if(a==null)continue; String g=s(a,ix,"CLGROUP").toUpperCase(); Side x=new Side(); x.longPos=n(a,ix,"POS_LONG"); x.shortPos=Math.abs(n(a,ix,"POS_SHORT")); x.longNum=n(a,ix,"POS_LONG_NUM"); x.shortNum=n(a,ix,"POS_SHORT_NUM"); if(g.equals("YUR"))f.yur=x; else if(g.equals("FIZ"))f.fiz=x;}
        return f;
    }
    static String s(JSONArray a,Map<String,Integer> m,String k){Integer i=m.get(k);return i==null?"":a.optString(i,"");}
    static double n(JSONArray a,Map<String,Integer> m,String k){Integer i=m.get(k);if(i==null)return 0;Object v=a.opt(i);try{return v instanceof Number?((Number)v).doubleValue():Double.parseDouble(String.valueOf(v));}catch(Exception e){return 0;}}
    public static final class Side {public double longPos,shortPos,longNum,shortNum; public double net(){return longPos-shortPos;}}
    public static final class SideDelta { public double dLong,dShort; public SideDelta(double l,double s){dLong=l;dShort=s;} }
    public static final class FutoDelta { public SideDelta fiz,yur; public double oiChange; }
    public static final class FutoI {public Side fiz=new Side(),yur=new Side(); public double oi(){return fiz.longPos+fiz.shortPos+yur.longPos+yur.shortPos;}}

    public java.util.List<String> allFutures() throws Exception {
        JSONObject j = new JSONObject(get("/iss/engines/futures/markets/forts/securities.json?iss.meta=off&iss.only=securities&securities.columns=SECID"));
        JSONObject sec = j.optJSONObject("securities");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (sec == null) return out;
        JSONArray data = sec.optJSONArray("data");
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONArray row = data.optJSONArray(i);
            if (row != null && row.length() > 0) {
                String ticker = row.optString(0, "");
                if (!ticker.isEmpty()) out.add(ticker);
            }
        }
        return out;
    }


    public java.util.Map<String,FutoI> allFutoi() throws Exception {
        java.util.Map<String,FutoI> out = new java.util.LinkedHashMap<>();
        for (String ticker : allFutures()) {
            try {
                out.put(ticker, parseFutoi(futoi(ticker)));
            } catch (Exception e) {
                // пропускаем тикер, если FUTOI для него недоступен
            }
        }
        return out;
    }

}
