package com.example.nasassistant;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout root, content;
    private EditText address, account, password, otp;
    private String base, sid, currentPath = "";
    private JSONObject apis;
    private byte[] pinnedFingerprint;
    private String pinnedHost;
    private int generation = 0;
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    @Override public void onCreate(Bundle state) { super.onCreate(state); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); showLogin(); }
    @Override protected void onDestroy() { generation++; executor.shutdownNow(); super.onDestroy(); }
    private TextView text(String s, int size, int color) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); v.setPadding(0,dp(8),0,dp(8)); return v; }
    private Button button(String label, Runnable action) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); return b; }
    private void setup(String title) { root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(22),dp(20),dp(12)); root.setBackgroundColor(Color.rgb(246,248,252)); setContentView(root); TextView t=text(title,25,Color.rgb(31,42,66)); t.setTypeface(null,1); root.addView(t); }
    private EditText field(String hint, boolean secret) { EditText e = new EditText(this); e.setSingleLine(true); e.setHint(hint); if(secret) e.setInputType(129); root.addView(e); return e; }
    private void showLogin() { sid=null; apis=null; setup("NAS 助手"); root.addView(text("连接你的群晖 DSM 7",15,Color.DKGRAY)); address=field("HTTPS 地址，例如 https://nas.example.com:5001",false); address.setText(getPreferences(MODE_PRIVATE).getString("address", "")); account=field("DSM 账号",false); password=field("密码",true); otp=field("一次性验证码（如已启用）",false); root.addView(button("安全登录",this::login)); root.addView(text("仅支持受手机信任的 HTTPS 证书；密码不会保存在手机。",13,Color.GRAY)); }
    private void login() {
        String host=address.getText().toString().trim(), user=account.getText().toString(), pass=password.getText().toString(), code=otp.getText().toString().trim();
        try { URL u=new URL(host); if (!"https".equalsIgnoreCase(u.getProtocol()) || u.getHost().isEmpty() || u.getUserInfo()!=null || u.getQuery()!=null || u.getRef()!=null || !(u.getPath().isEmpty() || "/".equals(u.getPath()))) throw new Exception(); base=host.replaceAll("/+$",""); } catch(Exception e) { message("请输入完整 HTTPS 地址和端口，不要包含路径。"); return; }
        if(user.isEmpty() || pass.isEmpty()) { message("请输入账号和密码"); return; }
        pinnedFingerprint=null; pinnedHost=null;
        setup("正在连接…");
        performLogin(user,pass,code);
    }
    private void performLogin(String user,String pass,String code) {
        run(() -> {
            JSONObject info;
            try {
                info=call("query.cgi",params("api","SYNO.API.Info","version","1","method","query","query","SYNO.API.Auth,SYNO.FileStation.List,SYNO.Core.System.Utilization,SYNO.Core.System,SYNO.Storage.CGI.Storage"),false);
            } catch (SSLHandshakeException failure) {
                if (pinnedFingerprint != null) throw failure;
                X509Certificate cert=inspectCertificate();
                byte[] fingerprint=MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
                String host=new URL(base).getHost();
                StringBuilder hex=new StringBuilder();
                for(byte b:fingerprint) { if(hex.length()>0)hex.append(':'); hex.append(String.format(java.util.Locale.ROOT,"%02X",b & 255)); }
                runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                    .setTitle("证书未受信任")
                    .setMessage("地址："+host+"\n证书主题："+cert.getSubjectX500Principal().getName()+"\nSHA-256 指纹：\n"+hex+"\n\n请先在群晖 DSM 核对证书的 SHA-256 指纹。仅在完全一致时选择信任；确认后仅信任此地址的这张证书，本次运行有效。此操作也会接受证书名称与地址不匹配。")
                    .setNegativeButton("取消",(d,w)->showLogin())
                    .setPositiveButton("核对一致，信任",(d,w)->{pinnedFingerprint=fingerprint.clone();pinnedHost=host;performLogin(user,pass,code);})
                    .setCancelable(false).show());
                return;
            }
            apis=info.getJSONObject("data"); JSONObject a=apis.getJSONObject("SYNO.API.Auth"); int version=Math.min(6,a.getInt("maxVersion")); Map<String,String> p=params("api","SYNO.API.Auth","version",String.valueOf(version),"method","login","account",user,"passwd",pass,"session","FileStation","format","sid"); if(!code.isEmpty())p.put("otp_code",code); JSONObject result=call(a.getString("path"),p,false); sid=result.getJSONObject("data").getString("sid"); getPreferences(MODE_PRIVATE).edit().putString("address",base).apply(); runOnUiThread(this::showHome);
        });
    }
    private SSLContext contextFor(byte[] fingerprint) throws Exception {
        X509TrustManager tm=new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String authType) throws java.security.cert.CertificateException {throw new java.security.cert.CertificateException("不支持客户端证书");}
            public void checkServerTrusted(X509Certificate[] chain,String authType) throws java.security.cert.CertificateException {
                if(chain==null || chain.length==0)throw new java.security.cert.CertificateException("服务器未返回证书");
                if(fingerprint==null)return;
                chain[0].checkValidity();
                try {if(!MessageDigest.isEqual(fingerprint,MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())))throw new java.security.cert.CertificateException("证书指纹已变化");}
                catch(java.security.NoSuchAlgorithmException e){throw new java.security.cert.CertificateException(e);}
            }
        };
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{tm},new SecureRandom());return context;
    }
    private X509Certificate inspectCertificate() throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL(base+"/webapi/query.cgi").openConnection();
        c.setSSLSocketFactory(contextFor(null).getSocketFactory()); c.setHostnameVerifier((h,s)->true); c.setInstanceFollowRedirects(false); c.setConnectTimeout(12000);c.setReadTimeout(15000);
        try {c.connect();return (X509Certificate)c.getServerCertificates()[0];}finally{c.disconnect();}
    }

    private void showHome() { setup("NAS 助手"); LinearLayout nav=new LinearLayout(this); nav.addView(button("概览",this::showHome),new LinearLayout.LayoutParams(0,-2,1)); nav.addView(button("文件",()->showFiles("")),new LinearLayout.LayoutParams(0,-2,1)); nav.addView(button("退出",this::logout),new LinearLayout.LayoutParams(0,-2,1)); root.addView(nav); ScrollView scroll=new ScrollView(this); content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content); root.addView(scroll); content.addView(text("正在读取设备数据…",15,Color.GRAY)); run(() -> { StringBuilder s=new StringBuilder("设备状态\n"); optional(s,"SYNO.Core.System.Utilization","get", "资源使用"); optional(s,"SYNO.Core.System","info", "设备信息"); optional(s,"SYNO.Storage.CGI.Storage","load_info", "存储空间"); runOnUiThread(()->{ if(content!=null){content.removeAllViews(); content.addView(text(s.toString(),15,Color.DKGRAY));} }); }); }
    private void optional(StringBuilder out,String api,String method,String label) { out.append("\n").append(label).append("\n"); try { JSONObject a=apis.getJSONObject(api); JSONObject result=call(a.getString("path"),params("api",api,"version",String.valueOf(a.getInt("minVersion")),"method",method,"_sid",sid),false); out.append(result.optJSONObject("data").toString(2)).append("\n"); } catch(Exception e) { out.append("当前设备或账号未提供此信息（").append(e.getMessage()).append("）\n"); } }
    private void showFiles(String path) { currentPath=path; setup("文件"); LinearLayout nav=new LinearLayout(this); nav.addView(button("概览",this::showHome),new LinearLayout.LayoutParams(0,-2,1)); nav.addView(button("返回上级",()->{ int p=path.lastIndexOf('/'); showFiles(p<=0?"":path.substring(0,p)); }),new LinearLayout.LayoutParams(0,-2,1)); root.addView(nav); root.addView(text(path.isEmpty()?"共享文件夹":path,14,Color.GRAY)); ScrollView scroll=new ScrollView(this); LinearLayout list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list); root.addView(scroll); TextView loading=text("正在读取…",14,Color.GRAY); list.addView(loading);
        run(() -> { JSONObject a=apis.getJSONObject("SYNO.FileStation.List"); Map<String,String> p=params("api","SYNO.FileStation.List","version",String.valueOf(Math.min(2,a.getInt("maxVersion"))),"method",path.isEmpty()?"list_share":"list","additional","[\"size\",\"time\"]","limit","500","_sid",sid); if(!path.isEmpty())p.put("folder_path",path); JSONArray files=call(a.getString("path"),p,false).getJSONObject("data").getJSONArray("files"); runOnUiThread(()->{list.removeAllViews(); if(files.length()==0)list.addView(text("这里没有文件",15,Color.GRAY)); for(int i=0;i<files.length();i++){JSONObject f=files.optJSONObject(i); if(f==null)continue; String name=f.optString("name"), filePath=f.optString("path"); boolean dir=f.optBoolean("isdir"); JSONObject extra=f.optJSONObject("additional"); long size=extra==null?0:extra.optLong("size",0); TextView row=text((dir?"📁  ":"📄  ")+name+(dir?"": "\n       "+formatSize(size)),16,Color.rgb(37,48,69)); row.setPadding(dp(8),dp(14),dp(8),dp(14)); if(dir)row.setOnClickListener(v->showFiles(filePath)); list.addView(row); View line=new View(this); line.setBackgroundColor(0xffe4e8ef); list.addView(line,new LinearLayout.LayoutParams(-1,dp(1))); } }); }); }
    private String formatSize(long bytes){ if(bytes<1024)return bytes+" B"; double x=bytes; String[] u={"B","KB","MB","GB","TB"}; int i=0; while(x>=1024&&i<u.length-1){x/=1024;i++;} return String.format(java.util.Locale.ROOT,"%.1f %s",x,u[i]); }
    private void logout() { final JSONObject a=apis.optJSONObject("SYNO.API.Auth"); final String old=sid; if(a!=null&&old!=null)executor.execute(()->{try{call(a.getString("path"),params("api","SYNO.API.Auth","version",String.valueOf(Math.min(6,a.getInt("maxVersion"))),"method","logout","session","FileStation","_sid",old),false);}catch(Exception ignored){}}); showLogin(); }
    private interface Task { void work() throws Exception; }
    private void run(Task task) { final int request=++generation; executor.execute(()->{try{task.work();}catch(Exception e){runOnUiThread(()->{if(request==generation){ new android.app.AlertDialog.Builder(this).setTitle("连接失败").setMessage(e.getMessage()).setPositiveButton("返回",(d,w)->showLogin()).show(); }});}}); }
    private void message(String s){new android.app.AlertDialog.Builder(this).setMessage(s).setPositiveButton("确定",null).show();}
    private Map<String,String> params(String...args){ Map<String,String> m=new LinkedHashMap<>(); for(int i=0;i<args.length;i+=2)m.put(args[i],args[i+1]);return m; }
    private JSONObject call(String path,Map<String,String> data,boolean unused)throws Exception { if(path.startsWith("/")||path.contains("..")||path.contains(":")||path.contains("?")||path.contains("#"))throw new Exception("不安全的接口路径"); StringBuilder body=new StringBuilder(); for(Map.Entry<String,String> e:data.entrySet()){if(body.length()>0)body.append('&');body.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));} HttpURLConnection c=(HttpURLConnection)new URL(base+"/webapi/"+path).openConnection(); if(pinnedFingerprint!=null) { final String expectedHost=pinnedHost; HttpsURLConnection tls=(HttpsURLConnection)c; tls.setSSLSocketFactory(contextFor(pinnedFingerprint.clone()).getSocketFactory()); tls.setHostnameVerifier((hostname,session)->hostname.equalsIgnoreCase(expectedHost)); } c.setInstanceFollowRedirects(false); c.setConnectTimeout(12000); c.setReadTimeout(15000); c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8"); try { c.getOutputStream().write(body.toString().getBytes("UTF-8")); int status=c.getResponseCode(); if(status!=200)throw new Exception("HTTP "+status); try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>4_000_000)throw new Exception("响应过大");} JSONObject j=new JSONObject(out.toString("UTF-8")); if(!j.optBoolean("success")){int error=j.optJSONObject("error")==null?-1:j.getJSONObject("error").optInt("code",-1);throw new Exception("DSM 错误码 "+error+"；请检查账号、验证码和权限");}return j;} }finally{c.disconnect();} }
}
