package org.dpdns.sqxy090123.kit.vulnkit;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 主界面终端。普通 shell 走 Process 管道，root shell 走 JNI 管道。
 * 所有读取都在后台线程，UI 线程只负责写入与显示。
 */
public class TerminalView extends LinearLayout {
    private static final int ROOT_POLL_INTERVAL_MS = 50;

    private TextView outputView;
    private ScrollView scrollView;
    private EditText inputEdit;
    private Process shellProcess;
    private DataOutputStream shellOutput;

    private volatile boolean usingRootShell = false;
    private volatile boolean rootReaderRunning = false;
    private volatile boolean shutDown = false;

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        setBackgroundColor(Color.BLACK);

        scrollView = new ScrollView(getContext());
        scrollView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(scrollView);

        outputView = new TextView(getContext());
        outputView.setTextColor(Color.GREEN);
        outputView.setTextSize(12);
        outputView.setPadding(16, 16, 16, 16);
        outputView.setTextIsSelectable(true);
        scrollView.addView(outputView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout inputRow = new LinearLayout(getContext());
        inputRow.setOrientation(HORIZONTAL);
        inputRow.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(inputRow);

        inputEdit = new EditText(getContext());
        inputEdit.setTextColor(Color.GREEN);
        inputEdit.setBackgroundColor(Color.BLACK);
        inputEdit.setHint("$ ");
        inputEdit.setHintTextColor(Color.DKGRAY);
        inputEdit.setInputType(EditorInfo.TYPE_CLASS_TEXT);
        inputEdit.setSingleLine(true);
        inputEdit.setImeOptions(EditorInfo.IME_ACTION_SEND);
        inputEdit.setOnEditorActionListener((v, actionId, event) -> {
            boolean imeAction = actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO;
            boolean enterKey = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (imeAction || enterKey) {
                submitInput();
                return true;
            }
            return false;
        });
        inputRow.addView(inputEdit, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        Button runButton = new Button(getContext());
        runButton.setText("运行");
        runButton.setTextColor(Color.GREEN);
        runButton.setOnClickListener(v -> submitInput());
        inputRow.addView(runButton, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    private void submitInput() {
        String cmd = inputEdit.getText().toString().trim();
        inputEdit.setText("");
        if (!cmd.isEmpty()) {
            executeCommand(cmd);
        }
    }

    public void startShell() {
        if (shutDown) return;
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh");
            builder.redirectErrorStream(true);
            shellProcess = builder.start();
            shellOutput = new DataOutputStream(shellProcess.getOutputStream());
            final Process proc = shellProcess;
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(shellProcess.getInputStream(), StandardCharsets.UTF_8));
            final long startTime = System.currentTimeMillis();

            new Thread(() -> {
                try {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = reader.read(buf)) > 0) {
                        append(new String(buf, 0, n));
                    }
                } catch (IOException ignored) {
                    // shell 被关闭时正常出现
                } finally {
                    if (!shutDown && shellProcess == proc) {
                        append("\n[shell 已退出]\n");
                        usingRootShell = false;
                        if (System.currentTimeMillis() - startTime > 1000) {
                            startShell();
                        }
                    }
                }
            }).start();

            append("VulnKit 终端已就绪，输入 help 查看内置命令。\n");
        } catch (IOException e) {
            append("启动 shell 失败: " + e.getMessage() + "\n");
        }
    }

    private void executeCommand(String cmd) {
        if (cmd.equals("clear")) {
            outputView.setText("");
            return;
        }

        append((usingRootShell ? "# " : "$ ") + cmd + "\n");

        if (cmd.equals("help")) {
            append("内置命令:\n"
                    + "  su              切换到 root shell（需已成功提权）\n"
                    + "  runin root      同 su\n"
                    + "  exituser        退出 root shell，回到普通 shell\n"
                    + "  clear           清屏\n"
                    + "其余输入直接交给当前 shell 执行。\n");
            return;
        }
        if (cmd.equals("su")) {
            if (usingRootShell) {
                append("已经是 root shell\n");
            } else if (JNIInterface.isRootShellAvailable()) {
                switchToRootShell();
            } else {
                append("root shell 不可用：请先在漏洞列表中成功执行一个提权漏洞\n");
            }
            return;
        }
        if (cmd.startsWith("runin ")) {
            String target = cmd.substring(6).trim();
            if (target.equals("root")) {
                if (usingRootShell) {
                    append("已经是 root shell\n");
                } else {
                    switchToRootShell();
                }
            } else if (target.equals("app") || target.equals("shell") || target.equals("system")) {
                append("切换到 " + target + " 的功能正在开发中\n");
            } else {
                append("用法: runin app|shell|system|root\n");
            }
            return;
        }
        if (cmd.equals("exituser")) {
            if (!usingRootShell) {
                append("当前不是 root shell，无需退出\n");
                return;
            }
            rootReaderRunning = false;
            usingRootShell = false;
            JNIInterface.closeRootShell();
            inputEdit.setHint("$ ");
            startShell();
            return;
        }

        if (usingRootShell) {
            // 输出由 root shell 读取线程负责，这里只写命令
            if (JNIInterface.writeRootShell(cmd) < 0) {
                append("写入 root shell 失败\n");
            }
        } else {
            if (shellOutput == null) {
                append("shell 未启动\n");
                return;
            }
            try {
                shellOutput.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                shellOutput.flush();
            } catch (IOException e) {
                append("命令执行失败: " + e.getMessage() + "\n");
            }
        }
    }

    private void switchToRootShell() {
        int ret = JNIInterface.startRootShell();
        if (ret != 0) {
            if (ret == -2) {
                append("当前进程不是 root（uid 未变成 0），无法启动 root shell\n");
            } else {
                append("root shell 启动失败，错误码 " + ret + "\n");
            }
            return;
        }

        if (shellProcess != null) {
            shellProcess.destroy();
            shellProcess = null;
            shellOutput = null;
        }
        usingRootShell = true;
        inputEdit.setHint("# ");
        append("已切换到 root shell\n");
        startRootReader();
        JNIInterface.writeRootShell("id");
    }

    private void startRootReader() {
        if (rootReaderRunning) return;
        rootReaderRunning = true;
        new Thread(() -> {
            while (rootReaderRunning) {
                if (!JNIInterface.isRootShellAvailable()) {
                    // usingRootShell 已置 false 说明是 exituser 主动关闭，不用报错重建
                    if (usingRootShell) {
                        append("\n[root shell 已退出]\n");
                        usingRootShell = false;
                        inputEdit.post(() -> inputEdit.setHint("$ "));
                        startShell();
                    }
                    break;
                }
                byte[] data = JNIInterface.readRootShellBytes();
                if (data != null && data.length > 0) {
                    append(new String(data, StandardCharsets.UTF_8));
                } else {
                    try {
                        Thread.sleep(ROOT_POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            rootReaderRunning = false;
        }).start();
    }

    /** Activity 回到前台时调用：漏洞提权成功后自动接上 root shell */
    public void syncRootShell() {
        if (!shutDown && !usingRootShell && JNIInterface.isRootShellAvailable()) {
            switchToRootShell();
        }
    }

    /** Activity 销毁时调用，释放普通 shell 进程（native root shell 保留给下次 attach） */
    public void shutdown() {
        shutDown = true;
        rootReaderRunning = false;
        if (shellProcess != null) {
            shellProcess.destroy();
            shellProcess = null;
        }
        shellOutput = null;
    }

    private void append(final String text) {
        if (shutDown) return;
        post(() -> {
            if (shutDown) return;
            outputView.append(text);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }
}
