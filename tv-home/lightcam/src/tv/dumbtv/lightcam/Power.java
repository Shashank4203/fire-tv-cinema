package tv.dumbtv.lightcam;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Keeps the camera screen up. The phone runs off the TV's USB port, which has power only while
 * the TV is on: power coming back (or the phone starting up on power) wakes the screen and brings
 * the camera back. A watchdog alarm every two minutes, and one just after a crash, does the same
 * whenever the phone is on power and the camera screen isn't in front (Android killed it, it
 * crashed, or a phantom tap on the broken touchscreen opened something else).
 *
 * Manifest-declared, which Android still delivers ACTION_POWER_CONNECTED to because the app
 * targets API 25. Starting an activity from here needs the "display over other apps" app-op,
 * which deploy.sh grants.
 */
public final class Power extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation") // the screen-waking wake lock is the only way to turn it on from here
    public void onReceive(Context c, Intent intent) {
        schedule(c); // lost on a restart: set again on every visit
        if (CHECK.equals(intent.getAction()) && Main.showing) return; // all well
        if (!plugged(c)) return;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "lightcam:wake")
                .acquire(5000);
        c.startActivity(new Intent(c, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    static final String CHECK = "tv.dumbtv.lightcam.CHECK";
    private static final long EVERY_MS = 2 * 60_000L;

    /** The watchdog: every two minutes, waking the phone if need be. Setting it again replaces it. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + EVERY_MS, EVERY_MS,
                check(c, 1));
    }

    /** After a crash: back in three seconds. */
    static void restartSoon(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3000, check(c, 2));
    }

    private static PendingIntent check(Context c, int id) {
        return PendingIntent.getBroadcast(c, id, new Intent(c, Power.class).setAction(CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean plugged(Context c) {
        Intent battery = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }
}
