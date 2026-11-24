package dev.floatingmemo;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
public class MainActivity extends Activity {
 @Override public void onCreate(Bundle b) {
 super.onCreate(b);
 LinearLayout l=new LinearLayout(this); l.setOrientation(1); l.setPadding(32,32,32,32);
 TextView t=new TextView(this); t.setText("Floating Memo\n\nAllow display over other apps, then open your floating notebook. Notes are saved locally."); t.setTextSize(20); l.addView(t);
 Button permission=new Button(this); permission.setText("Allow floating window"); l.addView(permission);
 permission.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName()))));
 Button open=new Button(this); open.setText("Open memo"); l.addView(open);
 open.setOnClickListener(v->{if(!Settings.canDrawOverlays(this)){Toast.makeText(this,"Grant overlay permission first",Toast.LENGTH_LONG).show();return;} startForegroundService(new Intent(this,MemoService.class)); moveTaskToBack(true);});
 Button close=new Button(this);close.setText("Close floating window");l.addView(close);close.setOnClickListener(v->stopService(new Intent(this,MemoService.class)));setContentView(l);
 }
}
