package com.simple.provision;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.Surface;

/** One-shot first-boot setup. Disables itself after it succeeds (re-runs after a factory reset). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        ContentResolver r = ctx.getContentResolver();
        try {
            // Skip setup wizard / provisioning state.
            Settings.Global.putInt(r, Settings.Global.DEVICE_PROVISIONED, 1);
            Settings.Secure.putInt(r, "user_setup_complete", 1);

            // Stay awake while on AC (1) + USB (2) + wireless (4) power.
            Settings.Global.putInt(r, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 7);

            // Lock rotation (hwrotation=90 already makes the panel landscape).
            Settings.System.putInt(r, Settings.System.ACCELEROMETER_ROTATION, 0);
            Settings.System.putInt(r, Settings.System.USER_ROTATION, Surface.ROTATION_0);
        } catch (Exception e) {
            return; // try again next boot
        }
        ctx.getPackageManager().setComponentEnabledSetting(
                new ComponentName(ctx, BootReceiver.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
