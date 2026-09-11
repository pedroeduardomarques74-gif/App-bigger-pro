package com.bigger.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class LocalBridge {
    private static final String TAG = "BIGGER-BRIDGE";
    private static final String PREF = "bigger_local_bridge";
    private static final String KEY_STATE = "panel_state";
    private static final int PORT = 8765;
    private static Context app;
    private static ServerSocket server;
    private static volatile boolean running;

    private LocalBridge() {}

    public static synchronized void start(Context context) {
        if (context == null) return;
        app = context.getApplicationContext();
        ensureDefaultState();
        populateDns();
        if (running) return;
        try {
            server = new ServerSocket(PORT, 30, InetAddress.getByName("127.0.0.1"));
            server.setReuseAddress(true);
            running = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { acceptLoop(); }
            }, "BiggerLocalBridge");
            t.setDaemon(true);
            t.start();
            Log.i(TAG, "Local bridge ready on 127.0.0.1:" + PORT);
        } catch (Throwable e) {
            Log.e(TAG, "Unable to start local bridge", e);
        }
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void ensureDefaultState() {
        if (prefs().contains(KEY_STATE)) return;
        try {
            JSONObject root = new JSONObject();
            root.put("dns", new JSONArray());
            root.put("clients", new JSONArray());
            JSONArray themes = new JSONArray();
            for (int i=1;i<=5;i++) {
                JSONObject t = new JSONObject();
                t.put("id", i); t.put("name", "Theme " + i); t.put("layout", i); t.put("active", i==1);
                themes.put(t);
            }
            root.put("themes", themes);
            JSONObject visual = new JSONObject();
            visual.put("name", "GRUPO BIGGER"); visual.put("logo", ""); visual.put("background", ""); visual.put("ads", "");
            root.put("visual", visual);
            JSONObject cfg = new JSONObject();
            cfg.put("warningMsg", ""); cfg.put("entryMsg", "");
            cfg.put("warningTextColor", "#FBF8F8"); cfg.put("warningBgColor", "#060606F7"); cfg.put("warningOpacity", 0.3); cfg.put("warningFontSize", 14); cfg.put("warningEffect", "rolante");
            cfg.put("entryTextColor", "#FBF8F8"); cfg.put("entryBgColor", "#060606F7"); cfg.put("entryOpacity", 1.0); cfg.put("entryFontSize", 15); cfg.put("entryEffect", "rolante");
            cfg.put("qrPhone", ""); cfg.put("chatbotEnabled", false); cfg.put("chatbotLink", ""); cfg.put("chatbotDns", ""); cfg.put("chatbotListName", "");
            root.put("cfg", cfg);
            JSONObject adverts = new JSONObject(); adverts.put("mode", "auto"); adverts.put("selected", "ad1"); adverts.put("groups", new JSONObject()); root.put("adverts", adverts);
            saveState(root);
        } catch (Throwable e) { Log.e(TAG, "default state", e); }
    }

    private static JSONObject state() {
        try { return new JSONObject(prefs().getString(KEY_STATE, "{}")); }
        catch (Throwable e) { return new JSONObject(); }
    }

    private static synchronized boolean saveState(JSONObject state) {
        try { return prefs().edit().putString(KEY_STATE, state.toString()).commit(); }
        catch (Throwable e) { Log.e(TAG, "save state", e); return false; }
    }

    private static void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(new Runnable() { @Override public void run() { handle(s); } }, "BiggerHttp");
                t.setDaemon(true); t.start();
            } catch (Throwable e) {
                if (running) Log.e(TAG, "accept", e);
            }
        }
    }

    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.length() == 0) { socket.close(); return; }
            String[] first = requestLine.split(" ");
            String method = first.length > 0 ? first[0] : "GET";
            String rawPath = first.length > 1 ? first[1] : "/";
            Map<String,String> headers = new LinkedHashMap<>();
            String line;
            while ((line=readLine(in)) != null && line.length() > 0) {
                int p=line.indexOf(':');
                if (p>0) headers.put(line.substring(0,p).trim().toLowerCase(Locale.US), line.substring(p+1).trim());
            }
            int len=0;
            try { len=Integer.parseInt(headers.get("content-length")); } catch (Throwable ignored) {}
            byte[] body=new byte[Math.max(0,len)];
            int off=0;
            while (off<body.length) { int n=in.read(body,off,body.length-off); if(n<0)break; off+=n; }
            route(method, rawPath, body, out);
            out.flush(); socket.close();
        } catch (Throwable e) {
            try { socket.close(); } catch (Throwable ignored) {}
            Log.e(TAG, "handle", e);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int c; boolean got=false;
        while ((c=in.read())!=-1) { got=true; if(c=='\n') break; if(c!='\r') b.write(c); }
        return got ? new String(b.toByteArray(), StandardCharsets.UTF_8) : null;
    }

    private static void route(String method, String rawPath, byte[] body, OutputStream out) throws Exception {
        String path=rawPath; int q=path.indexOf('?'); if(q>=0) path=path.substring(0,q);
        path=URLDecoder.decode(path,"UTF-8");
        if ("OPTIONS".equals(method)) { send(out,204,"text/plain",new byte[0]); return; }
        if ("/health".equals(path)) { json(out,200,new JSONObject().put("ok",true).put("port",PORT)); return; }

        if ("/panel/state".equals(path)) {
            if ("GET".equals(method)) { json(out,200,state()); return; }
            if ("POST".equals(method) || "PUT".equals(method)) {
                try {
                    JSONObject incoming=new JSONObject(new String(body,StandardCharsets.UTF_8));
                    if (!saveState(incoming)) throw new IOException("state commit failed");
                    populateDns();
                    triggerVisualRefresh();
                    json(out,200,new JSONObject().put("ok",true).put("message","saved"));
                } catch(Throwable e) {
                    json(out,400,new JSONObject().put("ok",false).put("error",String.valueOf(e.getMessage())));
                }
                return;
            }
        }

        if (path.startsWith("/panel/asset/")) {
            if (!"POST".equals(method) && !"PUT".equals(method)) { json(out,405,new JSONObject().put("ok",false)); return; }
            String key=path.substring("/panel/asset/".length());
            try {
                String payload=new String(body,StandardCharsets.UTF_8);
                String data=payload;
                try { JSONObject j=new JSONObject(payload); data=j.optString("data",payload); } catch(Throwable ignored) {}
                boolean ok=saveDataUrlAsset(key,data);
                if(ok) triggerVisualRefresh();
                json(out,ok?200:400,new JSONObject().put("ok",ok));
            } catch(Throwable e) { json(out,400,new JSONObject().put("ok",false).put("error",String.valueOf(e.getMessage()))); }
            return;
        }

        if ("/bigger/img/api.json".equals(path)) { json(out,200,visualApi()); return; }
        if (path.startsWith("/bigger/img/")) { serveAsset(path.substring("/bigger/img/".length()),out); return; }

        if (path.startsWith("/bigger/api/")) {
            String page=path.substring("/bigger/api/".length());
            if ("allads.php".equalsIgnoreCase(page)) { html(out,adHtml("ad1")); return; }
            if ("allads1.php".equalsIgnoreCase(page)) { html(out,adHtml("ad2")); return; }
            if ("allads2.php".equalsIgnoreCase(page)) { html(out,adHtml("ad3")); return; }
            if ("MSG_Intro_StudioLiveCode.php".equalsIgnoreCase(page)) { html(out,messageHtml(true)); return; }
            if ("Msg_Aviso_StudioLiveCode.php".equalsIgnoreCase(page)) { html(out,messageHtml(false)); return; }
            if ("qr.php".equalsIgnoreCase(page)) { html(out,qrHtml()); return; }
            if ("bg.php".equalsIgnoreCase(page)) { html(out,bgHtml()); return; }
        }

        json(out,404,new JSONObject().put("ok",false).put("error","not_found").put("path",path));
    }

    private static JSONObject visualApi() throws Exception {
        JSONObject s=state(); JSONObject v=s.optJSONObject("visual"); if(v==null)v=new JSONObject();
        JSONObject d=new JSONObject();
        d.put("logo", assetExists("globalLogo") ? "logo.png" : "transparent.png");
        d.put("background", assetExists("globalBackground") ? "background.png" : "transparent.png");
        d.put("ads", "transparent.png");
        d.put("name", v.optString("name","GRUPO BIGGER"));
        JSONArray a=new JSONArray(); a.put(d);
        return new JSONObject().put("code",200).put("data",a).put("msg","success");
    }

    private static File assetFile(String key) {
        String n="globalLogo".equals(key)?"logo.bin":"globalBackground".equals(key)?"background.bin":key.replaceAll("[^A-Za-z0-9._-]","_")+".bin";
        return new File(app.getFilesDir(),"bridge_"+n);
    }
    private static boolean assetExists(String key) { File f=assetFile(key); return f.exists()&&f.length()>0; }

    private static boolean saveDataUrlAsset(String key,String data) {
        try {
            if(data==null||data.length()==0) { File f=assetFile(key); if(f.exists())f.delete(); return true; }
            int comma=data.indexOf(','); String encoded=comma>=0?data.substring(comma+1):data;
            byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);
            FileOutputStream fos=new FileOutputStream(assetFile(key)); fos.write(bytes); fos.flush(); fos.close(); return true;
        } catch(Throwable e) { Log.e(TAG,"asset",e); return false; }
    }

    private static void serveAsset(String name,OutputStream out) throws Exception {
        if("transparent.png".equals(name)) { send(out,200,"image/png",transparentPng()); return; }
        File f;
        if("logo.png".equals(name)) f=assetFile("globalLogo");
        else if("background.png".equals(name)) f=assetFile("globalBackground");
        else { send(out,404,"text/plain",new byte[0]); return; }
        if(!f.exists()) { send(out,200,"image/png",transparentPng()); return; }
        byte[] bytes=readAll(new FileInputStream(f));
        send(out,200,guessImageMime(bytes),bytes);
    }

    private static byte[] transparentPng() { return Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAF/gL+AV6qWQAAAABJRU5ErkJggg==",Base64.DEFAULT); }
    private static String guessImageMime(byte[] b) { if(b.length>3 && (b[0]&255)==0x89 && b[1]=='P' && b[2]=='N' && b[3]=='G') return "image/png"; return "image/jpeg"; }

    private static String adHtml(String key) {
        try {
            JSONObject a=state().optJSONObject("adverts");
            JSONObject groups=a==null?null:a.optJSONObject("groups");
            JSONArray arr=groups==null?null:groups.optJSONArray(key);
            if(arr==null && a!=null) {
                JSONObject items=a.optJSONObject("items");
                Object old=items==null?null:items.opt(key);
                if(old instanceof JSONArray) arr=(JSONArray)old;
                else if(old instanceof JSONObject) { arr=new JSONArray(); arr.put(old); }
            }
            String title="",url="";
            if(arr!=null && arr.length()>0) { JSONObject o=arr.optJSONObject(0); if(o!=null){title=o.optString("title","");url=o.optString("url",o.optString("content",""));} }
            String content;
            if(url.startsWith("http://")||url.startsWith("https://")||url.startsWith("data:image/")) content="<img src=\""+esc(url)+"\" style=\"max-width:100%;max-height:100vh;object-fit:contain\">";
            else content="<div>"+esc(url.length()>0?url:title)+"</div>";
            return page(content,"transparent");
        } catch(Throwable e) { return page("","transparent"); }
    }

    private static String messageHtml(boolean intro) {
        try {
            JSONObject c=state().optJSONObject("cfg"); if(c==null)c=new JSONObject();
            String prefix=intro?"entry":"warning";
            String msg=c.optString(prefix+"Msg","");
            String fg=c.optString(prefix+"TextColor","#FFFFFF");
            String bg=c.optString(prefix+"BgColor","#000000");
            int size=c.optInt(prefix+"FontSize",15);
            double opacity=c.optDouble(prefix+"Opacity", intro?1.0:0.3);
            String inner="<div style=\"font-size:"+size+"px;color:"+esc(fg)+";padding:6px;white-space:nowrap\">"+esc(msg)+"</div>";
            return page(inner,rgba(bg,opacity));
        } catch(Throwable e) { return page("","transparent"); }
    }

    private static String qrHtml() {
        try { String phone=state().optJSONObject("cfg").optString("qrPhone",""); return page("<div style=\"color:white;font-size:22px\">"+esc(phone)+"</div>","transparent"); }
        catch(Throwable e){return page("","transparent");}
    }
    private static String bgHtml() { return "<!doctype html><html><body style=\"margin:0;background:#000 url('http://127.0.0.1:"+PORT+"/bigger/img/background.png') center/cover no-repeat;height:100vh\"></body></html>"; }
    private static String page(String inner,String bg) { return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body style=\"margin:0;background:"+bg+";color:white;font-family:sans-serif;display:flex;align-items:center;justify-content:center;overflow:hidden\">"+inner+"</body></html>"; }
    private static String rgba(String hex,double a) { try { String h=hex.replace("#",""); if(h.length()>6)h=h.substring(0,6); int n=Integer.parseInt(h,16); return "rgba("+((n>>16)&255)+","+((n>>8)&255)+","+(n&255)+","+a+")"; } catch(Throwable e){return "rgba(0,0,0,"+a+")";} }
    private static String esc(String s) { if(s==null)return ""; return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }

    public static String getTheme(Context c) {
        try {
            JSONArray a=state().optJSONArray("themes");
            if(a!=null) for(int i=0;i<a.length();i++){ JSONObject t=a.optJSONObject(i); if(t!=null&&t.optBoolean("active",false)) return String.valueOf(t.optInt("layout",t.optInt("id",1))); }
        } catch(Throwable ignored) {}
        return "1";
    }

    public static void populateDns() {
        try {
            JSONArray a=state().optJSONArray("dns");
            Class<?> andy=Class.forName("com.andyhax.AndyHax");
            Field listField=andy.getField("_haxDNS");
            Object o=listField.get(null);
            ArrayList list=(o instanceof ArrayList)?(ArrayList)o:new ArrayList();
            list.clear();
            Class<?> dc=Class.forName("com.andyhax.DNSContainer");
            Constructor<?> ctor=dc.getDeclaredConstructor(); ctor.setAccessible(true);
            if(a!=null) for(int i=0;i<a.length();i++){
                JSONObject d=a.optJSONObject(i); if(d==null||!d.optBoolean("active",true))continue;
                Object item=ctor.newInstance();
                dc.getField("DNSId").setInt(item,toInt(d.opt("id"),i+1));
                dc.getField("DNSName").set(item,d.optString("name","Servidor "+(i+1)));
                String u=d.optString("url","").trim(); while(u.endsWith("/"))u=u.substring(0,u.length()-1);
                dc.getField("DNSUrl").set(item,u);
                list.add(item);
            }
            listField.set(null,list);
            try { andy.getField("_numberAccounts").setInt(null,list.size()); } catch(Throwable ignored) {}
            Log.i(TAG,"DNS loaded: "+list.size());
        } catch(Throwable e) { Log.e(TAG,"populateDns",e); }
    }

    public static boolean isLoginAllowed(Context c,String user,String pass) {
        try {
            JSONArray a=state().optJSONArray("clients");
            if(a==null)return true;
            long now=System.currentTimeMillis();
            for(int i=0;i<a.length();i++){
                JSONObject x=a.optJSONObject(i); if(x==null)continue;
                String u=x.optString("user",x.optString("username",""));
                String p=x.optString("pass",x.optString("password",""));
                if(!u.equals(user))continue;
                if(p.length()>0 && pass!=null && pass.length()>0 && !p.equals(pass))continue;
                if(x.optBoolean("blocked",false))return false;
                long expiry=parseExpiry(x);
                if(expiry>0 && expiry<now)return false;
                return true;
            }
        } catch(Throwable e){Log.e(TAG,"allowed",e);}
        return true;
    }

    public static void noteLoginAttempt(Context c,String user,String pass) {
        try { prefs().edit().putString("last_user",user==null?"":user).putString("last_pass",pass==null?"":pass).putLong("last_attempt",System.currentTimeMillis()).apply(); }
        catch(Throwable ignored) {}
    }

    public static void noteLoginSuccess(Context c) {
        try {
            String user=prefs().getString("last_user",""); if(user.length()==0)return;
            String pass=prefs().getString("last_pass","");
            JSONObject s=state(); JSONArray clients=s.optJSONArray("clients"); if(clients==null){clients=new JSONArray();s.put("clients",clients);}
            JSONObject found=null;
            for(int i=0;i<clients.length();i++){JSONObject x=clients.optJSONObject(i);if(x!=null&&user.equals(x.optString("user",x.optString("username","")))){found=x;break;}}
            if(found==null){found=new JSONObject();found.put("id",System.currentTimeMillis());found.put("name","Cliente APK");found.put("user",user);found.put("pass",pass);found.put("mac",deviceId());found.put("blocked",false);clients.put(found);}
            found.put("last",new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date()));
            found.put("device",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL);
            found.put("version",versionName());
            saveState(s);
        } catch(Throwable e){Log.e(TAG,"note success",e);}
    }

    private static long parseExpiry(JSONObject x) {
        Object raw=x.opt("expiry"); if(raw==null)raw=x.opt("expire_date"); if(raw==null)raw=x.opt("expires"); if(raw==null)return 0;
        try { long n=Long.parseLong(String.valueOf(raw)); return n<100000000000L?n*1000L:n; } catch(Throwable ignored) {}
        String s=String.valueOf(raw);
        String[] fmts={"yyyy-MM-dd'T'HH:mm:ss","yyyy-MM-dd HH:mm:ss","yyyy-MM-dd","dd/MM/yyyy HH:mm:ss","dd/MM/yyyy"};
        for(String f:fmts)try{SimpleDateFormat df=new SimpleDateFormat(f,Locale.US);df.setLenient(false);Date d=df.parse(s);if(d!=null)return d.getTime();}catch(Throwable ignored){}
        return 0;
    }

    private static String deviceId() { try { String id=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ANDROID_ID); if(id==null)id="UNKNOWN"; MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] h=md.digest(id.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder(); for(int i=0;i<6;i++){if(i>0)b.append(':');b.append(String.format(Locale.US,"%02X",h[i]));} return b.toString(); } catch(Throwable e){return "LOCAL-DEVICE";} }
    private static String versionName(){try{return app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName;}catch(Throwable e){return "local";}}
    private static int toInt(Object o,int d){try{return Integer.parseInt(String.valueOf(o));}catch(Throwable e){return d;}}

    private static void triggerVisualRefresh() {
        try {
            Class<?> cl=Class.forName("com.bumptech.glide.load.engine.Api");
            Object api=cl.getConstructor(Context.class).newInstance(app);
            Method m=cl.getMethod("fetchDataAndDownloadImages");
            m.invoke(api);
        } catch(Throwable e) { Log.w(TAG,"visual refresh: "+e); }
    }

    private static byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)b.write(buf,0,n);in.close();return b.toByteArray(); }
    private static void json(OutputStream out,int code,JSONObject j)throws Exception{send(out,code,"application/json; charset=utf-8",j.toString().getBytes(StandardCharsets.UTF_8));}
    private static void html(OutputStream out,String h)throws Exception{send(out,200,"text/html; charset=utf-8",h.getBytes(StandardCharsets.UTF_8));}
    private static void send(OutputStream out,int code,String type,byte[] body)throws Exception{
        String reason=code>=200&&code<300?"OK":code==404?"Not Found":"Error";
        String head="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Content-Type\r\nAccess-Control-Allow-Methods: GET,POST,PUT,OPTIONS\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));out.write(body);
    }
}
