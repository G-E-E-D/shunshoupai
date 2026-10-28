package com.shunshoupai.app;

import android.app.Activity;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/** Debug-only synthetic reminder setup for process-death/Doze/reboot acceptance. Never in release APKs. */
public final class BackgroundFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            CalendarRuntime runtime=CalendarRuntime.get(this);
            if(getIntent().getBooleanExtra("with_peer",false)){
                String peer=java.util.UUID.randomUUID().toString(),space=java.util.UUID.randomUUID().toString(),challenge=PeerIdentity.challenge();
                LocalDeviceIdentity identity=new LocalDeviceIdentity(this,peer);
                JSONObject proof=new JSONObject().put("peer_id",peer).put("space_id",space)
                    .put("device",new JSONObject().put("id",peer).put("name","重启同步验收设备").put("platform","android"))
                    .put("identity",identity.proof(challenge,runtime.store.deviceId(),peer,space).getJSONObject("identity"));
                runtime.store.registerPeer("http://127.0.0.1:9/",PeerIdentity.verify(proof,challenge,runtime.store.deviceId(),null,null,null),"",new JSONObject().put("replica",1));
                new DeviceCredentials(this,runtime.store.deviceId()).save(peer,space,PeerIdentity.challenge());
                getSharedPreferences("background-operation",MODE_PRIVATE).edit().putBoolean("sync",true).commit();
                runtime.maintainBackground();BackgroundSyncService.ensureStarted(this);
            }
            long delay=Math.max(5,Math.min(180,getIntent().getIntExtra("delay_seconds",25)));
            LocalDateTime start=LocalDateTime.now(LocalScheduleStore.ZONE).plusMinutes(5).plusSeconds(delay).withNano(0);
            String id=runtime.store.save(runtime.store.primaryScope(),null,LocalScheduleStore.payload("后台闹钟验收 · 虚构日程",start,start.plusHours(1),"planned",false));
            runtime.reminders.setEnabled(true);runtime.reminders.refresh();
            JSONObject fixture=new JSONObject().put("id",id).put("pid",android.os.Process.myPid())
                .put("alarm_ms",start.atZone(LocalScheduleStore.ZONE).toInstant().toEpochMilli()-ReminderScheduler.ADVANCE);
            try(FileOutputStream output=new FileOutputStream(new File(getFilesDir(),"background-fixture.json"))){output.write(fixture.toString().getBytes(StandardCharsets.UTF_8));}
        }catch(Exception error){throw new IllegalStateException("Cannot prepare isolated background fixture",error);}
        finish();
    }
}
