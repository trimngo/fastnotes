package dev.floatingmemo;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
public class MemoService extends Service {
 WindowManager wm; WindowManager.LayoutParams p; LinearLayout panel,toolbar,footer; Ink ink; TextView title,status,mode; MemoStore store;
 final Handler handler=new Handler(Looper.getMainLooper()); NativeInk nativeInk; boolean minimized,attached,erase,fastWanted=true; int w,h,shade=110;
 final Runnable startNative=()->{if(attached&&!minimized&&fastWanted&&ink!=null&&ink.getWidth()>0){if(nativeInk.start(ink))status.setText("Native ink");else status.setText("Android fallback");}};
 final BroadcastReceiver screen=new BroadcastReceiver(){public void onReceive(Context c,Intent i){pauseInk();}};
 int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 int sw(){return getResources().getDisplayMetrics().widthPixels;} int sh(){return getResources().getDisplayMetrics().heightPixels;}
 @Override public IBinder onBind(Intent i){return null;}
 @Override public void onCreate(){super.onCreate();wm=(WindowManager)getSystemService(WINDOW_SERVICE);store=new MemoStore(this);nativeInk=new NativeInk(this);registerReceiver(screen,new IntentFilter(Intent.ACTION_SCREEN_OFF));}
 @Override public int onStartCommand(Intent i,int f,int id){
 NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("memo","Floating Memo",NotificationManager.IMPORTANCE_LOW));
 PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
 startForeground(1,new Notification.Builder(this,"memo").setContentTitle("Floating Memo").setContentText("Floating notebook is running").setSmallIcon(android.R.drawable.ic_menu_edit).setContentIntent(open).build());
 if(!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY;}if(!attached){try{show();}catch(RuntimeException e){android.util.Log.e("FloatingMemo","Overlay failed",e);stopSelf();}}else schedule();return START_NOT_STICKY;}
 TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.BLACK);t.setGravity(Gravity.CENTER);return t;}
 TextView button(LinearLayout r,String s,Runnable action){TextView t=text(s,14);t.setBackgroundColor(0xffeeeeee);r.addView(t,new LinearLayout.LayoutParams(0,dp(39),1));t.setOnClickListener(v->action.run());return t;}
 void show(){SharedPreferences prefs=getSharedPreferences("window",0);w=prefs.getInt("w",(int)(sw()*.72));h=prefs.getInt("h",(int)(sh()*.42));shade=prefs.getInt("shade",110);fastWanted=prefs.getBoolean("native",true);
 p=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;p.x=prefs.getInt("x",dp(10));p.y=prefs.getInt("y",dp(55));clamp();
 panel=new LinearLayout(this){@Override public boolean dispatchTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){pauseInk();return false;}return super.dispatchTouchEvent(e);}};panel.setOrientation(1);panel.setPadding(dp(1),dp(1),dp(1),dp(1));android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();border.setColor(Color.TRANSPARENT);border.setStroke(dp(1),Color.DKGRAY);panel.setBackground(border);
 LinearLayout head=new LinearLayout(this);head.setBackgroundColor(0xffdddddd);panel.addView(head,new LinearLayout.LayoutParams(-1,dp(42)));
 title=text("Memo",15);head.addView(title,new LinearLayout.LayoutParams(0,-1,1));title.setOnTouchListener(new Drag(false));
 TextView min=text("− / +",16);head.addView(min,new LinearLayout.LayoutParams(dp(60),-1));min.setOnClickListener(v->toggle());
 TextView close=text("×",20);head.addView(close,new LinearLayout.LayoutParams(dp(38),-1));close.setOnClickListener(v->stopSelf());
 toolbar=new LinearLayout(this);toolbar.setBackgroundColor(Color.WHITE);panel.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(40)));
 button(toolbar,"‹",()->change(-1));button(toolbar,"›",()->change(1));button(toolbar,"New",()->{pauseInk();store.notes.add(new MemoStore.Note());store.index=store.notes.size()-1;refresh();});
 button(toolbar,"Undo",()->{pauseInk();ArrayList<MemoStore.Stroke> a=store.current().strokes;if(!a.isEmpty())a.remove(a.size()-1);refresh();});
 TextView er=button(toolbar,"Erase",()->{});er.setOnClickListener(v->{pauseInk();erase=!erase;er.setText(erase?"Pen":"Erase");refresh();});
 button(toolbar,"Shade",()->{pauseInk();shade=shade==0?75:shade==75?145:shade==145?215:shade==215?255:0;ink.invalidate();persist();schedule();});
 ink=new Ink();panel.addView(ink,new LinearLayout.LayoutParams(-1,0,1));
 footer=new LinearLayout(this);footer.setBackgroundColor(0xffeeeeee);panel.addView(footer,new LinearLayout.LayoutParams(-1,dp(32)));
 status=text("",12);footer.addView(status,new LinearLayout.LayoutParams(0,-1,1));
 mode=text(fastWanted?"Fast: on":"Fast: off",12);footer.addView(mode,new LinearLayout.LayoutParams(dp(94),-1));mode.setOnClickListener(v->{pauseInk();fastWanted=!fastWanted;mode.setText(fastWanted?"Fast: on":"Fast: off");persist();schedule();});
 TextView resize=text("↘",18);footer.addView(resize,new LinearLayout.LayoutParams(dp(42),-1));resize.setOnTouchListener(new Drag(true));
 wm.addView(panel,p);attached=true;save();schedule();}
 void save(){boolean ok=store.save();if(title!=null)title.setText("Memo "+(store.index+1)+"/"+store.notes.size());if(status!=null)status.setText(ok?"Saved":"SAVE FAILED");}
 void pauseInk(){handler.removeCallbacks(startNative);if(nativeInk!=null)nativeInk.stop();if(ink!=null){ink.finish();ink.invalidate();}}
 void schedule(){handler.removeCallbacks(startNative);if(fastWanted&&!erase&&!minimized&&attached)handler.postDelayed(startNative,300);}
 void refresh(){ink.rebuild();save();schedule();}
 void change(int d){pauseInk();store.index=Math.max(0,Math.min(store.notes.size()-1,store.index+d));refresh();}
 void toggle(){pauseInk();save();minimized=!minimized;if(minimized){w=p.width;h=p.height;p.width=dp(220);p.height=dp(44);}else{p.width=w;p.height=h;}int vis=minimized?View.GONE:View.VISIBLE;toolbar.setVisibility(vis);ink.setVisibility(vis);footer.setVisibility(vis);update();persist();schedule();}
 void clamp(){p.width=Math.min(sw(),Math.max(dp(100),p.width));p.height=Math.min(sh()-dp(20),Math.max(dp(44),p.height));p.x=Math.max(0,Math.min(p.x,sw()-p.width));p.y=Math.max(0,Math.min(p.y,sh()-p.height-dp(20)));}
 void update(){clamp();if(attached)wm.updateViewLayout(panel,p);}
 void persist(){if(p!=null)getSharedPreferences("window",0).edit().putInt("x",p.x).putInt("y",p.y).putInt("w",w).putInt("h",h).putInt("shade",shade).putBoolean("native",fastWanted).apply();}
 @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);if(attached){pauseInk();update();schedule();}}
 @Override public void onDestroy(){pauseInk();if(store!=null)save();persist();if(attached){wm.removeView(panel);attached=false;}unregisterReceiver(screen);super.onDestroy();}
 class Drag implements View.OnTouchListener{final boolean resize;float x,y;int px,py,ow,oh;Drag(boolean r){resize=r;}public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:pauseInk();x=e.getRawX();y=e.getRawY();px=p.x;py=p.y;ow=p.width;oh=p.height;return true;case MotionEvent.ACTION_MOVE:int dx=Math.round(e.getRawX()-x),dy=Math.round(e.getRawY()-y);if(resize){p.width=Math.max(dp(300),ow+dx);p.height=Math.max(dp(220),oh+dy);}else{p.x=px+dx;p.y=py+dy;}update();return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:if(!minimized){w=p.width;h=p.height;}persist();schedule();return true;default:return true;}}}
 class Ink extends View{
 Bitmap bitmap; Canvas cache; final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); MemoStore.Stroke stroke; int pointer=-1;
 Ink(){super(MemoService.this);paint.setColor(Color.BLACK);paint.setStrokeCap(Paint.Cap.ROUND);}
 @Override protected void onSizeChanged(int a,int b,int oa,int ob){if(a>0&&b>0){pauseInk();bitmap=Bitmap.createBitmap(a,b,Bitmap.Config.ARGB_8888);cache=new Canvas(bitmap);rebuild();schedule();}}
 float width(float v){return dp(1)+dp(3)*(float)(Math.log1p(3*Math.max(0,Math.min(1,v)))/Math.log(4));}
 void segment(Canvas c,Paint pp,MemoStore.Point a,MemoStore.Point b,int w,int h){float x=a.x*w,y=a.y*h,xx=b.x*w,yy=b.y*h;pp.setStrokeWidth(width(b.p));if(x==xx&&y==yy)c.drawCircle(x,y,pp.getStrokeWidth()/2,pp);else c.drawLine(x,y,xx,yy,pp);}
 void rebuild(){if(cache==null)return;bitmap.eraseColor(Color.TRANSPARENT);for(MemoStore.Stroke s:store.current().strokes){MemoStore.Point prev=null;for(MemoStore.Point q:s.points){segment(cache,paint,prev==null?q:prev,q,getWidth(),getHeight());prev=q;}}invalidate();}
 @Override protected void onDraw(Canvas c){c.drawColor(Color.argb(shade,255,255,255));if(bitmap!=null)c.drawBitmap(bitmap,0,0,null);}
 void append(float x,float y,float pressure){if(stroke==null||cache==null)return;MemoStore.Point b=new MemoStore.Point(Math.max(0,Math.min(1,x/getWidth())),Math.max(0,Math.min(1,y/getHeight())),pressure);MemoStore.Point a=stroke.points.isEmpty()?b:stroke.points.get(stroke.points.size()-1);stroke.points.add(b);segment(cache,paint,a,b,getWidth(),getHeight());invalidate();}
 void eraseAt(float x,float y){ArrayList<MemoStore.Stroke> a=store.current().strokes;for(int i=a.size()-1;i>=0;i--){for(MemoStore.Point q:a.get(i).points){if(Math.hypot(x-q.x*getWidth(),y-q.y*getHeight())<dp(14)){a.remove(i);rebuild();save();return;}}}}
 void finish(){if(stroke!=null){stroke=null;save();}pointer=-1;}
 void accept(MemoStore.Stroke s){if(!s.points.isEmpty()){store.current().strokes.add(s);rebuild();save();}}
 @Override public boolean onTouchEvent(MotionEvent e){if(nativeInk.active)return true;int a=e.getActionMasked(),j=e.getActionIndex();if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){if(pointer!=-1||!(e.getToolType(j)==MotionEvent.TOOL_TYPE_STYLUS||e.getToolType(j)==MotionEvent.TOOL_TYPE_ERASER))return true;handler.removeCallbacks(startNative);pointer=e.getPointerId(j);if(erase||e.getToolType(j)==MotionEvent.TOOL_TYPE_ERASER){eraseAt(e.getX(j),e.getY(j));return true;}stroke=new MemoStore.Stroke();store.current().strokes.add(stroke);append(e.getX(j),e.getY(j),e.getPressure(j));return true;}if(a==MotionEvent.ACTION_CANCEL){finish();return true;}int k=e.findPointerIndex(pointer);if(k<0)return true;if(a==MotionEvent.ACTION_MOVE){if(stroke!=null){for(int i=0;i<e.getHistorySize();i++)append(e.getHistoricalX(k,i),e.getHistoricalY(k,i),e.getHistoricalPressure(k,i));append(e.getX(k),e.getY(k),e.getPressure(k));}else eraseAt(e.getX(k),e.getY(k));}else if((a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP)&&e.getPointerId(j)==pointer){if(stroke!=null)append(e.getX(k),e.getY(k),e.getPressure(k));finish();schedule();}return true;}
 }
}
