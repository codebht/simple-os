package com.simple.home;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ambient home screen: big clock, date, weather (Open-Meteo, no key/tracking),
 * next calendar events. Tap anywhere -> transparent control panel with
 * Volume + Brightness sliders and an Apps button.
 *
 * Weather location/unit are read from Settings.System keys written by the
 * Settings app: simple_lat, simple_lon, simple_unit ("C" or "F").
 */
public class HomeActivity extends Activity {

    private static final long HIDE_DELAY_MS = 6000;
    private static final long EVENTS_EVERY_MS = 5 * 60_000;
    private static final long WEATHER_MAX_AGE_MS = 30 * 60_000;
    private static final String APPS_ACTION = "com.simple.action.APPS";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random rnd = new Random();

    private LinearLayout content, panel;
    private TextView weather, events;
    private SeekBar volBar, briBar;
    private AudioManager audio;

    private String lastWeatherKey, lastWeatherText;
    private long lastWeatherAt;

    private final Runnable hidePanel = () -> panel.setVisibility(View.GONE);

    // Shifts content a few pixels every minute so a static clock can't burn in the panel.
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            int m = dp(14);
            content.setTranslationX(rnd.nextInt(2 * m + 1) - m);
            content.setTranslationY(rnd.nextInt(2 * m + 1) - m);
            ui.postDelayed(this, 60_000);
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            loadWeather();
            loadEvents();
            ui.postDelayed(this, EVENTS_EVERY_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        setContentView(buildUi());
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        loadEvents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
        ui.post(drift);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacksAndMessages(null);
        panel.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: back only closes the panel.
        panel.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnClickListener(v -> togglePanel());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(32);
        content.setPadding(pad, pad, pad, pad);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout left = column();
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(110);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(24);
        date.setTextColor(0xFFBBBBBB);
        left.addView(clock);
        left.addView(date);

        LinearLayout right = column();
        weather = text(26, Color.WHITE);
        weather.setText("…");
        events = text(20, 0xFFAAAAAA);
        events.setPadding(0, dp(16), 0, 0);
        events.setLineSpacing(0, 1.2f);
        right.addView(weather);
        right.addView(events);

        content.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        content.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ---- tap overlay panel
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(16), dp(24), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB0181818);
        bg.setCornerRadius(dp(24));
        panel.setBackground(bg);
        panel.setClickable(true); // swallow taps so they don't close the panel
        panel.setVisibility(View.GONE);

        int maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        volBar = addSlider("Volume", maxVol, audio.getStreamVolume(AudioManager.STREAM_MUSIC), new Change() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }
        });
        briBar = addSlider("Brightness", 255, currentBrightness(), new Change() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) setBrightness(p);
            }
        });

        Button apps = new Button(this);
        apps.setText("Apps");
        apps.setOnClickListener(v -> openApps());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.gravity = Gravity.END;
        bl.topMargin = dp(8);
        panel.addView(apps, bl);

        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        pl.bottomMargin = dp(24);
        root.addView(panel, pl);
        return root;
    }

    private abstract class Change implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { ui.removeCallbacks(hidePanel); }
        @Override public void onStopTrackingTouch(SeekBar s) { scheduleHide(); }
    }

    private SeekBar addSlider(String label, int max, int progress, SeekBar.OnSeekBarChangeListener l) {
        TextView t = text(16, Color.WHITE);
        t.setText(label);
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(progress);
        s.setOnSeekBarChangeListener(l);
        panel.addView(t);
        panel.addView(s);
        return s;
    }

    private void togglePanel() {
        if (panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
            ui.removeCallbacks(hidePanel);
        } else {
            volBar.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            briBar.setProgress(currentBrightness());
            panel.setVisibility(View.VISIBLE);
            scheduleHide();
        }
    }

    private void scheduleHide() {
        ui.removeCallbacks(hidePanel);
        ui.postDelayed(hidePanel, HIDE_DELAY_MS);
    }

    private void openApps() {
        try {
            startActivity(new Intent(APPS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            panel.setVisibility(View.GONE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Launcher not installed yet", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------- brightness

    private int currentBrightness() {
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException e) {
            return 128;
        }
    }

    private void setBrightness(int value) {
        int v = Math.max(5, Math.min(255, value));
        try {
            if (Settings.System.canWrite(this)) {
                ContentResolver cr = getContentResolver();
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v);
                return;
            }
        } catch (SecurityException ignored) { }
        // Fallback: window-level brightness (only while this screen is showing).
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = v / 255f;
        getWindow().setAttributes(lp);
    }

    // ------------------------------------------------------------ calendar

    private void loadEvents() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            events.setText("");
            return;
        }
        io.execute(() -> {
            StringBuilder sb = new StringBuilder();
            long now = System.currentTimeMillis();
            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(b, now);
            ContentUris.appendId(b, now + 24L * 3600_000);
            try (Cursor c = getContentResolver().query(b.build(),
                    new String[]{CalendarContract.Instances.TITLE,
                            CalendarContract.Instances.BEGIN,
                            CalendarContract.Instances.ALL_DAY},
                    null, null, CalendarContract.Instances.BEGIN + " ASC")) {
                int n = 0;
                while (c != null && c.moveToNext() && n < 4) {
                    String when = c.getInt(2) == 1 ? "All day"
                            : DateFormat.getTimeFormat(this).format(new Date(c.getLong(1)));
                    sb.append(when).append("   ").append(c.getString(0)).append('\n');
                    n++;
                }
            } catch (Exception ignored) { }
            String text = sb.length() == 0 ? "No upcoming events" : sb.toString().trim();
            ui.post(() -> events.setText(text));
        });
    }

    // ------------------------------------------------------------- weather

    private void loadWeather() {
        ContentResolver cr = getContentResolver();
        String lat = Settings.System.getString(cr, "simple_lat");
        String lon = Settings.System.getString(cr, "simple_lon");
        String unit = Settings.System.getString(cr, "simple_unit");
        if (lat == null || lon == null || lat.isEmpty() || lon.isEmpty()) {
            weather.setText("Set location in Settings");
            return;
        }
        boolean fahrenheit = "F".equalsIgnoreCase(unit);
        String key = lat + "," + lon + "," + fahrenheit;
        if (key.equals(lastWeatherKey) && lastWeatherText != null
                && System.currentTimeMillis() - lastWeatherAt < WEATHER_MAX_AGE_MS) {
            weather.setText(lastWeatherText);
            return;
        }
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + Uri.encode(lat)
                        + "&longitude=" + Uri.encode(lon) + "&current_weather=true"
                        + (fahrenheit ? "&temperature_unit=fahrenheit" : ""));
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                JSONObject cw = new JSONObject(sb.toString()).getJSONObject("current_weather");
                String t = Math.round(cw.getDouble("temperature")) + (fahrenheit ? "°F" : "°C")
                        + "  " + describe(cw.getInt("weathercode"));
                lastWeatherKey = key;
                lastWeatherText = t;
                lastWeatherAt = System.currentTimeMillis();
                ui.post(() -> weather.setText(t));
            } catch (Exception e) {
                if (lastWeatherText == null) ui.post(() -> weather.setText("Weather unavailable"));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Rain showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    // ------------------------------------------------------------- helpers

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
