package com.ghibli.calendar;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The ONE copy of your calendar data on the phone.
 *
 * It is exactly the same JSON the website keeps in localStorage under
 * "ghibliforest_v1" (same tasks / events / streak shape). The website, running
 * inside this app, pushes every change here; the widget reads and edits this.
 * Unknown fields are never dropped, so nothing the website stores is lost.
 */
final class StateStore {
    private static final String PREFS = "ghibli_native";
    private static final String K_STATE = "state";
    private static final String K_REV = "rev";

    /** revision the website page has last seen (in memory only) */
    private static long webSeenRev = -1;

    private StateStore() {}

    // ---------- models ----------
    static final class Task {
        String id, title, date, time, category;
        boolean done, high;
        double order = -1;
        int idx;
    }

    static final class Ev {
        String id, title, date, start, end, category;
    }

    // ---------- raw access ----------
    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized String raw(Context c) { return prefs(c).getString(K_STATE, null); }

    static synchronized boolean hasState(Context c) { return raw(c) != null; }

    static synchronized long rev(Context c) { return prefs(c).getLong(K_REV, 0); }

    static synchronized JSONObject root(Context c) {
        String s = raw(c);
        if (s == null) return null;
        try { return new JSONObject(s); } catch (JSONException e) { return null; }
    }

    /**
     * Website pushed its state. Returns false when the push was ignored because the
     * page is out of date (the widget/board changed the data after the page last synced);
     * the page is reloaded when you return to the app, so nothing is lost.
     */
    static synchronized boolean writeFromWeb(Context c, String json) {
        long cur = rev(c);
        if (webSeenRev != -1 && webSeenRev != cur) return false;
        String old = raw(c);
        if (json.equals(old)) { webSeenRev = cur; return false; } // nothing new
        dailySnapshot(c, old);
        long r = cur + 1;
        prefs(c).edit().putString(K_STATE, json).putLong(K_REV, r).apply();
        webSeenRev = r;
        return true;
    }

    /** Widget / board / import changed the state. */
    static synchronized void writeFromNative(Context c, JSONObject o) {
        dailySnapshot(c, raw(c));
        long r = rev(c) + 1;
        prefs(c).edit().putString(K_STATE, o.toString()).putLong(K_REV, r).apply();
    }

    static synchronized void markWebSeen(Context c) { webSeenRev = rev(c); }

    static synchronized boolean webIsStale(Context c) { return webSeenRev != rev(c); }


    // ---------- safety copies ----------
    private static File backupDir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "backups");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Keeps the last 12 safety copies (one per day, plus one before every import/restore). */
    static synchronized void snapshot(Context c, String json, String reason) {
        if (json == null || json.length() < 2) return;
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File f = new File(backupDir(c), "state-" + stamp + "-" + reason + ".json");
            FileOutputStream o = new FileOutputStream(f);
            o.write(json.getBytes(StandardCharsets.UTF_8));
            o.close();
            File[] all = backupDir(c).listFiles();
            if (all != null && all.length > 12) {
                java.util.Arrays.sort(all, new Comparator<File>() {
                    @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
                });
                for (int i = 0; i < all.length - 12; i++) all[i].delete();
            }
        } catch (Exception ignored) { }
    }

    private static void dailySnapshot(Context c, String previousJson) {
        if (previousJson == null) return;
        String today = todayKey();
        SharedPreferences p = prefs(c);
        if (today.equals(p.getString("snap_day", ""))) return;
        p.edit().putString("snap_day", today).apply();
        snapshot(c, previousJson, "daily");
    }

    /** Newest first. */
    static synchronized List<File> listBackups(Context c) {
        List<File> out = new ArrayList<>();
        File[] all = backupDir(c).listFiles();
        if (all != null) for (File f : all) if (f.getName().endsWith(".json")) out.add(f);
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return b.getName().compareTo(a.getName()); }
        });
        return out;
    }

    static synchronized boolean restoreBackup(Context c, File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            String json = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            if (!looksValid(json)) return false;
            snapshot(c, raw(c), "before-restore");
            writeFromNative(c, new JSONObject(json));
            return true;
        } catch (Exception e) { return false; }
    }

    static String describe(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray t = o.optJSONArray("tasks"), e = o.optJSONArray("events");
            return (t == null ? 0 : t.length()) + " tasks, " + (e == null ? 0 : e.length()) + " events";
        } catch (Exception x) { return ""; }
    }

    interface Mutator { boolean apply(JSONObject root) throws JSONException; }

    static synchronized boolean mutate(Context c, Mutator m) {
        JSONObject root = root(c);
        if (root == null) return false;
        try {
            if (!m.apply(root)) return false;
            writeFromNative(c, root);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    // ---------- helpers ----------
    static String str(JSONObject o, String k) {
        Object v = o.opt(k);
        return (v == null || v == JSONObject.NULL) ? "" : String.valueOf(v);
    }

    static String dateKey(Calendar cal) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    static String todayKey() { return dateKey(Calendar.getInstance()); }

    static String dateKeyPlus(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, days);
        return dateKey(c);
    }

    static Calendar parseKey(String key) {
        Calendar c = Calendar.getInstance();
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key);
            if (d != null) c.setTime(d);
        } catch (Exception ignored) { }
        return c;
    }

    static String addDays(String key, int n) {
        Calendar c = parseKey(key);
        c.add(Calendar.DAY_OF_YEAR, n);
        return dateKey(c);
    }

    static String fmtTime(String t) {
        if (t == null || t.isEmpty()) return "";
        try {
            String[] p = t.split(":");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            String ampm = h >= 12 ? "PM" : "AM";
            int h12 = h % 12 == 0 ? 12 : h % 12;
            return h12 + ":" + (m < 10 ? "0" : "") + m + " " + ampm;
        } catch (Exception e) { return t; }
    }

    static int toMinutes(String t) {
        if (t == null || t.isEmpty()) return -1;
        try {
            String[] p = t.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) { return -1; }
    }

    static int categoryColor(Context c, String cat) {
        int res;
        if ("study".equals(cat)) res = R.color.sage;
        else if ("college".equals(cat) || "meeting".equals(cat)) res = R.color.dusty_blue;
        else if ("homework".equals(cat)) res = R.color.lavender;
        else if ("creative".equals(cat) || "important".equals(cat)) res = R.color.gold;
        else if ("deadline".equals(cat)) res = R.color.terracotta;
        else res = R.color.peach; // personal + default
        return c.getColor(res);
    }

    static String categoryLabel(String cat) {
        if (cat == null || cat.isEmpty()) return "Personal";
        return cat.substring(0, 1).toUpperCase(Locale.US) + cat.substring(1);
    }

    private static String iso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    // ---------- reading ----------
    private static List<Task> allTasks(JSONObject root) {
        List<Task> out = new ArrayList<>();
        if (root == null) return out;
        JSONArray a = root.optJSONArray("tasks");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Task t = new Task();
            t.id = str(o, "id");
            t.title = str(o, "title");
            t.date = str(o, "date");
            t.time = str(o, "time");
            t.category = str(o, "category");
            t.done = o.optBoolean("done", false);
            t.high = "high".equals(str(o, "priority"));
            t.order = (o.has("wOrder") && t.date.equals(str(o, "wDate"))) ? o.optDouble("wOrder", -1) : -1;
            t.idx = i;
            out.add(t);
        }
        return out;
    }

    private static final Comparator<Task> TASK_ORDER = new Comparator<Task>() {
        @Override public int compare(Task a, Task b) {
            boolean ha = a.order >= 0, hb = b.order >= 0;
            if (ha && hb) return Double.compare(a.order, b.order);
            if (ha != hb) return ha ? -1 : 1;
            String ta = a.time.isEmpty() ? "23:59" : a.time;
            String tb = b.time.isEmpty() ? "23:59" : b.time;
            int c = ta.compareTo(tb);
            return c != 0 ? c : Integer.compare(a.idx, b.idx);
        }
    };

    /** All tasks of one day (done and not done), in widget order. */
    static List<Task> tasksOn(Context c, String date) {
        List<Task> out = new ArrayList<>();
        for (Task t : allTasks(root(c))) if (date.equals(t.date)) out.add(t);
        Collections.sort(out, TASK_ORDER);
        return out;
    }

    static List<Task> doneBetween(Context c, String from, String to) {
        List<Task> out = new ArrayList<>();
        for (Task t : allTasks(root(c))) if (t.done && t.date.compareTo(from) >= 0 && t.date.compareTo(to) <= 0) out.add(t);
        Collections.sort(out, new Comparator<Task>() {
            @Override public int compare(Task a, Task b) {
                int d = a.date.compareTo(b.date);
                return d != 0 ? d : TASK_ORDER.compare(a, b);
            }
        });
        return out;
    }

    static List<Ev> eventsBetween(Context c, String from, String to) {
        List<Ev> out = new ArrayList<>();
        JSONObject root = root(c);
        if (root == null) return out;
        JSONArray a = root.optJSONArray("events");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String d = str(o, "date");
            if (d.compareTo(from) < 0 || d.compareTo(to) > 0) continue;
            Ev e = new Ev();
            e.id = str(o, "id");
            e.title = str(o, "title");
            e.date = d;
            e.start = str(o, "startTime");
            e.end = str(o, "endTime");
            e.category = str(o, "category");
            out.add(e);
        }
        Collections.sort(out, new Comparator<Ev>() {
            @Override public int compare(Ev a, Ev b) {
                int d = a.date.compareTo(b.date);
                if (d != 0) return d;
                String sa = a.start.isEmpty() ? "00:00" : a.start;
                String sb = b.start.isEmpty() ? "00:00" : b.start;
                return sa.compareTo(sb);
            }
        });
        return out;
    }

    static List<Ev> eventsOn(Context c, String date) { return eventsBetween(c, date, date); }

    /** Next event that has not finished yet (today first, then the following days). */
    static Ev nextEvent(Context c) {
        Calendar now = Calendar.getInstance();
        String today = dateKey(now);
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (Ev e : eventsBetween(c, today, addDays(today, 30))) {
            if (!e.date.equals(today)) return e;
            int s = toMinutes(e.start);
            if (s < 0) return e; // all-day
            int en = toMinutes(e.end);
            if (en < 0) en = s + 30;
            if (en > nowMin) return e;
        }
        return null;
    }

    // ---------- writing (same rules as the website's state.js) ----------
    private static JSONObject findTask(JSONObject root, String id) {
        JSONArray a = root.optJSONArray("tasks");
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(str(o, "id"))) return o;
        }
        return null;
    }

    private static void bumpStreak(JSONObject root) throws JSONException {
        JSONObject s = root.optJSONObject("streak");
        if (s == null) { s = new JSONObject(); s.put("count", 0); s.put("lastActiveDate", JSONObject.NULL); root.put("streak", s); }
        String today = todayKey();
        String last = str(s, "lastActiveDate");
        if (today.equals(last)) return;
        s.put("count", yesterdayKey().equals(last) ? s.optInt("count", 0) + 1 : 1);
        s.put("lastActiveDate", today);
    }

    private static String yesterdayKey() { return dateKeyPlus(-1); }

    private static void setDone(JSONObject root, JSONObject t, boolean done) throws JSONException {
        if (t.optBoolean("done", false) == done) return;
        t.put("done", done);
        t.put("doneAt", done ? (Object) iso() : JSONObject.NULL);
        if (done) bumpStreak(root);
    }

    static boolean toggleTask(Context c, final String id) {
        return mutate(c, new Mutator() {
            @Override public boolean apply(JSONObject root) throws JSONException {
                JSONObject t = findTask(root, id);
                if (t == null) return false;
                setDone(root, t, !t.optBoolean("done", false));
                return true;
            }
        });
    }

    static boolean moveTaskToDate(Context c, final String id, final String date) {
        return mutate(c, new Mutator() {
            @Override public boolean apply(JSONObject root) throws JSONException {
                JSONObject t = findTask(root, id);
                if (t == null) return false;
                t.put("date", date);
                t.remove("wOrder");
                t.remove("wDate");
                return true;
            }
        });
    }

    /** Swap a not-done task with the one above it on the same day. */
    static boolean moveUp(Context c, String id, String date) {
        final List<String> ids = new ArrayList<>();
        for (Task t : tasksOn(c, date)) if (!t.done) ids.add(t.id);
        int i = ids.indexOf(id);
        if (i <= 0) return false;
        Collections.swap(ids, i, i - 1);
        return applyOrder(c, ids);
    }

    static boolean applyOrder(Context c, final List<String> ids) {
        return mutate(c, new Mutator() {
            @Override public boolean apply(JSONObject root) throws JSONException {
                for (int i = 0; i < ids.size(); i++) {
                    JSONObject t = findTask(root, ids.get(i));
                    if (t != null) { t.put("wOrder", i); t.put("wDate", str(t, "date")); }
                }
                return true;
            }
        });
    }

    /** One placement from the drag-and-drop board. */
    static final class Placement {
        String id;
        String date;          // day section the task ended up in (null = Completed)
        boolean wantDone;     // true = Completed section
        boolean moved;        // task landed in a different section than it started in
        int order = -1;       // position inside its day section (-1 = leave alone)
    }

    static boolean applyPlacements(Context c, final List<Placement> list) {
        if (list.isEmpty()) return false;
        return mutate(c, new Mutator() {
            @Override public boolean apply(JSONObject root) throws JSONException {
                boolean changed = false;
                for (Placement p : list) {
                    JSONObject t = findTask(root, p.id);
                    if (t == null) continue;
                    if (p.moved && !p.wantDone && p.date != null && !p.date.equals(str(t, "date"))) {
                        t.put("date", p.date);
                        changed = true;
                    }
                    boolean wasDone = t.optBoolean("done", false);
                    if (wasDone != p.wantDone) { setDone(root, t, p.wantDone); changed = true; }
                    if (!p.wantDone && p.order >= 0 && p.date != null) {
                        t.put("wOrder", p.order);
                        t.put("wDate", p.date);
                        changed = true;
                    }
                }
                return changed;
            }
        });
    }

    // ---------- import ----------
    static boolean looksValid(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return o.has("tasks") || o.has("events");
        } catch (JSONException e) { return false; }
    }
}
