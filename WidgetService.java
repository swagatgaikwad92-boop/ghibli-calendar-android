package com.ghibli.calendar;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Builds the scrolling list inside each widget. */
public class WidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent);
    }

    private static final int T_SECTION = 0, T_TASK = 1, T_TASK_L = 2, T_EVENT = 3, T_EMPTY = 4;

    private static final class Row {
        int type;
        String id, title, sub, date;
        boolean done;
        int color;
    }

    private static final class Factory implements RemoteViewsFactory {
        private final Context ctx;
        private final int widgetId, mode;
        private List<Row> rows = new ArrayList<>();

        Factory(Context c, Intent i) {
            ctx = c;
            widgetId = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            mode = i.getIntExtra("mode", 0);
        }

        @Override public void onCreate() {}
        @Override public void onDestroy() { rows.clear(); }

        @Override
        public void onDataSetChanged() {
            List<Row> out = new ArrayList<>();
            try {
                build(out);
            } catch (Exception e) {
                Row r = empty("Couldn’t read your calendar yet");
                out.clear();
                out.add(r);
            }
            rows = out;
        }

        private Row empty(String msg) { Row r = new Row(); r.type = T_EMPTY; r.title = msg; return r; }

        private Row section(String s) { Row r = new Row(); r.type = T_SECTION; r.title = s; return r; }

        private Row task(StateStore.Task t, boolean showDate) {
            Row r = new Row();
            r.type = mode == 2 ? T_TASK_L : T_TASK;
            r.id = t.id; r.title = t.title; r.date = t.date; r.done = t.done;
            r.color = StateStore.categoryColor(ctx, t.category);
            String sub = StateStore.categoryLabel(t.category);
            if (!t.time.isEmpty()) sub += " · " + StateStore.fmtTime(t.time);
            if (showDate) sub = shortDay(t.date) + " · " + sub;
            r.sub = sub;
            return r;
        }

        private Row event(StateStore.Ev e, boolean showDate) {
            Row r = new Row();
            r.type = T_EVENT;
            r.id = e.id; r.title = e.title; r.date = e.date;
            r.color = StateStore.categoryColor(ctx, e.category);
            String t = e.start.isEmpty() ? "All day" : StateStore.fmtTime(e.start);
            r.sub = showDate ? shortDay(e.date) + " · " + t : t;
            return r;
        }

        private String shortDay(String key) {
            return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(StateStore.parseKey(key).getTime());
        }

        private void build(List<Row> out) {
            if (!StateStore.hasState(ctx)) {
                out.add(empty("Open Ghibli Calendar once to start syncing 🌱"));
                return;
            }
            int off = mode == 0 ? 0 : Widgets.getOffset(ctx, widgetId);
            String key = StateStore.dateKeyPlus(off);
            String today = StateStore.todayKey(); // same rule as the website: a day shows only that day's tasks
            List<StateStore.Task> tasks = StateStore.tasksOn(ctx, key);
            List<StateStore.Task> open = new ArrayList<>(), done = new ArrayList<>();
            for (StateStore.Task t : tasks) { if (t.done) done.add(t); else open.add(t); }

            if (mode == 0) { // SMALL — date + essential tasks
                int n = 0;
                for (StateStore.Task t : open) { out.add(task(t, false)); if (++n >= 12) break; }
                if (open.isEmpty()) out.add(empty(done.isEmpty() ? "A quiet day 🌿" : "All done ✨"));
                return;
            }

            if (mode == 1) { // MEDIUM — today's agenda
                StateStore.Ev next = off == 0 ? StateStore.nextEvent(ctx) : null;
                if (off != 0) {
                    List<StateStore.Ev> evs = StateStore.eventsOn(ctx, key);
                    if (!evs.isEmpty()) next = evs.get(0);
                }
                if (next != null) {
                    out.add(section(off == 0 ? "Up next" : "First event"));
                    out.add(event(next, off == 0 && !next.date.equals(today)));
                }
                if (!open.isEmpty()) out.add(section("Tasks"));
                for (StateStore.Task t : open) out.add(task(t, false));
                if (!done.isEmpty()) {
                    out.add(section("Completed"));
                    for (StateStore.Task t : done) out.add(task(t, false));
                }
                if (out.isEmpty()) out.add(empty("Nothing planned — enjoy the quiet 🌿"));
                return;
            }

            // LARGE — full daily agenda + upcoming
            out.add(section("Tasks"));
            if (open.isEmpty()) out.add(empty(done.isEmpty() ? "No tasks for this day" : "All tasks done ✨"));
            for (StateStore.Task t : open) out.add(task(t, false));
            if (!done.isEmpty()) {
                out.add(section("Completed"));
                for (StateStore.Task t : done) out.add(task(t, false));
            }
            List<StateStore.Ev> evs = StateStore.eventsOn(ctx, key);
            if (!evs.isEmpty()) {
                out.add(section("Events"));
                for (StateStore.Ev e : evs) out.add(event(e, false));
            }
            List<StateStore.Ev> later = StateStore.eventsBetween(ctx, StateStore.addDays(key, 1), StateStore.addDays(key, 14));
            if (!later.isEmpty()) {
                out.add(section("Coming up"));
                int n = 0;
                for (StateStore.Ev e : later) { out.add(event(e, true)); if (++n >= 6) break; }
            }
        }

        @Override public int getCount() { return rows.size(); }

        @Override
        public RemoteViews getViewAt(int pos) {
            if (pos < 0 || pos >= rows.size()) return null;
            Row r = rows.get(pos);
            String pkg = ctx.getPackageName();
            RemoteViews rv;
            switch (r.type) {
                case T_SECTION:
                    rv = new RemoteViews(pkg, R.layout.row_section);
                    rv.setTextViewText(R.id.r_section, r.title);
                    return rv;
                case T_EMPTY:
                    rv = new RemoteViews(pkg, R.layout.row_empty);
                    rv.setTextViewText(R.id.r_empty, r.title);
                    return rv;
                case T_EVENT:
                    rv = new RemoteViews(pkg, R.layout.row_event);
                    rv.setTextViewText(R.id.r_title, r.title);
                    rv.setTextViewText(R.id.r_sub, r.sub);
                    rv.setInt(R.id.r_dot, "setColorFilter", r.color);
                    rv.setOnClickFillInIntent(R.id.r_body, fill("open", null, r.date));
                    return rv;
                default:
                    rv = new RemoteViews(pkg, r.type == T_TASK_L ? R.layout.row_task_large : R.layout.row_task);
                    CharSequence title = r.title;
                    if (r.done) {
                        SpannableString s = new SpannableString(r.title);
                        s.setSpan(new StrikethroughSpan(), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        title = s;
                        rv.setTextColor(R.id.r_title, Color.parseColor("#A39C8C"));
                    } else {
                        rv.setTextColor(R.id.r_title, ctx.getColor(R.color.ink));
                    }
                    rv.setTextViewText(R.id.r_title, title);
                    rv.setTextViewText(R.id.r_sub, r.sub);
                    rv.setInt(R.id.r_dot, "setColorFilter", r.color);
                    rv.setImageViewResource(R.id.r_check, r.done ? R.drawable.ic_check_done : R.drawable.ic_check_empty);
                    rv.setOnClickFillInIntent(R.id.r_check, fill("toggle", r.id, r.date));
                    rv.setOnClickFillInIntent(R.id.r_body, fill("open", r.id, r.date));
                    if (r.type == T_TASK_L) {
                        rv.setViewVisibility(R.id.r_up, r.done ? android.view.View.GONE : android.view.View.VISIBLE);
                        rv.setViewVisibility(R.id.r_next, r.done ? android.view.View.GONE : android.view.View.VISIBLE);
                        rv.setOnClickFillInIntent(R.id.r_up, fill("up", r.id, r.date));
                        rv.setOnClickFillInIntent(R.id.r_next, fill("next", r.id, r.date));
                    }
                    return rv;
            }
        }

        private Intent fill(String op, String id, String date) {
            Intent i = new Intent();
            i.putExtra("op", op);
            if (id != null) i.putExtra("id", id);
            if (date != null) i.putExtra("date", date);
            return i;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 5; }
        @Override public long getItemId(int pos) { return pos; }
        @Override public boolean hasStableIds() { return false; }
    }
}
