package com.gukangran.subtraffic;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String PREF = "subtraffic";
    private static final String K_LIST = "list";
    private static final String K_UA = "ua";
    private static final String K_MODE = "mode";
    /** which number goes in the big coloured spot: "remain" (default) or "used" */
    private static final String K_PRIMARY = "primary";
    private static final String PRIMARY_REMAIN = "remain";
    private static final String PRIMARY_USED = "used";
    /** index into FONT_SCALES: 0 小 / 1 标准 / 2 大 / 3 特大 */
    private static final String K_FONT = "font";
    private static final float[] FONT_SCALES = {0.88f, 1.0f, 1.15f, 1.3f};
    /** theme: "auto"（跟随系统，默认）/ "light" / "dark" */
    private static final String K_THEME = "theme";
    private static final String THEME_AUTO = "auto";
    /** colour scheme: "default"（品牌蓝）/ "monet"（莫奈，壁纸取色，Android 12+） */
    private static final String K_COLOR = "color";
    private static final String COLOR_DEFAULT = "default";
    private static final String COLOR_MONET = "monet";

    /** timer refresh: only the locked method, never the whole matrix */
    private static final int REFRESH_FAST = 0;
    /** user action: locked method first, whole matrix if that fails */
    private static final int REFRESH_MANUAL = 1;
    /** detail page "重新验证方式": always walk the whole matrix */
    private static final int REFRESH_FULL = 2;

    private EditText etSearch;
    private TextView tvHeaderTitle;
    private TextView tvHeaderSub;
    private View headerRow;
    private View titleBlock;
    private View searchPill;
    private View actionButtons;
    private ImageButton btnSearchBall;
    private ImageButton btnRefreshAll;
    /** true while the magnifier ball is stretched out into the search bar */
    private boolean searchExpanded = false;
    private ListView lv;
    private TextView tvEmpty;
    private Adapter adapter;

    private final List<Sub> subs = new ArrayList<Sub>();
    private final List<Sub> shown = new ArrayList<Sub>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private int pending = 0;

    private AlertDialog detailDialog;
    private Sub detailSub;
    private TextView detailBody;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        // 莫奈配色：换成壁纸取色的主题（这份主题只在 Android 12+ 存在）
        if (monet()) {
            setTheme(R.style.AppTheme_Monet);
        }
        // 先把保存的主题同步给系统（应用级夜间模式），再铺界面
        syncTheme(false);
        setContentView(R.layout.activity_main);

        etSearch = findViewById(R.id.etSearch);
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle);
        tvHeaderSub = findViewById(R.id.tvHeaderSub);
        headerRow = findViewById(R.id.headerRow);
        titleBlock = findViewById(R.id.titleBlock);
        searchPill = findViewById(R.id.searchPill);
        actionButtons = findViewById(R.id.actionButtons);
        btnSearchBall = findViewById(R.id.btnSearchBall);
        lv = findViewById(R.id.lv);
        tvEmpty = findViewById(R.id.tvEmpty);
        ImageButton btnAdd = findViewById(R.id.btnAdd);
        ImageButton btnMore = findViewById(R.id.btnMore);
        ImageButton btnSearchClose = findViewById(R.id.btnSearchClose);
        btnRefreshAll = findViewById(R.id.btnRefreshAll);
        // 跟主色相关的几处按当前配色刷一遍（默认配色时就是原来的品牌蓝）
        int accent = themePrimary();
        btnRefreshAll.setBackgroundTintList(ColorStateList.valueOf(accent));
        btnSearchBall.setImageTintList(ColorStateList.valueOf(accent));
        btnAdd.setImageTintList(ColorStateList.valueOf(accent));

        subs.addAll(SubCodec.decode(prefs.getString(K_LIST, "")));
        adapter = new Adapter();
        lv.setAdapter(adapter);
        updateShown();
        applyFontScale();
        // 冷启动（进程重开，不是界面重建）时自动查一次余额
        if (savedInstanceState == null && !subs.isEmpty()) {
            refreshAll(REFRESH_MANUAL);
        }

        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddSheet();
            }
        });

        btnSearchBall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (searchExpanded) {
                    collapseSearch();
                } else {
                    expandSearch();
                }
            }
        });

        // the × inside the bar closes it again, with the same animation played backwards
        btnSearchClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapseSearch();
            }
        });

        // 右下角：一键刷新所有订阅
        btnRefreshAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (subs.isEmpty()) {
                    toast("还没有订阅");
                } else {
                    refreshAll(REFRESH_MANUAL);
                    toast("正在刷新全部订阅");
                }
            }
        });

        // No ActionBar in this theme, so the options menu needs its own entry point.
        btnMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOptionsMenu();
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateShown();
            }
        });
    }

    /** Back first closes the search bar, and only then leaves the app. */
    @Override
    public void onBackPressed() {
        if (searchExpanded) {
            collapseSearch();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ search bar

    /**
     * The magnifier ball IS the search bar: the very same view is stretched leftwards until
     * its left edge reaches the right edge of the title. Only the width changes - no fade,
     * no scaling, no popup, and the title / + / more buttons never move.
     */
    private void expandSearch() {
        if (searchExpanded) {
            return;
        }
        searchExpanded = true;
        int target = searchTargetWidth();
        animatePillWidth(searchPill.getWidth(), target, 260, new Runnable() {
            @Override
            public void run() {
                etSearch.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    /** How far the ball may stretch: the gap between the title and the + button. */
    private int searchTargetWidth() {
        int right = actionButtons.getLeft() - dp(8);
        int gap = right - titleBlock.getRight() - dp(8);
        if (gap <= 0) {
            gap = getResources().getDisplayMetrics().widthPixels
                    - titleBlock.getWidth() - actionButtons.getWidth() - dp(70);
        }
        return Math.max(gap, dp(96));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void collapseSearch() {
        if (!searchExpanded) {
            return;
        }
        searchExpanded = false;
        etSearch.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        }
        animatePillWidth(searchPill.getWidth(), dp(36), 260, null);
        updateShown();
    }

    /** Animates the pill's layout width so it looks like it slides open from the right. */
    private void animatePillWidth(int from, int to, long duration, final Runnable onEnd) {
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                int w = (Integer) animation.getAnimatedValue();
                ViewGroup.LayoutParams lp = searchPill.getLayoutParams();
                if (lp.width != w) {
                    lp.width = w;
                    searchPill.setLayoutParams(lp);
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled && onEnd != null) {
                    onEnd.run();
                }
            }
        });
        animator.start();
    }

    // ------------------------------------------------------------------ list & filter

    /** The search bar filters the subscriptions already added; adding happens in the + sheet. */
    private void updateShown() {
        String q = etSearch.getText() == null ? "" : etSearch.getText().toString().trim().toLowerCase(Locale.US);
        shown.clear();
        for (int i = 0; i < subs.size(); i++) {
            Sub s = subs.get(i);
            if (q.isEmpty()
                    || s.displayName().toLowerCase(Locale.US).contains(q)
                    || s.url.toLowerCase(Locale.US).contains(q)) {
                shown.add(s);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmpty(q);
    }

    private void updateEmpty(String query) {
        if (subs.isEmpty()) {
            tvEmpty.setText(R.string.empty_hint);
            tvEmpty.setVisibility(View.VISIBLE);
        } else if (shown.isEmpty() && query != null && !query.isEmpty()) {
            tvEmpty.setText("没有匹配「" + query + "」的订阅");
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ add sheet

    /** The + button opens a sheet that slides up from the bottom. */
    private void showAddSheet() {
        final Dialog dialog = new Dialog(this, R.style.BottomSheetDialog);
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_add, null);
        final EditText input = root.findViewById(R.id.etNewUrl);
        Button paste = root.findViewById(R.id.btnSheetPaste);
        Button add = root.findViewById(R.id.btnSheetAdd);
        add.setBackgroundTintList(ColorStateList.valueOf(themePrimary()));
        Button cancel = root.findViewById(R.id.btnSheetCancel);

        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
                    toast("剪贴板是空的");
                    return;
                }
                CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                if (text == null || text.length() == 0) {
                    toast("剪贴板是空的");
                    return;
                }
                input.setText(text.toString().trim());
                input.setSelection(input.getText().length());
            }
        });

        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String raw = input.getText() == null ? "" : input.getText().toString().trim();
                String url = normalize(raw);
                if (url == null) {
                    toast("请填写 http / https 订阅链接");
                    return;
                }
                dialog.dismiss();
                addSubscription(url);
            }
        });

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        dialog.setContentView(root);
        dialog.show();

        Window w = dialog.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        input.requestFocus();
    }

    private void addSubscription(String url) {
        for (int i = 0; i < subs.size(); i++) {
            if (subs.get(i).url.equals(url)) {
                toast("这条订阅已经存在，正在刷新");
                enqueue(subs.get(i), REFRESH_MANUAL);
                return;
            }
        }
        Sub s = new Sub();
        s.url = url;
        s.name = Sub.hostOf(url);
        subs.add(s);
        save();
        updateShown();
        enqueue(s, REFRESH_MANUAL);
    }

    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace(" ", "").replace("\n", "");
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        if (s.contains("://")) {
            return null;
        }
        return "https://" + s;
    }

    // ------------------------------------------------------------------ detail dialog

    private void showDetail(final Sub s) {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_detail, null);
        detailBody = root.findViewById(R.id.tvDiagBody);
        detailBody.setText(buildDiagText(s));

        Button recheck = root.findViewById(R.id.btnRecheck);
        recheck.setBackgroundTintList(ColorStateList.valueOf(themePrimary()));
        Button now = root.findViewById(R.id.btnRefreshNow);
        Button edit = root.findViewById(R.id.btnEditUrl);
        Button copy = root.findViewById(R.id.btnCopyDiag);
        Button delete = root.findViewById(R.id.btnDeleteSub);
        Button close = root.findViewById(R.id.btnCloseDiag);
        Button rename = root.findViewById(R.id.btnRename);

        detailSub = s;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.diag_title)
                .setView(root)
                .create();
        detailDialog = dialog;

        recheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                enqueue(s, REFRESH_FULL);
            }
        });
        now.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                enqueue(s, REFRESH_MANUAL);
            }
        });
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(detailBody.getText().toString(), "已复制详情");
            }
        });
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showEditUrl(s);
            }
        });
        rename.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showRename(s);
            }
        });
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                confirmDelete(s);
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                detailDialog = null;
                detailSub = null;
                detailBody = null;
            }
        });
        dialog.show();
    }

    private String buildDiagText(Sub s) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(s.displayName()).append("\n");
        sb.append("订阅：").append(s.url).append("\n");
        if (s.lastQuery > 0L) {
            sb.append("最后刷新：").append(fmtTime(s.lastQuery)).append("\n");
        }
        sb.append("已锁定方式：").append(methodLabel(s)).append("\n");

        sb.append("\n【结果】\n");
        if (s.hasInfo) {
            Userinfo u = s.info();
            sb.append(u.summaryRemaining()).append("\n");
            sb.append(u.summaryUsed()).append("\n");
            sb.append(u.summaryExpire()).append("\n");
        } else {
            sb.append("未取到流量信息\n");
        }
        sb.append("\n【subscription-userinfo 原始值】\n");
        sb.append(s.rawHeader.isEmpty() ? "（空）" : s.rawHeader).append("\n");
        if (!TextUtils.isEmpty(s.error)) {
            sb.append("\n【状态】\n").append(s.error).append("\n");
        }
        sb.append("\n【本次尝试记录】\n");
        sb.append(TextUtils.isEmpty(s.diag) ? "（本次运行还没有记录）" : s.diag);
        return sb.toString();
    }

    private String methodLabel(Sub s) {
        if (TextUtils.isEmpty(s.method)) {
            return "还没有锁定（在下面点「重新验证方式」会自动匹配）";
        }
        Strategies.Attempt a = Strategies.byKey(s.method, prefs.getString(K_UA, Net.DEFAULT_UA));
        return (a != null) ? a.label : s.method;
    }

    private void showEditUrl(final Sub s) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(s.url);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("修改订阅链接")
                .setMessage("把机场面板里新的订阅链接粘进来（长按输入框可粘贴）。保存后会重新匹配查询方式。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并查询", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = normalize(input.getText() == null ? "" : input.getText().toString());
                        if (url == null) {
                            toast("这不是 http/https 订阅链接");
                            return;
                        }
                        s.url = url;
                        s.name = Sub.hostOf(url);
                        s.method = "";
                        s.error = "";
                        save();
                        updateShown();
                        enqueue(s, REFRESH_FULL);
                    }
                })
                .show();
    }

    private static String shortMsg(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    /** 自定义这条订阅的名字；留空就恢复默认（机场标题或域名）。 */
    private void showRename(final Sub s) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(s.displayName());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setMessage("给这条订阅起个自己的名字，留空则恢复默认名称。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String v = input.getText() == null ? "" : input.getText().toString().trim();
                        s.customName = v;
                        save();
                        updateShown();
                        toast(v.isEmpty() ? "已恢复默认名称" : "已重命名为「" + v + "」");
                    }
                })
                .show();
    }

    private void confirmDelete(final Sub s) {
        new AlertDialog.Builder(this)
                .setTitle("删除订阅")
                .setMessage("确定删除「" + s.displayName() + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        subs.remove(s);
                        save();
                        updateShown();
                    }
                })
                .show();
    }

    private void copy(String text, String toastText) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("subscription", text));
            toast(toastText);
        }
    }

    // ------------------------------------------------------------------ querying

    private void refreshAll(int mode) {
        for (int i = 0; i < subs.size(); i++) {
            Sub s = subs.get(i);
            if (mode == REFRESH_FAST && TextUtils.isEmpty(s.method)) {
                // nothing locked yet: don't burn a whole matrix run on every timer tick
                continue;
            }
            enqueue(s, mode);
        }
    }

    private void enqueue(final Sub s, final int mode) {
        if (s.loading) {
            return;
        }
        s.loading = true;
        s.progressNote = "正在刷新";
        s.error = "";
        pending++;
        adapter.notifyDataSetChanged();
        syncFabSpin();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                runQuery(s, mode);
            }
        });
    }

    /** 右下角一键刷新按钮：有查询在跑的时候自己转起来。 */
    private void syncFabSpin() {
        if (btnRefreshAll == null) {
            return;
        }
        btnRefreshAll.clearAnimation();
        if (pending > 0) {
            RotateAnimation spin = new RotateAnimation(0f, 360f,
                    Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
            spin.setDuration(900L);
            spin.setRepeatCount(Animation.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
            btnRefreshAll.startAnimation(spin);
        }
    }

    /** Result of one successful attempt. */
    private static final class Outcome {
        Userinfo u;
        String title = "";
        String label = "";
        String key = "";
        String preview = "";
        boolean noHttpResponse = false;
        boolean tokenRejected = false;
        String serverMessage = "";
    }

    private void runQuery(Sub s, int mode) {
        final String cfgUa = prefs.getString(K_UA, Net.DEFAULT_UA);
        final int strategyMode = prefs.getInt(K_MODE, Strategies.MODE_AUTO);

        StringBuilder log = new StringBuilder();
        String preview = "";
        Outcome out = null;
        int step = 0;
        boolean hadLock = false;
        boolean tokenRejected = false;
        String tokenMessage = "";

        Strategies.Attempt locked = Strategies.byKey(s.method, cfgUa);
        if (mode != REFRESH_FULL && locked != null) {
            hadLock = true;
            step++;
            Outcome o = tryAttempt(s, locked, cfgUa, log, step, "正在刷新");
            if (!o.preview.isEmpty()) {
                preview = o.preview;
            }
            if (o.u != null) {
                out = o;
            } else if (o.tokenRejected) {
                tokenRejected = true;
                tokenMessage = o.serverMessage;
                log.append("　→ 服务器判定订阅令牌无效，停止尝试\n");
            } else {
                log.append("　→ 已锁定的方式这次没取到\n");
            }
        }

        if (out == null && mode != REFRESH_FAST) {
            List<Strategies.Attempt> attempts = Strategies.build(cfgUa, strategyMode);
            boolean networkDown = false;
            for (int i = 0; i < attempts.size(); i++) {
                Strategies.Attempt a = attempts.get(i);
                if (Strategies.skipDirectAfterNetworkFailure(networkDown, a)) {
                    continue;
                }
                step++;
                Outcome o = tryAttempt(s, a, cfgUa, log, step,
                        "匹配方式 " + (i + 1) + "/" + attempts.size());
                if (preview.isEmpty() && !o.preview.isEmpty()) {
                    preview = o.preview;
                }
                if (!a.proxy && o.noHttpResponse) {
                    networkDown = true;
                    log.append("　→ 直连连不上，跳过其余直连方式，直接试本机代理\n");
                }
                if (o.u != null) {
                    out = o;
                    break;
                }
                if (o.tokenRejected) {
                    tokenRejected = true;
                    tokenMessage = o.serverMessage;
                    log.append("　→ 服务器判定订阅令牌无效（换标识和换代理都没用），停止尝试\n");
                    break;
                }
            }
        }

        final Outcome result = out;
        final String previewText = preview;
        final String logText = log.toString();
        final boolean lockedFailed = hadLock;
        final boolean tokenRejectedFinal = tokenRejected;
        final String tokenMessageFinal = tokenMessage;

        ui.post(new Runnable() {
            @Override
            public void run() {
                s.loading = false;
                if (result != null) {
                    s.apply(result.u, result.title);
                    if (!TextUtils.isEmpty(result.key)) {
                        s.method = result.key;
                    }
                    s.diag = "命中方式：" + result.label + "（已锁定，之后刷新只走这一种）\n\n" + logText
                            + (previewText.isEmpty() ? "" : ("\n响应内容片段：\n" + previewText + "\n"));
                } else {
                    if (tokenRejectedFinal) {
                        s.fail("订阅链接被服务器拒绝：" + shortMsg(tokenMessageFinal) + " · 点详情可改链接");
                    } else if (lockedFailed) {
                        s.fail("已锁定方式失效 · 点详情重新验证");
                    } else if (TextUtils.isEmpty(s.method)) {
                        s.fail("还没有锁定可用方式 · 点详情重新验证");
                    } else {
                        s.fail("本次刷新没取到流量信息 · 点详情看原因");
                    }
                    s.diag = logText
                            + (previewText.isEmpty() ? "" : ("\n响应内容片段：\n" + previewText + "\n"))
                            + (tokenRejectedFinal
                                ? "\n结论：服务器明确拒绝了这条订阅链接的令牌。"
                                  + "换客户端标识、换代理、换别的 App 结果都一样——"
                                  + "请在机场面板重新复制订阅链接，再用上面的「编辑链接」粘贴进来。"
                                : "\n提示：直连连不上就先把 VPN 打开，再在详情里点「重新验证方式」。");
                }
                pending--;
                if (pending < 0) {
                    pending = 0;
                }
                s.progressNote = "";
                save();
                adapter.notifyDataSetChanged();
                syncFabSpin();
                if (detailSub == s && detailBody != null && detailDialog != null && detailDialog.isShowing()) {
                    detailBody.setText(buildDiagText(s));
                }
            }
        });
    }

    private Outcome tryAttempt(Sub s, Strategies.Attempt a, String cfgUa,
                               StringBuilder log, int step, final String progressText) {
        s.progressNote = progressText;
        ui.post(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });

        String ua = (a.ua != null) ? a.ua : cfgUa;
        Net.Resp r = Net.get(s.url, ua, a.proxy, a.proxyType, a.port);
        Userinfo u = Userinfo.parse(r.header("subscription-userinfo"));

        log.append("[").append(step).append("] ").append(a.label).append(" → ");
        if (r.error != null) {
            log.append(r.code == 0 ? "连不上（" + r.error + "）" : "失败（" + r.error + "）");
        } else {
            log.append("HTTP ").append(r.code);
        }
        log.append(u != null ? "，取到流量信息" : "，无流量信息");
        String serverMsg = r.bodyPreview == null ? "" : r.bodyPreview.trim();
        if (u == null && r.code >= 400 && !serverMsg.isEmpty()) {
            log.append("，服务器说：").append(serverMsg);
        }
        log.append("\n");

        Outcome o = new Outcome();
        o.preview = serverMsg;
        o.noHttpResponse = (r.code == 0);
        o.serverMessage = serverMsg;
        o.tokenRejected = Net.isTokenRejected(r);
        if (u != null) {
            o.u = u;
            o.title = decodeProfileTitle(r.header("profile-title"));
            o.label = a.label;
            o.key = a.key();
        }
        return o;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private String decodeProfileTitle(String header) {
        if (isEmpty(header)) {
            return "";
        }
        String v = header.trim();
        try {
            if (v.startsWith("base64:")) {
                byte[] raw = Base64.decode(v.substring("base64:".length()).trim(), Base64.DEFAULT);
                return new String(raw, "UTF-8").trim();
            }
        } catch (Exception ignored) {
        }
        return v;
    }

    private void save() {
        prefs.edit().putString(K_LIST, SubCodec.encode(subs)).apply();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.menu_mode);
        menu.add(0, 3, 1, R.string.menu_refresh_all);
        menu.add(0, 4, 2, R.string.menu_ua);
        // label flips with the current state: "优先显示已用" <-> "优先显示剩余"
        menu.add(0, 6, 3, preferUsed() ? R.string.menu_show_remain : R.string.menu_show_used);
        menu.add(0, 7, 4, R.string.menu_font);
        menu.add(0, 8, 5, R.string.menu_theme);
        menu.add(0, 9, 6, R.string.menu_color);
        menu.add(0, 5, 7, R.string.menu_about);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            editMode();
            return true;
        }
        if (item.getItemId() == 3) {
            if (subs.isEmpty()) {
                toast("还没有订阅");
            } else {
                refreshAll(REFRESH_MANUAL);
                toast("已开始刷新（优先用已锁定方式）");
            }
            return true;
        }
        if (item.getItemId() == 4) {
            editUa();
            return true;
        }
        if (item.getItemId() == 5) {
            showAbout();
            return true;
        }
        if (item.getItemId() == 6) {
            togglePrimary();
            return true;
        }
        if (item.getItemId() == 7) {
            editFont();
            return true;
        }
        if (item.getItemId() == 8) {
            editTheme();
            return true;
        }
        if (item.getItemId() == 9) {
            editColor();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** true when the big coloured number should show the USED amount instead of the remaining. */
    private boolean preferUsed() {
        return PRIMARY_USED.equals(prefs.getString(K_PRIMARY, PRIMARY_REMAIN));
    }

    /** Flips the big number between remaining and used; the menu label follows. */
    private void togglePrimary() {
        boolean used = !preferUsed();
        prefs.edit().putString(K_PRIMARY, used ? PRIMARY_USED : PRIMARY_REMAIN).apply();
        invalidateOptionsMenu();
        adapter.notifyDataSetChanged();
        toast(used ? "大数字改为显示已用" : "大数字改为显示剩余");
    }

    private void editMode() {
        final String[] items = {
                "自动（推荐）：先直连，再换客户端标识，最后试本机代理",
                "仅直连：不使用本机代理",
                "仅走本机代理：先开好 VPN，再查"
        };
        int current = prefs.getInt(K_MODE, Strategies.MODE_AUTO);
        new AlertDialog.Builder(this)
                .setTitle("查询方式")
                .setSingleChoiceItems(items, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putInt(K_MODE, which).apply();
                        dialog.dismiss();
                        toast("已切换为：" + items[which].split("：")[0]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 0.88 / 1.0 / 1.15 / 1.3 – applied to the cards and the empty hint. */
    private float fontScale() {
        int idx = prefs.getInt(K_FONT, 1);
        if (idx < 0 || idx >= FONT_SCALES.length) {
            idx = 1;
        }
        return FONT_SCALES[idx];
    }

    private void editFont() {
        final String[] labels = {"小", "标准", "大", "特大"};
        int current = prefs.getInt(K_FONT, 1);
        if (current < 0 || current >= labels.length) {
            current = 1;
        }
        new AlertDialog.Builder(this)
                .setTitle("字体大小")
                .setSingleChoiceItems(labels, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putInt(K_FONT, which).apply();
                        dialog.dismiss();
                        applyFontScale();
                        adapter.notifyDataSetChanged();
                        toast("字体大小：" + labels[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 卡片文字 + 顶部标题 + 空列表提示都跟着这个比例走。 */
    private void applyFontScale() {
        float fs = fontScale();
        if (tvEmpty != null) {
            tvEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * fs);
        }
        if (tvHeaderTitle != null) {
            tvHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * fs);
        }
        if (tvHeaderSub != null) {
            tvHeaderSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * fs);
        }
    }

    /** 是否开了莫奈配色（壁纸取色）；低版本一律当成没开。 */
    private boolean monet() {
        return COLOR_MONET.equals(prefs.getString(K_COLOR, COLOR_DEFAULT))
                && Build.VERSION.SDK_INT >= 31;
    }

    /** 当前配色下的主色：默认配色 = 品牌蓝；莫奈配色 = 系统从壁纸算出来的主色。 */
    private int themePrimary() {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true)
                && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        return getResources().getColor(R.color.primary);
    }

    /** 配色：默认（品牌蓝）/ 莫奈（跟随壁纸取色，Android 12+）。 */
    private void editColor() {
        if (Build.VERSION.SDK_INT < 31) {
            toast("莫奈配色要 Android 12 及以上，这台机器用不了");
            return;
        }
        final String[] labels = {"默认（品牌蓝）", "莫奈（跟随壁纸）"};
        final String[] values = {COLOR_DEFAULT, COLOR_MONET};
        int current = COLOR_MONET.equals(prefs.getString(K_COLOR, COLOR_DEFAULT)) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("配色")
                .setSingleChoiceItems(labels, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putString(K_COLOR, values[which]).apply();
                        dialog.dismiss();
                        recreate();
                        toast("配色：" + labels[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 主题：跟随系统 / 浅色 / 深色。 */
    private void editTheme() {
        final String[] labels = {"跟随系统", "浅色", "深色"};
        final String[] values = {THEME_AUTO, "light", "dark"};
        String saved = prefs.getString(K_THEME, THEME_AUTO);
        int current = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(saved)) {
                current = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("主题")
                .setSingleChoiceItems(labels, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putString(K_THEME, values[which]).apply();
                        dialog.dismiss();
                        syncTheme(true);
                        toast("主题：" + labels[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 把设置里的主题同步给系统：Android 12(31) 起用 setApplicationNightMode，只影响本应用；
     * 更老的系统试着用 setNightMode，没有权限就忽略（"跟随系统"在任何版本都有效）。
     */
    private void syncTheme(boolean recreateNow) {
        String mode = prefs.getString(K_THEME, THEME_AUTO);
        boolean dark = "dark".equals(mode);
        boolean light = "light".equals(mode);
        try {
            UiModeManager um = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            if (um != null) {
                int want = dark ? UiModeManager.MODE_NIGHT_YES
                        : (light ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_AUTO);
                if (Build.VERSION.SDK_INT >= 31) {
                    um.setApplicationNightMode(want);
                } else if (Build.VERSION.SDK_INT >= 23) {
                    um.setNightMode(want);
                }
            }
        } catch (Throwable ignored) {
            // 老系统上没有这个能力，交给系统自己的夜间模式
        }
        if (recreateNow) {
            recreate();
        }
    }

    private void editUa() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(prefs.getString(K_UA, Net.DEFAULT_UA));
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("查询时使用的 User-Agent")
                .setMessage("部分机场按客户端类型下发流量信息。留空则使用默认值。\n"
                        + "一般不用改，自动匹配时会轮换常见的客户端标识。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String v = input.getText() == null ? "" : input.getText().toString().trim();
                        prefs.edit().putString(K_UA, v.isEmpty() ? Net.DEFAULT_UA : v).apply();
                    }
                })
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage("Quota v2.7\n\n"
                        + "作者：gukangran2026-source（GitHub）\n\n"
                        + "用途：把订阅链接里的额度信息读出来，显示剩余流量和到期时间。\n\n"
                        + "原理：向订阅链接发一次 GET 请求，读取服务器返回的 subscription-userinfo 响应头"
                        + "（upload / download / total / expire，单位字节）。"
                        + "剩余 = total − upload − download；total=0 视为不限量，expire=0 视为不过期。\n\n"
                        + "工作方式：\n"
                        + "· 添加订阅时自动逐个试各种方式，命中后把该方式锁定（记在这条订阅里）。\n"
                        + "· 之后每次刷新（卡片上的刷新按钮、右下角一键刷新、菜单里的立即刷新）只走锁定方式，一次请求完成。\n"
                        + "· 没有自动刷新：冷启动时查一次，平时只有你点才刷；锁定方式失效时状态行会提示，"
                        + "在详情里点「重新验证方式」重跑即可。\n\n"
                        + "· 配色：菜单里的「配色」可切换「默认（品牌蓝）」与「莫奈（跟随壁纸取色，Android 12 及以上）」。\n\n"
                        + "· 若服务器明确拒绝订阅令牌（例如 token is error），会立刻停止并提示，"
                        + "可在详情里用「编辑链接」换成新链接。\n\n"
                        + "说明：本应用不解析节点、不代理任何流量，只读响应头；数据只保存在本机。")
                .setPositiveButton("知道了", null)
                .show();
    }

    // ------------------------------------------------------------------ adapter

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_sub, parent, false);
            }
            final Sub s = shown.get(position);

            View header = v.findViewById(R.id.cardHeader);
            TextView name = v.findViewById(R.id.tvName);
            TextView badge = v.findViewById(R.id.tvBadge);
            TextView remain = v.findViewById(R.id.tvRemain);
            TextView detail = v.findViewById(R.id.tvDetail);
            View usageRow = v.findViewById(R.id.usageRow);
            ProgressBar usageBar = v.findViewById(R.id.pbUsage);
            usageBar.setProgressTintList(ColorStateList.valueOf(themePrimary()));
            TextView percent = v.findViewById(R.id.tvPercent);
            TextView status = v.findViewById(R.id.tvStatus);
            Button btnDetail = v.findViewById(R.id.btnDetail);
            ImageButton btnRefresh = v.findViewById(R.id.btnRefreshCard);

            // 字体大小设置：卡片文字按比例缩放，色块高度跟着长，避免被挤掉
            float fs = fontScale();
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * fs);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * fs);
            remain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * fs);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * fs);
            percent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * fs);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * fs);
            btnDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * fs);
            ViewGroup.LayoutParams hlp = header.getLayoutParams();
            hlp.height = Math.round(100f * fs * getResources().getDisplayMetrics().density);
            header.setLayoutParams(hlp);

            int blue = themePrimary();
            int green = getResources().getColor(R.color.ok);
            int red = getResources().getColor(R.color.warn);
            int gray = getResources().getColor(R.color.text_secondary);

            name.setText(s.displayName());
            // 卡片上的刷新按钮：点它只刷这一条（先用已锁定方式，失败再全矩阵）
            btnRefresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (!s.loading) {
                        enqueue(s, REFRESH_MANUAL);
                    }
                }
            });
            // 刷新中让这个图标自己转起来
            btnRefresh.clearAnimation();
            if (s.loading) {
                RotateAnimation spin = new RotateAnimation(0f, 360f,
                        Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                spin.setDuration(900L);
                spin.setRepeatCount(Animation.INFINITE);
                spin.setInterpolator(new LinearInterpolator());
                btnRefresh.startAnimation(spin);
            }
            btnDetail.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showDetail(s);
                }
            });

            // The card keeps its previous numbers while refreshing - only the pill in the
            // top-right corner changes to "正在刷新", so nothing jumps or disappears.
            Userinfo u = s.hasInfo ? s.info() : null;
            boolean tight = (u != null) && !u.unlimited() && u.remaining() * 100L / u.total < 10L;
            int block = (u == null) ? gray : (u.unlimited() ? green : (tight ? red : blue));
            header.setBackgroundTintList(ColorStateList.valueOf(block));

            if (s.loading) {
                badge.setVisibility(View.VISIBLE);
                badge.setTextColor(0xFFFFFFFF);
                badge.setText(TextUtils.isEmpty(s.progressNote) ? "正在刷新" : s.progressNote);
            } else if (u == null) {
                badge.setVisibility(View.GONE);
            } else {
                badge.setVisibility(View.VISIBLE);
                badge.setTextColor(0xFFFFFFFF);
                badge.setText(u.unlimited() ? "不限量" : (tight ? "即将用尽" : "正常"));
            }

            boolean preferUsed = preferUsed();
            if (u != null) {
                // the big number shows whichever metric the user picked in the menu;
                // the detail line then carries the other one, so both are always visible
                if (u.unlimited()) {
                    remain.setText(preferUsed ? Userinfo.fmtBytes(u.used()) : "不限量");
                } else {
                    remain.setText(Userinfo.fmtBytes(preferUsed ? u.used() : u.remaining()));
                }

                StringBuilder d = new StringBuilder();
                if (!u.unlimited()) {
                    d.append("共 ").append(Userinfo.fmtBytes(u.total)).append(" · ");
                    if (preferUsed) {
                        d.append("剩余 ").append(Userinfo.fmtBytes(u.remaining()));
                    } else {
                        d.append("已用 ").append(Userinfo.fmtBytes(u.used()))
                                .append("（").append(u.usedPercent()).append("%）");
                    }
                } else {
                    d.append(preferUsed ? "不限量" : ("已用 " + Userinfo.fmtBytes(u.used())));
                }
                d.append("\n↑").append(Userinfo.fmtBytes(u.upload))
                        .append("  ↓").append(Userinfo.fmtBytes(u.download));
                d.append("\n").append(u.summaryExpire());
                detail.setText(d.toString());

                if (u.unlimited()) {
                    usageRow.setVisibility(View.INVISIBLE);
                } else {
                    usageRow.setVisibility(View.VISIBLE);
                    usageBar.setProgress(u.usedPercent());
                    percent.setText(u.usedPercent() + "%");
                }
            } else {
                remain.setText("—");
                detail.setText("还没有取到流量信息\n—\n—");
                usageRow.setVisibility(View.INVISIBLE);
            }

            if (s.loading) {
                status.setText(s.lastQuery > 0L
                        ? "正在刷新 · 上次 " + fmtTime(s.lastQuery)
                        : "正在刷新");
            } else if (u != null) {
                status.setText(TextUtils.isEmpty(s.error)
                        ? (fmtTime(s.lastQuery) + " · " + shortMethod(s))
                        : s.error);
            } else {
                status.setText(TextUtils.isEmpty(s.error) ? "未锁定可用方式 · 点详情重新验证" : s.error);
            }
            return v;
        }
    }

    private String shortMethod(Sub s) {
        if (TextUtils.isEmpty(s.method)) {
            return "未锁定方式";
        }
        Strategies.Attempt a = Strategies.byKey(s.method, prefs.getString(K_UA, Net.DEFAULT_UA));
        return (a != null) ? a.label : s.method;
    }

    private static String fmtTime(long ms) {
        if (ms <= 0L) {
            return "—";
        }
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }
}