package com.q.cursorbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Foreground-сервис: держит WebSocket с мостом на ПК, показывает системные
 * уведомления о событиях Cursor Agent, сам находит мост через mDNS (NSD).
 */
public class BridgeService extends Service {

    private static final String TAG = "BridgeService";
    public static final String SERVICE_TYPE = "_cursor-bridge._tcp.";
    public static final String ACTION_STATE = "com.q.cursorbridge.STATE";
    public static final String ACTION_INPUT = "com.q.cursorbridge.INPUT";
    public static final String ACTION_RECONNECT = "com.q.cursorbridge.RECONNECT";
    public static final String ACTION_VISIBILITY = "com.q.cursorbridge.VISIBILITY";
    public static final String ACTION_SEND_DIAG = "com.q.cursorbridge.SEND_DIAG";
    public static final String INTERNAL_PERM = "com.q.cursorbridge.permission.INTERNAL";
    public static final String PREFS = "bridge";
    private static final String K_ONLINE_MODE = "tunOnlineMode";       // skip|bg|wait
    private static final String K_ONLINE_TO = "tunOnlineTimeoutSecs";
    private static final String K_DIAL_TO = "tunDialTimeoutSecs";
    private static final String K_WARMUP = "tunWarmup";
    private static final String K_LAN_FAIL = "lanFailBeforeTunnel";
    private static final String K_PREFER_TUN = "preferTunnel";
    private static final String K_TUN_READY_TO = "tunnelReadyTimeoutSecs";
    private static final String K_ADDR_MODE = "tunAddrMode"; // auto|lan|relay|all
    private static final String APP_VERSION = "0.1.14";

    private static final String CH_STATUS = "bridge_status";
    private static final String CH_EVENTS = "cursor_events";
    private static final int STATUS_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OkHttpClient http;
    private WebSocket ws;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock mcLock;
    private ConnectivityManager.NetworkCallback netCallback;

    private String host = null;
    private int port = 8790;
    private boolean connected = false;
    private boolean connecting = false;
    private boolean resolving = false;
    private boolean discovering = false;
    private boolean probeStarted = false;
    private Thread probeThread;
    // Сервис уничтожен (режим «app» останавливает его при закрытии приложения).
    // Асинхронные колбэки OkHttp/туннеля не должны после этого переподключаться
    // и вешать уведомление-сироту: ws.cancel() в onDestroy порождает onFailure,
    // тот — onDown → postDelayed(connect) уже на мёртвом сервисе.
    private volatile boolean destroyed = false;
    private volatile boolean appVisible = false; // активити на экране
    private String lastStatus = "Поиск моста в WiFi-сети…";
    private int retryMs = 2000;
    private int eventSeq = 100;

    // активные событийные уведомления: сессия -> id уведомления и тип события;
    // нужны, чтобы гасить устаревшие (ответили на ПК, пришло новое событие)
    private final java.util.Map<String, Integer> eventNids = new java.util.HashMap<>();
    private final java.util.Map<String, String> eventKinds = new java.util.HashMap<>();

    private String token = null;
    private String certFp = null;
    private BridgeStore store;

    // --- интернет-туннель (сайдкар cursor-tunnel, p2p iroh) ---
    private Process tunnelProc = null;
    private volatile int tunnelPort = 0;        // >0 — TCP listener на 127.0.0.1:port
    private volatile boolean tunnelIrohReady = false; // iroh dial OK — можно WS
    private volatile long tunnelStartedAt = 0;
    private volatile boolean viaTunnel = false; // текущее WS-соединение идёт через туннель
    private volatile String tunnelPath = null;  // direct | relay | mixed
    private volatile String tunnelAddr = null;  // фактический адрес пира / relay-URL
    private volatile String lastTunnelError = null;
    private volatile String lastWsError = null;
    private volatile long lanLastSeen = 0;      // активный ПК последний раз виделся в LAN
    private volatile long lanGraceUntil = 0;    // до этого момента даём LAN шанс, туннель не поднимаем
    private volatile int lanFailStreak = 0;     // подряд неудачных LAN-попыток → форс iroh
    private final java.util.ArrayDeque<String> diagEvents = new java.util.ArrayDeque<>();
    private volatile long lastDiagSentAt = 0;
    private volatile boolean diagSending = false;
    // Непроверенный LAN-адрес активного ПК из discovery: пробуем подключиться к
    // нему, но в store сохраняем ТОЛЬКО после успешного пиннингового рукопожатия
    // (onOpen). Иначе любой хост в LAN, назвав публичный fp моста, перенаправил
    // бы/оборвал бы связь, перезаписав сохранённый адрес (F4).
    private volatile String candHost = null;
    private volatile int candPort = 0;
    // Менеджер путей: перебор WSS-эндпоинтов моста (v6 → overlay) перед iroh.
    // pathIdx — текущий кандидат из remoteCandidates; viaRemoteKind — чем сейчас
    // подключены (lan|v6|ov), null = туннель.
    private volatile int pathIdx = 0;
    private volatile String viaRemoteKind = null;

    // Мосты, замеченные в сети (по fp): для экрана «добавить компьютер»
    private final java.util.Map<String, JSONObject> discovered =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Экран гаснет → помечаем, что после последней разблокировки был выключен
    // экран: активность потребует биометрию снова. Сервис живёт постоянно,
    // поэтому ловит гашение экрана даже когда активность уничтожена.
    private BroadcastReceiver screenReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();

        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                    MainActivity.screenOffSinceUnlock = true;
                }
            }
        };
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));

        LocalGateway.start(this); // локальный интерфейс + ws-прокси для WebView
        store = new BridgeStore(this); // мигрирует старые ключи и зеркалит активный ПК
        ensurePhoneNodeId(); // заранее вычисляем свой p2p-NodeId для allowlist
        startPhoneCmdUdp(); // удалённые тактики с ПК по LAN (даже без WSS)
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        host = p.getString("host", null);
        port = p.getInt("port", 8790);
        token = p.getString("token", null);
        certFp = p.getString("fp", null);
        http = Tls.buildClient(certFp);

        lanGraceUntil = System.currentTimeMillis() + 12_000; // сперва ищем ПК в LAN

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mcLock = wifi.createMulticastLock("cursor-bridge-mdns");
        mcLock.setReferenceCounted(false);
        mcLock.acquire();

        watchNetwork();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // не «Поиск моста…», а последний реальный статус: команды вроде
        // «приложение открылось» не должны сбрасывать текст уведомления
        Notification n = statusNotification(lastStatus);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(STATUS_ID, n);
        }

        // видимость приложения — для режимов экономии батареи
        if (intent != null && ACTION_VISIBILITY.equals(intent.getAction())) {
            appVisible = intent.getBooleanExtra("visible", false);
            if (appVisible) {
                retryMs = 2000;
                // сервис мог быть пересоздан в живом процессе (режим «app»):
                // обнаружение ПК должно работать и на этом пути запуска
                startDiscovery();
                if (!probeStarted) { probeStarted = true; startUdpProbe(); }
                handler.post(this::connect);
            } else if ("app".equals(bgMode())) {
                // «только при открытом приложении»: в фоне демон не нужен вовсе.
                // stopSelf(startId) — если уже пришла новая команда (приложение
                // тут же открыли снова), остановка молча отменяется
                stopSelf(startId);
                return START_NOT_STICKY;
            } else if (!allowedNow()) {
                handler.post(this::pauseConnection);
            }
            return stickiness();
        }

        // Быстрый ответ из кнопки уведомления: шлём ввод в PTY, не открывая приложение
        if (intent != null && ACTION_INPUT.equals(intent.getAction())) {
            String sess = intent.getStringExtra("session");
            String data = intent.getStringExtra("data");
            int nid = intent.getIntExtra("nid", -1);
            if (ws != null && connected && sess != null && data != null) {
                try {
                    JSONObject j = new JSONObject();
                    j.put("type", "input");
                    j.put("id", sess);
                    j.put("data", data);
                    ws.send(j.toString());
                } catch (Exception e) {
                    Log.w(TAG, "quick reply failed", e);
                }
            }
            if (nid >= 0) getSystemService(NotificationManager.class).cancel(nid);
            if (sess != null) cancelEventNotif(sess);
            // тап по залежавшемуся уведомлению не должен воскрешать демона
            // в режиме «только при открытом приложении»
            if (!appVisible && "app".equals(bgMode())) stopSelf(startId);
            return stickiness();
        }

        // Сменился активный компьютер (настройки) — переподключаемся к нему
        if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            handler.post(this::forceReconnect);
            return stickiness();
        }

        if (intent != null && ACTION_SEND_DIAG.equals(intent.getAction())) {
            String reason = intent.getStringExtra("reason");
            handler.post(() -> sendDiag(reason != null ? reason : "manual"));
            return stickiness();
        }

        broadcastState(); // свежая активити сразу узнаёт состояние, не ждёт событий
        startDiscovery();
        if (!probeStarted) { probeStarted = true; startUdpProbe(); }
        if (host != null) connect();
        return stickiness();
    }

    // ------------------------------------------------------- udp discovery

    /**
     * Мост найден в сети. По отпечатку сертификата понимаем, какой это из
     * сопряжённых компьютеров: обновляем его адрес; если это активный ПК и мы
     * не подключены — подключаемся. Незнакомые ПК попадают в список
     * «обнаруженные» для экрана добавления.
     */
    private void onBridgeFound(String h, int p, String fp, String name) {
        if (fp != null && fp.isEmpty()) fp = null;
        try {
            JSONObject d = new JSONObject()
                    .put("fp", fp == null ? "" : fp.toLowerCase())
                    .put("host", h).put("port", p)
                    .put("name", name == null || name.isEmpty() ? h : name)
                    .put("at", System.currentTimeMillis());
            discovered.put(fp != null ? fp.toLowerCase() : h, d);
        } catch (Exception ignored) { }

        boolean isActive;
        if (fp != null && store.byFp(fp) != null) {
            // адрес известного моста здесь НЕ сохраняем: discovery не
            // аутентифицирован. Запишем его в store лишь после успешного
            // пиннингового рукопожатия к нему (onOpen) — см. поля candHost (F4).
            isActive = fp.equalsIgnoreCase(store.activeFp());
        } else {
            // мост без fp (старый сервер) считаем активным; незнакомый ПК — нет
            isActive = fp == null;
        }
        if (store.list().isEmpty()) {
            // ещё нет ни одного сопряжения: адрес нужен экрану сопряжения
            // (доверие устанавливается самим сопряжением, там пиннится fp)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("host", h).putInt("port", p).apply();
        }
        broadcastState();
        if (!isActive) return;
        lanLastSeen = System.currentTimeMillis();
        if (connected) {
            // активный ПК появился в LAN, а мы на туннеле — LAN важнее
            if (viaTunnel) handler.post(this::forceReconnect);
            return;
        }
        boolean changed = !h.equals(host) || p != port;
        candHost = h; // непроверенный кандидат — подключимся к нему, но не сохраним, пока пиннинг не пройдёт
        candPort = p;
        host = h;
        port = p;
        handler.post(changed ? this::forceReconnect : this::connect);
    }

    /** Резервное обнаружение: broadcast "CURSOR_BRIDGE?" на порт 8791, мост отвечает JSON'ом. */
    private void startUdpProbe() {
        Thread t = new Thread(() -> {
            byte[] q = "CURSOR_BRIDGE?".getBytes();
            while (!Thread.interrupted()) {
                if (!allowedNow()) { // экономия: в фоне не опрашиваем сеть
                    try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
                    continue;
                }
                try (DatagramSocket sock = new DatagramSocket()) {
                    sock.setBroadcast(true);
                    sock.setSoTimeout(2000);
                    sock.send(new DatagramPacket(q, q.length,
                            InetAddress.getByName("255.255.255.255"), 8791));
                    byte[] buf = new byte[1024];
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    sock.receive(resp); // ждём unicast-ответ моста
                    JSONObject j = new JSONObject(new String(resp.getData(), 0, resp.getLength()));
                    if (j.optBoolean("cursorBridge")) {
                        String h = resp.getAddress().getHostAddress();
                        Log.i(TAG, "UDP: мост найден " + h + ":" + j.optInt("port", 8790));
                        onBridgeFound(h, j.optInt("port", 8790),
                                j.optString("fp", null), j.optString("host", h));
                    }
                } catch (Exception ignored) {
                    // таймаут/нет сети — просто пробуем снова
                }
                // подключены — опрашиваем реже, только чтобы видеть другие ПК
                try { Thread.sleep(connected ? 12000 : 4000); } catch (InterruptedException e) { return; }
            }
        }, "udp-probe");
        t.setDaemon(true);
        t.start();
        probeThread = t;
    }

    // ------------------------------------------------------------ discovery

    private void startDiscovery() {
        if (discovering) return;
        // флаг ставим сразу, не дожидаясь onDiscoveryStarted: повторный вызов
        // (onCreate-путь + сразу ACTION_VISIBILITY) не должен дублировать поиск
        discovering = true;
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { discovering = false; }
            @Override public void onStartDiscoveryFailed(String t, int e) { discovering = false; }
            @Override public void onStopDiscoveryFailed(String t, int e) { discovering = false; }
            @Override public void onServiceLost(NsdServiceInfo info) { }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (resolving || !info.getServiceType().contains("cursor-bridge")) return;
                resolving = true;
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) { resolving = false; }
                    @Override
                    public void onServiceResolved(NsdServiceInfo i) {
                        resolving = false;
                        String h = i.getHost() != null ? i.getHost().getHostAddress() : null;
                        if (h == null) return;
                        Log.i(TAG, "mDNS: мост найден " + h + ":" + i.getPort());
                        String fp = null;
                        try {
                            byte[] f = i.getAttributes().get("fp");
                            if (f != null) fp = new String(f);
                        } catch (Exception ignored) { }
                        onBridgeFound(h, i.getPort(), fp, null);
                    }
                });
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            discovering = false;
            Log.w(TAG, "NSD discover failed", e);
        }
    }

    // ------------------------------------------------------------ websocket

    // ------------------------------------------------------ экономия батареи
    // Режим bgMode в prefs: "always" — на связи всегда; "wifi" — в фоне только
    // по WiFi; "app" — только пока приложение открыто. При открытом приложении
    // связь есть в любом режиме.

    private boolean isOnWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    /** Текущий режим экономии батареи из настроек. */
    private String bgMode() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("bgMode", "always");
    }

    /**
     * В режиме «только при открытом приложении» система не должна воскрешать
     * убитый сервис: START_STICKY возвращал демона в фон через пару секунд
     * после закрытия приложения.
     */
    private int stickiness() {
        return "app".equals(bgMode()) ? START_NOT_STICKY : START_STICKY;
    }

    /** Разрешена ли связь прямо сейчас с учётом режима экономии. */
    private boolean allowedNow() {
        if (appVisible) return true;
        String mode = bgMode();
        if ("wifi".equals(mode)) return isOnWifi();
        if ("app".equals(mode)) return false;
        return true; // "always"
    }

    /** Текст паузы для уведомления: зависит от режима экономии. */
    private String pausedText() {
        return "wifi".equals(bgMode())
                ? "Экономия батареи — в фоне подключусь по WiFi"
                : "Экономия батареи — подключусь, когда откроете приложение";
    }

    /** Разорвать связь ради экономии батареи (возобновится по событию). */
    private synchronized void pauseConnection() {
        if (ws != null) { ws.cancel(); ws = null; }
        connected = false;
        connecting = false;
        viaTunnel = false;
        stopTunnel();
        handler.removeCallbacksAndMessages(null); // остановить цикл переподключения
        updateStatus(pausedText());
        broadcastState();
    }

    /** Разорвать текущее соединение и подключиться заново (например, сменился адрес моста). */
    private synchronized void forceReconnect() {
        if (ws != null) { ws.cancel(); ws = null; }
        connected = false;
        connecting = false;
        viaTunnel = false;
        stopTunnel(); // путь будет выбран заново; LAN в приоритете
        connect();
    }

    /** Идемпотентно: ничего не делает, если уже подключены или подключаемся. */
    private synchronized void connect() {
        if (destroyed) return;
        // перечитываем всё из prefs: активный ПК мог смениться в настройках,
        // учётные данные — появиться после сопряжения
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        token = p.getString("token", null);
        host = p.getString("host", host);
        port = p.getInt("port", port);
        String fp = p.getString("fp", null);
        if (fp != null && !fp.equals(certFp)) {
            certFp = fp;
            http = Tls.buildClient(certFp);
        }
        if (token == null || connected || connecting) return;
        if (!allowedNow()) return; // режим экономии: подключимся по событию

        // Выбор пути: LAN (свежий discovery) → WSS-эндпоинты моста (v6/overlay)
        // → iroh-туннель. На LTE v6/overlay пробуются ДО iroh: это TCP/WSS,
        // он проходит DPI и троттлинг UDP, на который завязан QUIC-туннель.
        long now = System.currentTimeMillis();
        BridgeStore.Bridge act = store.active();
        boolean onWifi = isOnWifi();
        if (!onWifi) {
            lanLastSeen = 0;
            lanGraceUntil = 0;
            candHost = null;
            lanFailStreak = 0; // на LTE сразу интернет-пути, стрик LAN не нужен
        }
        // Discovery может «видеть» ПК по UDP/mDNS, а WSS до него не доходит
        // (firewall / guest WiFi). После N фейлов LAN — форсим интернет-пути (N = cfg).
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int lanFailMax = Math.max(0, prefs.getInt(K_LAN_FAIL, 2));
        boolean preferTun = prefs.getBoolean(K_PREFER_TUN, false);
        boolean lanFresh = onWifi && lanFailStreak < lanFailMax
                && ((now - lanLastSeen < 15_000) || now < lanGraceUntil);
        boolean tunnelAllowed = act != null && act.internet;
        // v6/overlay WSS-кандидаты (порядок: v6 → overlay); preferTun их пропускает
        String[][] remotes = tunnelAllowed && !preferTun
                ? BridgeStore.remoteCandidates(act) : null;

        final String eh;
        final int epn;
        final boolean tun;
        final String rkind;
        if (lanFresh && (candHost != null || host != null)) {
            eh = candHost != null ? candHost : host;
            epn = candHost != null ? candPort : port;
            tun = false;
            rkind = "lan";
            updateStatus("Пробую WiFi " + eh + ":" + epn + "…");
        } else if (remotes != null && remotes.length > 0 && pathIdx < remotes.length) {
            String[] c = remotes[pathIdx];
            eh = c[0];
            epn = Integer.parseInt(c[1]);
            tun = false;
            rkind = c[2];
            updateStatus("Пробую " + ("v6".equals(rkind) ? "IPv6" : "overlay")
                    + " " + eh + ":" + epn + "…");
        } else {
            pathIdx = 0; // эндпоинты исчерпаны — дальше туннель или ожидание LAN
            if (!tunnelAllowed) {
                String lh = candHost != null ? candHost : host;
                int lp = candHost != null ? candPort : port;
                if (lh == null) {
                    updateStatus("Жду ПК в WiFi (discovery)…");
                    handler.postDelayed(this::connect, 2000);
                    return;
                }
                eh = lh;
                epn = lp;
                tun = false;
                rkind = "lan";
                updateStatus("Пробую WiFi " + eh + ":" + epn + "…");
            } else {
                if (act.nodeTicket == null || act.nodeTicket.isEmpty()) {
                    updateStatus("p2p: нет адреса ПК — зайдите по WiFi (на мосту: i включён)");
                    handler.postDelayed(this::connect, Math.min(retryMs, 8000));
                    retryMs = Math.min(retryMs * 2, 30000);
                    return;
                }
                if (tunnelPort == 0 || !tunnelIrohReady) {
                    long started = tunnelStartedAt;
                    int readyTo = Math.max(10, prefs.getInt(K_TUN_READY_TO, 45)) * 1000;
                    if (tunnelProc != null && started > 0 && now - started > readyTo) {
                        Log.w(TAG, "туннель не поднялся за " + readyTo + "ms — перезапуск");
                        updateStatus("p2p таймаут — перезапуск iroh…");
                        stopTunnel();
                    }
                    if (tunnelPort == 0) ensureTunnel(act.nodeTicket);
                    else updateStatus("iroh: жду dial к ПК…");
                    handler.postDelayed(this::connect, Math.min(retryMs, 4000));
                    retryMs = Math.min(retryMs * 2, 15000);
                    return;
                }
                eh = "127.0.0.1";
                epn = tunnelPort;
                tun = true;
                rkind = null;
            }
        }
        connecting = true;
        // эффективный адрес — его же используют WebView и загрузка аудио
        p.edit().putString("useHost", eh).putInt("usePort", epn).apply();
        // Токен — в заголовке Cookie, не в URL: query-строка попадает в логи
        // сервера/прокси, а это долгоживущий bearer полного доступа (F16).
        // Мост читает bridgeToken и из cookie (requestToken).
        String url = "wss://" + eh + ":" + epn + "/ws";
        Log.i(TAG, "connect wss://" + eh + ":" + epn + "/ws"
                + (tun ? " (туннель)" : (" (" + rkind + ")")));
        noteDiag("connect " + eh + ":" + epn + (tun ? " tunnel" : " " + rkind)
                + " pathIdx=" + pathIdx + " failStreak=" + lanFailStreak + " wifi=" + onWifi);
        // Удалённые кандидаты (v6/overlay) — короткий таймаут, чтобы быстро
        // перейти к следующему пути, если адрес не маршрутизируется.
        okhttp3.OkHttpClient attemptClient = (!tun && ("v6".equals(rkind) || "ov".equals(rkind)))
                ? http.newBuilder().connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS).build()
                : http;
        ws = attemptClient.newWebSocket(new Request.Builder().url(url)
                .header("Cookie", "bridgeToken=" + token).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response r) {
                connected = true;
                connecting = false;
                viaTunnel = tun;
                viaRemoteKind = tun ? null : rkind;
                pathIdx = 0; // путь найден — перебор эндпоинтов начинаем заново
                retryMs = 2000;
                lanFailStreak = 0;
                if (!tun) lanLastSeen = System.currentTimeMillis();
                // Адрес прошёл пиннинговое рукопожатие — теперь ему можно доверять
                // и сохранить (только LAN; имя из discovery не берём) (F4).
                if (!tun && candHost != null) {
                    String afp = store.activeFp();
                    if (afp != null) store.updateHost(afp, eh, epn, null);
                }
                candHost = null; // кандидат разрешён
                if (!tun && tunnelPort != 0) stopTunnel(); // LAN победил — туннель не нужен
                updateStatus("Подключено (" + pathRu() + "): " + bridgeName());
                broadcastState();
                socket.send("{\"type\":\"internet\"}"); // узнаём/обновляем p2p-адрес ПК
                registerNode(socket); // сообщаем мосту наш p2p-NodeId для allowlist
                noteDiag("ws open " + pathRu());
                flushPendingDiag();
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                try {
                    JSONObject m = new JSONObject(text);
                    String type = m.optString("type");
                    if ("notify".equals(type)) {
                        showEvent(m.optString("title", "Cursor Agent"),
                                m.optString("message", "Событие"),
                                m.optString("id", ""),
                                m.optString("event", ""));
                    } else if ("status".equals(type)) {
                        // Agent снова работает (например, ответили на ПК) —
                        // уведомление этой сессии больше не актуально
                        if ("working".equals(m.optString("status"))) {
                            cancelEventNotif(m.optString("id"));
                        }
                    } else if ("sessions".equals(type)) {
                        int alive = 0;
                        var arr = m.optJSONArray("sessions");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                if (arr.getJSONObject(i).optBoolean("alive")) alive++;
                            }
                        }
                        updateStatus("Подключено (" + pathRu() + "): " + bridgeName() + " · сессий: " + alive);
                    } else if ("internet".equals(type)) {
                        // мост сообщил p2p-адрес и WSS-эндпоинты (v6/overlay) —
                        // запоминаем; если на ПК iroh включён — автоматически
                        // разрешаем интернет у этого ПК
                        String afp = store.activeFp();
                        String tk = m.optString("ticket", "");
                        if (afp != null) {
                            if (m.optBoolean("enabled", false)) store.setInternet(afp, true);
                            if (!tk.isEmpty()) store.setTicket(afp, tk);
                            JSONArray eps = m.optJSONArray("endpoints");
                            if (eps != null) {
                                String[] arr = BridgeStore.parseEndpoints(eps);
                                if (arr.length > 0) store.setEndpoints(afp, arr);
                            }
                        }
                    } else if ("phoneCmd".equals(type)) {
                        handler.post(() -> handlePhoneCmd(m));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "bad message", e);
                }
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response r) {
                if (!tun) pathIdx++; // переходим к следующему эндпоинту (v6→overlay→туннель)
                lastWsError = String.valueOf(t) + (r != null ? " http=" + r.code() : "");
                Log.w(TAG, "ws failure: " + lastWsError);
                noteDiag("ws fail: " + lastWsError);
                onDown();
                maybeAutoDiag("ws_fail");
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason); // подтверждаем закрытие, иначе onClosed не наступит
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                lastWsError = "closed " + code + " " + reason;
                noteDiag("ws closed " + code + " " + reason);
                if (code == 4401) {
                    // устройство отозвано на этом мосту: убираем его из списка;
                    // если остались другие ПК — активным станет первый из них
                    String afp = store.activeFp();
                    if (afp != null) store.remove(afp);
                    token = null;
                    updateStatus(store.active() != null
                            ? "Доступ отозван, переключаюсь на другой компьютер…"
                            : "Доступ отозван — откройте приложение для сопряжения");
                }
                onDown();
                maybeAutoDiag("ws_closed_" + code);
            }
        });
    }

    private void onDown() {
        if (destroyed) return; // обрыв вызван остановкой сервиса — не переподключаемся
        boolean was = connected;
        boolean wasTun = viaTunnel;
        connected = false;
        connecting = false;
        viaTunnel = false;
        ws = null;
        candHost = null;
        if (!wasTun) {
            lanFailStreak++;
            if (lanFailStreak >= 2) {
                lanLastSeen = 0;
                lanGraceUntil = 0;
                Log.i(TAG, "LAN fail streak=" + lanFailStreak + " — следующий путь iroh");
            }
        }
        if (was) {
            updateStatus(allowedNow() ? "Связь потеряна, переподключение…" : pausedText());
        } else if (!wasTun && lanFailStreak >= 2) {
            updateStatus("WiFi не принял — пробую интернет (p2p)…");
            maybeAutoDiag("lan_fail_streak");
        }
        broadcastState();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::connect, retryMs);
        retryMs = Math.min(retryMs * 2, 30000);
    }

    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // появилась сеть (WiFi или мобильная) — пробуем переподключиться.
                retryMs = 2000;
                boolean wifi = false;
                try {
                    NetworkCapabilities c = cm.getNetworkCapabilities(network);
                    wifi = c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                } catch (Exception ignored) { }
                if (wifi) {
                    // WiFi: дать LAN-discovery фору перед iroh
                    lanGraceUntil = System.currentTimeMillis() + 12_000;
                } else {
                    // LTE/др.: LAN мёртв — сразу iroh, не долбить 192.168.x
                    lanLastSeen = 0;
                    lanGraceUntil = 0;
                    candHost = null;
                }
                final boolean preferTunnel = !wifi;
                handler.post(() -> {
                    if (!allowedNow()) { if (connected) pauseConnection(); return; }
                    if (preferTunnel && connected && !viaTunnel) {
                        forceReconnect();
                        return;
                    }
                    if (!connected) connect();
                });
            }

            @Override
            public void onLost(Network network) {
                handler.post(() -> {
                    lanLastSeen = 0;
                    lanGraceUntil = 0;
                    candHost = null;
                    if (connected && !viaTunnel) forceReconnect();
                });
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    // ------------------------------------------------------ интернет-туннель

    private boolean tunnelBinMissing = false;

    private String bridgeName() {
        BridgeStore.Bridge a = store.active();
        return a != null && a.name != null ? a.name : String.valueOf(host);
    }

    /** Человекочитаемый путь связи для уведомления и настроек. */
    private String pathRu() {
        if (!viaTunnel) {
            if ("v6".equals(viaRemoteKind)) return "интернет, IPv6";
            if ("ov".equals(viaRemoteKind)) return "интернет, overlay";
            return "WiFi";
        }
        if ("direct".equals(tunnelPath)) return "интернет, p2p напрямую";
        if ("relay".equals(tunnelPath)) return "интернет, через relay";
        return "интернет";
    }

    // ----- постоянная p2p-личность телефона (NodeId) для allowlist моста -----

    private java.io.File tunnelBin() {
        return new java.io.File(getApplicationInfo().nativeLibraryDir, "libcursortunnel.so");
    }

    private String phoneKeyPath() {
        return new java.io.File(getFilesDir(), "iroh_phone.key").getAbsolutePath();
    }

    private volatile boolean computingNodeId = false;

    /** Вычислить (один раз) и запомнить постоянный NodeId телефона. */
    private synchronized void ensurePhoneNodeId() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (p.getString("phoneNodeId", null) != null || computingNodeId) return;
        java.io.File bin = tunnelBin();
        if (!bin.exists()) return;
        computingNodeId = true;
        new Thread(() -> {
            try {
                Process proc = new ProcessBuilder(bin.getAbsolutePath(),
                        "id", "--secret-file", phoneKeyPath()).start();
                String id = null;
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        try {
                            JSONObject j = new JSONObject(line);
                            if ("id".equals(j.optString("event"))) { id = j.optString("node_id", null); break; }
                        } catch (Exception ignored) { }
                    }
                }
                proc.waitFor();
                if (id != null && !id.isEmpty()) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("phoneNodeId", id).apply();
                    Log.i(TAG, "p2p NodeId телефона готов");
                    // если уже подключены — сразу зарегистрируем
                    if (connected && ws != null) registerNode(ws);
                }
            } catch (Exception e) {
                Log.w(TAG, "не удалось получить NodeId телефона", e);
            } finally {
                computingNodeId = false;
            }
        }, "phone-node-id").start();
    }

    /** Сообщить мосту наш постоянный NodeId (для allowlist интернет-туннеля). */
    private void registerNode(WebSocket socket) {
        String id = getSharedPreferences(PREFS, MODE_PRIVATE).getString("phoneNodeId", null);
        if (id == null) { ensurePhoneNodeId(); return; } // вычислим и зарегистрируем позже
        if (socket == null) return;
        try {
            JSONObject j = new JSONObject();
            j.put("type", "registerNode");
            j.put("nodeId", id);
            socket.send(j.toString());
        } catch (Exception ignored) { }
    }

    /** Запустить сайдкар cursor-tunnel в режиме connect (телефон -> ПК). */
    private synchronized void ensureTunnel(String ticket) {
        if (tunnelProc != null || tunnelBinMissing) return;
        java.io.File bin = tunnelBin();
        if (!bin.exists()) {
            tunnelBinMissing = true;
            Log.w(TAG, "туннель недоступен: " + bin + " не найден");
            updateStatus("p2p: в APK нет libcursortunnel.so — переустановите приложение");
            return;
        }
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String onlineMode = p.getString(K_ONLINE_MODE, "bg");
        int onlineTo = p.getInt(K_ONLINE_TO, 30);
        int dialTo = p.getInt(K_DIAL_TO, 20);
        boolean warmup = p.getBoolean(K_WARMUP, true);
        String addrModePref = p.getString(K_ADDR_MODE, "auto");
        // auto: WiFi → lan (relay+192.168), LTE → только relay (не долбить мёртвые IP)
        String addrMode = addrModePref;
        if ("auto".equalsIgnoreCase(addrModePref) || addrModePref == null || addrModePref.isEmpty()) {
            addrMode = isOnWifi() ? "lan" : "relay";
        }
        if ("relay".equals(addrMode)) {
            onlineTo = Math.max(onlineTo, 45); // ждать home-relay до dial
        }
        final Process proc;
        try {
            proc = new ProcessBuilder(
                    bin.getAbsolutePath(),
                    "connect",
                    "--ticket=" + ticket,
                    "--listen", "127.0.0.1:0",
                    "--secret-file", phoneKeyPath(),
                    "--online-mode", onlineMode,
                    "--online-timeout-secs", String.valueOf(onlineTo),
                    "--dial-timeout-secs", String.valueOf(dialTo),
                    "--warmup", warmup ? "true" : "false",
                    "--addr-mode", addrMode)
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception e) {
            Log.w(TAG, "не удалось запустить туннель", e);
            updateStatus("p2p: не запустился сайдкар — " + e.getMessage());
            return;
        }
        tunnelProc = proc;
        tunnelStartedAt = System.currentTimeMillis();
        lastTunnelError = null;
        tunnelIrohReady = false;
        noteDiag("tunnel start mode=" + onlineMode + " onlineTo=" + onlineTo
                + " dialTo=" + dialTo + " warmup=" + warmup + " addrMode=" + addrMode);
        updateStatus("ПК не в WiFi — поднимаю iroh (p2p)…");
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    JSONObject j;
                    try { j = new JSONObject(line); } catch (Exception e) {
                        Log.i(TAG, "tunnel: " + line);
                        continue;
                    }
                    String ev = j.optString("event");
                    if ("ready".equals(ev)) {
                        // Порт слушателя готов, но dial ещё может идти.
                        // WS не открываем здесь — иначе Read timed out на LTE.
                        tunnelPort = j.optInt("port", 0);
                        noteDiag("tunnel ready port=" + tunnelPort
                                + " online=" + j.optBoolean("online", false));
                    } else if ("cfg".equals(ev)) {
                        noteDiag("tunnel cfg: " + j.toString());
                    } else if ("dialing".equals(ev)) {
                        updateStatus("iroh: звоню на ПК…");
                    } else if ("connected".equals(ev)) {
                        tunnelIrohReady = true;
                        updateStatus("iroh: канал к ПК есть, подключаю мост…");
                        handler.post(BridgeService.this::connect);
                    } else if ("path".equals(ev)) {
                        tunnelPath = j.optString("path", null);
                        tunnelAddr = j.optString("addr", null);
                        broadcastState();
                        if (connected && viaTunnel) {
                            updateStatus("Подключено (" + pathRu() + "): " + bridgeName());
                        }
                    } else if ("status".equals(ev)) {
                        if (j.optBoolean("online", false)) {
                            noteDiag("tunnel status: phone home-relay online");
                        }
                    } else if ("warn".equals(ev)) {
                        String msg = j.optString("message", "warn iroh");
                        Log.w(TAG, "туннель warn: " + msg);
                        noteDiag("tunnel warn: " + msg);
                    } else if ("error".equals(ev)) {
                        lastTunnelError = j.optString("message", "ошибка iroh");
                        Log.w(TAG, "туннель: " + lastTunnelError);
                        updateStatus("p2p: " + lastTunnelError);
                        noteDiag("tunnel error: " + lastTunnelError);
                        maybeAutoDiag("tunnel_error");
                    }
                }
            } catch (Exception ignored) { }
            synchronized (BridgeService.this) {
                if (tunnelProc == proc) {
                    tunnelProc = null;
                    tunnelPort = 0;
                    tunnelIrohReady = false;
                    tunnelStartedAt = 0;
                    tunnelPath = null;
                    tunnelAddr = null;
                }
            }
            broadcastState();
        }, "tunnel-io");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void stopTunnel() {
        if (tunnelProc != null) {
            try { tunnelProc.destroy(); } catch (Exception ignored) { }
            tunnelProc = null;
        }
        tunnelPort = 0;
        tunnelIrohReady = false;
        tunnelStartedAt = 0;
        tunnelPath = null;
        tunnelAddr = null;
    }

    // ---------------------------------------- удалённые тактики с ПК (WS + UDP)

    private static final int PHONE_CMD_UDP_PORT = 8793;
    private DatagramSocket phoneCmdSock;
    private volatile String lastPhoneCmdId = null;

    private String tokenHash16(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void startPhoneCmdUdp() {
        Thread t = new Thread(() -> {
            try {
                phoneCmdSock = new DatagramSocket(PHONE_CMD_UDP_PORT);
                phoneCmdSock.setReuseAddress(true);
                byte[] buf = new byte[8192];
                Log.i(TAG, "phoneCmd UDP listen :" + PHONE_CMD_UDP_PORT);
                while (!Thread.interrupted() && !destroyed) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    phoneCmdSock.receive(pkt);
                    String s = new String(pkt.getData(), 0, pkt.getLength(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (!s.startsWith("CURSOR_BRIDGE_CMD")) continue;
                    try {
                        JSONObject m = new JSONObject(s.substring("CURSOR_BRIDGE_CMD".length()));
                        handler.post(() -> handlePhoneCmd(m));
                    } catch (Exception e) {
                        Log.w(TAG, "bad phoneCmd udp", e);
                    }
                }
            } catch (Exception e) {
                if (!destroyed) Log.w(TAG, "phoneCmd UDP ended", e);
            }
        }, "phone-cmd-udp");
        t.setDaemon(true);
        t.start();
    }

    private boolean phoneCmdAuthorized(JSONObject m) {
        String tok = getSharedPreferences(PREFS, MODE_PRIVATE).getString("token", token);
        if (tok == null) return false;
        String mine = tokenHash16(tok);
        org.json.JSONArray arr = m.optJSONArray("tokenHashes");
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            if (mine.equalsIgnoreCase(arr.optString(i, ""))) return true;
        }
        return false;
    }

    private void handlePhoneCmd(JSONObject m) {
        if (!phoneCmdAuthorized(m)) {
            noteDiag("phoneCmd rejected: bad token hash");
            return;
        }
        String cmd = m.optString("cmd", "");
        String cmdId = m.optString("cmdId", "");
        if (cmd.isEmpty()) return;
        if (cmdId.equals(lastPhoneCmdId)) return; // дедуп WS+UDP
        lastPhoneCmdId = cmdId;
        noteDiag("phoneCmd recv: " + cmd + " id=" + cmdId);
        updateStatus("ПК-команда: " + cmd + "…");
        final JSONObject args = m.optJSONObject("args");
        new Thread(() -> runPhoneCmd(cmd, cmdId, args != null ? args : new JSONObject()), "phone-cmd").start();
    }

    private JSONObject readTunCfg() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONObject c = new JSONObject();
        try {
            c.put("onlineMode", p.getString(K_ONLINE_MODE, "bg"));
            c.put("onlineTimeoutSecs", p.getInt(K_ONLINE_TO, 30));
            c.put("dialTimeoutSecs", p.getInt(K_DIAL_TO, 20));
            c.put("warmup", p.getBoolean(K_WARMUP, true));
            c.put("lanFailBeforeTunnel", p.getInt(K_LAN_FAIL, 2));
            c.put("preferTunnel", p.getBoolean(K_PREFER_TUN, false));
            c.put("tunnelReadyTimeoutSecs", p.getInt(K_TUN_READY_TO, 45));
            c.put("addrMode", p.getString(K_ADDR_MODE, "auto"));
            c.put("note", "set-cfg + restart-tunnel чтобы применить к сайдкару");
        } catch (Exception ignored) {}
        return c;
    }

    /** Применить knobs из args. Возвращает список изменённых ключей. */
    private org.json.JSONArray applyTunCfg(JSONObject args) {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        org.json.JSONArray changed = new org.json.JSONArray();
        if (args == null) return changed;
        if (args.has("onlineMode") || args.has("online_mode")) {
            String v = args.optString("onlineMode", args.optString("online_mode", "bg")).trim().toLowerCase();
            if (!v.equals("skip") && !v.equals("bg") && !v.equals("wait")) v = "bg";
            ed.putString(K_ONLINE_MODE, v);
            changed.put("onlineMode=" + v);
        }
        if (args.has("onlineTimeoutSecs") || args.has("online_timeout_secs")) {
            int v = args.optInt("onlineTimeoutSecs", args.optInt("online_timeout_secs", 30));
            ed.putInt(K_ONLINE_TO, Math.max(0, Math.min(v, 120)));
            changed.put("onlineTimeoutSecs=" + Math.max(0, Math.min(v, 120)));
        }
        if (args.has("dialTimeoutSecs") || args.has("dial_timeout_secs")) {
            int v = args.optInt("dialTimeoutSecs", args.optInt("dial_timeout_secs", 20));
            ed.putInt(K_DIAL_TO, Math.max(1, Math.min(v, 120)));
            changed.put("dialTimeoutSecs=" + Math.max(1, Math.min(v, 120)));
        }
        if (args.has("warmup")) {
            boolean v = args.optBoolean("warmup", true);
            ed.putBoolean(K_WARMUP, v);
            changed.put("warmup=" + v);
        }
        if (args.has("lanFailBeforeTunnel") || args.has("lan_fail_before_tunnel")) {
            int v = args.optInt("lanFailBeforeTunnel", args.optInt("lan_fail_before_tunnel", 2));
            ed.putInt(K_LAN_FAIL, Math.max(0, Math.min(v, 10)));
            changed.put("lanFailBeforeTunnel=" + Math.max(0, Math.min(v, 10)));
        }
        if (args.has("preferTunnel") || args.has("prefer_tunnel")) {
            boolean v = args.optBoolean("preferTunnel", args.optBoolean("prefer_tunnel", false));
            ed.putBoolean(K_PREFER_TUN, v);
            changed.put("preferTunnel=" + v);
        }
        if (args.has("tunnelReadyTimeoutSecs") || args.has("tunnel_ready_timeout_secs")) {
            int v = args.optInt("tunnelReadyTimeoutSecs", args.optInt("tunnel_ready_timeout_secs", 45));
            ed.putInt(K_TUN_READY_TO, Math.max(10, Math.min(v, 180)));
            changed.put("tunnelReadyTimeoutSecs=" + Math.max(10, Math.min(v, 180)));
        }
        if (args.has("addrMode") || args.has("addr_mode")) {
            String v = args.optString("addrMode", args.optString("addr_mode", "auto")).trim().toLowerCase();
            if (!v.equals("auto") && !v.equals("lan") && !v.equals("relay") && !v.equals("all")) v = "auto";
            ed.putString(K_ADDR_MODE, v);
            changed.put("addrMode=" + v);
        }
        ed.apply();
        return changed;
    }

    private void runPhoneCmd(String cmd, String cmdId, JSONObject args) {
        JSONObject result = new JSONObject();
        boolean ok = true;
        try {
            result.put("cmd", cmd);
            result.put("cmdId", cmdId);
            switch (cmd) {
                case "snapshot":
                case "diag":
                    result.put("snapshot", buildDiagSnapshot("phoneCmd_snapshot"));
                    handler.post(() -> sendDiag("phoneCmd_snapshot"));
                    break;
                case "reconnect":
                    handler.post(this::forceReconnect);
                    result.put("action", "forceReconnect");
                    break;
                case "force-tunnel":
                case "force_tunnel": {
                    lanFailStreak = 2;
                    lanLastSeen = 0;
                    lanGraceUntil = 0;
                    candHost = null;
                    BridgeStore.Bridge act = store.active();
                    if (act != null) store.setInternet(act.fp, true);
                    handler.post(() -> {
                        stopTunnel();
                        forceReconnect();
                    });
                    result.put("action", "forceTunnel");
                    result.put("hasTicket", act != null && act.nodeTicket != null);
                    break;
                }
                case "force-lan":
                case "force_lan":
                    lanFailStreak = 0;
                    lanGraceUntil = System.currentTimeMillis() + 20_000;
                    handler.post(() -> {
                        stopTunnel();
                        forceReconnect();
                    });
                    result.put("action", "forceLan");
                    break;
                case "iroh-probe": {
                    BridgeStore.Bridge act = store.active();
                    JSONObject probe = new JSONObject();
                    if (act == null || act.nodeTicket == null || act.nodeTicket.isEmpty()) {
                        ok = false;
                        probe.put("error", "no nodeTicket on phone");
                    } else {
                        store.setInternet(act.fp, true);
                        handler.post(() -> {
                            stopTunnel();
                            ensureTunnel(act.nodeTicket);
                        });
                        // ждём ready; soft warn (online timeout) не стопает — dial идёт дальше
                        long deadline = System.currentTimeMillis() + 45_000;
                        while (System.currentTimeMillis() < deadline) {
                            if (tunnelIrohReady && tunnelPort > 0) break;
                            if (lastTunnelError != null && !tunnelIrohReady) break;
                            try { Thread.sleep(400); } catch (InterruptedException e) { break; }
                        }
                        probe.put("tunnelPort", tunnelPort);
                        probe.put("tunnelIrohReady", tunnelIrohReady);
                        probe.put("tunnelError", lastTunnelError);
                        probe.put("tunnelPath", tunnelPath);
                        probe.put("ticketLen", act.nodeTicket.length());
                        ok = tunnelIrohReady && tunnelPort > 0;
                    }
                    result.put("probe", probe);
                    result.put("snapshot", buildDiagSnapshot("phoneCmd_iroh_probe"));
                    handler.post(() -> sendDiag("phoneCmd_iroh_probe"));
                    break;
                }
                case "net": {
                    JSONObject net = new JSONObject();
                    net.put("onWifi", isOnWifi());
                    try {
                        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                        NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
                        net.put("hasInternet", c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                        net.put("hasValidated", c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                        net.put("transportWifi", c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                        net.put("transportCell", c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                    } catch (Exception e) {
                        net.put("capError", String.valueOf(e.getMessage()));
                    }
                    net.put("host", host);
                    net.put("port", port);
                    net.put("useHost", getSharedPreferences(PREFS, MODE_PRIVATE).getString("useHost", null));
                    result.put("net", net);
                    break;
                }
                case "relay-ping":
                case "relay_ping": {
                    result.put("relays", pingN0Relays());
                    result.put("udp7842", pingN0RelayUdp());
                    result.put("onWifi", isOnWifi());
                    handler.post(() -> sendDiag("phoneCmd_relay_ping"));
                    break;
                }
                case "cfg":
                case "get-cfg":
                case "get_cfg": {
                    result.put("cfg", readTunCfg());
                    break;
                }
                case "set-cfg":
                case "set_cfg": {
                    org.json.JSONArray changed = applyTunCfg(args);
                    result.put("changed", changed);
                    result.put("cfg", readTunCfg());
                    boolean restart = args != null && (args.optBoolean("restart", false)
                            || args.optBoolean("restartTunnel", false));
                    if (restart) {
                        handler.post(() -> {
                            stopTunnel();
                            BridgeStore.Bridge a = store.active();
                            if (a != null && a.nodeTicket != null) ensureTunnel(a.nodeTicket);
                        });
                        result.put("restartTunnel", true);
                    }
                    break;
                }
                case "restart-tunnel":
                case "restart_tunnel": {
                    BridgeStore.Bridge act = store.active();
                    handler.post(() -> {
                        stopTunnel();
                        if (act != null && act.nodeTicket != null) ensureTunnel(act.nodeTicket);
                    });
                    result.put("action", "restartTunnel");
                    result.put("cfg", readTunCfg());
                    break;
                }
                case "bundle":
                case "self-test": {
                    // один вызов: cfg + net + relay + udp + optional iroh-probe
                    result.put("cfg", readTunCfg());
                    JSONObject net = new JSONObject();
                    net.put("onWifi", isOnWifi());
                    try {
                        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                        NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
                        net.put("hasInternet", c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                        net.put("transportWifi", c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                        net.put("transportCell", c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                    } catch (Exception e) {
                        net.put("capError", String.valueOf(e.getMessage()));
                    }
                    result.put("net", net);
                    result.put("relays", pingN0Relays());
                    result.put("udp7842", pingN0RelayUdp());
                    boolean doProbe = args == null || args.optBoolean("iroh", true);
                    if (doProbe) {
                        BridgeStore.Bridge act = store.active();
                        JSONObject probe = new JSONObject();
                        if (act == null || act.nodeTicket == null || act.nodeTicket.isEmpty()) {
                            probe.put("error", "no nodeTicket");
                        } else {
                            store.setInternet(act.fp, true);
                            handler.post(() -> {
                                stopTunnel();
                                ensureTunnel(act.nodeTicket);
                            });
                            long deadline = System.currentTimeMillis() + 45_000;
                            while (System.currentTimeMillis() < deadline) {
                                if (tunnelIrohReady && tunnelPort > 0) break;
                                if (lastTunnelError != null && !tunnelIrohReady) break;
                                try { Thread.sleep(400); } catch (InterruptedException e) { break; }
                            }
                            probe.put("tunnelPort", tunnelPort);
                            probe.put("tunnelIrohReady", tunnelIrohReady);
                            probe.put("tunnelError", lastTunnelError);
                            probe.put("tunnelPath", tunnelPath);
                            probe.put("tunnelAddr", tunnelAddr);
                        }
                        result.put("probe", probe);
                    }
                    result.put("snapshot", buildDiagSnapshot("phoneCmd_bundle"));
                    handler.post(() -> sendDiag("phoneCmd_bundle"));
                    break;
                }
                default:
                    ok = false;
                    result.put("error", "unknown cmd: " + cmd);
                    result.put("hint", "cfg|set-cfg|restart-tunnel|bundle|relay-ping|iroh-probe|…");
            }
        } catch (Exception e) {
            ok = false;
            try { result.put("exception", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        replyPhoneCmd(cmd, cmdId, ok, result);
        updateStatus(ok ? ("ПК-команда OK: " + cmd) : ("ПК-команда fail: " + cmd));
    }

    /** HTTPS ping публичных n0 relay (+ dns) с телефона. */
    private org.json.JSONArray pingN0Relays() {
        String[] urls = {
                "https://euc1-1.relay.n0.iroh.link/",
                "https://use1-1.relay.n0.iroh.link/",
                "https://usw1-1.relay.n0.iroh.link/",
                "https://aps1-1.relay.n0.iroh.link/",
                "https://dns.iroh.link/",
        };
        org.json.JSONArray out = new org.json.JSONArray();
        // системный trust store — публичные сертификаты n0
        OkHttpClient c = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        for (String u : urls) {
            JSONObject row = new JSONObject();
            long t0 = System.currentTimeMillis();
            try {
                row.put("url", u);
                Request req = new Request.Builder().url(u).get().build();
                try (Response resp = c.newCall(req).execute()) {
                    row.put("ok", true);
                    row.put("http", resp.code());
                    row.put("ms", System.currentTimeMillis() - t0);
                }
            } catch (Exception e) {
                try {
                    row.put("url", u);
                    row.put("ok", false);
                    row.put("ms", System.currentTimeMillis() - t0);
                    row.put("error", String.valueOf(e.getMessage()));
                } catch (Exception ignored) {}
            }
            out.put(row);
        }
        return out;
    }

    /**
     * UDP probe iroh relay QUIC port 7842.
     * Ответ не обязателен (QUIC handshake не делаем) — смотрим resolve + send + recv/timeout/err.
     */
    private org.json.JSONArray pingN0RelayUdp() {
        String[] hosts = {
                "euc1-1.relay.n0.iroh.link",
                "use1-1.relay.n0.iroh.link",
                "usw1-1.relay.n0.iroh.link",
                "aps1-1.relay.n0.iroh.link",
        };
        org.json.JSONArray out = new org.json.JSONArray();
        byte[] payload = new byte[]{0x00}; // мусор — ждём любой UDP или timeout
        for (String host : hosts) {
            JSONObject row = new JSONObject();
            long t0 = System.currentTimeMillis();
            try {
                row.put("host", host);
                row.put("port", 7842);
                java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
                if (addrs.length == 0) {
                    row.put("ok", false);
                    row.put("error", "dns empty");
                    row.put("ms", System.currentTimeMillis() - t0);
                    out.put(row);
                    continue;
                }
                java.net.InetAddress ip = addrs[0];
                row.put("ip", ip.getHostAddress());
                try (java.net.DatagramSocket sock = new java.net.DatagramSocket()) {
                    sock.setSoTimeout(3000);
                    java.net.DatagramPacket send = new java.net.DatagramPacket(
                            payload, payload.length, ip, 7842);
                    sock.send(send);
                    row.put("sent", true);
                    byte[] buf = new byte[64];
                    java.net.DatagramPacket recv = new java.net.DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(recv);
                        row.put("ok", true);
                        row.put("recv", true);
                        row.put("recvBytes", recv.getLength());
                    } catch (java.net.SocketTimeoutException te) {
                        // timeout ≠ блок: DPI мог дропнуть, firewall silent drop
                        row.put("ok", true);
                        row.put("recv", false);
                        row.put("note", "udp send ok, no reply in 3s (QUIC может игнорить мусор)");
                    }
                }
                row.put("ms", System.currentTimeMillis() - t0);
            } catch (Exception e) {
                try {
                    row.put("host", host);
                    row.put("port", 7842);
                    row.put("ok", false);
                    row.put("ms", System.currentTimeMillis() - t0);
                    row.put("error", String.valueOf(e.getMessage()));
                } catch (Exception ignored) {}
            }
            out.put(row);
        }
        return out;
    }

    private void replyPhoneCmd(String cmd, String cmdId, boolean ok, JSONObject result) {
        try {
            JSONObject out = new JSONObject();
            out.put("type", "phoneCmdResult");
            out.put("cmd", cmd);
            out.put("cmdId", cmdId);
            out.put("ok", ok);
            out.put("result", result);
            out.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
            out.put("at", System.currentTimeMillis());
            // WS если жив
            if (ws != null && connected) {
                try { ws.send(out.toString()); } catch (Exception ignored) {}
            }
            // всегда ещё /diag — чтобы агент прочитал файл на ПК
            JSONObject diag = buildDiagSnapshot("phoneCmd_" + cmd);
            diag.put("phoneCmdResult", out);
            // sendDiag строит свой snapshot — положим result в events
            noteDiag("phoneCmdResult ok=" + ok + " " + cmd);
            new Thread(() -> {
                // прямой POST с полным result
                try {
                    SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
                    String tok = p.getString("token", token);
                    String fp = p.getString("fp", certFp);
                    String eh = p.getString("useHost", host);
                    int ep = p.getInt("usePort", port > 0 ? port : 8790);
                    if (tunnelPort > 0) { eh = "127.0.0.1"; ep = tunnelPort; }
                    if (tok == null || fp == null || eh == null) return;
                    OkHttpClient c = Tls.buildClient(fp).newBuilder()
                            .connectTimeout(8, TimeUnit.SECONDS).build();
                    JSONObject body = new JSONObject();
                    body.put("reason", "phoneCmdResult");
                    body.put("cmd", cmd);
                    body.put("cmdId", cmdId);
                    body.put("ok", ok);
                    body.put("result", result);
                    body.put("snapshot", diag);
                    body.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
                    Request req = new Request.Builder()
                            .url("https://" + eh + ":" + ep + "/diag")
                            .header("Cookie", "bridgeToken=" + tok)
                            .post(RequestBody.create(body.toString(),
                                    MediaType.get("application/json; charset=utf-8")))
                            .build();
                    try (Response resp = c.newCall(req).execute()) {
                        noteDiag("phoneCmdResult diag http=" + resp.code());
                    }
                } catch (Exception e) {
                    noteDiag("phoneCmdResult diag fail: " + e.getMessage());
                }
            }, "phone-cmd-reply").start();
        } catch (Exception e) {
            Log.w(TAG, "replyPhoneCmd", e);
        }
    }

    // ------------------------------------------------------------ диагностика → ПК

    private void noteDiag(String msg) {
        String line = System.currentTimeMillis() + " " + msg;
        synchronized (diagEvents) {
            diagEvents.addLast(line);
            while (diagEvents.size() > 80) diagEvents.removeFirst();
        }
        Log.i(TAG, "diag: " + msg);
    }

    private void maybeAutoDiag(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastDiagSentAt < 20_000) return;
        sendDiag(reason);
    }

    private void flushPendingDiag() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "pending-diag.json");
            if (f.exists()) {
                noteDiag("have pending-diag.json, resending");
                f.delete();
            }
        } catch (Exception ignored) {}
        if (System.currentTimeMillis() - lastDiagSentAt > 5_000) {
            sendDiag("connected_flush");
        }
    }

    private JSONObject buildDiagSnapshot(String reason) {
        JSONObject j = new JSONObject();
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            BridgeStore.Bridge act = store != null ? store.active() : null;
            j.put("reason", reason != null ? reason : "manual");
            j.put("appVersion", APP_VERSION);
            j.put("sdk", Build.VERSION.SDK_INT);
            j.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
            j.put("at", System.currentTimeMillis());
            j.put("connected", connected);
            j.put("connecting", connecting);
            j.put("viaTunnel", viaTunnel);
            j.put("onWifi", isOnWifi());
            j.put("bgMode", bgMode());
            j.put("appVisible", appVisible);
            j.put("lastStatus", lastStatus);
            j.put("host", host);
            j.put("port", port);
            j.put("useHost", p.getString("useHost", null));
            j.put("usePort", p.getInt("usePort", -1));
            j.put("candHost", candHost);
            j.put("candPort", candPort);
            j.put("pathIdx", pathIdx);
            j.put("viaRemoteKind", viaRemoteKind);
            j.put("lanLastSeenAgeMs", lanLastSeen == 0 ? -1 : (System.currentTimeMillis() - lanLastSeen));
            j.put("lanGraceLeftMs", Math.max(0, lanGraceUntil - System.currentTimeMillis()));
            j.put("lanFailStreak", lanFailStreak);
            j.put("cfg", readTunCfg());
            j.put("tunnelPort", tunnelPort);
            j.put("tunnelPath", tunnelPath);
            j.put("tunnelAddr", tunnelAddr);
            j.put("tunnelStartedAgeMs", tunnelStartedAt == 0 ? -1 : (System.currentTimeMillis() - tunnelStartedAt));
            j.put("tunnelBinExists", tunnelBin().exists());
            j.put("tunnelBinMissingFlag", tunnelBinMissing);
            j.put("lastTunnelError", lastTunnelError);
            j.put("lastWsError", lastWsError);
            j.put("phoneNodeId", p.getString("phoneNodeId", null));
            j.put("hasToken", token != null || p.getString("token", null) != null);
            j.put("fpPrefix", certFp != null && certFp.length() >= 12 ? certFp.substring(0, 12) : certFp);
            if (act != null) {
                j.put("activeName", act.name);
                j.put("activeInternet", act.internet);
                j.put("activeHasTicket", act.nodeTicket != null && !act.nodeTicket.isEmpty());
                j.put("activeTicketLen", act.nodeTicket != null ? act.nodeTicket.length() : 0);
                j.put("activeEndpointCount", act.endpoints != null ? act.endpoints.length : 0);
                j.put("activeHost", act.host);
                j.put("activePort", act.port);
            } else {
                j.put("activeName", JSONObject.NULL);
            }
            j.put("discoveredCount", discovered.size());
            org.json.JSONArray ev = new org.json.JSONArray();
            synchronized (diagEvents) {
                for (String s : diagEvents) ev.put(s);
            }
            j.put("events", ev);
        } catch (Exception e) {
            try { j.put("buildError", String.valueOf(e)); } catch (Exception ignored) {}
        }
        return j;
    }

    /** Отправить снимок диагностики на мост (POST /diag). */
    private void sendDiag(String reason) {
        if (diagSending) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String tok = p.getString("token", token);
        String fp = p.getString("fp", certFp);
        if (tok == null || fp == null) {
            noteDiag("diag skip: no token/fp");
            updateStatus("Диагностика: нет сопряжения — некуда слать");
            return;
        }
        // куда слать: живой туннель / useHost / сохранённый host
        String eh = null;
        int ep = 0;
        if (tunnelPort > 0) {
            eh = "127.0.0.1";
            ep = tunnelPort;
        } else {
            eh = p.getString("useHost", null);
            ep = p.getInt("usePort", 0);
            if (eh == null || ep <= 0) {
                eh = p.getString("host", host);
                ep = p.getInt("port", port > 0 ? port : 8790);
            }
        }
        if (eh == null || ep <= 0) {
            noteDiag("diag skip: no host");
            updateStatus("Диагностика: нет адреса ПК");
            return;
        }
        final String hostSend = eh;
        final int portSend = ep;
        final JSONObject body = buildDiagSnapshot(reason);
        diagSending = true;
        noteDiag("diag send → " + hostSend + ":" + portSend + " reason=" + reason);
        new Thread(() -> {
            try {
                OkHttpClient c = Tls.buildClient(fp).newBuilder()
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build();
                Request req = new Request.Builder()
                        .url("https://" + hostSend + ":" + portSend + "/diag")
                        .header("Cookie", "bridgeToken=" + tok)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")))
                        .build();
                try (Response resp = c.newCall(req).execute()) {
                    String rs = resp.body() != null ? resp.body().string() : "";
                    if (resp.isSuccessful()) {
                        lastDiagSentAt = System.currentTimeMillis();
                        noteDiag("diag ok http=" + resp.code());
                        updateStatus("Диагностика ушла на ПК (bridge/logs)");
                    } else {
                        noteDiag("diag fail http=" + resp.code() + " " + rs);
                        updateStatus("Диагностика: HTTP " + resp.code());
                        // очередь на диск — дошлём при следующем удачном коннекте
                        try {
                            java.io.File f = new java.io.File(getFilesDir(), "pending-diag.json");
                            java.nio.file.Files.write(f.toPath(), body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                noteDiag("diag exception: " + e.getMessage());
                updateStatus("Диагностика не дошла: " + e.getMessage());
                try {
                    java.io.File f = new java.io.File(getFilesDir(), "pending-diag.json");
                    java.nio.file.Files.write(f.toPath(), body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            } finally {
                diagSending = false;
            }
        }, "diag-upload").start();
    }

    // ------------------------------------------------------------ notifications

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel st = new NotificationChannel(CH_STATUS, "Состояние подключения",
                NotificationManager.IMPORTANCE_MIN);
        st.setShowBadge(false);
        nm.createNotificationChannel(st);
        NotificationChannel ev = new NotificationChannel(CH_EVENTS, "События Cursor Agent",
                NotificationManager.IMPORTANCE_HIGH);
        ev.enableVibration(true);
        // на локскрине содержимое (текст запроса Agent) скрыто по умолчанию
        ev.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(ev);
    }

    private Notification statusNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Cursor Bridge")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateStatus(String text) {
        lastStatus = text;
        // после onDestroy уведомление не трогаем: notify() от мёртвого сервиса
        // вернул бы на экран «вечное» уведомление, которое нечем снять
        if (destroyed) return;
        getSystemService(NotificationManager.class).notify(STATUS_ID, statusNotification(text));
    }

    private int reqSeq = 1000;

    /**
     * Секрет для подтверждения, что intent с extra «session» создан этим
     * приложением (уведомлением), а не чужим. Живёт в приватных prefs — переживает
     * смерть процесса, поэтому тап по уведомлению работает и после перезапуска.
     */
    static synchronized String notifAuthSecret(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = p.getString("notifAuth", null);
        if (s == null) {
            byte[] b = new byte[16];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            s = sb.toString();
            p.edit().putString("notifAuth", s).apply();
        }
        return s;
    }

    private Notification.Action quickAction(String sessionId, String data, String label,
                                            int nid, boolean requireAuth) {
        Intent i = new Intent(this, BridgeService.class)
                .setAction(ACTION_INPUT)
                .putExtra("session", sessionId)
                .putExtra("data", data)
                .putExtra("nid", nid);
        PendingIntent pi = PendingIntent.getService(this, reqSeq++, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder ab = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat),
                label, pi);
        // при включённой биометрии нажатие требует разблокировки устройства
        // (иначе ответ Agent можно было бы дать прямо с локскрина)
        if (requireAuth && Build.VERSION.SDK_INT >= 31) ab.setAuthenticationRequired(true);
        return ab.build();
    }

    /** Убрать событийное уведомление сессии (стало неактуальным). */
    private void cancelEventNotif(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        Integer nid;
        synchronized (eventNids) {
            nid = eventNids.remove(sessionId);
            eventKinds.remove(sessionId);
        }
        if (nid != null) getSystemService(NotificationManager.class).cancel(nid);
    }

    private void showEvent(String title, String message, String sessionId, String event) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        synchronized (eventNids) {
            // новое событие заменяет прежнее уведомление этой же сессии
            Integer prev = eventNids.remove(sessionId);
            if (prev != null) { nm.cancel(prev); eventKinds.remove(sessionId); }
            // «Agent завершил ответ» устаревает, как только пришло любое новое событие
            var it = eventNids.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if ("Stop".equals(eventKinds.get(e.getKey()))) {
                    nm.cancel(e.getValue());
                    eventKinds.remove(e.getKey());
                    it.remove();
                }
            }
        }
        int nid = eventSeq++;
        // cbAuth — секрет из приватных prefs: подтверждает, что intent с session
        // создан нами. Чужое приложение (экспортированная MainActivity) его не
        // знает, поэтому не сможет заставить нас переключить сессию (F15).
        Intent open = new Intent(this, MainActivity.class)
                .putExtra("session", sessionId)
                .putExtra("cbAuth", notifAuthSecret(this))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, nid, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean biolock = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("biolock", false);
        Notification.Builder b = new Notification.Builder(this, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(pi)
                // при биометрии на локскрине не показываем ничего, иначе только заголовок
                .setVisibility(biolock ? Notification.VISIBILITY_SECRET : Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true);
        // Agent ждёт ответа — отвечаем прямо из уведомления. Но при включённой
        // биометрии кнопки НЕ должны срабатывать с локскрина без разблокировки:
        //  • API ≥ 31: требуем разблокировку на нажатие (setAuthenticationRequired);
        //  • API < 31 (нельзя защитить кнопку): не показываем кнопки вовсе.
        if ("Notification".equals(event) && sessionId != null && !sessionId.isEmpty()
                && (!biolock || Build.VERSION.SDK_INT >= 31)) {
            b.addAction(quickAction(sessionId, "1", "Да (1)", nid, biolock));
            b.addAction(quickAction(sessionId, "2", "Всегда (2)", nid, biolock));
            b.addAction(quickAction(sessionId, "\u001b", "Нет (Esc)", nid, biolock));
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            synchronized (eventNids) {
                eventNids.put(sessionId, nid);
                eventKinds.put(sessionId, event);
            }
        }
        nm.notify(nid, b.build());
    }

    private void broadcastState() {
        // список замеченных в сети мостов (для экрана «добавить компьютер»)
        org.json.JSONArray arr = new org.json.JSONArray();
        long now = System.currentTimeMillis();
        for (JSONObject d : discovered.values()) {
            if (now - d.optLong("at") < 60_000) arr.put(d);
        }
        sendBroadcast(new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra("host", host)
                .putExtra("port", port)
                .putExtra("connected", connected)
                .putExtra("path", connected
                        ? (viaTunnel ? "inet-" + (tunnelPath == null ? "" : tunnelPath) : "lan")
                        : "none")
                .putExtra("pathAddr", viaTunnel ? tunnelAddr : null)
                .putExtra("activeFp", store.activeFp())
                .putExtra("discovered", arr.toString()));
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onDestroy() {
        destroyed = true; // колбэки OkHttp/туннеля больше не должны переподключаться
        if (probeThread != null) probeThread.interrupt();
        try { if (phoneCmdSock != null) phoneCmdSock.close(); } catch (Exception ignored) {}
        try { if (discovery != null) nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (netCallback != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {}
        if (ws != null) ws.cancel();
        stopTunnel();
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (mcLock != null && mcLock.isHeld()) mcLock.release();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
