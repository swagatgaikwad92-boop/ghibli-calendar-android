package com.ghibli.calendar;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

abstract class GhibliWidgetBase extends AppWidgetProvider {
    abstract int mode();

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) Widgets.render(c, m, id, mode());
        Widgets.scheduleTick(c);
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        for (int id : ids) Widgets.clearOffset(c, id);
    }

    @Override
    public void onEnabled(Context c) { Widgets.scheduleTick(c); }

    @Override
    public void onDisabled(Context c) { Widgets.refreshAll(c); }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, android.os.Bundle o) {
        Widgets.render(c, m, id, mode());
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Widgets.ACTION_PREV.equals(a) || Widgets.ACTION_NEXT.equals(a) || Widgets.ACTION_TODAY.equals(a)) {
            int id = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                int off = Widgets.getOffset(c, id);
                if (Widgets.ACTION_PREV.equals(a)) off--;
                else if (Widgets.ACTION_NEXT.equals(a)) off++;
                else off = 0;
                Widgets.setOffset(c, id, off);
                Widgets.render(c, AppWidgetManager.getInstance(c), id, mode());
            }
            return;
        }
        if (Widgets.ACTION_TICK.equals(a)
                || Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_DATE_CHANGED.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Widgets.refreshAll(c);
            return;
        }
        super.onReceive(c, i);
    }
}
