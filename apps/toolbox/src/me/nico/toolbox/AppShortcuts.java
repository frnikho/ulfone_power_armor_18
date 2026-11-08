package me.nico.toolbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Editable row of app shortcuts.
 * Tap = launch. Long press = drag: drop on another slot to reorder (live), on "Retirer" to remove.
 * "+" = add from a list of launcher apps (with icons).
 */
final class AppShortcuts {
    private static final String PREFS = "shortcuts";
    private static final String KEY = "packages";
    private static final List<String> DEFAULTS = Arrays.asList(
            "deezer.android.app", "com.waze", "com.google.android.gm", "com.ajaxsystems");
    private static final int COLUMNS = 4;
    private static final int TEXT_COLOR = 0xFFECEFF1;
    private static final int ADD_COLOR = 0xFF2A3237;
    private static final int TRASH_COLOR = 0x33E5484D;
    private static final int TRASH_ACTIVE_COLOR = 0xCCE5484D;

    private final Activity activity;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final int cellWidth;
    private final LinearLayout root;
    private final TextView trash;
    private final GridLayout grid;

    /** Packages currently shown (launchable), in display order. */
    private List<String> shown = new ArrayList<>();
    private String dragging;

    AppShortcuts(Activity activity, int cellWidth) {
        this.activity = activity;
        this.cellWidth = cellWidth;
        this.pm = activity.getPackageManager();
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        trash = new TextView(activity);
        trash.setText("Retirer");
        trash.setGravity(Gravity.CENTER);
        trash.setTextColor(TEXT_COLOR);
        trash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        trash.setVisibility(View.GONE);
        setTrashActive(false);
        trash.setOnDragListener(this::onTrashDrag);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        tlp.setMargins(dp(8), dp(10), dp(8), 0);
        root.addView(trash, tlp);

        grid = new GridLayout(activity);
        grid.setColumnCount(COLUMNS);
        grid.setPadding(0, dp(10), 0, 0);
        grid.setOnDragListener(this::onGridDrag);
        root.addView(grid);

        render();
    }

    View view() {
        return root;
    }

    // ---- Storage --------------------------------------------------------

    private List<String> load() {
        String raw = prefs.getString(KEY, null);
        if (raw == null) {
            return new ArrayList<>(DEFAULTS);
        }
        List<String> list = new ArrayList<>();
        for (String pkg : raw.split(",")) {
            if (!pkg.isEmpty()) {
                list.add(pkg);
            }
        }
        return list;
    }

    private void save(List<String> packages) {
        prefs.edit().putString(KEY, TextUtils.join(",", packages)).apply();
    }

    // ---- Rendering ------------------------------------------------------

    private void render() {
        grid.removeAllViews();
        List<String> visible = new ArrayList<>();
        for (String pkg : load()) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) {
                continue; // uninstalled: hidden, kept in prefs in case it comes back
            }
            try {
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                View cell = cell(pm.getApplicationIcon(pkg), label);
                cell.setOnClickListener(v -> launch(launch));
                cell.setOnLongClickListener(v -> startDrag(v, pkg));
                if (pkg.equals(dragging)) {
                    cell.setAlpha(0.25f);
                }
                grid.addView(cell);
                visible.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Raced with an uninstall: skip.
            }
        }
        shown = visible;
        GradientDrawable plus = new GradientDrawable();
        plus.setShape(GradientDrawable.OVAL);
        plus.setColor(ADD_COLOR);
        View add = cell(plus, "Ajouter");
        add.setOnClickListener(v -> showPicker());
        grid.addView(add);
    }

    private View cell(Drawable icon, CharSequence label) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(8), 0, dp(6));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = cellWidth;
        cell.setLayoutParams(lp);

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(50), dp(50));
        if (icon instanceof GradientDrawable) {
            TextView plus = new TextView(activity);
            plus.setText("+");
            plus.setGravity(Gravity.CENTER);
            plus.setTextColor(TEXT_COLOR);
            plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            plus.setBackground(icon);
            cell.addView(plus, iconLp);
        } else {
            ImageView image = new ImageView(activity);
            image.setImageDrawable(icon);
            cell.addView(image, iconLp);
        }

        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextColor(TEXT_COLOR);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        text.setPadding(dp(4), dp(6), dp(4), 0);
        cell.addView(text, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private void launch(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
        activity.finish();
    }

    // ---- Drag & drop ----------------------------------------------------

    private boolean startDrag(View cell, String pkg) {
        cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        dragging = pkg;
        cell.startDragAndDrop(ClipData.newPlainText("package", pkg),
                new View.DragShadowBuilder(cell), pkg, 0);
        cell.setAlpha(0.25f);
        trash.setVisibility(View.VISIBLE);
        return true;
    }

    /** Live reorder: the dragged app takes the slot under the finger. */
    private boolean onGridDrag(View v, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return dragging != null;
            case DragEvent.ACTION_DRAG_LOCATION:
                moveDraggedTo(slotAt(event.getX(), event.getY()));
                return true;
            case DragEvent.ACTION_DROP:
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                endDrag();
                return true;
            default:
                return true;
        }
    }

    private boolean onTrashDrag(View v, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return dragging != null;
            case DragEvent.ACTION_DRAG_ENTERED:
                setTrashActive(true);
                trash.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                setTrashActive(false);
                return true;
            case DragEvent.ACTION_DROP:
                remove(dragging);
                return true;
            default:
                return true;
        }
    }

    private int slotAt(float x, float y) {
        if (grid.getChildCount() == 0) {
            return -1;
        }
        int cellHeight = Math.max(1, grid.getChildAt(0).getHeight());
        int col = Math.min(COLUMNS - 1, Math.max(0, (int) (x / cellWidth)));
        int row = Math.max(0, (int) ((y - grid.getPaddingTop()) / cellHeight));
        return Math.min(row * COLUMNS + col, shown.size() - 1);
    }

    private void moveDraggedTo(int slot) {
        int from = shown.indexOf(dragging);
        if (slot < 0 || from < 0 || slot == from) {
            return;
        }
        List<String> order = new ArrayList<>(shown);
        order.remove(from);
        order.add(slot, dragging);
        // Keep hidden (uninstalled) packages at the end so they are not lost.
        for (String pkg : load()) {
            if (!order.contains(pkg)) {
                order.add(pkg);
            }
        }
        save(order);
        render();
    }

    private void endDrag() {
        dragging = null;
        trash.setVisibility(View.GONE);
        setTrashActive(false);
        render();
    }

    private void setTrashActive(boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(active ? TRASH_ACTIVE_COLOR : TRASH_COLOR);
        trash.setBackground(bg);
    }

    private void remove(String pkg) {
        if (pkg == null) {
            return;
        }
        List<String> packages = load();
        packages.remove(pkg);
        save(packages);
        CharSequence label = pkg;
        try {
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException ignored) {
            // Keep the package name.
        }
        Toast.makeText(activity, label + " retiré", Toast.LENGTH_SHORT).show();
    }

    // ---- Picker ---------------------------------------------------------

    private void showPicker() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String> current = load();
        List<ResolveInfo> candidates = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            String pkg = info.activityInfo.packageName;
            if (!current.contains(pkg) && !pkg.equals(activity.getPackageName())
                    && !seen.contains(pkg)) {
                candidates.add(info);
                seen.add(pkg);
            }
        }
        List<CharSequence> labels = new ArrayList<>();
        for (ResolveInfo info : candidates) {
            labels.add(info.loadLabel(pm));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, (a, b) -> labels.get(a).toString()
                .compareToIgnoreCase(labels.get(b).toString()));

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Ajouter une app")
                .setAdapter(new PickerAdapter(candidates, labels, order), (dialog, which) -> {
                    List<String> packages = load();
                    packages.add(candidates.get(order.get(which)).activityInfo.packageName);
                    save(packages);
                    render();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private final class PickerAdapter extends BaseAdapter {
        private final List<ResolveInfo> apps;
        private final List<CharSequence> labels;
        private final List<Integer> order;

        PickerAdapter(List<ResolveInfo> apps, List<CharSequence> labels, List<Integer> order) {
            this.apps = apps;
            this.labels = labels;
            this.order = order;
        }

        @Override
        public int getCount() {
            return order.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(order.get(position));
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = convertView instanceof LinearLayout
                    ? (LinearLayout) convertView : newRow();
            int index = order.get(position);
            ((ImageView) row.getChildAt(0)).setImageDrawable(apps.get(index).loadIcon(pm));
            ((TextView) row.getChildAt(1)).setText(labels.get(index));
            return row;
        }

        private LinearLayout newRow() {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(24), dp(10), dp(24), dp(10));
            row.addView(new ImageView(activity), new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView text = new TextView(activity);
            text.setTextColor(TEXT_COLOR);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            text.setPadding(dp(18), 0, 0, 0);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(text);
            return row;
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
