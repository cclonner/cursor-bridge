package com.q.cursorbridge;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * TLS с пиннингом самоподписанного сертификата моста.
 * fpHex == null — режим TOFU (доверяем при первом сопряжении и запоминаем).
 */
public final class Tls {

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String fingerprint(java.security.cert.Certificate cert) {
        try {
            return sha256Hex(cert.getEncoded());
        } catch (Exception e) {
            return "";
        }
    }

    /** Доверяем только сертификату с данным отпечатком (fpHex == null — TOFU). */
    public static X509TrustManager pinnedTrust(String fpHex) {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String a) throws CertificateException {
                if (fpHex == null) return; // TOFU при сопряжении
                try {
                    if (!fingerprint(chain[0]).equalsIgnoreCase(fpHex)) {
                        throw new CertificateException("Отпечаток сертификата моста не совпадает");
                    }
                } catch (CertificateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CertificateException(e);
                }
            }
        };
    }

    /**
     * Клиент для первичного (ручного) сопряжения: доверяет любому серту (TOFU),
     * но ЗАПИСЫВАЕТ отпечаток фактически предъявленного сертификата в seen.
     * Пиннить надо именно его, а не значение из тела ответа /pair (иначе MITM
     * подсунет свой серт и «подтвердит» собственный отпечаток).
     */
    public static OkHttpClient capturingClient(java.util.concurrent.atomic.AtomicReference<String> seen) {
        try {
            X509TrustManager tm = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String a) {
                    if (chain != null && chain.length > 0) seen.set(fingerprint(chain[0]));
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            return new OkHttpClient.Builder()
                    .sslSocketFactory(ctx.getSocketFactory(), tm)
                    .hostnameVerifier((h, s) -> true)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Фабрика сырых TLS-сокетов с тем же пиннингом (для локального WS-прокси). */
    public static javax.net.ssl.SSLSocketFactory socketFactory(String fpHex) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{pinnedTrust(fpHex)}, null);
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static OkHttpClient buildClient(String fpHex) {
        try {
            X509TrustManager tm = pinnedTrust(fpHex);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            return new OkHttpClient.Builder()
                    .sslSocketFactory(ctx.getSocketFactory(), tm)
                    .hostnameVerifier((h, s) -> true) // доверие определяет пиннинг, не имя хоста
                    .pingInterval(20, TimeUnit.SECONDS)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Tls() { }
}
