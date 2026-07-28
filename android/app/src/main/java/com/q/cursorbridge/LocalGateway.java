package com.q.cursorbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;

/**
 * Локальный шлюз для WebView. Интерфейс всегда живой, даже когда ПК выключен:
 *  - статика (index.html, app.js, xterm…) отдаётся из assets APK по
 *    http://127.0.0.1:uiPort/ — грузится мгновенно и без сети;
 *  - ws://127.0.0.1:wsPort — прозрачный TCP-прокси до моста через TLS
 *    с пиннингом сертификата (адрес берётся из prefs при каждом соединении,
 *    так что смена ПК/переход на интернет-туннель подхватываются сами).
 */
public final class LocalGateway {

    private static final String TAG = "LocalGateway";

    public static volatile int uiPort = 0;
    public static volatile int wsPort = 0;
    // Секрет этого запуска: ws-прокси на 127.0.0.1 доступен ЛЮБОМУ приложению на
    // телефоне, поэтому токен он подставляет только тому, кто предъявил этот
    // nonce. Его знает лишь наш WebView (через AndroidApp.wsUrl); чужое
    // приложение подключиться к мосту через прокс не сможет. Даже утечка nonce
    // через XSS ничего не даёт: страница и так имеет свой WS.
    public static volatile String proxyNonce = null;

    private static ServerSocket uiSock;
    private static ServerSocket wsSock;

    public static synchronized void start(Context ctx) {
        if (uiPort != 0) return;
        final Context app = ctx.getApplicationContext();
        byte[] nb = new byte[16];
        new java.security.SecureRandom().nextBytes(nb);
        StringBuilder ns = new StringBuilder();
        for (byte b : nb) ns.append(String.format("%02x", b));
        proxyNonce = ns.toString();
        try {
            // фиксированные порты: стабильный origin -> localStorage переживает рестарт
            uiSock = bind(38471);
            wsSock = bind(38472);
        } catch (Exception e) {
            Log.e(TAG, "не удалось поднять локальный шлюз", e);
            return;
        }
        uiPort = uiSock.getLocalPort();
        wsPort = wsSock.getLocalPort();
        Log.i(TAG, "интерфейс: http://127.0.0.1:" + uiPort + ", ws-прокси: " + wsPort);
        acceptLoop("gw-static", uiSock, (s) -> serveStatic(app, s));
        acceptLoop("gw-wsproxy", wsSock, (s) -> proxyWs(app, s));
    }

    private static ServerSocket bind(int preferred) throws IOException {
        try {
            return new ServerSocket(preferred, 16, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            return new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        }
    }

    private interface Handler { void handle(Socket s) throws Exception; }

    private static void acceptLoop(String name, ServerSocket srv, Handler h) {
        Thread t = new Thread(() -> {
            while (!srv.isClosed()) {
                try {
                    Socket s = srv.accept();
                    Thread w = new Thread(() -> {
                        try { h.handle(s); } catch (Exception ignored) { }
                        finally { try { s.close(); } catch (Exception ignored) { } }
                    }, name + "-conn");
                    w.setDaemon(true);
                    w.start();
                } catch (Exception e) {
                    if (srv.isClosed()) return;
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------- статика

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json") || path.endsWith(".webmanifest")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static void serveStatic(Context app, Socket s) throws Exception {
        s.setSoTimeout(10_000);
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line = r.readLine();
        if (line == null) return;
        String[] parts = line.split(" ");
        if (parts.length < 2) return;
        String path = parts[1];
        // остальные заголовки не нужны — дочитываем до пустой строки
        String h;
        while ((h = r.readLine()) != null && !h.isEmpty()) { /* skip */ }

        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) { plain(s, "400 Bad Request", "bad path"); return; }

        byte[] body;
        try (InputStream in = app.getAssets().open("web" + path)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            body = buf.toByteArray();
        } catch (IOException e) {
            plain(s, "404 Not Found", "not found");
            return;
        }
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime(path) +
                "\r\nContent-Length: " + body.length +
                // CSP как HTTP-заголовок (frame-ancestors в <meta> игнорируется),
                // плюс защита от кликджекинга и MIME-sniffing
                "\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; " +
                "style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                "connect-src 'self' ws://127.0.0.1:* wss://127.0.0.1:*; object-src 'none'; " +
                "base-uri 'none'; form-action 'none'; frame-ancestors 'none'" +
                "\r\nX-Content-Type-Options: nosniff" +
                "\r\nX-Frame-Options: DENY" +
                "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static void plain(Socket s, String status, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        s.getOutputStream().write(("HTTP/1.1 " + status +
                "\r\nContent-Type: text/plain\r\nContent-Length: " + b.length +
                "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().write(b);
        s.getOutputStream().flush();
    }

    // ------------------------------------------------------------ ws-прокси

    private static void proxyWs(Context app, Socket client) throws Exception {
        SharedPreferences p = app.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE);
        String host = p.getString("useHost", p.getString("host", null));
        int port = p.getInt("usePort", p.getInt("port", 8790));
        String fp = p.getString("fp", null);
        String token = p.getString("token", null);
        if (host == null || fp == null || token == null) return; // моста/токена ещё нет

        // Заголовок апгрейда от WebView (страница токен НЕ знает) — вставляем токен
        // здесь, поэтому XSS в UI не может его утащить: он не покидает нативную часть.
        byte[] head = readHttpHead(client.getInputStream());
        if (head == null) return;
        // прокси подставит токен только предъявителю nonce этого запуска —
        // иначе к мосту мог бы подключиться любой процесс на телефоне
        if (!nonceOk(head)) return;
        byte[] injected = injectTokenCookie(head, token);

        SSLSocket backend = (SSLSocket) Tls.socketFactory(fp).createSocket();
        try {
            backend.connect(new java.net.InetSocketAddress(host, port), 5000);
            backend.startHandshake();
            backend.getOutputStream().write(injected);
            backend.getOutputStream().flush();
        } catch (Exception e) {
            try { backend.close(); } catch (Exception ignored) { }
            return; // ПК недоступен: страница сама переподключится позже
        }

        Thread up = pump("gw-up", client, backend);
        Thread down = pump("gw-down", backend, client);
        up.join();
        down.join();
        try { backend.close(); } catch (Exception ignored) { }
    }

    /** Прочитать HTTP-заголовок запроса (до CRLFCRLF) побайтно, не задев тело. */
    private static byte[] readHttpHead(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int state = 0, b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (b == '\r') state = (state == 2) ? 3 : 1;
            else if (b == '\n') {
                if (state == 1) state = 2;
                else if (state == 3) return buf.toByteArray();
                else state = 0;
            } else state = 0;
            if (buf.size() > 16384) return null; // аномально длинный заголовок
        }
        return null; // соединение закрылось до конца заголовка
    }

    /** Проверить, что запрос содержит nonce этого запуска (?cbnonce=…). */
    private static boolean nonceOk(byte[] head) {
        String expected = proxyNonce;
        if (expected == null) return false;
        String s = new String(head, StandardCharsets.ISO_8859_1);
        int nl = s.indexOf("\r\n");
        String line = nl > 0 ? s.substring(0, nl) : s; // строка запроса GET …
        int k = line.indexOf("cbnonce=");
        if (k < 0) return false;
        String v = line.substring(k + 8);
        int end = 0;
        while (end < v.length() && "0123456789abcdef".indexOf(v.charAt(end)) >= 0) end++;
        v = v.substring(0, end);
        // сравнение постоянного времени
        return java.security.MessageDigest.isEqual(
                v.getBytes(StandardCharsets.ISO_8859_1),
                expected.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Вставить Cookie с токеном перед завершающей пустой строкой заголовка. */
    private static byte[] injectTokenCookie(byte[] head, String token) {
        String s = new String(head, StandardCharsets.ISO_8859_1);
        String safe = token.replaceAll("[\\r\\n]", ""); // токен — hex, перестрахуемся
        // head оканчивается на CRLFCRLF: срезаем его и дописываем строку Cookie
        String out = s.substring(0, s.length() - 4)
                + "\r\nCookie: bridgeToken=" + safe + "\r\n\r\n";
        return out.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Thread pump(String name, Socket from, Socket to) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[16384];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception ignored) { }
            // конец потока в одну сторону закрывает всё соединение
            try { to.close(); } catch (Exception ignored) { }
            try { from.close(); } catch (Exception ignored) { }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private LocalGateway() { }
}
