package com.moex.scanner;

import java.util.*;

/**
 * v0.8 trading core.
 * Strict sequence: H1 trend + zone -> M5 sweep -> displacement/BOS -> retest.
 * Positioning is confirmation only: YUR/FIZ + OI classify new positions vs covering/liquidation.
 */
public final class ScannerEngine {
    public static class Bar { public double o,h,l,c,v; public long ts; public Bar(double o,double h,double l,double c,double v){this(o,h,l,c,v,0);} public Bar(double o,double h,double l,double c,double v,long ts){this.o=o;this.h=h;this.l=l;this.c=c;this.v=v;this.ts=ts;} }
    public static class Result {
        public String ticker, signal="WAIT", setup="NO SETUP", reasons="", progress="";
        public double score,entry,sl,tp1,tp2,price,atr,h1Atr,vwap,rr;
        public double zoneLow,zoneHigh; public boolean sweepLow,sweepHigh,breakUp,breakDown,retest,retestUp,retestDown,displacement,cancelled;
        public String zoneType="",positionRegime="";
        public MarketApi.FutoI futoI; public MarketApi.FutoDelta delta;
    }
    static final class Zone { double low,high; boolean demand; int pivotIndex; Zone(double l,double h,boolean d,int i){low=l;high=h;demand=d;pivotIndex=i;} }
    static final class Event { int sweep=-1,bos=-1,retest=-1; }

    public Result analyze(String ticker,List<Bar> h1,List<Bar> m5,MarketApi.FutoI f,MarketApi.FutoDelta d){
        Result r=new Result(); r.ticker=ticker; r.futoI=f; r.delta=d;
        if(m5.size()<80 || h1.size()<40){r.setup="INSUFFICIENT_DATA";r.reasons="Недостаточно H1/M5 данных";return r;}
        Bar last=m5.get(m5.size()-1); r.price=last.c; r.atr=atr(m5,14); r.h1Atr=atr(h1,14); r.vwap=vwap(m5);
        if(r.atr<=0 || r.h1Atr<=0){r.setup="BAD_DATA";r.reasons="ATR не рассчитан";return r;}
        List<String> why=new ArrayList<>(); double score=0;
        boolean hUp=trendUp(h1), hDn=trendDown(h1);
        if(hUp){score+=1.0;why.add("H1 trend ↑");} else if(hDn){score-=1.0;why.add("H1 trend ↓");} else why.add("H1 range");

        Zone demand=latestZone(h1,true), supply=latestZone(h1,false);
        double tol=Math.max(r.atr*0.15,last.c*0.0005);
        boolean nearDemand=demand!=null && touchesZone(m5,demand,tol,18);
        boolean nearSupply=supply!=null && touchesZone(m5,supply,tol,18);
        if(nearDemand){r.zoneLow=demand.low;r.zoneHigh=demand.high;r.zoneType="DEMAND";score+=1.0;why.add("H1 demand zone");}
        if(nearSupply && !nearDemand){r.zoneLow=supply.low;r.zoneHigh=supply.high;r.zoneType="SUPPLY";score-=1.0;why.add("H1 supply zone");}

        Event bull=findBullishSequence(m5,r.atr,nearDemand?demand:null);
        Event bear=findBearishSequence(m5,r.atr,nearSupply?supply:null);
        r.sweepLow=bull.sweep>=0; r.breakUp=bull.bos>=0; r.retest=bull.retest>=0;
        boolean bullRetest=bull.retest>=0; r.retestUp=bullRetest;
        boolean bearRetest=bear.retest>=0; r.retestDown=bearRetest;
        r.sweepHigh=bear.sweep>=0; r.breakDown=bear.bos>=0;
        r.displacement=(bull.bos>=0 || bear.bos>=0);
        if(nearDemand) r.progress="ЗОНА H1 ✓ → СНЯТИЕ LOW "+(r.sweepLow?"✓":"⏳")+" → СЛОМ ВВЕРХ "+(r.breakUp?"✓":"⏳")+" → РЕТЕСТ "+(r.retestUp?"✓":"⏳"); else if(nearSupply) r.progress="ЗОНА H1 ✓ → СНЯТИЕ HIGH "+(r.sweepHigh?"✓":"⏳")+" → СЛОМ ВНИЗ "+(r.breakDown?"✓":"⏳")+" → РЕТЕСТ "+(r.retestDown?"✓":"⏳"); else r.progress="ЗОНА H1 ⏳ → СНЯТИЕ — → СЛОМ — → РЕТЕСТ —";
        if(r.sweepLow){score+=1.4;why.add("M5 sweep ↓ + close back");}
        if(r.sweepHigh){score-=1.4;why.add("M5 sweep ↑ + close back");}
        if(bull.bos>=0){score+=1.5;why.add("BOS ↑ + displacement");}
        if(bear.bos>=0){score-=1.5;why.add("BOS ↓ + displacement");}
        if(bullRetest){score+=1.7;why.add("BOS retest ↑");}
        if(bearRetest){score-=1.7;why.add("BOS retest ↓");}

        if(last.c>r.vwap){score+=0.4;why.add("above VWAP");} else {score-=0.4;why.add("below VWAP");}
        double av=avgVol(m5,20); if(last.v>=av*1.25){score+=(last.c>=last.o?0.35:-0.35);why.add("volume expansion");}

        score += positioningScore(f,d,why,r,last.c>=m5.get(m5.size()-2).c);
        r.score=score;

        boolean longStructure=nearDemand && bull.sweep>=0 && bull.bos>=0 && bullRetest;
        boolean shortStructure=nearSupply && bear.sweep>=0 && bear.bos>=0 && bearRetest;
        boolean oppositeBreak=longStructure && bear.bos>=0 || shortStructure && bull.bos>=0;
        if(oppositeBreak){r.cancelled=true;r.setup="CANCELLED";r.signal="WAIT";r.reasons=String.join(" · ",why)+" · противоположный BOS";return r;}

        double buffer=Math.max(r.atr*0.65,last.c*0.0010);
        if(longStructure && hUp && score>=4.5){
            r.signal="LONG";r.setup="ZONE→SWEEP→DISPLACEMENT→BOS→RETEST LONG";r.entry=last.c;
            Bar sw=m5.get(bull.sweep); double slBase=Math.min(sw.l,r.zoneLow>0?r.zoneLow:last.c-buffer); r.sl=slBase-buffer*0.10;
            r.tp1=r.entry+r.atr*1.5; r.tp2=r.entry+r.atr*2.5; r.rr=(r.tp1-r.entry)/Math.max(1e-9,r.entry-r.sl);
            if(r.rr<1.5){r.signal="WAIT";r.setup="LOW_RR";r.cancelled=true;why.add("RR TP1 < 1.5");}
        } else if(shortStructure && hDn && score<=-4.5){
            r.signal="SHORT";r.setup="ZONE→SWEEP→DISPLACEMENT→BOS→RETEST SHORT";r.entry=last.c;
            Bar sw=m5.get(bear.sweep); double slBase=Math.max(sw.h,r.zoneHigh>0?r.zoneHigh:last.c+buffer); r.sl=slBase+buffer*0.10;
            r.tp1=r.entry-r.atr*1.5; r.tp2=r.entry-r.atr*2.5; r.rr=(r.entry-r.tp1)/Math.max(1e-9,r.sl-r.entry);
            if(r.rr<1.5){r.signal="WAIT";r.setup="LOW_RR";r.cancelled=true;why.add("RR TP1 < 1.5");}
        } else {
            if(longStructure || shortStructure) {
    r.setup="STRUCTURE FORMED — FILTERED";
} else if(r.signal.equals("WAIT")) {
    if(!nearDemand && !nearSupply)
        r.setup="WAIT — НЕТ H1 ЗОНЫ";
    else if(nearDemand && bull.sweep<0)
        r.setup="WAIT — ЖДЁМ SWEEP LOW";
    else if(nearSupply && bear.sweep<0)
        r.setup="WAIT — ЖДЁМ SWEEP HIGH";
    else if(nearDemand && bull.bos<0)
        r.setup="WAIT — ЖДЁМ BOS UP";
    else if(nearSupply && bear.bos<0)
        r.setup="WAIT — ЖДЁМ BOS DOWN";
    else if(nearDemand && !bullRetest)
        r.setup="WAIT — ЖДЁМ RETEST LONG";
    else if(nearSupply && !bearRetest)
        r.setup="WAIT — ЖДЁМ RETEST SHORT";
    else
        r.setup="WAIT — ФИЛЬТР SCORE/OI/FUTOI";
}
        }
        r.score=score; r.reasons=String.join(" · ",why); return r;
    }

    private double positioningScore(MarketApi.FutoI f,MarketApi.FutoDelta d,List<String> why,Result r,boolean priceUp){
        if(f==null||d==null)return 0; double x=0;
        x+=sidePosition(f.yur,d.yur,0.75,"ЮР",why); x+=sidePosition(f.fiz,d.fiz,0.35,"ФИЗ",why);
        if(d.oiChange>0)why.add("OI ↑"); else if(d.oiChange<0)why.add("OI ↓");
        r.positionRegime=classify(f,d,priceUp);
        return x;
    }
    private String classify(MarketApi.FutoI f,MarketApi.FutoDelta d,boolean priceUp){
        if(d==null || d.yur==null)return "нет динамики";

        double dl=d.yur.dLong;
        double ds=d.yur.dShort;
        double oi=d.oiChange;

        if(priceUp && dl>0 && ds<0 && oi>0)
            return "BULL: цена↑ · ЮР Long↑ Short↓ · OI↑ — набор Long";

        if(!priceUp && dl<0 && ds>0 && oi>0)
            return "BEAR: цена↓ · ЮР Long↓ Short↑ · OI↑ — набор Short";

        if(priceUp && ds<0 && oi<0)
            return "SHORT COVERING: цена↑ · ЮР Short↓ · OI↓";

        if(!priceUp && dl<0 && oi<0)
            return "LONG LIQUIDATION: цена↓ · ЮР Long↓ · OI↓";

        if(oi>0 && dl>0 && ds>0)
            return "FUTOI: OI↑ · ЮР Long↑ Short↑ — открытие/перераспределение";

        if(oi<0 && dl<0 && ds<0)
            return "FUTOI: OI↓ · ЮР Long↓ Short↓ — закрытие позиций";

        return "FUTOI: смешанная динамика";
    }

    private double sidePosition(MarketApi.Side s,MarketApi.SideDelta d,double w,String label,List<String> why){
        if(s==null||d==null)return 0; double x=0;
        if(d.dLong>0&&d.dShort<0){x+=w;why.add(label+" Long↑ Short↓");}
        else if(d.dLong<0&&d.dShort>0){x-=w;why.add(label+" Long↓ Short↑");}
        else if(d.dLong>0&&d.dShort>0)why.add(label+" Long↑ Short↑");
        else if(d.dLong<0&&d.dShort<0)why.add(label+" Long↓ Short↓");
        return x;
    }

    private Event findBullishSequence(List<Bar>b,double atr,Zone z){Event e=new Event(); int n=b.size(); int from=Math.max(20,n-32), end=n-1;
        for(int i=from;i<end-3;i++){
            double prevLow=lowBefore(b,i,12); Bar x=b.get(i);
            boolean zoneOk=z==null || x.l<=z.high+atr*.25;
            boolean sweep=x.l<prevLow-atr*.03 && x.c>prevLow && bullishRejection(x) && zoneOk;
            if(!sweep)continue;
            e.sweep=i;
            int bos=findBullBos(b,i+1,Math.min(end,i+10),atr);
            if(bos<0)return e;
            e.bos=bos;
            int ret=findBullRetest(b,bos,Math.min(end,bos+8),atr); if(ret<0)return e;
            e.sweep=i;e.bos=bos;e.retest=ret;return e;
        } return e; }
    private Event findBearishSequence(List<Bar>b,double atr,Zone z){Event e=new Event(); int n=b.size(),from=Math.max(20,n-32),end=n-1;
        for(int i=from;i<end-3;i++){
            double prevHigh=highBefore(b,i,12); Bar x=b.get(i); boolean zoneOk=z==null||x.h>=z.low-atr*.25;
            boolean sweep=x.h>prevHigh+atr*.03&&x.c<prevHigh&&bearishRejection(x)&&zoneOk; if(!sweep)continue;
            int bos=findBearBos(b,i+1,Math.min(end,i+10),atr); if(bos<0)continue;
            int ret=findBearRetest(b,bos,Math.min(end,bos+8),atr); if(ret<0)return e;
            e.sweep=i;e.bos=bos;e.retest=ret;return e;
        } return e; }
    private int findBullBos(List<Bar>b,int s,int e,double atr){for(int i=s;i<=e;i++){double level=highBefore(b,i,8);Bar x=b.get(i);if(x.c>level+atr*.05&&body(x)>=atr*.45&&x.v>=avgVolBefore(b,i,20)*1.10)return i;}return -1;}
    private int findBearBos(List<Bar>b,int s,int e,double atr){for(int i=s;i<=e;i++){double level=lowBefore(b,i,8);Bar x=b.get(i);if(x.c<level-atr*.05&&body(x)>=atr*.45&&x.v>=avgVolBefore(b,i,20)*1.10)return i;}return -1;}
    private int findBullRetest(List<Bar>b,int bos,int e,double atr){double level=highBefore(b,bos,8);for(int i=bos+1;i<=e;i++){Bar x=b.get(i);if(x.l<=level+atr*.12&&x.c>level)return i;}return -1;}
    private int findBearRetest(List<Bar>b,int bos,int e,double atr){double level=lowBefore(b,bos,8);for(int i=bos+1;i<=e;i++){Bar x=b.get(i);if(x.h>=level-atr*.12&&x.c<level)return i;}return -1;}
    private boolean touchesZone(List<Bar>b,Zone z,double tol,int bars){int s=Math.max(0,b.size()-bars);for(int i=s;i<b.size();i++){Bar x=b.get(i);if(x.l<=z.high+tol&&x.h>=z.low-tol)return true;}return false;}
    private boolean trendUp(List<Bar>b){return b.get(b.size()-1).c>b.get(b.size()-6).c&&b.get(b.size()-6).c>b.get(b.size()-12).c&&b.get(b.size()-1).c>ema(b,20);}
    private boolean trendDown(List<Bar>b){return b.get(b.size()-1).c<b.get(b.size()-6).c&&b.get(b.size()-6).c<b.get(b.size()-12).c&&b.get(b.size()-1).c<ema(b,20);}
    private Zone latestZone(List<Bar>b,boolean demand){int end=b.size()-3,start=Math.max(3,end-55);Zone best=null;for(int i=start;i<end;i++){boolean p=demand?b.get(i).l<b.get(i-1).l&&b.get(i).l<=b.get(i+1).l&&b.get(i).l<=b.get(i-2).l&&b.get(i).l<=b.get(i+2).l:b.get(i).h>b.get(i-1).h&&b.get(i).h>=b.get(i+1).h&&b.get(i).h>=b.get(i-2).h&&b.get(i).h>=b.get(i+2).h;if(!p)continue;double w=Math.max(atr(b,14)*.35,b.get(i).c*.0007);Zone z=demand?new Zone(b.get(i).l,b.get(i).l+w,true,i):new Zone(b.get(i).h-w,b.get(i).h,false,i);if(best==null||i>best.pivotIndex)best=z;}return best;}
    private double highBefore(List<Bar>b,int end,int n){double x=-Double.MAX_VALUE;for(int i=Math.max(0,end-n);i<end;i++)x=Math.max(x,b.get(i).h);return x;}
    private double lowBefore(List<Bar>b,int end,int n){double x=Double.MAX_VALUE;for(int i=Math.max(0,end-n);i<end;i++)x=Math.min(x,b.get(i).l);return x;}
    private double avgVolBefore(List<Bar>b,int end,int n){int s=Math.max(0,end-n);double x=0;for(int i=s;i<end;i++)x+=b.get(i).v;return x/Math.max(1,end-s);}
    private double body(Bar x){return Math.abs(x.c-x.o);} private boolean bullishRejection(Bar x){double range=Math.max(1e-9,x.h-x.l);return x.c>x.o&&(x.c-x.l)/range>=.55;} private boolean bearishRejection(Bar x){double range=Math.max(1e-9,x.h-x.l);return x.c<x.o&&(x.h-x.c)/range>=.55;}
    static double atr(List<Bar>b,int n){if(b.size()<2)return 0;int s=Math.max(1,b.size()-n);double sum=0;for(int i=s;i<b.size();i++){Bar x=b.get(i),p=b.get(i-1);sum+=Math.max(x.h-x.l,Math.max(Math.abs(x.h-p.c),Math.abs(x.l-p.c)));}return sum/(b.size()-s);}
    static double vwap(List<Bar>b){double pv=0,v=0;for(Bar x:b){double tp=(x.h+x.l+x.c)/3;pv+=tp*x.v;v+=x.v;}return v==0?b.get(b.size()-1).c:pv/v;}
    static double avgVol(List<Bar>b,int n){int s=Math.max(0,b.size()-n);double x=0;for(int i=s;i<b.size();i++)x+=b.get(i).v;return x/Math.max(1,b.size()-s);}
    static double ema(List<Bar>b,int n){double a=2.0/(n+1),e=b.get(Math.max(0,b.size()-n)).c;for(int i=Math.max(0,b.size()-n)+1;i<b.size();i++)e=a*b.get(i).c+(1-a)*e;return e;}
    static double high(List<Bar>b,int n){double x=-Double.MAX_VALUE;int s=Math.max(0,b.size()-n);for(int i=s;i<b.size();i++)x=Math.max(x,b.get(i).h);return x;} static double low(List<Bar>b,int n){double x=Double.MAX_VALUE;int s=Math.max(0,b.size()-n);for(int i=s;i<b.size();i++)x=Math.min(x,b.get(i).l);return x;}
}
