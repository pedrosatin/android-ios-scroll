package dev.satin.scrollhelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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

    private TextView overlayStatusBadge;
    private TextView a11yStatusBadge;
    private TextView overallBanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#0B0D12"));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (36 * density), pad, pad);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("iOS Scroll & Tap-to-Top");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Barra de rolagem fluida de 120Hz com scrub interativo e toque no topo para voltar ao início no Android.");
        subtitle.setTextColor(Color.parseColor("#94A3B8"));
        subtitle.setTextSize(14f);
        subtitle.setPadding(0, (int) (6 * density), 0, (int) (18 * density));
        root.addView(subtitle);

        overallBanner = new TextView(this);
        overallBanner.setTextSize(14f);
        overallBanner.setTypeface(Typeface.DEFAULT_BOLD);
        int bannerPad = (int) (14 * density);
        overallBanner.setPadding(bannerPad, bannerPad, bannerPad, bannerPad);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bannerLp.bottomMargin = (int) (16 * density);
        root.addView(overallBanner, bannerLp);

        // Card 1: Overlay Permission
        overlayStatusBadge = new TextView(this);
        root.addView(buildStepCard(
                density,
                "1. Permissão de sobreposição",
                "Necessária para desenhar a pílula de rolagem na borda direita e a área de toque no topo da tela.",
                overlayStatusBadge,
                "Configurar sobreposição",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                        );
                        startActivity(intent);
                    }
                }
        ));

        // Card 2: Restricted Settings helper (Android 13+)
        root.addView(buildStepCard(
                density,
                "2. Desbloquear configurações restritas (Android 13+)",
                "Se o Android bloquear a ativação na tela de Acessibilidade, abra as informações do app, toque no menu ⋮ no canto superior direito e selecione \"Permitir configurações restritas\".",
                null,
                "Abrir informações do app",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())
                        );
                        startActivity(intent);
                    }
                }
        ));

        // Card 3: Accessibility Service
        a11yStatusBadge = new TextView(this);
        root.addView(buildStepCard(
                density,
                "3. Serviço de acessibilidade",
                "Ative \"S25 Scroll Helper\" em Configurações > Acessibilidade > Aplicativos instalados.",
                a11yStatusBadge,
                "Abrir Acessibilidade",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                }
        ));

        // Preferences Card
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        LinearLayout prefsCard = createCardContainer(density);

        TextView prefsHeader = new TextView(this);
        prefsHeader.setText("Preferências");
        prefsHeader.setTextColor(Color.WHITE);
        prefsHeader.setTextSize(16f);
        prefsHeader.setTypeface(Typeface.DEFAULT_BOLD);
        prefsHeader.setPadding(0, 0, 0, (int) (8 * density));
        prefsCard.addView(prefsHeader);

        prefsCard.addView(buildToggleRow(
                density,
                prefs,
                KEY_ENABLE_SCROLLBAR,
                true,
                "Barra de rolagem na borda direita",
                "Exibe o indicador estilo iOS em apps que ocultam a barra nativa (como X/Twitter)."
        ));

        prefsCard.addView(buildToggleRow(
                density,
                prefs,
                KEY_ENABLE_DRAG,
                true,
                "Arrastar barra para rolar (Scrubbing)",
                "Segure a barra na lateral direita e deslize para rolar a página com resposta tátil."
        ));

        prefsCard.addView(buildToggleRow(
                density,
                prefs,
                KEY_ENABLE_TOP_TAP,
                true,
                "Toque no topo para voltar ao início",
                "Toque na região da câmera frontal / barra de status para subir ao topo da lista."
        ));

        prefsCard.addView(buildToggleRow(
                density,
                prefs,
                KEY_HIDE_IN_BANKS,
                true,
                "Pausar em apps de banco",
                "Desativa a sobreposição automaticamente em aplicativos bancários e corretoras."
        ));

        root.addView(prefsCard);
        setContentView(scrollView);
    }

    private LinearLayout createCardContainer(float density) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#161A23"));
        bg.setCornerRadius(14 * density);
        bg.setStroke((int) (1 * density), Color.parseColor("#262C3A"));
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = (int) (14 * density);
        card.setLayoutParams(lp);
        return card;
    }

    private View buildStepCard(
            float density,
            String titleText,
            String bodyText,
            TextView badgeView,
            String buttonLabel,
            View.OnClickListener onClick
    ) {
        LinearLayout card = createCardContainer(density);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerRow.addView(title, titleLp);

        if (badgeView != null) {
            badgeView.setTextSize(12f);
            badgeView.setTypeface(Typeface.DEFAULT_BOLD);
            int hPad = (int) (10 * density);
            int vPad = (int) (4 * density);
            badgeView.setPadding(hPad, vPad, hPad, vPad);
            headerRow.addView(badgeView);
        }

        card.addView(headerRow);

        TextView desc = new TextView(this);
        desc.setText(bodyText);
        desc.setTextColor(Color.parseColor("#94A3B8"));
        desc.setTextSize(13.5f);
        desc.setPadding(0, (int) (8 * density), 0, (int) (12 * density));
        card.addView(desc);

        Button btn = new Button(this);
        btn.setText(buttonLabel);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(14f);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#2563EB"));
        btnBg.setCornerRadius(10 * density);
        btn.setBackground(btnBg);
        btn.setOnClickListener(onClick);
        card.addView(btn);

        return card;
    }

    private View buildToggleRow(
            float density,
            final SharedPreferences prefs,
            final String key,
            boolean defaultVal,
            String titleText,
            String subtitleText
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * density), 0, (int) (10 * density));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = (int) (12 * density);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(Color.parseColor("#F1F5F9"));
        t.setTextSize(14.5f);
        textCol.addView(t);

        TextView sub = new TextView(this);
        sub.setText(subtitleText);
        sub.setTextColor(Color.parseColor("#64748B"));
        sub.setTextSize(12.5f);
        textCol.addView(sub);

        row.addView(textCol, colLp);

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

    private void styleBadge(TextView badge, boolean ok, String okText, String pendingText) {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999 * density);
        if (ok) {
            badge.setText(okText);
            badge.setTextColor(Color.parseColor("#4ADE80"));
            bg.setColor(Color.parseColor("#14532D"));
        } else {
            badge.setText(pendingText);
            badge.setTextColor(Color.parseColor("#F87171"));
            bg.setColor(Color.parseColor("#450A0A"));
        }
        badge.setBackground(bg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        float density = getResources().getDisplayMetrics().density;
        boolean hasOverlay = Settings.canDrawOverlays(this);
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean hasA11y = enabled != null && enabled.contains(getPackageName());

        styleBadge(overlayStatusBadge, hasOverlay, "Concedida", "Pendente");
        styleBadge(a11yStatusBadge, hasA11y, "Ativo", "Inativo");

        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setCornerRadius(12 * density);
        if (hasOverlay && hasA11y) {
            overallBanner.setText("Tudo pronto. A barra lateral estilo iOS e o toque no topo estão ativos.");
            overallBanner.setTextColor(Color.parseColor("#4ADE80"));
            bannerBg.setColor(Color.parseColor("#052E16"));
            bannerBg.setStroke((int) (1 * density), Color.parseColor("#166534"));
        } else {
            overallBanner.setText("Conclua os passos pendentes abaixo para ativar a barra de rolagem.");
            overallBanner.setTextColor(Color.parseColor("#FBBF24"));
            bannerBg.setColor(Color.parseColor("#422006"));
            bannerBg.setStroke((int) (1 * density), Color.parseColor("#854D0E"));
        }
        overallBanner.setBackground(bannerBg);
    }
}
