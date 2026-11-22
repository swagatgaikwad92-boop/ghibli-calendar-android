package com.ghibli.calendar;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Invisible: receives a tap from the widget (or a notification button), does the job, closes. */
public class ActionActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        String op = in.getStringExtra("op");
        String id = in.getStringExtra("id");
        String date = in.getStringExtra("date");
        if (op != null) {
            switch (op) {
                case "toggle":
                    if (id != null) StateStore.toggleTask(this, id);
                    break;
                case "up":
                    if (id != null && date != null) StateStore.moveUp(this, id, date);
                    break;
                case "next":
                    if (id != null && date != null) {
                        String d = date.compareTo(StateStore.todayKey()) < 0 ? StateStore.dateKeyPlus(1) : StateStore.addDays(date, 1);
                        StateStore.moveTaskToDate(this, id, d);
                    }
                    break;
                case "open":
                    startActivity(new Intent(this, MainActivity.class)
                            .putExtra("day", date)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    break;
                default:
                    break;
            }
        }
        Widgets.refreshAll(this);
        finish();
    }
}
