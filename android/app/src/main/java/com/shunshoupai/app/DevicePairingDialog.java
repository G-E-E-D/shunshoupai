package com.shunshoupai.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.PersistableBundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Foreground-only pairing code UI. Tickets and generated images are never persisted. */
final class DevicePairingDialog {
    interface TicketSource { JSONObject issue() throws Exception; }
    private final Context context;
    private final TicketSource source;
    private final List<String> origins;
    private final TextView status;
    private final ImageView qr;
    private final Button copy;
    private final Button address;
    private final AlertDialog dialog;
    private CountDownTimer timer;
    private String token;
    private int selected;

    DevicePairingDialog(Context context,int port,TicketSource source) {
        this.context=context;this.source=source;origins=lanOrigins(context,port);
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(4),dp(22),dp(12));
        TextView note=new TextView(context);note.setText("展示此码表示允许共享本机全部主日程和未来修改，并继续传播到对方已配对的设备。解除配对无法撤回已传出的副本，模型密钥和设置不传播。请只给自己的设备扫描；两边保持应用前台。");note.setTextSize(14);content.addView(note);
        qr=new ImageView(context);qr.setContentDescription("本机一次性配对二维码");int size=Math.min(dp(270),context.getResources().getDisplayMetrics().widthPixels-dp(88));LinearLayout.LayoutParams image=new LinearLayout.LayoutParams(size,size);image.gravity=Gravity.CENTER_HORIZONTAL;image.topMargin=dp(14);content.addView(qr,image);
        status=new TextView(context);status.setGravity(Gravity.CENTER);status.setTextSize(13);status.setPadding(0,dp(8),0,dp(8));status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(status);
        address=new Button(context);address.setAllCaps(false);address.setTextSize(12);address.setMinHeight(dp(48));content.addView(address);
        address.setOnClickListener(v->{if(origins.size()>1)new AlertDialog.Builder(context).setTitle("选择另一台设备可访问的地址").setSingleChoiceItems(origins.toArray(new String[0]),selected,(picker,index)->{selected=index;render();picker.dismiss();}).show();});
        copy=new Button(context);copy.setText("复制配对链接");copy.setAllCaps(false);copy.setMinHeight(dp(48));content.addView(copy);
        copy.setOnClickListener(v->{if(token==null||origins.isEmpty())return;ClipData clip=ClipData.newPlainText("顺手排一次性设备配对",link());if(Build.VERSION.SDK_INT>=33){PersistableBundle extra=new PersistableBundle();extra.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true);clip.getDescription().setExtras(extra);}((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);Toast.makeText(context,"已复制，只在配对有效期内使用",Toast.LENGTH_SHORT).show();});
        android.widget.ScrollView scroll=new android.widget.ScrollView(context);scroll.addView(content);
        dialog=new AlertDialog.Builder(context).setTitle("让另一台设备扫描").setView(scroll).setNegativeButton("返回",null).setPositiveButton("换一个配对码",null).create();
        dialog.setOnShowListener(ignored->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->issue());issue();});
        dialog.setOnDismissListener(ignored->{if(timer!=null)timer.cancel();token=null;qr.setImageDrawable(null);});
    }

    void show(){dialog.show();}
    void dismiss(){dialog.dismiss();}
    private void issue(){
        if(timer!=null)timer.cancel();
        if(origins.isEmpty()){status.setText("连接同一 Wi-Fi 后重新打开此页面即可配对。手机日程仍可独立使用。");copy.setEnabled(false);address.setVisibility(android.view.View.GONE);return;}
        try{
            JSONObject ticket=source.issue();token=ticket.getString("token");
            if(!token.matches("[A-Za-z0-9_-]{43}"))throw new IllegalStateException("无效配对码");
            long lifetime=Math.max(0,java.time.Instant.parse(ticket.getString("expires_at")).toEpochMilli()-System.currentTimeMillis());
            render();copy.setEnabled(true);timer=new CountDownTimer(lifetime,1000){@Override public void onTick(long remaining){status.setText("一次性配对码 · 剩余 "+((remaining+999)/1000)+" 秒");}@Override public void onFinish(){token=null;qr.setImageDrawable(null);copy.setEnabled(false);status.setText("配对码已过期，点击“换一个配对码”即可继续。");}}.start();
        }catch(Exception error){token=null;qr.setImageDrawable(null);copy.setEnabled(false);status.setText("暂时无法生成配对码，请重试。");}
    }
    private String link(){return origins.get(selected)+"#pair="+token;}
    private void render(){
        address.setText(origins.get(selected)+(origins.size()>1?" · 切换地址":""));address.setEnabled(origins.size()>1);
        if(token==null)return;
        try{Map<EncodeHintType,Object> hints=new LinkedHashMap<>();hints.put(EncodeHintType.MARGIN,2);BitMatrix bits=new QRCodeWriter().encode(link(),BarcodeFormat.QR_CODE,720,720,hints);int[] pixels=new int[720*720];for(int y=0;y<720;y++)for(int x=0;x<720;x++)pixels[y*720+x]=bits.get(x,y)?Color.BLACK:Color.WHITE;Bitmap image=Bitmap.createBitmap(pixels,720,720,Bitmap.Config.ARGB_8888);qr.setImageBitmap(image);}catch(Exception error){status.setText("无法显示二维码，可复制配对链接到另一台设备。");}
    }
    static List<String> lanOrigins(Context context,int port){
        List<InetAddress> addresses=new ArrayList<>();
        ConnectivityManager connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties properties=connectivity.getLinkProperties(connectivity.getActiveNetwork());
        if(properties!=null)for(LinkAddress link:properties.getLinkAddresses())addresses.add(link.getAddress());
        try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces!=null&&interfaces.hasMoreElements()){NetworkInterface network=interfaces.nextElement();if(!network.isUp()||network.isLoopback())continue;Enumeration<InetAddress> values=network.getInetAddresses();while(values.hasMoreElements())addresses.add(values.nextElement());}}catch(Exception ignored){}
        List<String> result=new ArrayList<>();for(InetAddress address:addresses){if(!(address instanceof Inet4Address)||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isAnyLocalAddress())continue;String origin="http://"+address.getHostAddress()+":"+port+"/";try{origin=PeerHttp.origin(origin);if(!result.contains(origin))result.add(origin);}catch(IllegalArgumentException ignored){}}return result;
    }
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
