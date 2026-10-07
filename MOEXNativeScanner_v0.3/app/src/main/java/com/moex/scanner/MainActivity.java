package com.moex.scanner;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** v0.5: ranked scanner UI, filters, auto-refresh and Android notifications. */
public class MainActivity extends Activity {
    LinearLayout list; TextView status; SecureStore store; FutoSnapshotStore snaps;
    final String[] tickers={"SiZ6","RIZ6","MXZ6","GZZ6","SRZ6","LKZ6","RNZ6","TBZ6","BRX6"};
    final ArrayList<ScannerEngine.Result> results=new ArrayList<>();
    String filter="ALL"; Handler handler=new Handler(Looper.getMainLooper());
    boolean scanning=false; final Runnable auto=new Runnable(){public void run(){scan();handler.postDelayed(this,300000);}};
    int refreshNo=0;

    @Override public void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_main);
        store=new SecureStore(this); snaps=new FutoSnapshotStore(this);
        list=findViewById(R.id.list); status=findViewById(R.id.status);
        findViewById(R.id.settings).setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));
        findViewById(R.id.refresh).setOnClickListener(v->scan());
        findViewById(R.id.filterAll).setOnClickListener(v->{filter="ALL";render();});
        findViewById(R.id.filterLong).setOnClickListener(v->{filter="LONG";render();});
        findViewById(R.id.filterShort).setOnClickListener(v->{filter="SHORT";render();});
        findViewById(R.id.filterWait).setOnClickListener(v->{filter="WAIT";render();});
        createChannel();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);
        scan();
    }
    @Override protected void onResume(){super.onResume();handler.postDelayed(auto,300000);}
    @Override protected void onPause(){super.onPause();handler.removeCallbacks(auto);}

    void scan(){
        if(scanning)return; String token=store.get();
        if(token.isEmpty()){status.setText("Добавьте Algopack-токен в ⚙");return;}
        scanning=true; refreshNo++; status.setText("Сканирование • H1→M5 • FIZ/YUR • OI…");
        new Thread(()->{
            ArrayList<ScannerEngine.Result> fresh=new ArrayList<>(); ArrayList<String> errors=new ArrayList<>();
            MarketApi api=new MarketApi(token); ScannerEngine eng=new ScannerEngine();
            for(String t:tickers){try{
                MarketApi.FutoI f=MarketApi.parseFutoi(api.futoi(t)); double liveOi=f.oi(); try{ double x=api.openPosition(t); if(x>0) liveOi=x; }catch(Exception ignored){}
                    MarketApi.FutoDelta d=snaps.delta(t,f,liveOi);
                List<ScannerEngine.Bar> h1=new ArrayList<>(),m1=new ArrayList<>(); String today=java.time.LocalDate.now().minusDays(4).toString();
String h1from=java.time.LocalDate.now().minusDays(14).toString();

parse(api.candles(t,60,h1from),h1);
parse(api.candles(t,1,today),m1);

/* Защита от старых свечей.
   marketdata может иметь свежую LAST, а candles — старую историю
   (особенно BR). В таком случае не используем старые ATR/VWAP
   как будто они актуальные. */
long now=System.currentTimeMillis();
boolean m1Fresh=!m1.isEmpty()
        && m1.get(m1.size()-1).ts>0
        && now-m1.get(m1.size()-1).ts < 30L*60L*1000L;

boolean h1Fresh=!h1.isEmpty()
        && h1.get(h1.size()-1).ts>0
        && now-h1.get(h1.size()-1).ts < 3L*60L*60L*1000L;

if(!m1Fresh || !h1Fresh){
    android.util.Log.w("MOEXSCAN",
        t+" STALE CANDLES m1Fresh="+m1Fresh+
        " h1Fresh="+h1Fresh);
}
                List<ScannerEngine.Bar> m5=aggregate5Aligned(m1); if(m5.size()>300)m5=new ArrayList<>(m5.subList(m5.size()-300,m5.size()));
                ScannerEngine.Result r=eng.analyze(t,h1,m5,f,d); double livePrice=api.lastPrice(t); if(livePrice>0) r.price=livePrice; fresh.add(r);
            }catch(Exception e){errors.add(t+": "+safe(e.getMessage())); android.util.Log.e("MOEXSCAN",t+": "+safe(e.getMessage()),e);}}
            runOnUiThread(()->{results.clear();results.addAll(fresh);results.sort((a,b)->Double.compare(Math.abs(b.score),Math.abs(a.score)));render();
                status.setText("Обновлено • "+results.size()+" инструментов • авто 5 мин"+(errors.isEmpty()?"":" • ошибок "+errors.size())+"\n"+String.join("\n",errors));
                notifyStrongSignals(fresh); scanning=false;});
        }).start();
    }
    String safe(String s){return s==null?"ошибка запроса":s;}
    void parse(MarketApi.CandleSeries series,List<ScannerEngine.Bar> out){
        org.json.JSONArray cols=series.columns, data=series.data;
        java.util.Map<String,Integer> ix=new java.util.HashMap<>();
        for(int i=0;i<cols.length();i++)ix.put(cols.optString(i).toLowerCase(java.util.Locale.US),i);
        Integer io=ix.get("open"), ic=ix.get("close"), ih=ix.get("high"), il=ix.get("low"), iv=ix.get("volume"), ib=ix.get("begin");
        if(io==null||ic==null||ih==null||il==null)return;
        for(int i=0;i<data.length();i++){org.json.JSONArray a=data.optJSONArray(i);if(a==null)continue;try{
            long ts=0; if(ib!=null){String z=a.optString(ib,""); try{ts=java.time.Instant.parse(z.replace(" ","T")+"Z").toEpochMilli();}catch(Exception ignored){}}
            double v=iv==null?0:a.optDouble(iv,0); out.add(new ScannerEngine.Bar(a.optDouble(io),a.optDouble(ih),a.optDouble(il),a.optDouble(ic),v,ts));
        }catch(Exception ignored){}}
    }
    List<ScannerEngine.Bar> aggregate5Aligned(List<ScannerEngine.Bar> m){
        List<ScannerEngine.Bar> out=new ArrayList<>(); if(m.isEmpty())return out;
        java.util.LinkedHashMap<Long,ScannerEngine.Bar> buckets=new java.util.LinkedHashMap<>();
        for(ScannerEngine.Bar x:m){ if(x.ts<=0)continue; long k=(x.ts/300000L)*300000L; ScannerEngine.Bar b=buckets.get(k);
            if(b==null)buckets.put(k,new ScannerEngine.Bar(x.o,x.h,x.l,x.c,x.v,k)); else {b.h=Math.max(b.h,x.h);b.l=Math.min(b.l,x.l);b.c=x.c;b.v+=x.v;} }
        out.addAll(buckets.values()); return out;
    }

    void render(){list.removeAllViews(); ArrayList<ScannerEngine.Result> view=new ArrayList<>(); for(ScannerEngine.Result r:results)if(filter.equals("ALL")||r.signal.equals(filter))view.add(r);
        view.sort((a,b)->Double.compare(Math.abs(b.score),Math.abs(a.score)));
        if(view.isEmpty()){TextView e=new TextView(this);e.setText("Нет сигналов в выбранном фильтре");e.setTextColor(0xffB0BEC5);e.setPadding(16,24,16,24);list.addView(e);return;}
        for(ScannerEngine.Result r:view)addCard(r);
    }
    void addCard(ScannerEngine.Result r){
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(16,14,16,14);
        GradientDrawable bg=new GradientDrawable(); bg.setCornerRadius(18); bg.setStroke(1,0xff30343b); bg.setColor(r.signal.equals("LONG")?0xff10251a:r.signal.equals("SHORT")?0xff2a1517:0xff17191d); card.setBackground(bg);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,0,0,10);card.setLayoutParams(cp);
        TextView head=t("%s   •   %s   •   Оценка %s\n%s",r.ticker,r.signal,String.format(Locale.US,"%+.2f",r.score),r.setup);head.setTextSize(16);head.setTextColor(r.signal.equals("LONG")?0xff7CFF9B:r.signal.equals("SHORT")?0xffff8a80:Color.WHITE);head.setTypeface(null,1);card.addView(head);
            card.addView(t(r.progress));
            card.addView(t("Готовность сетапа: "+r.readiness+"%"));
        card.addView(t("Цена %s   Вход %s\nСтоп %s   Цель1 %s   Цель2 %s",fmt(r.price),fmt(r.entry),fmt(r.sl),fmt(r.tp1),fmt(r.tp2)));
        card.addView(t("H1 ATR %s   M5 ATR %s   VWAP %s",fmt(r.h1Atr),fmt(r.atr),fmt(r.vwap)));
        String y=String.format(Locale.US,"ЮР   Лонг %.0f / Шорт %.0f / Баланс %+.0f",r.futoI.yur.longPos,r.futoI.yur.shortPos,r.futoI.yur.net());
        String f=String.format(Locale.US,"ФИЗ  L %.0f / S %.0f / Net %+.0f",r.futoI.fiz.longPos,r.futoI.fiz.shortPos,r.futoI.fiz.net());
        String d="Δ "+(r.delta==null?"—":String.format(Locale.US,"ЮР L %+.0f S %+.0f | ФИЗ L %+.0f S %+.0f | OI %+.0f",r.delta.yur==null?0:r.delta.yur.dLong,r.delta.yur==null?0:r.delta.yur.dShort,r.delta.fiz==null?0:r.delta.fiz.dLong,r.delta.fiz==null?0:r.delta.fiz.dShort,r.delta.oiChange));
        card.addView(t(y+"\n"+f+"\n"+d));
        card.addView(t("FUTOI режим: "+(r.positionRegime==null?"—":r.positionRegime)));
        card.addView(t(r.reasons)); list.addView(card);
    }
    TextView t(String fmt,Object...a){TextView v=new TextView(this);v.setText(String.format(Locale.US,fmt,a));v.setTextColor(0xffD8DEE9);v.setTextSize(13);v.setPadding(0,5,0,5);return v;}
    String fmt(double x){return x==0?"—":String.format(Locale.US,"%.2f",x);}

    void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel("signals","MOEX Signals",NotificationManager.IMPORTANCE_DEFAULT);c.setDescription("Сильные LONG/SHORT сигналы сканера");getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    void notifyStrongSignals(List<ScannerEngine.Result> rs){
        if(refreshNo<=1)return; NotificationManager nm=getSystemService(NotificationManager.class); int id=100;
        for(ScannerEngine.Result r:rs){if(Math.abs(r.score)<4.5 || r.signal.equals("WAIT"))continue;
            String key="last_signal_"+r.ticker; String prev=getPreferences(0).getString(key,""); String now=r.signal+":"+String.format(Locale.US,"%.1f",r.score);
            if(prev.startsWith(r.signal))continue; getPreferences(0).edit().putString(key,now).apply();
            if(Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED){
                Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"signals"):new Notification.Builder(this);
                b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(r.ticker+" • "+r.signal).setContentText("Score "+String.format(Locale.US,"%.1f",r.score)+" | Entry "+fmt(r.entry)+" | SL "+fmt(r.sl)).setAutoCancel(true);nm.notify(id++,b.build());
            }
        }
    }
}
