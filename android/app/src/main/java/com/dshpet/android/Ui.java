package com.dshpet.android;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.*;
import android.widget.*;

/** Small native design system: accessible controls, system insets and responsive cards. */
final class Ui {
  static final int ACCENT = Color.rgb(119, 96, 214);

  private Ui() {}

  static void theme(Activity activity) {
    String selected = activity.getSharedPreferences("app", 0).getString("theme", "system");
    activity.setTheme(
        "dark".equals(selected)
            ? R.style.Theme_Pet_Dark
            : "light".equals(selected) ? R.style.Theme_Pet_Light : R.style.Theme_Pet);
  }

  static int dp(Context context, float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  static int background(Context context) {
    android.util.TypedValue value = new android.util.TypedValue();
    context.getTheme().resolveAttribute(android.R.attr.colorBackground, value, true);
    return value.data;
  }

  static boolean dark(Context context) {
    return Color.luminance(background(context)) < .4;
  }

  static LinearLayout column(Context context) {
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    return layout;
  }

  static LinearLayout row(Context context) {
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.HORIZONTAL);
    layout.setGravity(Gravity.CENTER_VERTICAL);
    return layout;
  }

  static LinearLayout screen(Activity activity) {
    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(background(activity));
    LinearLayout column = column(activity);
    int padding = dp(activity, 20);
    column.setPadding(padding, padding, padding, padding);
    scroll.addView(column);
    activity.setContentView(scroll);
    insets(scroll);
    return column;
  }

  static void insets(View view) {
    view.setOnApplyWindowInsetsListener(
        (target, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else
            target.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
  }

  static TextView text(Context context, String value, int size) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(
        size <= 14
            ? (dark(context) ? 0xffb1b9d0 : 0xff6b7891)
            : (dark(context) ? 0xffe9e9f5 : 0xff24304d));
    view.setPadding(0, dp(context, 6), 0, dp(context, 10));
    return view;
  }

  static void heading(LinearLayout parent, String value, String description) {
    TextView title = text(parent.getContext(), value, 26);
    title.setTypeface(null, Typeface.BOLD);
    parent.addView(title);
    if (!description.isEmpty()) parent.addView(text(parent.getContext(), description, 14));
  }

  static void cardHeading(LinearLayout parent, String title, String description) {
    TextView label = text(parent.getContext(), title, 20);
    label.setTypeface(null, Typeface.BOLD);
    parent.addView(label);
    if (!description.isEmpty()) parent.addView(text(parent.getContext(), description, 13));
  }

  private static GradientDrawable rounded(Context context, int fill, int stroke) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(fill);
    drawable.setCornerRadius(dp(context, 12));
    if (stroke != 0) drawable.setStroke(dp(context, 1), stroke);
    return drawable;
  }

  static LinearLayout card(LinearLayout parent) {
    Context context = parent.getContext();
    LinearLayout card = column(context);
    int padding = dp(context, 16);
    card.setPadding(padding, padding, padding, padding);
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(dark(context) ? 0xff222638 : Color.WHITE);
    drawable.setCornerRadius(dp(context, 18));
    drawable.setStroke(dp(context, 1), dark(context) ? 0xff34394d : 0xffe8e9f3);
    card.setBackground(drawable);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(context, 12);
    parent.addView(card, params);
    return card;
  }

  static Button button(LinearLayout parent, String label, Runnable action) {
    Context context = parent.getContext();
    Button button = new Button(context);
    button.setText(label);
    button.setAllCaps(false);
    button.setMinHeight(dp(context, 48));
    button.setTextSize(14);
    button.setStateListAnimator(null);
    button.setMaxLines(2);
    button.setEllipsize(android.text.TextUtils.TruncateAt.END);
    button.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
    boolean primary = label.startsWith("＋") || label.equals("发送") || label.startsWith("保存 AI");
    android.graphics.drawable.StateListDrawable states =
        new android.graphics.drawable.StateListDrawable();
    states.addState(
        new int[] {-android.R.attr.state_enabled},
        rounded(context, dark(context) ? 0xff303547 : 0xffe9ebf3, 0));
    states.addState(new int[] {android.R.attr.state_selected}, rounded(context, ACCENT, 0));
    states.addState(
        new int[] {},
        rounded(
            context,
            primary ? ACCENT : dark(context) ? 0xff292e43 : 0xfffaf9ff,
            primary ? 0 : dark(context) ? 0xff48415e : 0xffddd7f1));
    button.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x227760d6), states, null));
    button.setTextColor(
        new android.content.res.ColorStateList(
            new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_selected}, {}},
            new int[] {
              dark(context) ? 0xff7f879c : 0xff98a0b3,
              Color.WHITE,
              primary ? Color.WHITE : dark(context) ? 0xffc5b7ff : 0xff6c54c1
            }));
    button.setOnClickListener(view -> action.run());
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            parent.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1,
            -2,
            parent.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0);
    params.topMargin = dp(context, 8);
    if (parent.getOrientation() == LinearLayout.HORIZONTAL) params.setMarginEnd(dp(context, 6));
    parent.addView(button, params);
    return button;
  }

  static EditText field(LinearLayout parent, String label, String value, boolean multiline) {
    parent.addView(text(parent.getContext(), label, 13));
    EditText edit = new EditText(parent.getContext());
    edit.setText(value);
    edit.setContentDescription(label);
    edit.setTextSize(16);
    edit.setMinHeight(dp(parent.getContext(), 48));
    edit.setBackground(
        rounded(
            parent.getContext(),
            dark(parent.getContext()) ? 0xff1c2133 : 0xfff8f9fd,
            dark(parent.getContext()) ? 0xff3d435c : 0xffdfe3ef));
    edit.setPadding(
        dp(parent.getContext(), 12),
        dp(parent.getContext(), 10),
        dp(parent.getContext(), 12),
        dp(parent.getContext(), 10));
    edit.setSingleLine(!multiline);
    if (multiline) {
      edit.setMinLines(3);
      edit.setGravity(Gravity.TOP);
      edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    }
    parent.addView(edit, new LinearLayout.LayoutParams(-1, -2));
    return edit;
  }

  static Switch toggle(LinearLayout parent, String label, boolean checked) {
    Switch control = new Switch(parent.getContext());
    control.setText(label);
    control.setChecked(checked);
    control.setMinHeight(dp(parent.getContext(), 52));
    parent.addView(control);
    return control;
  }

  static void message(Context context, String message) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
  }

  static void confirm(Activity activity, String title, String message, Runnable accept) {
    new AlertDialog.Builder(activity)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton("取消", null)
        .setPositiveButton("确定", (dialog, which) -> accept.run())
        .show();
  }

  static String safeError(Exception error) {
    String message = error.getMessage();
    return error instanceof java.io.IOException
            && message != null
            && !message.contains("https:")
            && !message.contains("http:")
        ? message
        : "操作未完成，请检查输入、网络或系统权限后重试";
  }
}
