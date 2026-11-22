package com.ghibli.calendar;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drag-and-drop board. (Android home-screen widgets cannot do drag-and-drop themselves,
 * so the widget's ⇅ button opens this screen.)
 *
 * Sections: Today, Completed, Tomorrow and the next three days.
 * Press & hold a task, then drag:
 *   up/down inside a day  = reorder
 *   onto another day      = reschedule
 *   into Completed        = mark done      (out of Completed = not done again)
 * Every drop is saved immediately to the shared data and the widgets refresh.
 */
public class BoardActivity extends FileActivity {
    private static final int T_HEAD = BoardModel.HEAD, T_TASK = BoardModel.TASK, T_DROP = BoardModel.DROP;
    private static final String DONE = BoardModel.DONE;

    private final BoardModel model = new BoardModel();
    private final List<BoardModel.Item> items = model.items;
    private RecyclerView rv;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_board);
        if (!StateStore.hasState(this)) {
            Toast.makeText(this, "Open Ghibli Calendar once first, then come back 🌱", Toast.LENGTH_LONG).show();
        }
        rv = findViewById(R.id.board_list);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        new ItemTouchHelper(new Touch()).attachToRecyclerView(rv);
        findViewById(R.id.b_import).setOnClickListener(v -> confirmThenImport());
        findViewById(R.id.b_export).setOnClickListener(v -> pickExport());
        findViewById(R.id.b_backups).setOnClickListener(v -> showBackups());
        findViewById(R.id.b_sync).setOnClickListener(v -> openApp("sync-setup.html"));
        findViewById(R.id.b_open).setOnClickListener(v -> openApp(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload(); // always show the freshest data (e.g. after the website changed something)
    }

    private void openApp(String page) {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (page != null) i.putExtra("page", page);
        startActivity(i);
        finish();
    }

    @Override
    void onDataReplaced() { reload(); }

    // ---------- build the list ----------
    private void reload() {
        model.rebuild(this);
        adapter.notifyDataSetChanged();
    }

    /** Save whatever changed since the last save. */
    private void persist() {
        if (StateStore.applyPlacements(this, model.placements())) Widgets.refreshAll(this);
    }

    // ---------- drag & drop ----------
    private final class Touch extends ItemTouchHelper.Callback {
        @Override
        public int getMovementFlags(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
            int pos = vh.getBindingAdapterPosition();
            if (pos < 0 || pos >= items.size() || items.get(pos).type != T_TASK) return 0;
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
            int from = vh.getBindingAdapterPosition(), to = target.getBindingAdapterPosition();
            if (!model.move(from, to)) return false;
            adapter.notifyItemMoved(from, to);
            return true;
        }

        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {}

        @Override
        public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
            super.clearView(r, vh);
            persist();
            r.post(() -> { model.addPlaceholders(); adapter.notifyDataSetChanged(); });
        }
    }

    // ---------- backups ----------
    private void showBackups() {
        final List<File> files = StateStore.listBackups(this);
        if (files.isEmpty()) {
            Toast.makeText(this, "No safety copies yet — one is made automatically each day.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) labels[i] = backupLabel(files.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Restore a safety copy")
                .setItems(labels, (d, which) -> new AlertDialog.Builder(this)
                        .setTitle("Restore this copy?")
                        .setMessage("Your current calendar is saved as a new safety copy first, so you can undo this.")
                        .setPositiveButton("Restore", (d2, w2) -> {
                            if (StateStore.restoreBackup(this, files.get(which))) {
                                Widgets.refreshAll(this);
                                Toast.makeText(this, "Restored 🌿 Open the calendar to see it.", Toast.LENGTH_LONG).show();
                                reload();
                            } else {
                                Toast.makeText(this, "Couldn’t read that copy.", Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Close", null)
                .show();
    }

    private String backupLabel(File f) {
        // state-20261121-140512-daily.json
        String n = f.getName().replace("state-", "").replace(".json", "");
        String[] p = n.split("-");
        String when = n;
        try {
            java.util.Date d = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(p[0] + "-" + p[1]);
            when = new SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(d);
        } catch (Exception ignored) { }
        String why = p.length > 2 ? p[2] : "";
        String info = "";
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            in.close();
            info = StateStore.describe(new String(b, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        return when + " · " + why + (info.isEmpty() ? "" : "\n" + info);
    }

    // ---------- list adapter ----------
    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return items.size(); }
        @Override public int getItemViewType(int pos) { return items.get(pos).type; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
            LayoutInflater inf = LayoutInflater.from(p.getContext());
            int res = type == T_HEAD ? R.layout.item_header : type == T_TASK ? R.layout.item_task : R.layout.item_drop;
            return new RecyclerView.ViewHolder(inf.inflate(res, p, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            BoardModel.Item it = items.get(pos);
            View v = vh.itemView;
            if (it.type == T_HEAD) { ((TextView) v).setText(it.label); return; }
            if (it.type == T_DROP) return;
            StateStore.Task t = it.task;
            boolean done = DONE.equals(it.key);
            TextView title = v.findViewById(R.id.t_title);
            title.setText(t.title);
            title.setPaintFlags(done ? title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    : title.getPaintFlags() & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            String sub = StateStore.categoryLabel(t.category);
            if (!t.time.isEmpty()) sub += " · " + StateStore.fmtTime(t.time);
            ((TextView) v.findViewById(R.id.t_sub)).setText(sub);
            ((ImageView) v.findViewById(R.id.t_dot)).setColorFilter(StateStore.categoryColor(BoardActivity.this, t.category));
            ImageView check = v.findViewById(R.id.t_check);
            check.setImageResource(done ? R.drawable.ic_check_done : R.drawable.ic_check_empty);
            final String id = t.id;
            check.setOnClickListener(x -> {
                StateStore.toggleTask(BoardActivity.this, id);
                Widgets.refreshAll(BoardActivity.this);
                reload();
            });
        }
    }
}
