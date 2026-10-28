package com.shunshoupai.app;

import java.util.UUID;

/** Only a currently requested, unconsumed system result can start a capture session. No grant is saved. */
final class CaptureGrantGate {
    private static String pending;
    private static final java.util.LinkedHashSet<String> retired=new java.util.LinkedHashSet<>();
    private static void retire(String id){if(id!=null){retired.add(id);while(retired.size()>32)retired.remove(retired.iterator().next());}}
    static synchronized String begin(){if(pending!=null)return null;pending=UUID.randomUUID().toString();return pending;}
    static synchronized boolean restore(String id){if(id==null||retired.contains(id))return false;if(pending==null)pending=id;return id.equals(pending);}
    static synchronized boolean pending(String id){return id!=null&&id.equals(pending);}
    static synchronized boolean consume(String id){if(!pending(id))return false;retire(id);pending=null;return true;}
    static synchronized void cancel(String id){if(pending(id)){retire(id);pending=null;}}
    static synchronized void stop(){retire(pending);pending=null;}
    private CaptureGrantGate(){}
}
