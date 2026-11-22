package com.ghibli.calendar;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pure logic of the drag-and-drop board (no Android classes), so it can be unit-tested.
 * The list is flat: a HEAD item starts a section, TASK items under it belong to it,
 * empty sections hold one DROP placeholder.
 */
final class BoardModel {
    static final int HEAD = 0, TASK = 1, DROP = 2;
    static final String DONE = "DONE";

    static final class Item {
        int type;
        String key, label, origKey;
        StateStore.Task task;
    }

    final List<Item> items = new ArrayList<>();
    private Map<String, List<String>> baseline = new HashMap<>();

    void clear() { items.clear(); }

    void addHeader(String key, String label) {
        Item h = new Item(); h.type = HEAD; h.key = key; h.label = label; items.add(h);
    }

    void addTask(StateStore.Task t, String section) {
        Item it = new Item(); it.type = TASK; it.task = t; it.key = section; it.origKey = section; items.add(it);
    }

    /** Reads the shared data and lays out: Today, Completed, Tomorrow, then the next three days. */
    void rebuild(Context c) {
        clear();
        String today = StateStore.todayKey();
        List<StateStore.Task> doneTasks = StateStore.doneBetween(c, today, StateStore.dateKeyPlus(4));
        for (int i = 0; i < 5; i++) {
            String key = StateStore.dateKeyPlus(i);
            String label = i == 0 ? "Today" : i == 1 ? "Tomorrow"
                    : new SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(StateStore.parseKey(key).getTime());
            addHeader(key, label);
            for (StateStore.Task t : StateStore.tasksOn(c, key)) if (!t.done) addTask(t, key);
            if (i == 0) { // Completed sits right under Today so it is always a short drag away
                addHeader(DONE, "Completed ✓");
                for (StateStore.Task t : doneTasks) addTask(t, DONE);
            }
        }
        finishBuild();
    }

    /** Call once after building the list. */
    void finishBuild() {
        addPlaceholders();
        baseline = sections();
    }

    /** Every empty section shows one dashed "drop here" slot. */
    void addPlaceholders() {
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).type == DROP) items.remove(i);
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).type != HEAD) continue;
            boolean empty = i + 1 >= items.size() || items.get(i + 1).type == HEAD;
            if (empty) { Item d = new Item(); d.type = DROP; items.add(i + 1, d); }
        }
    }

    /** Same rule the on-screen drag uses. Returns false when the move is not allowed. */
    boolean move(int from, int to) {
        if (from < 0 || from >= items.size() || to < 1 || to >= items.size()) return false; // never above the first heading
        if (items.get(from).type != TASK) return false;
        Item it = items.remove(from);
        items.add(to, it);
        return true;
    }

    /** After a drop: every task belongs to the heading above it. */
    void settle() {
        String cur = null;
        for (Item it : items) {
            if (it.type == HEAD) cur = it.key;
            else if (it.type == TASK && cur != null) it.key = cur;
        }
    }

    /** section key -> ordered task ids */
    Map<String, List<String>> sections() {
        Map<String, List<String>> m = new HashMap<>();
        String cur = null;
        for (Item it : items) {
            if (it.type == HEAD) { cur = it.key; m.put(cur, new ArrayList<String>()); }
            else if (it.type == TASK && cur != null) m.get(cur).add(it.task.id);
        }
        return m;
    }

    /** What must be saved since the last save (and marks it as saved). */
    List<StateStore.Placement> placements() {
        settle();
        Map<String, List<String>> now = sections();
        List<StateStore.Placement> ps = new ArrayList<>();
        String cur = null;
        int order = 0;
        for (Item it : items) {
            if (it.type == HEAD) { cur = it.key; order = 0; continue; }
            if (it.type != TASK || cur == null) continue;
            boolean sectionChanged = !now.get(cur).equals(baseline.get(cur));
            boolean moved = !cur.equals(it.origKey);
            if (sectionChanged || moved) {
                StateStore.Placement p = new StateStore.Placement();
                p.id = it.task.id;
                p.wantDone = DONE.equals(cur);
                p.date = p.wantDone ? null : cur;
                p.moved = moved;
                p.order = p.wantDone ? -1 : order;
                ps.add(p);
            }
            order++;
            it.origKey = cur;
        }
        baseline = now;
        return ps;
    }

    int indexOfHeader(String key) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).type == HEAD && items.get(i).key.equals(key)) return i;
        return -1;
    }

    int indexOfTask(String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).type == TASK && items.get(i).task.id.equals(id)) return i;
        return -1;
    }
}
