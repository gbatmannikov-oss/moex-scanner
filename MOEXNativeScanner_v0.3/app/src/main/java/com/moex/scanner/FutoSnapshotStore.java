package com.moex.scanner;
import android.content.Context;
import android.content.SharedPreferences;

public final class FutoSnapshotStore {
    private final SharedPreferences p;
    public FutoSnapshotStore(Context c){p=c.getSharedPreferences("futo_snapshots",Context.MODE_PRIVATE);}
    public MarketApi.FutoDelta delta(String t, MarketApi.FutoI now, double openPosition){
        String k="s_"+t;
        double yl=p.getFloat(k+"_yl",Float.NaN), ys=p.getFloat(k+"_ys",Float.NaN), fl=p.getFloat(k+"_fl",Float.NaN), fs=p.getFloat(k+"_fs",Float.NaN);
        long prevOi=p.getLong(k+"_oi",Long.MIN_VALUE);
        MarketApi.FutoDelta d=new MarketApi.FutoDelta();
        if(!Double.isNaN(yl)){d.yur=new MarketApi.SideDelta(now.yur.longPos-yl,now.yur.shortPos-ys);d.fiz=new MarketApi.SideDelta(now.fiz.longPos-fl,now.fiz.shortPos-fs);d.oiChange=(prevOi==Long.MIN_VALUE)?0:(openPosition-prevOi);}
        p.edit().putFloat(k+"_yl",(float)now.yur.longPos).putFloat(k+"_ys",(float)now.yur.shortPos).putFloat(k+"_fl",(float)now.fiz.longPos).putFloat(k+"_fs",(float)now.fiz.shortPos).putLong(k+"_oi",(long)openPosition).apply();
        return d;
    }
}
