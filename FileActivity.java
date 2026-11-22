package com.ghibli.calendar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Shared backup import / export (used by the app and the board). */
abstract class FileActivity extends Activity {
    static final int REQ_IMPORT = 701, REQ_EXPORT = 702;

    /** Called after imported data replaced the current data. */
    abstract void onDataReplaced();

    void confirmThenImport() {
        if (!StateStore.hasState(this)) { pickImport(); return; }
        new AlertDialog.Builder(this)
                .setTitle("Replace your current calendar?")
                .setMessage("The backup will replace the tasks and events currently in this app.")
                .setPositiveButton("Choose backup file", (d, w) -> pickImport())
                .setNegativeButton("Cancel", null)
                .show();
    }

    void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    void pickExport() {
        if (!StateStore.hasState(this)) { Toast.makeText(this, "Nothing to export yet.", Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "ghibli-calendar-backup.json");
        startActivityForResult(i, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_IMPORT) {
                InputStream in = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while (in != null && (n = in.read(b)) > 0) buf.write(b, 0, n);
                if (in != null) in.close();
                String json = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
                if (!StateStore.looksValid(json)) throw new IllegalArgumentException("bad file");
                StateStore.snapshot(this, StateStore.raw(this), "before-import");
                StateStore.writeFromNative(this, new JSONObject(json));
                Widgets.refreshAll(this);
                Toast.makeText(this, "Calendar imported 🌿", Toast.LENGTH_LONG).show();
                onDataReplaced();
            } else if (req == REQ_EXPORT) {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out != null) {
                    out.write(StateStore.raw(this).getBytes(StandardCharsets.UTF_8));
                    out.close();
                }
                Toast.makeText(this, "Backup saved ✓", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "That file doesn’t look like a Ghibli Calendar backup.", Toast.LENGTH_LONG).show();
        }
    }
}
