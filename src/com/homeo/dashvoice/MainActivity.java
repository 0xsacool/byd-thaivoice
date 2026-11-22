package com.homeo.dashvoice;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * Push-to-talk English climate control for BYD DiLink.
 *
 * <p>Backend: BydAcApi, using the normal application permissions.
 *
 * <p>The Vosk speech model is loaded from the app's external files dir so it
 * can be pushed with adb without rebuilding a 60 MB APK:
 * <pre>
 *   adb push vosk-model-small-en-us-0.15 \
 *     /sdcard/Android/data/com.oxsacool.thaivoice/files/model
 * </pre>
 */
public class MainActivity extends Activity implements VoskEngine.Listener {

    private static final String TAG = "DashVoice";
    private static final int REQ_MIC = 1;

    private VoskEngine engine;
    private BydAcApi ac;
    private BydBodyworkApi body;
    private Button talkBtn;
    private TextView heard;
    private TextView presetLabel;
    private TextView status;
    private TextView setupView;
    private TextView stockVoiceStatus;
    private Button stockVoiceManage;
    // Verified on the upstream Di2.1H unit only; other firmware may differ.
    private static final String STOCK_VOICE_PACKAGE = "com.byd.autovoice.aispeech";
    private static final String VOICE_OPTIONS_PREFS = "voice_options";
    private static final String KEY_EXPERIMENTAL_VOICE = "experimental_voice_options";

    /**
     * BYDAUTO permissions we request. All are dangerous-level on this build
     * and grantable at runtime. If the user declines, the corresponding API
     * simply isn't available and the setup checklist will show it as missing.
     */
    private static final String[] BYD_PERMS = {
            "android.permission.BYDAUTO_AC_COMMON",
                                                                };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = new VoskEngine();
        // APIs are (re)acquired after permissions are granted / on resume.
        acquireApis();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);

        // ---- title ----
        TextView title = new TextView(this);
        title.setText("BYD ThaiVoice");
        title.setTextSize(22f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1565C0"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Alpha 1 • หน้าจอไทย | เสียงยังเป็นภาษาอังกฤษ");
        sub.setTextSize(12f);
        sub.setTextColor(Color.parseColor("#666666"));
        root.addView(sub);

        // ---- setup checklist ----
        setupView = new TextView(this);
        setupView.setTextSize(13f);
        setupView.setTypeface(Typeface.MONOSPACE);
        setupView.setPadding(dp(10), dp(10), dp(10), dp(10));
        setupView.setBackground(rounded(0xFFBDBDBD, 0xFFF5F5F5));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(10);
        root.addView(setupView, sp);

        // ---- push to talk ----
        talkBtn = new Button(this);
        talkBtn.setText("แตะเพื่อพูด (EN)");
        talkBtn.setTextSize(20f);
        talkBtn.setTypeface(talkBtn.getTypeface(), Typeface.BOLD);
        talkBtn.setBackgroundColor(Color.parseColor("#1565C0"));
        talkBtn.setTextColor(Color.WHITE);
        talkBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onTalk(); }
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        tp.topMargin = dp(12);
        root.addView(talkBtn, tp);

        // ---- Mic-button hook toggle (starts/stops MicKeyService) ----
        TextView hookHdr = new TextView(this);
        hookHdr.setText("ปุ่มไมค์พวงมาลัย (ทดลอง)");
        hookHdr.setTextSize(12f);
        hookHdr.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams hhp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hhp.topMargin = dp(10);
        root.addView(hookHdr, hhp);

        final Button hookBtn = new Button(this);
        final SharedPreferences prefs = getSharedPreferences(MicKeyService.PREFS, MODE_PRIVATE);
        Feedback.init(this);
        Commands.attach(this);
        final boolean[] hookOn = new boolean[]{ prefs.getBoolean(MicKeyService.KEY_MIC_HOOK,
                MicKeyService.HOOK_DEFAULT) };
        // If the hook was enabled previously, make sure the service is alive.
        // A force-stop (or our own reinstall) kills it, and without this it
        // would stay dead until the next reboot or a manual re-toggle.
        if (hookOn[0]) {
            MicKeyService.ensureRunning(this);
            KeepAliveJobService.schedule(this);
        }
        hookBtn.setText(hookOn[0]
                ? "ปุ่มไมค์: เปิด • แตะเพื่อปิด"
                : "เปิดปุ่มไมค์ (อาจชนกับผู้ช่วย BYD)");
        hookBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hookOn[0] = !hookOn[0];
                prefs.edit().putBoolean(MicKeyService.KEY_MIC_HOOK, hookOn[0]).apply();
                if (hookOn[0]) {
                    MicKeyService.ensureRunning(MainActivity.this);
                    KeepAliveJobService.schedule(MainActivity.this);
                    hookBtn.setText("ปุ่มไมค์: เปิด • แตะเพื่อปิด");
                    toast("เปิดปุ่มไมค์แล้ว");
                } else {
                    MicKeyService.stop(MainActivity.this);
                    KeepAliveJobService.cancel(MainActivity.this);
                    hookBtn.setText("เปิดปุ่มไมค์ (อาจชนกับผู้ช่วย BYD)");
                    toast("ปิดปุ่มไมค์แล้ว");
                }
            }
        });
        LinearLayout.LayoutParams hbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hbp.topMargin = dp(4);
        root.addView(hookBtn, hbp);

        addStockVoiceOptions(root);

        // ---- openable apps (install-dependent, so listed live) ----
        AppLauncher launcher = Commands.launcher();
        if (launcher != null && !launcher.isEmpty()) {
            TextView appHdr = new TextView(this);
            appHdr.setText("แอปที่เปิดด้วยเสียงได้");
            appHdr.setTextSize(12f);
            appHdr.setTextColor(Color.parseColor("#666666"));
            LinearLayout.LayoutParams ahp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ahp.topMargin = dp(10);
            root.addView(appHdr, ahp);

            // Chips, three per row, each tappable so a name can be tried
            // without speaking — useful for checking an alias resolved to the
            // app you expected rather than a namesake.
            LinearLayout row = null;
            int n = 0;
            for (final String name : launcher.names()) {
                if (n % 3 == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    rp.topMargin = dp(4);
                    root.addView(row, rp);
                }
                TextView chip = new TextView(this);
                chip.setText(name + "\n" + launcher.labelFor(name));
                chip.setTextSize(10.5f);
                chip.setPadding(dp(6), dp(5), dp(6), dp(5));
                chip.setTextColor(Color.parseColor("#0D47A1"));
                GradientDrawable cbg = new GradientDrawable();
                cbg.setCornerRadius(dp(6));
                cbg.setColor(Color.parseColor("#E8F0FE"));
                chip.setBackground(cbg);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        append("run: open " + name);
                        dispatch(Commands.matchAll("open " + name));
                    }
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                cp.rightMargin = dp(4);
                row.addView(chip, cp);
                n++;
            }
        }

        // ---- Saved preset ----
        TextView preHdr = new TextView(this);
        preHdr.setText("ตั้งค่าแอร์ที่บันทึก");
        preHdr.setTextSize(12f);
        preHdr.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams php = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        php.topMargin = dp(10);
        root.addView(preHdr, php);

        presetLabel = new TextView(this);
        presetLabel.setTextSize(12f);
        presetLabel.setTextColor(Color.parseColor("#0D47A1"));
        presetLabel.setText("บันทึกแล้ว: " + ClimatePreset.load(this).describe());
        root.addView(presetLabel);

        LinearLayout preRow = new LinearLayout(this);
        preRow.setOrientation(LinearLayout.HORIZONTAL);

        Button savePre = new Button(this);
        savePre.setText("บันทึกค่าปัจจุบัน");
        savePre.setTextSize(11f);
        savePre.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ac == null) { toast("ยังเข้าถึงระบบแอร์ไม่ได้"); return; }
                // Read on a worker thread: each getter is a binder round trip.
                new Thread(new Runnable() {
                    @Override public void run() {
                        final ClimatePreset p = ClimatePreset.capture(ac);
                        if (!p.saved) {
                            postAppend("อ่านสถานะแอร์ไม่สำเร็จ");
                            return;
                        }
                        p.save(MainActivity.this);
                        postPresetLabel("บันทึกแล้ว: " + p.describe());
                        postAppend("บันทึกค่าแอร์แล้ว: " + p.describe());
                    }
                }, "preset-save").start();
            }
        });
        preRow.addView(savePre, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button applyPre = new Button(this);
        applyPre.setText("เรียกใช้");
        applyPre.setTextSize(11f);
        applyPre.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dispatch(Commands.matchAll("my preset"));
            }
        });
        preRow.addView(applyPre, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        addRow(root, preRow);

        // ---- sound check ----
        // The command chips further down cover manual dispatch, so the old
        // duplicate test-button grid is gone. What is still worth testing on
        // its own is audio, because the feedback tones compete with Xiaodi.
        TextView sndHdr = new TextView(this);
        sndHdr.setText("ทดสอบเสียง (EN)");
        sndHdr.setTextSize(12f);
        sndHdr.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shp.topMargin = dp(10);
        root.addView(sndHdr, shp);

        LinearLayout sndRow = new LinearLayout(this);
        sndRow.setOrientation(LinearLayout.HORIZONTAL);
        addSoundBtn(sndRow, "กำลังฟัง", 0);
        addSoundBtn(sndRow, "สำเร็จ", 1);
        addSoundBtn(sndRow, "ไม่เข้าใจ", 2);
        addSoundBtn(sndRow, "รถปฏิเสธ", 3);
        addRow(root, sndRow);

        // ---- heard / status ----
        heard = new TextView(this);
        heard.setTextSize(18f);
        heard.setTextColor(Color.parseColor("#111111"));
        heard.setText("\u2014");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(10);
        root.addView(heard, hp);

        status = new TextView(this);
        status.setTextSize(13f);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextColor(Color.parseColor("#333333"));
        status.setTextIsSelectable(true);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(6);
        root.addView(status, stp);

        // ---- command reference ----
        TextView cmdHdr = new TextView(this);
        cmdHdr.setText("คำสั่งเสียงที่รองรับ (ภาษาอังกฤษ)");
        cmdHdr.setTextSize(14f);
        cmdHdr.setAllCaps(true);
        cmdHdr.setTypeface(cmdHdr.getTypeface(), Typeface.BOLD);
        cmdHdr.setTextColor(Color.parseColor("#1565C0"));
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chp.topMargin = dp(16);
        root.addView(cmdHdr, chp);

        TextView cmdSub = new TextView(this);
        cmdSub.setText("พูดภาษาอังกฤษ หรือแตะตัวอย่างภาษาไทยเพื่อทดสอบคำสั่งโดยไม่ใช้เสียง (ขณะจอดรถ)");
        cmdSub.setTextSize(11f);
        cmdSub.setTextColor(Color.parseColor("#777777"));
        root.addView(cmdSub);

        for (CommandReference.Group g : CommandReference.GROUPS) {
            addCommandGroup(root, g);
        }

        scroll.addView(root);
        setContentView(scroll);

        requestMicIfNeeded();
        loadModelAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSetup();
        refreshStockVoiceStatus();
        // Revive the hook if it should be running but is not. Opening the app
        // also clears Android's stopped flag, which is what blocks the boot
        // broadcast after a force-stop, so this is the reliable recovery path.
        if (getSharedPreferences(MicKeyService.PREFS, MODE_PRIVATE)
                .getBoolean(MicKeyService.KEY_MIC_HOOK, MicKeyService.HOOK_DEFAULT)) {
            MicKeyService.ensureRunning(this);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshSetup();
    }

    /* ---------------- Stock BYD voice assistant options ---------------- */

    private void addStockVoiceOptions(LinearLayout root) {
        TextView header = new TextView(this);
        header.setText("ตั้งค่าผู้ช่วยเสียง BYD");
        header.setTextColor(Color.parseColor("#1565C0"));
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        header.setTextSize(14f);
        root.addView(header);

        final SharedPreferences settings = getSharedPreferences(VOICE_OPTIONS_PREFS, MODE_PRIVATE);
        final android.widget.Switch experimental = new android.widget.Switch(this);
        experimental.setText("แสดงตัวเลือก Freeze / คืนค่า (ทดลอง)");
        experimental.setChecked(settings.getBoolean(KEY_EXPERIMENTAL_VOICE, false));
        root.addView(experimental);

        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel.setVisibility(experimental.isChecked() ? View.VISIBLE : View.GONE);
        root.addView(panel);

        stockVoiceStatus = new TextView(this);
        stockVoiceStatus.setTextSize(13f);
        panel.addView(stockVoiceStatus);

        TextView warning = new TextView(this);
        warning.setText("ทดลองขณะจอดรถเท่านั้น การปิดแอประบบอาจทำให้คำสั่งเสียงเดิมไม่ทำงาน "
                + "หากระบบไม่อนุญาต ให้คงผู้ช่วยเดิมไว้");
        warning.setTextSize(12f);
        panel.addView(warning);

        stockVoiceManage = new Button(this);
        stockVoiceManage.setText("จัดการ Freeze / คืนค่าผ่านระบบ");
        stockVoiceManage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openStockVoiceSettings(); }
        });
        panel.addView(stockVoiceManage);

        experimental.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                settings.edit().putBoolean(KEY_EXPERIMENTAL_VOICE, checked).apply();
                panel.setVisibility(checked ? View.VISIBLE : View.GONE);
                if (checked) refreshStockVoiceStatus();
            }
        });
        refreshStockVoiceStatus();
    }

    private void refreshStockVoiceStatus() {
        if (stockVoiceStatus == null || stockVoiceManage == null) return;
        PackageManager pm = getPackageManager();
        try {
            android.content.pm.ApplicationInfo info =
                    pm.getApplicationInfo(STOCK_VOICE_PACKAGE, 0);
            int state = pm.getApplicationEnabledSetting(STOCK_VOICE_PACKAGE);
            boolean disabled = state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                    || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !info.enabled);
            stockVoiceStatus.setText("สถานะการเปิดใช้งาน: "
                    + (disabled ? "ปิดใช้งาน (ตรวจจากระบบ)" : "เปิดใช้งาน (ตรวจจากระบบ)")
                    + "\n" + STOCK_VOICE_PACKAGE);
            stockVoiceManage.setText(disabled ? "คืนค่าผู้ช่วยเสียงผ่านระบบ"
                    : "จัดการการปิดผู้ช่วยเสียงผ่านระบบ");
            stockVoiceManage.setEnabled(true);
        } catch (PackageManager.NameNotFoundException e) {
            stockVoiceStatus.setText("ไม่พบแพ็กเกจผู้ช่วยเสียงที่ยืนยันแล้วในเฟิร์มแวร์นี้");
            stockVoiceManage.setEnabled(false);
        } catch (RuntimeException e) {
            stockVoiceStatus.setText("ไม่สามารถตรวจสถานะผู้ช่วยเสียงจากระบบได้");
            stockVoiceManage.setEnabled(false);
        }
    }

    private void openStockVoiceSettings() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("จัดการผู้ช่วยเสียงเดิม")
                .setMessage("เปิดหน้าข้อมูลแอปของระบบเพื่อปิดหรือเปิดกลับ หากรถไม่มีปุ่มปิดใช้งาน "
                        + "แสดงว่าเฟิร์มแวร์ไม่รองรับ ไม่ควรบังคับปิด")
                .setNegativeButton("ยกเลิก", null)
                .setPositiveButton("เปิดการตั้งค่า", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            android.content.Intent intent = new android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + STOCK_VOICE_PACKAGE));
                            startActivity(intent);
                        } catch (RuntimeException e) {
                            toast("รถไม่รองรับการเปิดหน้าจัดการแอปนี้");
                        }
                    }
                })
                .show();
    }

    /* ---------------- setup state ---------------- */

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private File modelDir() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        return new File(base, "model");
    }

    private void refreshSetup() {
        boolean mic   = micGranted();
        boolean model = engine.isModelLoaded();
        boolean api   = ac != null;
        boolean bodyOk= body != null;
        File md = modelDir();

        StringBuilder sb = new StringBuilder();
        sb.append(mic     ? "[ok] " : "[--] ").append("สิทธิ์ไมโครโฟน\n");
        sb.append(model   ? "[ok] " : "[--] ").append("โมเดลเสียงพร้อมใช้งาน\n");
        sb.append(api     ? "[ok] " : "[--] ").append("เชื่อมต่อ API แอร์ (ยังไม่ยืนยันสั่งงานจริง)\n");
        sb.append(bodyOk  ? "[ok] " : "[--] ").append("กระจก/ซันรูฟ: ไม่รองรับใน Alpha 1\n");
        if (!model) {
            sb.append("\nตำแหน่งโมเดลเสียงอังกฤษ:\n  ")
              .append(md.getAbsolutePath()).append("\n");
            sb.append("ติดตั้งโมเดลโดย:\n");
            sb.append("  adb push vosk-model-small-en-us-0.15/. \\\n    ")
              .append(md.getAbsolutePath()).append("/");
        }
        if (!api) {
            sb.append("\nอนุญาตสิทธิ์แอร์ผ่านหน้าต่างของระบบ");
            sb.append("\nถ้ารถไม่อนุญาต ระบบจะไม่ส่งคำสั่ง");

        }
        setupView.setText(sb.toString());

        boolean ready = mic && model && api;
        talkBtn.setEnabled(ready);
        talkBtn.setBackgroundColor(ready
                ? Color.parseColor("#1565C0") : Color.parseColor("#9E9E9E"));
    }

    private void requestMicIfNeeded() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (!micGranted()) need.add(Manifest.permission.RECORD_AUDIO);
        for (String p : BYD_PERMS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                need.add(p);
            }
        }
        if (!need.isEmpty()) {
            Log.i(TAG, "requesting " + need.size() + " permissions");
            requestPermissions(need.toArray(new String[0]), REQ_MIC);
        }
    }

    private void acquireApis() {
        // Only try to instantiate if the required permission is granted.
        // Otherwise getInstance() throws a SecurityException that clutters logs.
        if (checkSelfPermission("android.permission.BYDAUTO_AC_COMMON")
                == PackageManager.PERMISSION_GRANTED) {
            ac = BydAcApi.tryCreate(this);
        }
        body = null; // Alpha 1: climate only.
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        Log.i(TAG, "permission result: " + java.util.Arrays.toString(perms));
        acquireApis();
        refreshSetup();
    }

    private void loadModelAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = engine.loadModel(modelDir());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!ok) append("ยังไม่โหลดโมเดลเสียง — ดูรายการด้านบน");
                        refreshSetup();
                    }
                });
            }
        }, "model-load").start();
    }

    /* ---------------- test buttons ---------------- */

    private void addRow(LinearLayout root, LinearLayout row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        root.addView(row, lp);
    }

    private void addTestBtn(LinearLayout row, String label, final String phrase) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12f);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ac == null) { toast("ยังเข้าถึงระบบแอร์ไม่ได้"); return; }
                heard.setText("\u201c" + phrase + "\u201d (manual)");
                append("manual: " + phrase);
                List<Commands.Entry> m = Commands.matchAll(phrase);
                if (m.isEmpty()) { append("ไม่พบคำสั่งที่ตรงกัน"); return; }
                dispatch(m);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(b, lp);
    }

    /* ---------------- talk ---------------- */

    private void onTalk() {
        if (engine.isListening()) { engine.stopListening(); return; }
        status.setText("");
        heard.setText("กำลังฟัง\u2026");
        engine.startListening(this);
    }

    @Override
    public void onStateChange(boolean listening) {
        talkBtn.setText(listening ? "กำลังฟัง\u2026 (แตะเพื่อหยุด)" : "แตะเพื่อพูด (EN)");
        talkBtn.setBackgroundColor(listening
                ? Color.parseColor("#C62828") : Color.parseColor("#1565C0"));
        if (listening) Feedback.listening();
    }

    @Override
    public void onPartial(String text) {
        if (!text.isEmpty()) heard.setText(text + " \u2026");
    }

    @Override
    public void onError(String message) {
        Feedback.unheard();
        append("ข้อผิดพลาด: " + message);
    }

    @Override
    public void onFinal(String text) {
        Log.i(TAG, "onFinal: '" + text + "'");
        if (text == null || text.isEmpty()) {
            Feedback.unheard();
            heard.setText("ไม่พบเสียงที่รู้จำได้");
            append("ไม่พบเสียง ลองพูดใกล้ไมโครโฟน");
            return;
        }
        heard.setText("\u201c" + text + "\u201d");
        append("ได้ยิน: " + text);
        if (ac == null) { Feedback.refused(); append("ยังเข้าถึงระบบแอร์ไม่ได้"); return; }
        List<Commands.Entry> matches = Commands.matchAll(text);
        if (matches.isEmpty()) { Feedback.unheard(); append("ไม่พบคำสั่งที่ตรงกัน"); return; }
        dispatch(matches);
    }

    /** Plays one of the three feedback clips so audibility can be checked. */
    private void addSoundBtn(LinearLayout row, String label, final int which) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11f);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                switch (which) {
                    case 0: Feedback.listeningNow(); break;
                    case 1: Feedback.ack();      break;
                    case 2: Feedback.unheard();  break;
                    default: Feedback.refused(); break;
                }
            }
        });
        row.addView(b, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /**
     * Renders one command group as a card: a title with a status badge, the
     * phrases as tappable chips, and an optional explanatory note.
     *
     * <p>Chips run the phrase through the same dispatch path as speech, so the
     * screen doubles as a manual control surface and a way to prove a command
     * works when recognition is in doubt.
     */
    private void addCommandGroup(LinearLayout root, final CommandReference.Group g) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(Color.parseColor(
                g.status == CommandReference.Status.WORKS ? "#FAFAFA" : "#F5F0E8"));
        bg.setStroke(dp(1), Color.parseColor(
                g.status == CommandReference.Status.WORKS ? "#E0E0E0" : "#D8C9A8"));
        card.setBackground(bg);

        // Title line, with a badge when the group is not usable.
        TextView title = new TextView(this);
        String badge;
        switch (g.status) {
            case BLOCKED: badge = "   \u2014 รถไม่รองรับ"; break;
            case ABSENT:  badge = "   \u2014 ไม่พร้อมใช้งาน";  break;
            default:      badge = "";
        }
        title.setText(g.title + badge);
        title.setTextSize(13f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(Color.parseColor(
                g.status == CommandReference.Status.WORKS ? "#212121" : "#8D6E63"));
        card.addView(title);

        // Phrases as chips, wrapped across rows of three.
        LinearLayout row = null;
        for (int i = 0; i < g.phrases.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(6);
                card.addView(row, rp);
            }
            row.addView(buildChip(g.phrases[i], g.status), chipParams());
        }

        if (g.note != null) {
            TextView note = new TextView(this);
            note.setText(g.note);
            note.setTextSize(10.5f);
            note.setTextColor(Color.parseColor("#8A8A8A"));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.topMargin = dp(6);
            card.addView(note, np);
        }

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(8);
        root.addView(card, cp);
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = dp(6);
        return p;
    }

    /** A tappable phrase chip that runs the phrase through voice dispatch. */
    private View buildChip(final String phrase, CommandReference.Status status) {
        TextView chip = new TextView(this);
        chip.setText(CommandReference.labelFor(phrase) + "\n" + phrase);
        chip.setTextSize(11.5f);
        chip.setPadding(dp(8), dp(7), dp(8), dp(7));
        chip.setMaxLines(4);
        chip.setTextColor(Color.parseColor(
                status == CommandReference.Status.WORKS ? "#0D47A1" : "#9E9E9E"));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor(
                status == CommandReference.Status.WORKS ? "#E8F0FE" : "#EEEEEE"));
        chip.setBackground(bg);

        if (status != CommandReference.Status.WORKS) {
            chip.setEnabled(false);
        } else {
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    heard.setText("\u201c" + phrase + "\u201d  (tapped)");
                    append("เรียกใช้: " + phrase);
                    dispatch(Commands.matchAll(phrase));
                }
            });
        }
        return chip;
    }

    /** Run matched commands on a worker thread; UI updates posted back. */
    private void dispatch(final List<Commands.Entry> matches) {
        if (matches == null || matches.isEmpty()) {
            Feedback.unheard();
            return;
        }
        final BydAcApi acRef = ac;
        final BydBodyworkApi bodyRef = body;
        new Thread(new Runnable() {
            @Override public void run() {
                for (Commands.Entry c : matches) {
                    Commands.Result r = Commands.execute(acRef, bodyRef, c);
                    Feedback.forResult(r);
                    postAppend((r.success ? "  สำเร็จ " : "  ไม่สำเร็จ ")
                            + c.phrase + " -> " + r.message);
                    sleep(350);   // MCU throttles rapid writes
                }
            }
        }, "dispatch").start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /* ---------------- helpers ---------------- */

    private void append(String s) {
        Log.i(TAG, s);
        status.append(s + "\n");
    }

    private void postAppend(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { append(s); } });
    }

    /** Update the preset summary from a worker thread. */
    private void postPresetLabel(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (presetLabel != null) presetLabel.setText(text);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable rounded(int stroke, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(6));
        return g;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        if (engine != null) engine.release();
        super.onDestroy();
    }
}
