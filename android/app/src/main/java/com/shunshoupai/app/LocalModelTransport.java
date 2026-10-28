package com.shunshoupai.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Direct phone transport: pinned DNS address, normal TLS hostname validation, no redirects. */
final class LocalModelTransport {
    interface Resolver { InetAddress[] resolve(String host)throws Exception; }
    private final Resolver resolver;
    private static final int MAX_RESPONSE=1024*1024;
    private static final ThreadPoolExecutor DNS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"model-dns");t.setDaemon(true);return t;});
    private static final ScheduledThreadPoolExecutor DEADLINES=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"model-deadline");t.setDaemon(true);return t;});
    private volatile Socket active;
    private volatile long generation;
    LocalModelTransport(){this(InetAddress::getAllByName);}
    LocalModelTransport(Resolver resolver){this.resolver=resolver;}
    synchronized void cancel(){generation++;Socket socket=active;active=null;if(socket!=null)try{socket.close();}catch(Exception ignored){}}
    static String normalizeBase(String text,boolean allowPrivate)throws LocalPlanningException {
        try {
            if(text.isEmpty()||text.length()>2048||text.chars().anyMatch(c->c<=32||c==127)||text.contains("\\"))throw new IllegalArgumentException();
            URI uri=new URI(text);String scheme=uri.getScheme(),host=uri.getHost();
            if(host==null||!("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()==0||uri.getPort()>65535)throw new IllegalArgumentException();
            host=host.replace("[","").replace("]","").toLowerCase(Locale.ROOT);
            if(host.contains("%")||host.endsWith(".")||host.isEmpty())throw new IllegalArgumentException();
            if(host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")){
                if(!host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))throw new IllegalArgumentException();
                for(String part:host.split("\\."))if(Integer.parseInt(part)>255)throw new IllegalArgumentException();
            }
            String path=uri.getRawPath()==null?"":uri.getRawPath().replaceAll("/+$","");
            if(path.toLowerCase(Locale.ROOT).endsWith("/chat/completions"))throw new LocalPlanningException("INVALID_MODEL_URL","请填写 Base URL，程序会自动追加 /chat/completions。");
            if(!allowPrivate&&!scheme.equalsIgnoreCase("https"))throw blocked();
            if(host.contains(":")||host.matches("[0-9.]+"))checkAddress(InetAddress.getByName(host),allowPrivate,scheme.equalsIgnoreCase("https"));
            return new URI(scheme.toLowerCase(Locale.ROOT)+"://"+(host.contains(":")?"["+host+"]":host)+(uri.getPort()<0?"":":"+uri.getPort())+path).toASCIIString();
        }catch(LocalPlanningException failure){throw failure;}catch(Exception failure){throw new LocalPlanningException("INVALID_MODEL_URL","请填写有效的 HTTP(S) Base URL，不要包含账号、密码、查询参数或片段。");}
    }
    private static LocalPlanningException blocked(){return new LocalPlanningException("MODEL_ADDRESS_BLOCKED","默认只连接公网 HTTPS 模型。使用自己的本地模型时，请明确允许此接口访问私有网络；系统与元数据地址不可使用。");}
    private static void checkAddress(InetAddress address,boolean allowPrivate,boolean https)throws LocalPlanningException {
        byte[] b=address.getAddress();boolean local=address.isLoopbackAddress()||address.isSiteLocalAddress()||(b.length==16&&(b[0]&0xfe)==0xfc);
        if(address.isAnyLocalAddress()||address.isMulticastAddress()||address.isLinkLocalAddress())throw blocked();
        if(local){if(!allowPrivate)throw blocked();return;}
        if(!https)throw blocked();
        if(b.length==4){int x=b[0]&255,y=b[1]&255,z=b[2]&255;
            if(x==0||x>=224||x==100&&y>=64&&y<=127||x==192&&(y==0||y==88&&z==99||y==175&&z==48||y==31&&z==196||y==52&&z==193)||x==198&&(y==18||y==19||y==51&&z==100)||x==203&&y==0&&z==113)throw blocked();
        }else if(b.length==16){int first=b[0]&255,second=b[1]&255;if((first&0xe0)!=0x20||first==0x20&&second==1&&((b[2]&255)<2||(b[2]&255)==0x0d&&(b[3]&255)==0xb8)||first==0x20&&second==2||first==0x3f&&second==0xff&&(b[2]&0xf0)==0||first==0x26&&second==0x20&&b[2]==0&&(b[3]&255)==0x4f&&(b[4]&255)==0x80&&b[5]==0)throw blocked();}
        else throw blocked();
    }
    String request(LocalModelSettings.Config config,JSONArray messages)throws Exception {
        final long requestGeneration=generation;Socket socket=null;Attempt attempt=new Attempt();ScheduledFuture<?> deadline=null;long started=android.os.SystemClock.elapsedRealtime();
        try {
            URI uri=new URI(normalizeBase(config.baseUrl,config.allowPrivate));String host=uri.getHost().replace("[","").replace("]","");boolean https="https".equals(uri.getScheme());
            Future<InetAddress[]> lookup=DNS.submit(()->resolver.resolve(host));InetAddress[] answers;
            try{answers=lookup.get(5,TimeUnit.SECONDS);}finally{lookup.cancel(true);}
            if(answers.length==0)throw blocked();List<InetAddress> addresses=new ArrayList<>();
            // Validate every DNS answer before opening any socket, including
            // answers beyond the bounded fallback list. Never resolve a second time.
            for(InetAddress address:answers){checkAddress(address,config.allowPrivate,https);if(!addresses.contains(address))addresses.add(address);}
            addresses.sort(Comparator.comparingInt(address->address.getAddress().length==4?0:1));
            if(requestGeneration!=generation)throw cancelled();int port=uri.getPort()<0?(https?443:80):uri.getPort();
            deadline=DEADLINES.schedule(attempt::expire,Math.max(1,25000-(android.os.SystemClock.elapsedRealtime()-started)),TimeUnit.MILLISECONDS);
            socket=connect(addresses,port,started,requestGeneration,attempt);
            if(https){SSLSocket secure=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(socket,host,port,true);SSLParameters parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(parameters);secure.startHandshake();socket=secure;}
            JSONObject request=new JSONObject().put("model",config.model).put("messages",messages).put("temperature",0).put("response_format",new JSONObject().put("type","json_object"));
            byte[] body=request.toString().getBytes(StandardCharsets.UTF_8);String path=(uri.getRawPath()==null?"":uri.getRawPath())+"/chat/completions";
            String authority=uri.getRawAuthority();String headers="POST "+path+" HTTP/1.1\r\nHost: "+authority+"\r\nAuthorization: Bearer "+config.apiKey+"\r\nContent-Type: application/json\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n";
            OutputStream output=socket.getOutputStream();output.write(headers.getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
            String response=readResponse(socket.getInputStream());if(requestGeneration!=generation)throw cancelled();
            JSONObject parsed=PlanningJson.object(response,MAX_RESPONSE);JSONArray choices=parsed.getJSONArray("choices");if(choices.length()==0)throw new IllegalArgumentException();
            String content=PlanningJson.string(choices.getJSONObject(0).getJSONObject("message"),"content");if(content.trim().isEmpty()||content.length()>65536)throw new IllegalArgumentException();return content;
        }catch(LocalPlanningException failure){throw failure;}
        catch(java.util.concurrent.TimeoutException|java.net.SocketTimeoutException failure){throw new LocalPlanningException("MODEL_TIMEOUT","模型请求超时，输入仍保留。可重试或使用本地规则。");}
        catch(Exception failure){if(requestGeneration!=generation)throw cancelled();if(android.os.SystemClock.elapsedRealtime()-started>=24000)throw new LocalPlanningException("MODEL_TIMEOUT","模型请求超时，输入仍保留。可重试或使用本地规则。");throw new LocalPlanningException("MODEL_REQUEST_FAILED","无法取得有效的模型响应。请检查手机网络、接口和密钥；也可使用本地规则。");}
        finally {if(deadline!=null)deadline.cancel(false);attempt.close();if(socket!=null)try{socket.close();}catch(Exception ignored){}synchronized(this){if(active==attempt.socket)active=null;}}
    }
    private static final class Attempt {
        volatile Socket socket;volatile boolean expired;
        void expire(){expired=true;close();}
        void close(){Socket current=socket;if(current!=null)try{current.close();}catch(Exception ignored){}}
    }
    private Socket connect(List<InetAddress> addresses,int port,long started,long requestGeneration,Attempt attempt)throws Exception {
        java.io.IOException last=null;
        for(int index=0;index<Math.min(4,addresses.size());index++) {
            long remaining=25000-(android.os.SystemClock.elapsedRealtime()-started);
            if(remaining<=0||attempt.expired)throw new java.net.SocketTimeoutException();
            Socket candidate=new Socket();
            synchronized(this){
                if(requestGeneration!=generation){candidate.close();throw cancelled();}
                if(active!=null&&active!=attempt.socket){candidate.close();throw new LocalPlanningException("MODEL_BUSY","已有模型请求进行中，请稍候。");}
                active=candidate;attempt.socket=candidate;
            }
            try {
                if(attempt.expired)throw new java.net.SocketTimeoutException();
                candidate.connect(new InetSocketAddress(addresses.get(index),port),(int)Math.min(5000,remaining));
                if(requestGeneration!=generation)throw cancelled();
                remaining=25000-(android.os.SystemClock.elapsedRealtime()-started);
                if(remaining<=0||attempt.expired)throw new java.net.SocketTimeoutException();
                candidate.setSoTimeout((int)Math.min(15000,remaining));return candidate;
            }catch(java.io.IOException failure){
                candidate.close();last=failure;
                if(requestGeneration!=generation)throw cancelled();
                if(attempt.expired||android.os.SystemClock.elapsedRealtime()-started>=25000)throw new java.net.SocketTimeoutException();
            }
        }
        // TLS and HTTP happen only after a successful TCP connection. Failure
        // there never replays a request or sends the key to another address.
        throw last==null?new java.io.IOException("No verified endpoint"):last;
    }
    private LocalPlanningException cancelled(){return new LocalPlanningException("CANCELLED","本次整理已取消，输入仍保留。");}
    private static String readResponse(InputStream input)throws Exception {
        String status=line(input,8192);String[] words=status.split(" ",3);if(words.length<2||!words[0].matches("HTTP/1\\.[01]")||!words[1].matches("[0-9]{3}"))throw new IllegalArgumentException();int code=Integer.parseInt(words[1]);
        Map<String,String> headers=new HashMap<>();int total=status.length();
        for(int count=0;;count++){String value=line(input,8192);total+=value.length();if(count>64||total>16384)throw new IllegalArgumentException();if(value.isEmpty())break;int split=value.indexOf(':');if(split<=0)throw new IllegalArgumentException();String name=value.substring(0,split).trim().toLowerCase(Locale.ROOT);if(headers.put(name,value.substring(split+1).trim())!=null&&(name.equals("content-length")||name.equals("transfer-encoding")))throw new IllegalArgumentException();}
        if(code<200||code>=300)throw new LocalPlanningException("MODEL_HTTP_ERROR","模型服务返回 HTTP "+code+"，请检查接口、密钥和额度。输入仍保留。");
        String encoding=headers.get("content-encoding");if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IllegalArgumentException();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();String transfer=headers.get("transfer-encoding"),length=headers.get("content-length");
        if(transfer!=null){if(!transfer.equalsIgnoreCase("chunked")||length!=null)throw new IllegalArgumentException();while(true){String size=line(input,128).split(";",2)[0];if(!size.matches("[0-9a-fA-F]{1,8}"))throw new IllegalArgumentException();long count=Long.parseLong(size,16);if(count>MAX_RESPONSE-bytes.size())throw new IllegalArgumentException();if(count==0)break;copy(input,bytes,(int)count);if(!line(input,2).isEmpty())throw new IllegalArgumentException();}}
        else if(length!=null){if(!length.matches("[0-9]{1,9}"))throw new IllegalArgumentException();int count=Integer.parseInt(length);if(count>MAX_RESPONSE)throw new IllegalArgumentException();copy(input,bytes,count);}
        else{byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){if(bytes.size()+count>MAX_RESPONSE)throw new IllegalArgumentException();bytes.write(buffer,0,count);}}
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
    private static String line(InputStream input,int limit)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1;while(true){int value=input.read();if(value<0)throw new java.io.EOFException();if(previous==13&&value==10){byte[] all=bytes.toByteArray();return new String(all,0,all.length-1,StandardCharsets.US_ASCII);}if(bytes.size()>=limit)throw new IllegalArgumentException();bytes.write(value);previous=value;}}
    private static void copy(InputStream input,ByteArrayOutputStream out,int count)throws Exception {byte[] buffer=new byte[4096];while(count>0){int read=input.read(buffer,0,Math.min(buffer.length,count));if(read<0)throw new java.io.EOFException();out.write(buffer,0,read);count-=read;}}
}
