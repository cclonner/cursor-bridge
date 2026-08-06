package com.q.cursorbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaRecorder;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends Activity {

    private WebView web;
    private LinearLayout splashBox;
    private TextView splashText;
    private TextView pairStatus;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String loadedUrl = null;
    private String pendingSession = null;
    private BroadcastReceiver stateReceiver;
    private AlertDialog pcDialog;

    private SharedPreferences prefs;
    private BridgeStore store;

    // последнее состояние от сервиса: найденные в сети мосты, подключение, путь
    private JSONArray lastDiscovered = new JSONArray();
    private boolean lastConnected = false;
    private String lastPath = "none"; // lan | inet-direct | inet-relay | none
    private String lastPathAddr = null; // фактический адрес пира при туннеле

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE);
        store = new BridgeStore(this);

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        armScreenWatcher(getApplicationContext());
        startForegroundService(new Intent(this, BridgeService.class));
        registerStateReceiver();

        applySecureFlag();

        if (needLock()) {
            showLockScreen();
        } else {
            buildUi();
        }
    }

    /** При включённой биометрии прячем содержимое из «недавних» и от скриншотов. */
    private void applySecureFlag() {
        if (prefs.getBoolean("biolock", false)) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void buildUi() {
        if (store.active() == null) {
            buildPairUi();
        } else {
            buildMainUi();
        }
    }

    // ==================================================== защита по биометрии

    private static final long LOCK_GRACE_MS = 60_000; // фора после ухода из приложения
    private static long lastUnlock = 0; // static: переживает recreate()
    // Экран гас после последней разблокировки → телефон могли взять в руки,
    // требуем биометрию снова, не дожидаясь конца форы. Значение сбрасывает в
    // true сервис по ACTION_SCREEN_OFF; по умолчанию true — свежий процесс
    // всегда запрашивает вход. Закрывает «дыру»: разблокировал → выключил экран
    // → кто-то взял телефон и в течение форы открыл приложение без биометрии.
    static volatile boolean screenOffSinceUnlock = true;
    private boolean lockShown = false;

    // В режиме «только при открытом приложении» сервис в фоне остановлен и его
    // приёмник ACTION_SCREEN_OFF молчит. Дублируем слежение на контексте
    // процесса (регистрируется один раз, живёт до смерти процесса): гашение
    // экрана снова требует биометрию независимо от судьбы сервиса.
    private static boolean screenWatcherArmed = false;

    private static synchronized void armScreenWatcher(Context app) {
        if (screenWatcherArmed) return;
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                    screenOffSinceUnlock = true;
                }
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        screenWatcherArmed = true;
    }

    /** Нужно ли требовать биометрию/PIN прямо сейчас. */
    private boolean needLock() {
        if (prefs == null || !prefs.getBoolean("biolock", false)) return false;
        return screenOffSinceUnlock
                || System.currentTimeMillis() - lastUnlock > LOCK_GRACE_MS;
    }

    private void showLockScreen() {
        lockShown = true;
        // старые view недействительны, пока экран заблокирован
        web = null;
        splashBox = null;
        splashText = null;
        loadedUrl = null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#0f172a"));
        TextView icon = new TextView(this);
        icon.setText("🔒");
        icon.setTextSize(52);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);
        TextView t = themedText("Cursor Bridge защищён", C_TEXT, 18);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(20));
        box.addView(t);
        Button unlock = themedBtn("Разблокировать", C_BLUE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(220), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        unlock.setLayoutParams(lp);
        unlock.setOnClickListener(v -> doBiometric());
        box.addView(unlock);
        setContentView(box);

        doBiometric();
    }

    private void unlockNow() {
        lastUnlock = System.currentTimeMillis();
        screenOffSinceUnlock = false; // разблокировали — новый отсчёт до гашения экрана
        lockShown = false;
        buildUi();
    }

    private void doBiometric() {
        if (Build.VERSION.SDK_INT >= 28) {
            android.hardware.biometrics.BiometricPrompt.Builder bb =
                    new android.hardware.biometrics.BiometricPrompt.Builder(this)
                            .setTitle("Cursor Bridge")
                            .setSubtitle("Подтвердите, что это вы");
            if (Build.VERSION.SDK_INT >= 30) {
                bb.setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                                | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                bb.setDeviceCredentialAllowed(true);
            }
            bb.build().authenticate(new android.os.CancellationSignal(), getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                            unlockNow();
                        }
                    });
        } else {
            // Android 8/9 без BiometricPrompt: системное подтверждение PIN/графического ключа
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            Intent i = km == null ? null
                    : km.createConfirmDeviceCredentialIntent("Cursor Bridge", "Подтвердите, что это вы");
            if (i != null) startActivityForResult(i, 42);
            else unlockNow(); // блокировка экрана не настроена — не запираем насмерть
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42 && resultCode == RESULT_OK) unlockNow();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            startForegroundService(new Intent(this, BridgeService.class)
                    .setAction(BridgeService.ACTION_VISIBILITY).putExtra("visible", true));
        } catch (Exception ignored) { }
        // вернулись после гашения экрана или долгого отсутствия — запираем снова
        if (!lockShown && needLock()) {
            showLockScreen();
        }
    }

    @Override
    protected void onStop() {
        // фора отсчитывается с момента ухода из приложения
        if (!lockShown) lastUnlock = System.currentTimeMillis();
        try {
            startForegroundService(new Intent(this, BridgeService.class)
                    .setAction(BridgeService.ACTION_VISIBILITY).putExtra("visible", false));
        } catch (Exception ignored) { }
        super.onStop();
    }

    // ================================================== сопряжение (1-й запуск)

    private void buildPairUi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#0f172a"));
        box.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Сопряжение с Cursor Bridge");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("\nЗапустите мост на компьютере\n(npm start в папке bridge)\nи отсканируйте QR-код из терминала.\n");
        hint.setTextColor(Color.parseColor("#94a3b8"));
        hint.setTextSize(15);
        hint.setGravity(Gravity.CENTER);
        box.addView(hint);

        Button scan = new Button(this);
        scan.setText("Сканировать QR-код");
        scan.setBackgroundColor(Color.parseColor("#16a34a"));
        scan.setTextColor(Color.WHITE);
        scan.setOnClickListener(v -> startScan());
        box.addView(scan);

        TextView or = new TextView(this);
        or.setText("\nили введите код с экрана ПК:\n");
        or.setTextColor(Color.parseColor("#94a3b8"));
        or.setGravity(Gravity.CENTER);
        box.addView(or);

        EditText code = new EditText(this);
        code.setHint("например: A1B2C3");
        code.setHintTextColor(Color.parseColor("#475569"));
        code.setTextColor(Color.WHITE);
        code.setGravity(Gravity.CENTER);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(code);

        Button manual = new Button(this);
        manual.setText("Подключиться по коду");
        manual.setBackgroundColor(Color.parseColor("#2563eb"));
        manual.setTextColor(Color.WHITE);
        manual.setOnClickListener(v -> pairByCode(code.getText().toString().trim()));
        box.addView(manual);

        pairStatus = new TextView(this);
        pairStatus.setTextColor(Color.parseColor("#fbbf24"));
        pairStatus.setTextSize(14);
        pairStatus.setGravity(Gravity.CENTER);
        pairStatus.setPadding(0, pad / 2, 0, 0);
        box.addView(pairStatus);

        setContentView(box);
        updatePairDiscoveryStatus();
    }

    private void updatePairDiscoveryStatus() {
        if (pairStatus == null) return;
        String host = prefs.getString("host", null);
        if (host != null && pairStatus.getText().length() == 0) {
            pairStatus.setText("Мост найден: " + host);
        }
    }

    private void setPairStatus(String s) {
        runOnUiThread(() -> { if (pairStatus != null) pairStatus.setText(s); });
    }

    private void startScan() {
        GmsBarcodeScannerOptions opts = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, opts);
        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    try {
                        JSONObject j = new JSONObject(barcode.getRawValue());
                        if (j.optInt("crb") != 1) throw new Exception("не тот QR");
                        pair(j.getString("host"), j.optInt("port", 8790),
                                j.getString("code"), j.optString("fp", null),
                                j.optString("ticket", null));
                    } catch (Exception e) {
                        setPairStatus("Это не QR-код Cursor Bridge");
                    }
                })
                .addOnFailureListener(e -> setPairStatus("Сканер недоступен: " + e.getMessage()));
    }

    /** Обмен кода сопряжения на постоянный токен + пиннинг сертификата. */
    private void pair(String host, int port, String code, String expectedFp) {
        pair(host, port, code, expectedFp, null);
    }

    private void pair(String host, int port, String code, String expectedFp, String ticket) {
        setPairStatus("Сопряжение…");
        new Thread(() -> {
            Exception lanErr = null;
            try {
                doPairHttp(host, port, code, expectedFp, host);
                return;
            } catch (Exception e) {
                lanErr = e;
            }
            // LTE / другая сеть: LAN 192.168.x недоступен — pair через iroh ticket из QR
            if (ticket != null && !ticket.isEmpty() && expectedFp != null) {
                try {
                    setPairStatus("LAN недоступен — поднимаю iroh (LTE)…");
                    pairOverIroh(ticket, code, expectedFp, host, port);
                    return;
                } catch (Exception e) {
                    setPairStatus("LTE/iroh: " + e.getMessage()
                            + (lanErr != null ? "\n(LAN: " + lanErr.getMessage() + ")" : ""));
                    return;
                }
            }
            setPairStatus("Не удалось: " + (lanErr != null ? lanErr.getMessage() : "нет ticket в QR")
                    + "\nНа LTE отсканируйте свежий QR с ПК (нужен iroh).");
        }, "pair").start();
    }

    private void doPairHttp(String host, int port, String code, String expectedFp, String storeHost)
            throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        OkHttpClient client = expectedFp != null
                ? Tls.buildClient(expectedFp).newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                : Tls.capturingClient(seen).newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
        JSONObject body = new JSONObject()
                .put("code", code)
                .put("name", Build.MANUFACTURER + " " + Build.MODEL);
        Request req = new Request.Builder()
                .url("https://" + host + ":" + port + "/pair")
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.code() == 403) throw new Exception("Неверный код. Проверьте код в терминале ПК.");
            if (!resp.isSuccessful()) throw new Exception("Ошибка моста: HTTP " + resp.code());
            JSONObject j = new JSONObject(resp.body().string());
            String realFp = expectedFp != null ? expectedFp : seen.get();
            if (realFp == null || realFp.isEmpty()) {
                throw new Exception("Не удалось получить сертификат моста");
            }
            BridgeStore.Bridge b = new BridgeStore.Bridge();
            b.token = j.getString("token");
            b.fp = realFp;
            b.host = storeHost != null ? storeHost : host;
            b.port = j.optInt("port", port);
            b.name = j.optString("hostname", b.host);
            String tk = j.optString("nodeTicket", "");
            if (!tk.isEmpty()) b.nodeTicket = tk;
            b.internet = j.optBoolean("internet", false) || (b.nodeTicket != null);
            b.endpoints = BridgeStore.parseEndpoints(j.optJSONArray("endpoints"));
            if (expectedFp != null) {
                commitPairing(b);
            } else {
                runOnUiThread(() -> confirmFingerprint(b));
            }
        }
    }

    /** Pair через iroh: ticket из QR → local tunnel → POST /pair на 127.0.0.1. */
    private void pairOverIroh(String ticket, String code, String expectedFp,
                              String lanHost, int lanPort) throws Exception {
        java.io.File bin = new java.io.File(getApplicationInfo().nativeLibraryDir, "libcursortunnel.so");
        if (!bin.exists()) throw new Exception("в APK нет libcursortunnel.so");
        java.io.File key = new java.io.File(getFilesDir(), "phone-iroh.key");
        Process proc = new ProcessBuilder(
                bin.getAbsolutePath(),
                "connect",
                "--ticket=" + ticket,
                "--listen", "127.0.0.1:0",
                "--secret-file", key.getAbsolutePath(),
                "--online-mode", "bg",
                "--online-timeout-secs", "45",
                "--dial-timeout-secs", "60",
                "--warmup", "true",
                "--addr-mode", "relay")
                .redirectErrorStream(true)
                .start();
        final java.util.concurrent.BlockingQueue<String> lines =
                new java.util.concurrent.LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) lines.offer(line);
            } catch (Exception ignored) {}
        }, "pair-iroh-io");
        reader.setDaemon(true);
        reader.start();

        int tunPort = 0;
        boolean irohOk = false;
        String lastErr = null;
        long deadline = System.currentTimeMillis() + 90_000;
        try {
            while (System.currentTimeMillis() < deadline) {
                String line = lines.poll(300, TimeUnit.MILLISECONDS);
                if (line == null) {
                    if (!proc.isAlive() && tunPort == 0) break;
                    continue;
                }
                try {
                    JSONObject j = new JSONObject(line);
                    String ev = j.optString("event");
                    if ("ready".equals(ev)) tunPort = j.optInt("port", 0);
                    else if ("connected".equals(ev)) {
                        irohOk = true;
                        break;
                    } else if ("error".equals(ev)) {
                        lastErr = j.optString("message", "iroh error");
                    } else if ("status".equals(ev) && j.optBoolean("online")) {
                        setPairStatus("iroh relay online — звоню на ПК…");
                    } else if ("dialing".equals(ev)) {
                        setPairStatus("iroh: dial на ПК…");
                    }
                } catch (Exception ignored) {}
            }
            if (!irohOk || tunPort <= 0) {
                throw new Exception(lastErr != null ? lastErr : "iroh dial timeout (relay)");
            }
            setPairStatus("iroh OK — сопряжение через туннель…");
            doPairHttp("127.0.0.1", tunPort, code, expectedFp, lanHost);
        } finally {
            try { proc.destroy(); } catch (Exception ignored) {}
        }
    }

    /** Ручное сопряжение: пользователь сверяет отпечаток с показанным в терминале ПК. */
    private void confirmFingerprint(BridgeStore.Bridge b) {
        String shown = b.fp.length() >= 16 ? b.fp.substring(0, 16) : b.fp;
        new AlertDialog.Builder(this)
                .setTitle("Проверьте отпечаток")
                .setMessage("Отпечаток сертификата этого компьютера:\n\n" + shown + "\n\n"
                        + "Он совпадает с тем, что показан в терминале на ПК\n"
                        + "(строка «Отпечаток сертификата»)?\n\n"
                        + "Если НЕ совпадает — возможен перехват в сети, не подключайтесь.")
                .setPositiveButton("Совпадает", (d, w) -> commitPairing(b))
                .setNegativeButton("Нет", (d, w) ->
                        setPairStatus("Отменено: отпечаток не подтверждён"))
                .setCancelable(false)
                .show();
    }

    private void commitPairing(BridgeStore.Bridge b) {
        store.addOrUpdate(b, true); // новый ПК сразу становится активным
        startForegroundService(new Intent(MainActivity.this, BridgeService.class)
                .setAction(BridgeService.ACTION_RECONNECT));
        runOnUiThread(this::recreate); // перезапуск на основной экран
    }

    // ---------------------------------------------- сопряжение по коду: цель

    /** Мосты, замеченные в сети, с которыми мы ещё не сопряжены. */
    private java.util.List<JSONObject> unpairedDiscovered() {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        for (int i = 0; i < lastDiscovered.length(); i++) {
            JSONObject d = lastDiscovered.optJSONObject(i);
            if (d == null) continue;
            String fp = d.optString("fp", "");
            if (fp.isEmpty() || store.byFp(fp) == null) out.add(d);
        }
        return out;
    }

    /** Сопряжение по коду: цель — новый (несопряжённый) ПК, найденный в сети. */
    private void pairByCode(String code) {
        if (code.isEmpty()) { setPairStatus("Введите код с экрана ПК"); return; }
        java.util.List<JSONObject> cand = unpairedDiscovered();
        if (cand.isEmpty()) {
            // ещё ни одного сопряжения — сервис кладёт адрес найденного моста в prefs
            String host = store.list().isEmpty() ? prefs.getString("host", null) : null;
            if (host == null) {
                setPairStatus("Новый компьютер в сети не найден — подождите пару секунд или используйте QR");
                return;
            }
            pair(host, prefs.getInt("port", 8790), code, null);
            return;
        }
        if (cand.size() == 1) {
            JSONObject d = cand.get(0);
            pair(d.optString("host"), d.optInt("port", 8790), code, null);
            return;
        }
        // несколько новых ПК в сети: уточняем, к какому подключаться
        String[] names = new String[cand.size()];
        for (int i = 0; i < cand.size(); i++) {
            names[i] = cand.get(i).optString("name") + "  (" + cand.get(i).optString("host") + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("К какому компьютеру подключиться?")
                .setItems(names, (dlg, which) -> {
                    JSONObject d = cand.get(which);
                    pair(d.optString("host"), d.optInt("port", 8790), code, null);
                })
                .show();
    }

    // ================================================== настройки компьютеров

    private JSONObject discoveredByFp(String fp) {
        if (fp == null) return null;
        for (int i = 0; i < lastDiscovered.length(); i++) {
            JSONObject d = lastDiscovered.optJSONObject(i);
            if (d != null && fp.equalsIgnoreCase(d.optString("fp"))) return d;
        }
        return null;
    }

    /**
     * Живой адрес ПК для меню: что происходит СЕЙЧАС (текущий IP из сети или
     * путь через интернет), а не адрес, записанный при сопряжении.
     */
    private String liveAddr(BridgeStore.Bridge b, boolean isActive) {
        if (isActive && lastConnected) {
            // фактический адрес: на туннеле сайдкар сообщает реальный адрес пира
            String a = lastPathAddr != null && !lastPathAddr.isEmpty() ? ": " + lastPathAddr : "";
            switch (lastPath) {
                case "inet-direct": return "через интернет, p2p напрямую" + a;
                case "inet-relay": return "через интернет, relay" + a;
                case "inet-": case "inet-mixed": case "inet": return "через интернет" + a;
                default: return "в сети: " + prefs.getString("host", "?");
            }
        }
        JSONObject d = discoveredByFp(b.fp);
        if (d != null) return "в сети: " + d.optString("host");
        return b.host != null ? "не в сети (последний адрес: " + b.host + ")" : "не в сети";
    }

    // --- палитра web-интерфейса: тёмно-синий фон, синие акценты ---
    private static final String C_BG = "#1e293b";
    private static final String C_INPUT = "#0b1120";
    private static final String C_TEXT = "#e2e8f0";
    private static final String C_MUTED = "#94a3b8";
    private static final String C_BLUE = "#2563eb";
    private static final String C_GREEN = "#16a34a";
    private static final String C_RED = "#b91c1c";
    private static final String C_GRAY = "#475569";

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.GradientDrawable rounded(String color, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Color.parseColor(color));
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Button themedBtn(String text, String bg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(bg, 10));
        b.setPadding(dp(16), dp(11), dp(16), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView themedText(String text, String color, int sizeSp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor(color));
        t.setTextSize(sizeSp);
        return t;
    }

    /** Диалог в стиле web-интерфейса: скруглённая тёмно-синяя карточка. */
    private AlertDialog themedDialog(String title, LinearLayout content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(C_BG, 14));
        card.setPadding(dp(18), dp(18), dp(18), dp(14));
        TextView t = themedText(title, C_TEXT, 20);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(10));
        card.addView(t);
        card.addView(content);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(card);
        AlertDialog d = new AlertDialog.Builder(this).setView(sc).create();
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    /** Список сопряжённых ПК: выбор активного, удаление, добавление нового. */
    private void showPcSettings() {
        if (pcDialog != null) { pcDialog.dismiss(); pcDialog = null; }
        android.content.res.ColorStateList blue =
                android.content.res.ColorStateList.valueOf(Color.parseColor(C_BLUE));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView ch = themedText("Компьютеры", C_TEXT, 16);
        ch.setTypeface(null, Typeface.BOLD);
        ch.setPadding(0, 0, 0, dp(2));
        box.addView(ch);

        String pathDesc;
        switch (lastPath) {
            case "lan": pathDesc = "по WiFi (локальная сеть)"; break;
            case "inet-direct": pathDesc = "через интернет: p2p напрямую"; break;
            case "inet-relay": pathDesc = "через интернет: relay-сервер"; break;
            case "inet-": case "inet-mixed": pathDesc = "через интернет"; break;
            default: pathDesc = ""; break;
        }
        TextView state = themedText(lastConnected
                ? "Подключено " + pathDesc
                : "Нет подключения — идёт поиск…", C_MUTED, 13);
        state.setPadding(0, 0, 0, dp(8));
        box.addView(state);

        java.util.List<BridgeStore.Bridge> list = store.list();
        String activeFp = store.activeFp();
        for (BridgeStore.Bridge b : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            boolean isActive = b.fp != null && b.fp.equalsIgnoreCase(activeFp);
            rb.setChecked(isActive);
            rb.setText(b.name + "\n" + liveAddr(b, isActive));
            rb.setTextColor(Color.parseColor(C_TEXT));
            rb.setTextSize(15);
            rb.setButtonTintList(blue);
            rb.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            rb.setOnClickListener(v -> {
                if (!isActive) {
                    store.setActive(b.fp);
                    switchBridge();
                } else {
                    rb.setChecked(true);
                }
            });
            row.addView(rb);

            Button del = new Button(this);
            del.setText("✕");
            del.setAllCaps(false);
            del.setTextColor(Color.WHITE);
            del.setBackground(rounded(C_RED, 8));
            del.setMinWidth(dp(44));
            del.setMinimumWidth(dp(44));
            del.setPadding(dp(10), dp(6), dp(10), dp(6));
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("Удалить «" + b.name + "» из приложения?\n" +
                            "Сопряжение на самом ПК останется (отзыв — cursor-mobile --device).")
                    .setPositiveButton("Удалить", (d, w) -> {
                        boolean wasActive = b.fp != null && b.fp.equalsIgnoreCase(store.activeFp());
                        store.remove(b.fp);
                        if (wasActive) switchBridge();
                        else showPcSettings();
                    })
                    .setNegativeButton("Отмена", null)
                    .show());
            row.addView(del);
            box.addView(row);

            // опциональный доступ через интернет (p2p iroh); LAN всегда в приоритете
            android.widget.CheckBox inet = new android.widget.CheckBox(this);
            inet.setText("разрешить через интернет (p2p)");
            inet.setTextSize(13);
            inet.setTextColor(Color.parseColor(C_MUTED));
            inet.setButtonTintList(blue);
            inet.setChecked(b.internet);
            inet.setPadding(dp(6), 0, 0, dp(4));
            inet.setOnCheckedChangeListener((v, on) -> {
                store.setInternet(b.fp, on);
                // выключили интернет у активного ПК, будучи на туннеле — сменить путь
                if (isActive && lastPath.startsWith("inet")) {
                    startForegroundService(new Intent(this, BridgeService.class)
                            .setAction(BridgeService.ACTION_RECONNECT));
                }
            });
            box.addView(inet);
            if (b.internet && b.nodeTicket == null) {
                box.addView(themedText("Нет p2p-адреса этого ПК: подключитесь к нему по WiFi,\n" +
                        "включив на нём интернет-доступ (cursor-mobile --device → i).", "#f59e0b", 12));
            } else if (b.internet && b.nodeTicket != null) {
                box.addView(themedText("p2p-адрес есть — вне WiFi пойдёт через iroh.", C_GREEN, 12));
            }
            Button repair = themedBtn("Обновить привязку (QR/код)", C_GRAY);
            repair.setOnClickListener(v -> {
                store.setActive(b.fp); // этот ПК станет активным после повторного pair
                pcDialog.dismiss();
                showAddPc();
            });
            box.addView(repair);
        }

        if (list.isEmpty()) {
            box.addView(themedText("Нет сопряжённых компьютеров.", C_MUTED, 14));
        }

        Button add = themedBtn("＋ Добавить компьютер", C_GREEN);
        add.setOnClickListener(v -> { pcDialog.dismiss(); showAddPc(); });
        box.addView(add);

        Button diag = themedBtn("Отправить диагностику на ПК", C_BLUE);
        diag.setOnClickListener(v -> {
            startForegroundService(new Intent(this, BridgeService.class)
                    .setAction(BridgeService.ACTION_SEND_DIAG)
                    .putExtra("reason", "manual_button"));
            android.widget.Toast.makeText(this,
                    "Шлю диагностику на мост… смотри уведомление",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        box.addView(diag);
        box.addView(themedText(
                "Лог на ПК: bridge/logs/phone-diag.jsonl\nи phone-diag-latest.json",
                C_MUTED, 12));

        // ------------------------------------------ фоновая связь (батарея)
        TextView bh = themedText("Фоновая связь", C_TEXT, 16);
        bh.setTypeface(null, Typeface.BOLD);
        bh.setPadding(0, dp(18), 0, dp(2));
        box.addView(bh);

        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        String cur = prefs.getString("bgMode", "always");
        String[][] modes = {
                {"always", "Всегда на связи"},
                {"wifi", "В фоне — только по WiFi"},
                {"app", "Только при открытом приложении"},
        };
        for (String[] m : modes) {
            android.widget.RadioButton r = new android.widget.RadioButton(this);
            // ID нужен ДО addView: RadioGroup запоминает отмеченную кнопку по id
            // в момент добавления, а без id (=-1) «теряет» её и потом не снимает
            // отметку — в группе оказываются две выбранные кнопки сразу
            r.setId(View.generateViewId());
            r.setText(m[1]);
            r.setTextColor(Color.parseColor(C_TEXT));
            r.setTextSize(14);
            r.setButtonTintList(blue);
            r.setChecked(cur.equals(m[0]));
            r.setOnClickListener(v -> {
                prefs.edit().putString("bgMode", m[0]).apply();
                // сервис пересмотрит связь с учётом нового режима
                startForegroundService(new Intent(this, BridgeService.class)
                        .setAction(BridgeService.ACTION_RECONNECT));
            });
            rg.addView(r);
        }
        box.addView(rg);
        box.addView(themedText(
                "В экономных режимах уведомления приходят только пока есть связь.",
                C_MUTED, 12));

        // ------------------------------------------------------ безопасность
        TextView sh = themedText("Безопасность", C_TEXT, 16);
        sh.setTypeface(null, Typeface.BOLD);
        sh.setPadding(0, dp(16), 0, dp(2));
        box.addView(sh);

        android.widget.CheckBox bio = new android.widget.CheckBox(this);
        bio.setText("Вход по биометрии или PIN");
        bio.setTextColor(Color.parseColor(C_TEXT));
        bio.setTextSize(14);
        bio.setButtonTintList(blue);
        bio.setChecked(prefs.getBoolean("biolock", false));
        bio.setOnCheckedChangeListener((v, on) -> {
            if (on) {
                android.app.KeyguardManager km =
                        (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                if (km == null || !km.isDeviceSecure()) {
                    v.setChecked(false);
                    android.widget.Toast.makeText(this,
                            "Сначала настройте блокировку экрана (PIN или биометрию)",
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                lastUnlock = System.currentTimeMillis(); // не запирать сразу после включения
            }
            prefs.edit().putBoolean("biolock", on).apply();
            applySecureFlag(); // сразу применяем защиту экрана
        });
        box.addView(bio);

        Button close = themedBtn("Закрыть", C_GRAY);
        close.setOnClickListener(v -> { if (pcDialog != null) pcDialog.dismiss(); });
        box.addView(close);

        pcDialog = themedDialog("Настройки", box);
    }

    /** Диалог добавления нового ПК: QR или код с экрана. */
    private void showAddPc() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(themedText("На новом ПК выполните cursor-mobile --qr\n" +
                "и отсканируйте QR-код, либо введите код:", C_MUTED, 14));

        Button scan = themedBtn("Сканировать QR-код", C_GREEN);
        scan.setOnClickListener(v -> startScan());
        box.addView(scan);

        java.util.List<JSONObject> cand = unpairedDiscovered();
        StringBuilder sb = new StringBuilder();
        if (cand.isEmpty()) {
            sb.append("Новых компьютеров в сети пока не видно.");
        } else {
            sb.append("Найдено в сети:");
            for (JSONObject d : cand) {
                sb.append("\n• ").append(d.optString("name")).append("  (").append(d.optString("host")).append(")");
            }
        }
        TextView found = themedText(sb.toString(), C_MUTED, 13);
        found.setPadding(0, dp(10), 0, 0);
        box.addView(found);

        EditText code = new EditText(this);
        code.setHint("код, например A1B2C3");
        code.setHintTextColor(Color.parseColor(C_GRAY));
        code.setTextColor(Color.parseColor(C_TEXT));
        android.graphics.drawable.GradientDrawable inputBg = rounded(C_INPUT, 8);
        inputBg.setStroke(dp(1), Color.parseColor(C_GRAY));
        code.setBackground(inputBg);
        code.setPadding(dp(12), dp(10), dp(12), dp(10));
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        code.setLayoutParams(clp);
        box.addView(code);

        Button byCode = themedBtn("Подключиться по коду", C_BLUE);
        byCode.setOnClickListener(v -> pairByCode(code.getText().toString().trim()));
        box.addView(byCode);

        // сюда pair() пишет ход сопряжения («Сопряжение…», ошибки)
        pairStatus = themedText("", "#fbbf24", 13);
        pairStatus.setPadding(0, dp(8), 0, 0);
        box.addView(pairStatus);

        Button close = themedBtn("Закрыть", C_GRAY);
        box.addView(close);

        AlertDialog d = themedDialog("Добавить компьютер", box);
        close.setOnClickListener(v -> { pairStatus = null; d.dismiss(); });
        d.setOnCancelListener(x -> pairStatus = null);
    }

    /** Активный ПК сменился: сервис — переподключиться, интерфейс — перестроиться. */
    private void switchBridge() {
        if (pcDialog != null) { pcDialog.dismiss(); pcDialog = null; }
        startForegroundService(new Intent(this, BridgeService.class)
                .setAction(BridgeService.ACTION_RECONNECT));
        recreate();
    }

    /** true → навигацию перехватили (адрес не наш): в WebView не пускаем,
     *  открываем во внешнем браузере. false → свой локальный origin, грузим. */
    private boolean handleExternalNav(String url) {
        if (url == null) return true; // неизвестно куда — точно не в наш WebView
        int port = LocalGateway.uiPort;
        String origin = "http://127.0.0.1:" + port;
        if (port != 0 && (url.equals(origin) || url.startsWith(origin + "/"))) {
            return false; // свой origin шлюза — грузим в WebView
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
        return true;
    }

    /** Мост между web-интерфейсом и приложением. */
    private class AppBridge {
        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(MainActivity.this::showPcSettings);
        }

        /** Куда странице подключать WebSocket: локальный прокси-порт шлюза. */
        @JavascriptInterface
        public String wsUrl() {
            // Токен НЕ отдаём в JS — его подставляет нативный ws-прокси
            // (LocalGateway). Странице даём лишь nonce запуска: он лишь допускает
            // НАС к проксе (чужое приложение на 127.0.0.1 его не знает), но сам
            // по себе бесполезен — учётные данные не покидают нативную часть.
            return "ws://127.0.0.1:" + LocalGateway.wsPort + "/ws?cbnonce=" + LocalGateway.proxyNonce;
        }

        /** Скопировать выделенный в терминале текст в системный буфер. */
        @JavascriptInterface
        public void copyText(String text) {
            runOnUiThread(() -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text));
                } catch (Exception ignored) { }
            });
        }

        /** Текст из системного буфера (для кнопки «вставить»). */
        @JavascriptInterface
        public String pasteText() {
            // ClipboardManager надёжнее дёргать на главном потоке
            final String[] out = {""};
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            runOnUiThread(() -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                        if (t != null) out[0] = t.toString();
                    }
                } catch (Exception ignored) { }
                done.countDown();
            });
            try { done.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return out[0];
        }
    }

    // ================================================== основной экран

    private void buildMainUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0f172a"));

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        // страница грузится по http://127.0.0.1 — доступ к файловой системе
        // WebView не нужен; закрываем на всякий случай (защита в глубину)
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setAllowFileAccessFromFileURLs(false);
        web.getSettings().setAllowUniversalAccessFromFileURLs(false);
        web.setBackgroundColor(Color.parseColor("#0f172a"));
        web.setWebViewClient(new WebViewClient() {
            // Навигацию пускаем только на наш локальный origin шлюза. Любой другой
            // адрес (случайная ссылка из вывода терминала и т.п.) НЕ грузим в этот
            // WebView, а отдаём внешнему браузеру: иначе недоверенная страница,
            // открывшись здесь, через JS-мост AndroidApp.wsUrl() выманила бы nonce
            // прокси и получила бы доступ к мосту. Стартовая загрузка идёт через
            // loadUrl() и сюда не попадает.
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest req) {
                return handleExternalNav(req.getUrl() != null ? req.getUrl().toString() : null);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleExternalNav(url); // для старых WebView
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                // самоподписанный сертификат моста: доверяем только запиннингованному
                String pinned = prefs.getString("fp", null);
                X509Certificate cert = null;
                if (Build.VERSION.SDK_INT >= 29 && error.getCertificate() != null) {
                    cert = error.getCertificate().getX509Certificate();
                }
                if (pinned != null && cert != null && Tls.fingerprint(cert).equalsIgnoreCase(pinned)) {
                    handler.proceed();
                } else {
                    handler.cancel();
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (validSession(pendingSession)) {
                    // JSONObject.quote экранирует значение из интента — иначе
                    // кавычка в session разрывала строку и исполняла чужой JS
                    String js = "setTimeout(function(){ if (window.attach) attach("
                            + JSONObject.quote(pendingSession) + ", true); }, 400);";
                    web.evaluateJavascript(js, null);
                }
                pendingSession = null;
            }

            @Override
            public void onReceivedError(WebView v, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                // локальная страница почти не может не загрузиться, но на всякий
                // случай: показываем заставку и пробуем снова
                if (req.isForMainFrame()) {
                    loadedUrl = null;
                    if (splashBox != null) splashBox.setVisibility(View.VISIBLE);
                    web.setVisibility(View.GONE);
                    uiHandler.postDelayed(tryLoad, 2000);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView v, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("Да", (d, w) -> result.confirm())
                        .setNegativeButton("Отмена", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });
        // Долгое нажатие: страница сама глушит его над терминалом через
        // preventDefault(touchstart) — там своё выделение; в поле ввода
        // жест доходит до системы, и работает нативное меню
        // «Выделить/Копировать/Вставить». Java-перехват здесь НЕ нужен:
        // OnLongClickListener с return true рвёт жест (touchcancel),
        // из-за чего растягивание выделения пальцем обрывалось.
        web.addJavascriptInterface(new SttBridge(), "AndroidSTT");
        web.addJavascriptInterface(new AppBridge(), "AndroidApp");
        web.setVisibility(View.GONE);
        root.addView(web);

        splashBox = new LinearLayout(this);
        splashBox.setOrientation(LinearLayout.VERTICAL);
        splashBox.setGravity(Gravity.CENTER);
        splashText = new TextView(this);
        BridgeStore.Bridge act = store.active();
        splashText.setText("Ищу «" + (act != null ? act.name : "мост") + "»…");
        splashText.setTextColor(Color.parseColor("#94a3b8"));
        splashText.setTextSize(16);
        splashText.setGravity(Gravity.CENTER);
        int sp = (int) (24 * getResources().getDisplayMetrics().density);
        splashText.setPadding(sp, 0, sp, 0);
        splashBox.addView(splashText);
        // оффлайн-экран — полноценный: настройки и добавление ПК доступны всегда
        Button pcs = new Button(this);
        pcs.setText("🖥 Компьютеры…");
        pcs.setOnClickListener(v -> showPcSettings());
        Button addPc = new Button(this);
        addPc.setText("＋ Добавить компьютер");
        addPc.setOnClickListener(v -> showAddPc());
        for (Button btn : new Button[]{pcs, addPc}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            lp.topMargin = (int) (18 * getResources().getDisplayMetrics().density);
            btn.setLayoutParams(lp);
            splashBox.addView(btn);
        }
        root.addView(splashBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        pendingSession = trustedSession(getIntent()); // только из нашего уведомления (F15)

        // Интерфейс живёт в самом приложении (assets + локальный шлюз) и
        // грузится ВСЕГДА, даже если ПК выключен: страница сама тянется к
        // мосту через ws-прокси и переподключается, когда он появится.
        uiHandler.post(tryLoad);
    }

    /** Ждём готовности локального шлюза (мгновения) и загружаем интерфейс. */
    private final Runnable tryLoad = new Runnable() {
        @Override
        public void run() {
            if (web == null) return; // экран сопряжения
            if (LocalGateway.uiPort != 0) { loadLocal(); return; }
            uiHandler.postDelayed(this, 300);
        }
    };

    private void loadLocal() {
        String url = "http://127.0.0.1:" + LocalGateway.uiPort + "/";
        if (url.equals(loadedUrl)) return;
        loadedUrl = url;
        splashBox.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(url);
    }


    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                lastConnected = i.getBooleanExtra("connected", false);
                lastPath = i.getStringExtra("path") != null ? i.getStringExtra("path") : lastPath;
                lastPathAddr = i.getStringExtra("pathAddr");
                String disc = i.getStringExtra("discovered");
                if (disc != null) {
                    try { lastDiscovered = new JSONArray(disc); } catch (Exception ignored) { }
                }
                if (web == null) updatePairDiscoveryStatus();
            }
        };
        IntentFilter f = new IntentFilter(BridgeService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            // на API<33 нет RECEIVER_NOT_EXPORTED: защищаем signature-правом —
            // отправитель обязан им владеть, а его имеет только наше приложение
            registerReceiver(stateReceiver, f, BridgeService.INTERNAL_PERM, null);
        }
    }

    /** Идентификатор сессии из интента (экспортируемая активность) — только
     *  безопасный набор символов, чтобы чужое приложение не подсовывало мусор. */
    private static boolean validSession(String s) {
        return s != null && s.matches("[A-Za-z0-9_-]{1,64}");
    }

    /**
     * Значение «session» принимаем только из интента, созданного нами
     * (уведомлением): сверяем секрет cbAuth. MainActivity экспортирована, поэтому
     * любое приложение может её запустить с произвольным session; без этой
     * проверки чужое приложение переключало бы нашу сессию (F15).
     */
    private String trustedSession(Intent i) {
        if (i == null) return null;
        String want = BridgeService.notifAuthSecret(this);
        String got = i.getStringExtra("cbAuth");
        if (want == null || got == null) return null;
        if (!java.security.MessageDigest.isEqual(
                want.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                got.getBytes(java.nio.charset.StandardCharsets.UTF_8))) return null;
        return i.getStringExtra("session");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String session = trustedSession(intent);
        if (!validSession(session)) return;
        if (web != null && loadedUrl != null) {
            // экранируем значение из интента (см. onPageFinished)
            web.evaluateJavascript("if (window.attach) attach("
                    + JSONObject.quote(session) + ", true);", null);
        } else {
            pendingSession = session;
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true); // не закрывать, просто свернуть
    }

    // ============================================== голосовой ввод (диктовка)
    // Запись — нативным MediaRecorder, распознавание — whisper на ПК через мост.

    private MediaRecorder recorder;
    private File sttFile;

    private class SttBridge {

        @JavascriptInterface
        public boolean start() {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() ->
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2));
                return false;
            }
            try {
                sttFile = new File(getCacheDir(), "stt.m4a");
                recorder = new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(128000);
                recorder.setOutputFile(sttFile.getAbsolutePath());
                recorder.prepare();
                recorder.start();
                return true;
            } catch (Exception e) {
                releaseRecorder();
                return false;
            }
        }

        @JavascriptInterface
        public void stop() {
            try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
            releaseRecorder();
        }

        @JavascriptInterface
        public void cancel() {
            try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
            releaseRecorder();
            if (sttFile != null) sttFile.delete();
        }

        @JavascriptInterface
        public void transcribe() {
            new Thread(() -> {
                try {
                    String host = prefs.getString("useHost", prefs.getString("host", null));
                    int port = prefs.getInt("usePort", prefs.getInt("port", 8790));
                    String token = prefs.getString("token", null);
                    String fp = prefs.getString("fp", null);
                    if (host == null || token == null || sttFile == null || !sttFile.exists()) {
                        throw new Exception("нет соединения или записи");
                    }
                    byte[] audio = Files.readAllBytes(sttFile.toPath());
                    OkHttpClient c = Tls.buildClient(fp).newBuilder()
                            .readTimeout(180, TimeUnit.SECONDS)
                            .build();
                    // Токен — в заголовке Cookie, не в query-строке URL: последняя
                    // оседает в логах сервера/прокси, а это bearer полного доступа
                    // к мосту (F5). Мост читает bridgeToken и из cookie.
                    Request req = new Request.Builder()
                            .url("https://" + host + ":" + port + "/stt")
                            .header("Cookie", "bridgeToken=" + token)
                            .post(RequestBody.create(audio, MediaType.get("audio/mp4")))
                            .build();
                    try (Response resp = c.newCall(req).execute()) {
                        JSONObject j = new JSONObject(resp.body().string());
                        if (!resp.isSuccessful()) throw new Exception(j.optString("error", "HTTP " + resp.code()));
                        String text = j.optString("text", "");
                        js("window.sttResult(" + JSONObject.quote(text) + ")");
                    }
                } catch (Exception e) {
                    js("window.sttError(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                } finally {
                    if (sttFile != null) sttFile.delete();
                }
            }, "stt-upload").start();
        }

        private void js(String code) {
            runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
        }

        private void releaseRecorder() {
            try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (stateReceiver != null) unregisterReceiver(stateReceiver);
        super.onDestroy();
    }
}
