package dev.satin.scrollhelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {
    public static final String PREFS_NAME = "scroll_helper_prefs";
    public static final String KEY_ENABLE_SCROLLBAR = "enable_scrollbar";
    public static final String KEY_ENABLE_DRAG = "enable_drag_scrub";
    public static final String KEY_ENABLE_TOP_TAP = "enable_top_tap";
    public static final String KEY_HIDE_IN_BANKS = "hide_in_banks";
    public static final String KEY_THEME_MODE = "theme_mode"; // 0 = auto, 1 = dark, 2 = light

    private TextView overlayValueView;
    private TextView a11yValueView;

    private int bgColor;
    private int surfaceColor;
    private int borderColor;
    private int textPrimary;
    private int textSecondary;
    private int pillSelectedBg;
    private int pillSelectedText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        rebuildUi();
    }

    private boolean resolveDarkMode(SharedPreferences prefs) {
        int mode = prefs.getInt(KEY_THEME_MODE, 0);
        if (mode == 1) return true;
        if (mode == 2) return false;
        int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void applyPalette(boolean isDark) {
        if (isDark) {
            bgColor = Color.parseColor("#0A0A0A");
            surfaceColor = Color.parseColor("#161616");
            borderColor = Color.parseColor("#262626");
            textPrimary = Color.parseColor("#EDEDED");
            textSecondary = Color.parseColor("#8A8A8E");
            pillSelectedBg = Color.parseColor("#EDEDED");
            pillSelectedText = Color.parseColor("#0A0A0A");
        } else {
            bgColor = Color.parseColor("#F4F4F5");
            surfaceColor = Color.parseColor("#FFFFFF");
            borderColor = Color.parseColor("#E4E4E7");
            textPrimary = Color.parseColor("#18181B");
            textSecondary = Color.parseColor("#71717A");
            pillSelectedBg = Color.parseColor("#18181B");
            pillSelectedText = Color.parseColor("#FFFFFF");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(bgColor);
            getWindow().setNavigationBarColor(bgColor);
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (isDark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    private void rebuildUi() {
        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isDark = resolveDarkMode(prefs);
        applyPalette(isDark);

        final float d = getResources().getDisplayMetrics().density;
        int hPad = (int) (18 * d);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(bgColor);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(hPad, (int) (42 * d), hPad, (int) (28 * d));
        scrollView.addView(root);

        // Header row with title and theme selector
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, (int) (20 * d));

        TextView title = new TextView(this);
        title.setText("Scroll Helper");
        title.setTextColor(textPrimary);
        title.setTextSize(22f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(title, titleLp);

        topBar.addView(buildThemeSwitcher(d, prefs));
        root.addView(topBar);

        // Section: Permissions
        root.addView(buildSectionLabel(d, "PERMISSIONS"));
        LinearLayout permGroup = createGroupCard(d);

        overlayValueView = new TextView(this);
        permGroup.addView(buildActionRow(
                d,
                "Display over other apps",
                overlayValueView,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                        ));
                    }
                }
        ));
        permGroup.addView(buildDivider(d));

        TextView restrictedValue = new TextView(this);
        restrictedValue.setText("App info ›");
        permGroup.addView(buildActionRow(
                d,
                "Allow restricted settings",
                restrictedValue,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())
                        ));
                    }
                }
        ));
        permGroup.addView(buildDivider(d));

        a11yValueView = new TextView(this);
        permGroup.addView(buildActionRow(
                d,
                "Accessibility service",
                a11yValueView,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                }
        ));
        root.addView(permGroup);

        // Section: Behavior
        root.addView(buildSectionLabel(d, "BEHAVIOR"));
        LinearLayout optsGroup = createGroupCard(d);

        optsGroup.addView(buildSwitchRow(d, prefs, KEY_ENABLE_SCROLLBAR, true, "Right-edge scrollbar"));
        optsGroup.addView(buildDivider(d));
        optsGroup.addView(buildSwitchRow(d, prefs, KEY_ENABLE_DRAG, true, "Hold and drag scrollbar"));
        optsGroup.addView(buildDivider(d));
        optsGroup.addView(buildSwitchRow(d, prefs, KEY_ENABLE_TOP_TAP, true, "Tap status bar to scroll top"));
        optsGroup.addView(buildDivider(d));
        optsGroup.addView(buildSwitchRow(d, prefs, KEY_HIDE_IN_BANKS, true, "Pause in banking apps"));

        root.addView(optsGroup);

        // Section: Playground
        root.addView(buildSectionLabel(d, "PLAYGROUND"));
        LinearLayout testGroup = createGroupCard(d);
        for (int i = 1; i <= 36; i++) {
            if (i > 1) {
                testGroup.addView(buildDivider(d));
            }
            testGroup.addView(buildSampleRow(d, i));
        }
        root.addView(testGroup);

        setContentView(scrollView);
        refreshPermissionLabels();
    }

    private View buildThemeSwitcher(float d, final SharedPreferences prefs) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (3 * d);
        bar.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surfaceColor);
        bg.setCornerRadius(8 * d);
        bg.setStroke((int) Math.max(1, d), borderColor);
        bar.setBackground(bg);

        String[] labels = {"Auto", "Dark", "Light"};
        int current = prefs.getInt(KEY_THEME_MODE, 0);

        for (int i = 0; i < labels.length; i++) {
            final int modeIndex = i;
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(12f);
            int hPad = (int) (10 * d);
            int vPad = (int) (5 * d);
            chip.setPadding(hPad, vPad, hPad, vPad);

            if (i == current) {
                chip.setTextColor(pillSelectedText);
                chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                GradientDrawable chipBg = new GradientDrawable();
                chipBg.setColor(pillSelectedBg);
                chipBg.setCornerRadius(6 * d);
                chip.setBackground(chipBg);
            } else {
                chip.setTextColor(textSecondary);
            }

            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().putInt(KEY_THEME_MODE, modeIndex).apply();
                    rebuildUi();
                }
            });
            bar.addView(chip);
        }
        return bar;
    }

    private TextView buildSectionLabel(float d, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textSecondary);
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setLetterSpacing(0.06f);
        tv.setPadding((int) (4 * d), (int) (14 * d), 0, (int) (6 * d));
        return tv;
    }

    private LinearLayout createGroupCard(float d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surfaceColor);
        bg.setCornerRadius(12 * d);
        bg.setStroke((int) Math.max(1, d), borderColor);
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = (int) (6 * d);
        card.setLayoutParams(lp);
        return card;
    }

    private View buildDivider(float d) {
        View v = new View(this);
        v.setBackgroundColor(borderColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, (int) d)
        );
        lp.leftMargin = (int) (16 * d);
        v.setLayoutParams(lp);
        return v;
    }

    private View buildActionRow(float d, String label, TextView rightView, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = (int) (16 * d);
        int vPad = (int) (14 * d);
        row.setPadding(hPad, vPad, hPad, vPad);
        row.setOnClickListener(onClick);

        TextView left = new TextView(this);
        left.setText(label);
        left.setTextColor(textPrimary);
        left.setTextSize(15f);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(left, leftLp);

        rightView.setTextColor(textSecondary);
        rightView.setTextSize(14f);
        row.addView(rightView);

        return row;
    }

    private View buildSwitchRow(
            float d,
            final SharedPreferences prefs,
            final String key,
            boolean defaultVal,
            String label
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = (int) (16 * d);
        int vPad = (int) (11 * d);
        row.setPadding(hPad, vPad, hPad, vPad);

        TextView left = new TextView(this);
        left.setText(label);
        left.setTextColor(textPrimary);
        left.setTextSize(15f);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(left, leftLp);

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, defaultVal));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
            }
        });
        row.addView(sw);

        return row;
    }

    private View buildSampleRow(float d, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = (int) (16 * d);
        int vPad = (int) (14 * d);
        row.setPadding(hPad, vPad, hPad, vPad);

        TextView left = new TextView(this);
        left.setText("Sample item " + (index < 10 ? "0" + index : String.valueOf(index)));
        left.setTextColor(textPrimary);
        left.setTextSize(14.5f);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(left, leftLp);

        TextView right = new TextView(this);
        right.setText("#" + (index < 10 ? "0" + index : String.valueOf(index)));
        right.setTextColor(textSecondary);
        right.setTextSize(13f);
        row.addView(right);

        return row;
    }

    private void refreshPermissionLabels() {
        if (overlayValueView == null || a11yValueView == null) return;
        boolean hasOverlay = Settings.canDrawOverlays(this);
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean hasA11y = enabled != null && enabled.contains(getPackageName());

        overlayValueView.setText(hasOverlay ? "Granted" : "Grant ›");
        overlayValueView.setTextColor(hasOverlay ? textPrimary : textSecondary);

        a11yValueView.setText(hasA11y ? "Active" : "Enable ›");
        a11yValueView.setTextColor(hasA11y ? textPrimary : textSecondary);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionLabels();
    }
}
