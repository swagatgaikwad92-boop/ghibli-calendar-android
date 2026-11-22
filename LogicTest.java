package com.ghibli.calendar;

import android.content.Context;
import org.json.*;
import java.util.*;

public class LogicTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { if (ok) { pass++; System.out.println("PASS  " + name); } else { fail++; System.out.println("FAIL  " + name); } }

    static String today = StateStore.todayKey(), tom = StateStore.dateKeyPlus(1), d2 = StateStore.dateKeyPlus(2);

    static JSONObject task(String id, String title, String date, String time) throws Exception {
        JSONObject t = new JSONObject();
        t.put("id", id); t.put("title", title); t.put("date", date);
        t.put("time", time == null ? JSONObject.NULL : time); t.put("category", "study"); t.put("done", false);
        t.put("subtasks", new JSONArray()); t.put("someFutureField", "keep-me");
        return t;
    }
    static Context fresh(JSONObject... tasks) throws Exception {
        Context c = new Context();
        JSONObject root = new JSONObject();
        root.put("version", 1);
        JSONArray a = new JSONArray(); for (JSONObject t : tasks) a.put(t);
        root.put("tasks", a); root.put("events", new JSONArray());
        JSONObject streak = new JSONObject(); streak.put("count", 0); streak.put("lastActiveDate", JSONObject.NULL);
        root.put("streak", streak);
        StateStore.writeFromNative(c, root);
        StateStore.markWebSeen(c);
        return c;
    }
    static List<String> ids(List<StateStore.Task> l) { List<String> o = new ArrayList<>(); for (StateStore.Task t : l) o.add(t.id); return o; }
    static List<String> list(String... s) { return Arrays.asList(s); }
    /** simulate dragging task `id` over the item at index of header/other (what ItemTouchHelper would do step by step) */
    static void drag(BoardModel m, String id, int toIndexFinal) {
        int from = m.indexOfTask(id);
        // ItemTouchHelper moves one neighbour at a time
        while (from != toIndexFinal) {
            int next = from < toIndexFinal ? from + 1 : from - 1;
            if (!m.move(from, next)) break;
            from = next;
        }
    }
    static void drop(Context c, BoardModel m) {
        StateStore.applyPlacements(c, m.placements());
        m.addPlaceholders();
    }

    public static void main(String[] a) throws Exception {
        // ---- T1: reorder inside Today persists and survives a fresh board ----
        Context c = fresh(task("a", "A 9am", today, "09:00"), task("b", "B 10am", today, "10:00"), task("c", "C no time", today, null));
        check("default order = website order (time, untimed last)", ids(StateStore.tasksOn(c, today)).equals(list("a", "b", "c")));
        BoardModel m = new BoardModel(); m.rebuild(c);
        drag(m, "c", m.indexOfTask("a"));                 // drag C above A
        drop(c, m);
        check("T1 reorder saved: C,A,B", ids(StateStore.tasksOn(c, today)).equals(list("c", "a", "b")));
        BoardModel m2 = new BoardModel(); m2.rebuild(c);   // "close and reopen the board"
        check("T1 reorder still there after reopening board", m2.sections().get(today).equals(list("c", "a", "b")));
        check("T1 unknown task fields preserved", StateStore.raw(c).contains("keep-me"));

        // ---- T2: move between date sections ----
        m = new BoardModel(); m.rebuild(c);
        drag(m, "a", m.indexOfHeader(tom));               // A crosses the Completed header and the Tomorrow header? (drag down)
        // after crossing, A must sit under Tomorrow
        m.settle();
        check("T2 on-screen: A is under the Tomorrow heading", m.items.get(m.indexOfTask("a")).key.equals(tom) && m.indexOfTask("a") > m.indexOfHeader(tom));
        check("T2 cannot drag above the first heading", !m.move(m.indexOfTask("a"), 0));
        drop(c, m);
        List<StateStore.Task> tomTasks = StateStore.tasksOn(c, tom);
        check("T2 A rescheduled to tomorrow", ids(tomTasks).equals(list("a")) && !ids(StateStore.tasksOn(c, today)).contains("a"));

        // ---- T3: into Completed, and back out ----
        m = new BoardModel(); m.rebuild(c);
        int doneHead = m.indexOfHeader(BoardModel.DONE);
        drag(m, "b", doneHead + 1 > m.items.size() - 1 ? doneHead : doneHead);  // B (in Today) moves down over the Completed header
        drop(c, m);
        StateStore.Task b = null; for (StateStore.Task t : StateStore.tasksOn(c, today)) if (t.id.equals("b")) b = t;
        check("T3 B is done, date unchanged", b != null && b.done && b.date.equals(today));
        check("T3 doneAt + streak updated like the website", StateStore.raw(c).contains("\"doneAt\":\"20") && StateStore.root(c).optJSONObject("streak").optInt("count", 0) == 1);
        m = new BoardModel(); m.rebuild(c);
        check("T3 B shows in Completed section on a fresh board", m.sections().get(BoardModel.DONE).contains("b"));
        drag(m, "b", m.indexOfHeader(today) + 1);         // drag B up out of Completed, into Today (top)
        drop(c, m);
        b = null; for (StateStore.Task t : StateStore.tasksOn(c, today)) if (t.id.equals("b")) b = t;
        check("T3 B back to not-done in Today", b != null && !b.done && b.date.equals(today));
        check("T3 B first in Today", ids(StateStore.tasksOn(c, today)).get(0).equals("b"));

        // ---- T4: empty sections accept drops (from above and from below) ----
        c = fresh(task("x", "X", today, "08:00"), task("y", "Y", d2, "08:00"));
        m = new BoardModel(); m.rebuild(c);
        check("T4 empty sections have a drop slot", m.items.get(m.indexOfHeader(tom) + 1).type == BoardModel.DROP);
        drag(m, "x", m.indexOfHeader(tom));               // from above: crossing the Tomorrow heading
        drop(c, m);
        check("T4 dropped into empty Tomorrow (from above)", ids(StateStore.tasksOn(c, tom)).equals(list("x")));
        m = new BoardModel(); m.rebuild(c);
        drag(m, "y", m.indexOfHeader(d2));                // from below: Y is under day+2, moves up over its own heading
        drop(c, m);
        check("T4 Y moved up into Tomorrow (from below)", ids(StateStore.tasksOn(c, tom)).contains("y") && StateStore.tasksOn(c, d2).isEmpty());
        // empty Today accepts too
        c = fresh(task("p", "P", tom, "08:00"));
        m = new BoardModel(); m.rebuild(c);
        drag(m, "p", m.indexOfHeader(tom) - 1);           // up over Tomorrow heading, then DROP/Completed slots
        // keep dragging up until it is under Today
        while (!m.items.get(m.indexOfTask("p")).key.equals(today) && m.indexOfTask("p") > 1) { if (!m.move(m.indexOfTask("p"), m.indexOfTask("p") - 1)) break; m.settle(); }
        drop(c, m);
        check("T4 dropped into empty Today", ids(StateStore.tasksOn(c, today)).equals(list("p")));

        // ---- T5: the website adds a task later; ordered tasks stay ordered; website round-trip keeps wOrder ----
        c = fresh(task("a", "A", today, "09:00"), task("b", "B", today, "10:00"));
        m = new BoardModel(); m.rebuild(c); drag(m, "b", m.indexOfTask("a")); drop(c, m);   // B, A
        StateStore.markWebSeen(c);                                                           // page has (re)loaded the latest data
        JSONObject root = StateStore.root(c);                                                // website: parse -> add task -> save whole object
        root.optJSONArray("tasks").put(task("n", "New from website", today, "07:00"));
        StateStore.writeFromWeb(c, root.toString());
        check("T5 order kept after website save; new task after ordered ones", ids(StateStore.tasksOn(c, today)).equals(list("b", "a", "n")));

        // ---- T6: stale page cannot overwrite widget changes ----
        c = fresh(task("a", "A", today, "09:00"));
        String pageCopy = StateStore.raw(c);                 // the page loaded this
        StateStore.toggleTask(c, "a");                       // widget ticks the task while page is in background
        boolean accepted = StateStore.writeFromWeb(c, pageCopy.replace("\"A\"", "\"A edited\""));
        check("T6 stale web push rejected", !accepted && StateStore.tasksOn(c, today).get(0).done);
        check("T6 web is flagged stale until it reloads", StateStore.webIsStale(c));
        StateStore.markWebSeen(c);
        check("T6 after reload web pushes are accepted again", StateStore.writeFromWeb(c, StateStore.raw(c).replace("\"A\"", "\"A2\"")));

        // ---- T7: nothing moved => nothing written ----
        c = fresh(task("a", "A", today, "09:00"), task("b", "B", today, "10:00"));
        long r0 = StateStore.rev(c);
        m = new BoardModel(); m.rebuild(c); drop(c, m);
        check("T7 no-op drop writes nothing", StateStore.rev(c) == r0);

        // ---- T8: a stale order is ignored if the website moved the task to another day ----
        c = fresh(task("a", "A", today, "09:00"), task("b", "B", today, "10:00"), task("t", "T", tom, "08:00"));
        m = new BoardModel(); m.rebuild(c); drag(m, "b", m.indexOfTask("a")); drop(c, m);   // B before A (wOrder/wDate = today)
        StateStore.markWebSeen(c);
        root = StateStore.root(c);                                                           // website changes only the date of B
        for (int i = 0; i < root.optJSONArray("tasks").length(); i++) { JSONObject o = root.optJSONArray("tasks").optJSONObject(i); if (o.opt("id").equals("b")) o.put("date", tom); }
        StateStore.writeFromWeb(c, root.toString());
        check("T8 stale order ignored: T(08:00) then B(10:00) on tomorrow", ids(StateStore.tasksOn(c, tom)).equals(list("t", "b")));

        // ---- T9: widget actions ----
        c = fresh(task("a", "A", today, "09:00"), task("b", "B", today, "10:00"));
        StateStore.moveUp(c, "b", today);
        check("T9 widget ↑ moves B above A", ids(StateStore.tasksOn(c, today)).equals(list("b", "a")));
        StateStore.moveTaskToDate(c, "a", StateStore.addDays(today, 1));
        check("T9 widget → moves A to tomorrow", ids(StateStore.tasksOn(c, tom)).equals(list("a")));

        // ---- T10: safety copies ----
        c = fresh(task("a", "A", today, "09:00"));
        StateStore.toggleTask(c, "a");                       // first write of the day makes a daily snapshot
        List<java.io.File> bk = StateStore.listBackups(c);
        check("T10 daily safety copy created", bk.size() >= 1);
        String before = StateStore.raw(c);
        check("T10 restore works", StateStore.restoreBackup(c, bk.get(0)) && !StateStore.raw(c).equals(before));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
