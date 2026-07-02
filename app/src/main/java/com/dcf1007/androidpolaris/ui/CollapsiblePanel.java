package com.dcf1007.androidpolaris.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Reusable card used by every major control section.
 *
 * <p>The component owns only presentation behavior: header text, collapse/expand state and the body
 * container. It has no application logic and is safe to use from any UI layer.</p>
 */
final class CollapsiblePanel {
    private final Context context;
    private final LinearLayout root;
    private final LinearLayout body;
    private final TextView header;
    private final String title;
    private boolean collapsed;

    CollapsiblePanel(Context context, String title) {
        this.context = context;
        this.title = title.toUpperCase(Locale.US);
        this.root = new LinearLayout(context);
        this.root.setOrientation(LinearLayout.VERTICAL);
        this.root.setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 12), UiStyle.dp(context, 12), UiStyle.dp(context, 12));
        this.root.setBackground(UiStyle.roundedBackground(context, UiStyle.PANEL, UiStyle.BORDER, 16));

        this.header = UiStyle.text(context, "", 13, UiStyle.TEXT, true);
        this.header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        this.header.setLetterSpacing(0.03f);
        this.header.setPadding(UiStyle.dp(context, 2), UiStyle.dp(context, 4), UiStyle.dp(context, 2), UiStyle.dp(context, 8));
        this.header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { setCollapsed(!collapsed); }
        });
        this.root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        this.body = new LinearLayout(context);
        this.body.setOrientation(LinearLayout.VERTICAL);
        this.root.addView(body, new LinearLayout.LayoutParams(-1, -2));
        setCollapsed(false);
    }

    LinearLayout root() { return root; }
    LinearLayout body() { return body; }
    boolean isCollapsed() { return collapsed; }

    void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        header.setText(title + (collapsed ? "  ▼" : "  ▲"));
    }
}
