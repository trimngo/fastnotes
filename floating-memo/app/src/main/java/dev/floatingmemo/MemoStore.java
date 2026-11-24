package dev.floatingmemo;
import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
final class MemoStore {
 static final class Point { final float x,y,p; Point(float x,float y,float p){this.x=x;this.y=y;this.p=p;} }
 static final class Stroke { final ArrayList<Point> points=new ArrayList<>(); }
 static final class Note { final ArrayList<Stroke> strokes=new ArrayList<>(); }
 final ArrayList<Note> notes=new ArrayList<>(); int index; final AtomicFile file; boolean readable=true;
 MemoStore(Context c){file=new AtomicFile(new File(c.getFilesDir(),"memos.json"));try{JSONObject root=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));JSONArray all=root.getJSONArray("notes");for(int n=0;n<all.length();n++){Note note=new Note();JSONArray ss=all.getJSONArray(n);for(int s=0;s<ss.length();s++){Stroke stroke=new Stroke();JSONArray ps=ss.getJSONArray(s);for(int p=0;p<ps.length();p++){JSONArray a=ps.getJSONArray(p);stroke.points.add(new Point((float)a.getDouble(0),(float)a.getDouble(1),(float)a.getDouble(2)));}if(!stroke.points.isEmpty())note.strokes.add(stroke);}notes.add(note);}index=root.optInt("index",0);}catch(FileNotFoundException e){}catch(Exception e){readable=false;notes.clear();android.util.Log.e("FloatingMemo","Existing notes unreadable; will not overwrite",e);}if(notes.isEmpty())notes.add(new Note());index=Math.max(0,Math.min(index,notes.size()-1));}
 Note current(){return notes.get(index);}
 boolean save(){if(!readable)return false;FileOutputStream out=null;try{JSONArray all=new JSONArray();for(Note note:notes){JSONArray ss=new JSONArray();for(Stroke stroke:note.strokes){JSONArray ps=new JSONArray();for(Point p:stroke.points)ps.put(new JSONArray().put(p.x).put(p.y).put(p.p));ss.put(ps);}all.put(ss);}JSONObject root=new JSONObject().put("index",index).put("notes",all);out=file.startWrite();out.write(root.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);return true;}catch(Exception e){if(out!=null)file.failWrite(out);android.util.Log.e("FloatingMemo","Save failed",e);return false;}}
}
