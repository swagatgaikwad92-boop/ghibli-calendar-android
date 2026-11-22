package com.ghibli.calendar;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Draws the three widgets and keeps them fresh. */
final class Widgets {
    static final String PREFS = "ghibli_widget";
    static final String ACTION_TICK = "com.ghibli.calendar.TICK";
    static final String ACTION_PREV = "com.ghibli.calendar.PREV";
    static final String ACTION_NEXT = "com.ghibli.calendar.NEXT";
    static final String ACTION_TODAY = "com.ghibli.calendar.TODAY";

    static final Class<?>[] PROVIDERS = { SmallWidget.class, MediumWidget.class, LargeWidget.class };
    private static final int[] LAYOUTS = { R.layout.widget_small, R.layout.widget_medium, R.layout.widget_large };

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Runnable pending;

    private Widgets() {}

    private static int immutable() { return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0; }

    static int getOffset(Context c, int id) { return c.getSharedPreferences(PREFS, 0).getInt("off_" + id, 0); }

    static void setOffset(Context c, int id, int off) { c.getSharedPreferences(PREFS, 0).edit().putInt("off_" + id, off).apply(); }

    static void clearOffset(Context c, int id) { c.getSharedPreferences(PREFS, 0).edit().remove("off_" + id).apply(); }

    /** Many saves in a row from the website => one widget refresh. */
    static void refreshAllDebounced(Context c) {
        final Context app = c.getApplicationContext();
        if (pending != null) H.removeCallbacks(pending);
        pending = new Runnable() { @Override public void run() { refreshAll(app); } };
        H.postDelayed(pending, 350);
    }

    static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int total = 0;
        for (int mode = 0; mode < PROVIDERS.length; mode++) {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, PROVIDERS[mode]));
            total += ids.length;
            for (int id : ids) render(c, m, id, mode);
        }
        if (total > 0) scheduleTick(c); else cancelTick(c);
        MainActivity.reloadIfStale(c);
    }

    static void render(Context c, AppWidgetManager m, int id, int mode) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), LAYOUTS[mode]);
        int off = mode == 0 ? 0 : getOffset(c, id);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, off);
        String key = StateStore.dateKey(cal);

        rv.setTextViewText(R.id.w_weekday, new SimpleDateFormat("EEEE", Locale.getDefault()).format(cal.getTime()));
        rv.setTextViewText(R.id.w_date, new SimpleDateFormat("d MMMM", Locale.getDefault()).format(cal.getTime()));
        List<StateStore.Task> tasks = StateStore.tasksOn(c, key);
        int done = 0;
        for (StateStore.Task t : tasks) if (t.done) done++;
        rv.setTextViewText(R.id.w_count, tasks.isEmpty() ? "" : done + " of " + tasks.size() + " done");

        Intent svc = new Intent(c, WidgetService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.putExtra("mode", mode);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(R.id.list, svc);

        // Taps on list rows go through ActionActivity (works reliably on every launcher)
        Intent tpl = new Intent(c, ActionActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        rv.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(c, id, tpl, flags));

        // Header: if you are looking at another day, tap returns to today; otherwise opens the app
        if (off != 0) {
            rv.setOnClickPendingIntent(R.id.w_header, broadcast(c, id, mode, Widgets.ACTION_TODAY));
        } else {
            rv.setOnClickPendingIntent(R.id.w_header, openApp(c, id, key));
        }
        if (mode >= 1) {
            rv.setOnClickPendingIntent(R.id.w_prev, broadcast(c, id, mode, ACTION_PREV));
            rv.setOnClickPendingIntent(R.id.w_next, broadcast(c, id, mode, ACTION_NEXT));
            Intent board = new Intent(c, BoardActivity.class).setData(Uri.parse("ghibli://board/" + id));
            rv.setOnClickPendingIntent(R.id.w_arrange, PendingIntent.getActivity(c, id, board, PendingIntent.FLAG_UPDATE_CURRENT | immutable()));
            rv.setOnClickPendingIntent(R.id.w_add, openApp(c, id + 1000, key));
        }
        m.updateAppWidget(id, rv);
        m.notifyAppWidgetViewDataChanged(id, R.id.list);
    }

    private static PendingIntent openApp(Context c, int code, String day) {
        Intent i = new Intent(c, MainActivity.class).putExtra("day", day).setData(Uri.parse("ghibli://open/" + day + "/" + code));
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
    }

    private static PendingIntent broadcast(Context c, int id, int mode, String action) {
        Class<?> k = PROVIDERS[mode];
        Intent i = new Intent(c, k).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        i.setData(Uri.parse("ghibli://w/" + id + "/" + action));
        return PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
    }

    // ---------- background refresh ----------
    /** Next refresh: within 15 minutes, never later than just after midnight. */
    static void scheduleTick(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar mid = Calendar.getInstance();
        mid.add(Calendar.DAY_OF_YEAR, 1);
        mid.set(Calendar.HOUR_OF_DAY, 0); mid.set(Calendar.MINUTE, 0); mid.set(Calendar.SECOND, 5); mid.set(Calendar.MILLISECOND, 0);
        long at = Math.min(System.currentTimeMillis() + 15 * 60 * 1000L, mid.getTimeInMillis());
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC, at, tickIntent(c));
        } catch (Exception ignored) { }
    }

    static void cancelTick(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(tickIntent(c));
    }

    private static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, SmallWidget.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(c, 77, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
    }
}
