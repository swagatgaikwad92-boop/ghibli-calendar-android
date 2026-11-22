# Bug report: website lost its design — cause and fix

## What was wrong (exact cause)
Every symptom comes from ONE thing: **the page loaded, but its style files (`core.css`, `components.css`, `themes.css`) and script files (`app.js`, `state.js`, …) did not.** The page reaches them by plain relative names (`href="core.css"`, `src="app.js"`), so they must sit in the **same folder** as `index.html`.

How each symptom follows (checked in your code):
1. Default serif font — the fonts are chosen in `core.css`; no CSS, no fonts.
2. All styling missing / default buttons and inputs / broken layout — same reason.
3. Giant solid green shape — the leaf logo is an SVG in `index.html` with no width or height; only the CSS sizes it.
4. Wrong date — `index.html` line 48 contains the fixed placeholder text "Thursday, September 10". `app.js` replaces it with today's date at start-up; `app.js` never ran.
5. No illustration files exist — the forest picture is drawn by JavaScript (also not running). There are no image files to be missing except the icons.

What it was **not** (checked): no CSS `@import` anywhere; the only `url()` is an inline noise texture; `sync.js` cannot cause it (my `index.html` differs from yours by exactly one added line near the end, and `sync.js` does nothing unless configured); the calendar's own date code uses the phone's local time, so there is no timezone fault.

## Why it happened
In my last package the modified `index.html` sat inside a sub-folder named `optional-website-sync/`, away from your CSS and JavaScript files. A copy of `index.html` in any folder that does not also contain `core.css`, `app.js` etc. produces exactly the symptoms you saw. That was a packaging hazard I created; the instructions did not warn about it.

**Honest limit:** I cannot see your GitHub repository, so I cannot prove which folder the broken page was in. The symptoms point to this single cause. `diagnose.html` (below) will show it on your phone in seconds.

## Fix (3 steps)
1. In your website repo, upload **all files from `website-restored/`** to the same folder (usually the repo root), choosing *replace* for existing ones. These are your original files byte-for-byte (verified), plus `diagnose.html` and `export-data.html`. On a phone, select all files in one go in the picker; GitHub's phone uploader cannot upload folders. If you earlier uploaded an `optional-website-sync` folder, or a stray `index.html` somewhere else, delete it.
2. Open `https://swagatgaikwad92-boop.github.io/Ghibli-Calander/diagnose.html` (your address). It must say **All files found**. If it lists missing files, upload those files into that folder.
3. Your phone may still show an old saved copy (the site caches itself for offline use). On the diagnose page press **Reset offline cache** (your calendar data is not touched), then open the calendar. Open it twice if it still looks old.

The Android app loads the same live website, so it is fixed by the same upload. Open the app twice afterwards.

Want cloud sync later? Upload **all files from `website-with-sync/`** the same way (it is `website-restored/` plus sync; only `index.html` differs, by one line).

## What was checked
- `website-restored/`: 21 original files byte-identical to your upload; every file referenced by `index.html`, `sw.js` and `manifest.json` exists in that same folder.
- `website-with-sync/`: identical except `index.html` (+1 line) and the added `sync.js`, `sync-setup.html`; same completeness check passes.
- `diagnose.html`: its verdict logic ran in a fake browser (all present / wrong folder / some missing). It was **not** run in real Chrome.
