package com.meshvoice;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private EditText groupEdit;
    private Button startBtn;
    private Button stopBtn;
    private TextView statusView;
    private TextView logView;
    private boolean receiverOn;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MeshService.BC_LOG.equals(intent.getAction())) {
                appendLog(intent.getStringExtra(MeshService.EXTRA_LINE));
            } else if (MeshService.BC_STATUS.equals(intent.getAction())) {
                int peers = intent.getIntExtra(MeshService.EXTRA_PEERS, 0);
                int sid = intent.getIntExtra(MeshService.EXTRA_SID, 0);
                boolean codec = intent.getBooleanExtra(MeshService.EXTRA_CODEC, false);
                statusView.setText("本机 sid=" + sid + " · 已连接 " + peers + " 人 · 编码 "
                        + (codec ? "Opus 24kbps" : "不可用"));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNeededPermissions();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFF7F7F9);

        TextView title = new TextView(this);
        title.setText("MeshVoice 全互联对讲");
        title.setTextSize(22);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("3–5 台安卓设备，同一组名即可自动全互联。\n"
                + "请先把蓝牙耳机连到手机，语音从耳机麦克风进出。");
        hint.setTextSize(13);
        hint.setPadding(0, dp(6), 0, dp(12));
        root.addView(hint);

        groupEdit = new EditText(this);
        groupEdit.setHint("组名（相同即同一组）");
        groupEdit.setText("mesh");
        groupEdit.setSingleLine(true);
        root.addView(groupEdit);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, dp(8));

        startBtn = new Button(this);
        startBtn.setText("开始对讲");
        startBtn.setOnClickListener(v -> startMesh());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(startBtn, lp);

        stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setEnabled(false);
        stopBtn.setOnClickListener(v -> stopMesh());
        buttons.addView(stopBtn, lp);
        root.addView(buttons);

        statusView = new TextView(this);
        statusView.setText("未启动");
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setBackgroundColor(0xFF111111);
        logView.setTextColor(0xFFD0FFD0);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, slp);

        setContentView(root);
    }

    private void startMesh() {
        if (!hasPermissions()) {
            requestNeededPermissions();
            return;
        }
        String group = groupEdit.getText().toString().trim();
        if (group.isEmpty()) group = "mesh";
        Intent i = new Intent(this, MeshService.class)
                .setAction(MeshService.ACTION_START)
                .putExtra(MeshService.EXTRA_GROUP, group);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        statusView.setText("启动中…");
        appendLog("请求启动，组=\"" + group + "\"");
    }

    private void stopMesh() {
        Intent i = new Intent(this, MeshService.class).setAction(MeshService.ACTION_STOP);
        startService(i);
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        statusView.setText("已停止");
    }

    private String[] neededPermissions() {
        List<String> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN);
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        list.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return list.toArray(new String[0]);
    }

    private boolean hasPermissions() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void requestNeededPermissions() {
        List<String> missing = new ArrayList<>();
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 7);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 7) return;
        boolean all = true;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) all = false;
        }
        if (!all) {
            Toast.makeText(this, "缺少蓝牙/麦克风权限，无法对讲", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(MeshService.BC_LOG);
        f.addAction(MeshService.BC_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        receiverOn = true;
    }

    @Override
    protected void onStop() {
        if (receiverOn) {
            try {
                unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            receiverOn = false;
        }
        super.onStop();
    }

    private void appendLog(String line) {
        if (line == null || logView == null) return;
        CharSequence old = logView.getText();
        String text = old == null ? "" : old.toString();
        text = text + line + "\n";
        if (text.length() > 24000) {
            text = text.substring(text.length() - 20000);
        }
        logView.setText(text);
        if (logView.getLayout() != null && logView.getLineCount() > 0) {
            int scroll = logView.getLayout().getLineBottom(logView.getLineCount() - 1)
                    - logView.getHeight();
            if (scroll > 0) logView.scrollTo(0, scroll);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && statusView != null && "未启动".equals(statusView.getText().toString())) {
            // no-op, keeps the status text stable
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        View v = findViewById(android.R.id.content);
        if (v != null) v.setKeepScreenOn(false);
    }
}
