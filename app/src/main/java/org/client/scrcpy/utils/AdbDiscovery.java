package org.client.scrcpy.utils;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds devices on the local network that advertise ADB over mDNS, using the
 * platform {@link NsdManager} (no help from the adb binary required).
 * <ul>
 *     <li>{@code _adb-tls-connect._tcp} – Android 11+ "Wireless debugging" (already paired)</li>
 *     <li>{@code _adb-tls-pairing._tcp} – the "Pair device with pairing code" screen is open</li>
 *     <li>{@code _adb._tcp} – classic {@code adb tcpip 5555} mode</li>
 * </ul>
 * All callbacks are delivered on the main thread.
 */
public class AdbDiscovery {

    private static final String TAG = "AdbDiscovery";

    public enum Kind {
        CONNECT("_adb-tls-connect._tcp"),
        PAIRING("_adb-tls-pairing._tcp"),
        LEGACY("_adb._tcp");

        final String type;

        Kind(String type) {
            this.type = type;
        }

        static Kind fromType(String type) {
            if (type == null) {
                return null;
            }
            for (Kind kind : values()) {
                if (type.startsWith(kind.type)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public static final class Service {
        public final String name;
        public final Kind kind;
        public final String host;
        public final int port;

        Service(String name, Kind kind, String host, int port) {
            this.name = name;
            this.kind = kind;
            this.host = host;
            this.port = port;
        }

        public String address() {
            return host + ":" + port;
        }

        String key() {
            return kind + "/" + name;
        }
    }

    public interface Listener {
        void onServiceFound(Service service);

        void onServiceLost(Kind kind, String name);
    }

    private final Context context;
    private final NsdManager nsdManager;
    private final Listener listener;
    private final List<NsdManager.DiscoveryListener> discoveryListeners = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving = false;
    private volatile boolean running = false;
    private WifiManager.MulticastLock multicastLock;

    public AdbDiscovery(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public void start(Kind... kinds) {
        if (running || nsdManager == null) {
            return;
        }
        running = true;
        acquireMulticastLock();
        if (kinds == null || kinds.length == 0) {
            kinds = Kind.values();
        }
        for (Kind kind : kinds) {
            NsdManager.DiscoveryListener discoveryListener = createDiscoveryListener();
            try {
                nsdManager.discoverServices(kind.type, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
                discoveryListeners.add(discoveryListener);
            } catch (Exception e) {
                Log.w(TAG, "discoverServices " + kind.type + " failed", e);
            }
        }
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        for (NsdManager.DiscoveryListener discoveryListener : discoveryListeners) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
                // Not started or already stopped.
            }
        }
        discoveryListeners.clear();
        synchronized (resolveQueue) {
            resolveQueue.clear();
            resolving = false;
        }
        releaseMulticastLock();
    }

    private NsdManager.DiscoveryListener createDiscoveryListener() {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "start discovery failed " + serviceType + " code=" + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "stop discovery failed " + serviceType + " code=" + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "discovery started " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "discovery stopped " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                enqueueResolve(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Kind kind = Kind.fromType(normalizeType(serviceInfo.getServiceType()));
                if (kind != null && running) {
                    String name = serviceInfo.getServiceName();
                    ThreadUtils.post(() -> listener.onServiceLost(kind, name));
                }
            }
        };
    }

    private void enqueueResolve(NsdServiceInfo info) {
        synchronized (resolveQueue) {
            resolveQueue.add(info);
        }
        resolveNext();
    }

    /** Resolves one service at a time (older NsdManager rejects concurrent resolves). */
    @SuppressWarnings("deprecation")
    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (resolveQueue) {
            if (resolving || !running) {
                return;
            }
            next = resolveQueue.poll();
            if (next == null) {
                return;
            }
            resolving = true;
        }
        try {
            nsdManager.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "resolve failed " + serviceInfo.getServiceName() + " code=" + errorCode);
                    finishResolve();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    Kind kind = Kind.fromType(normalizeType(serviceInfo.getServiceType()));
                    String host = pickHost(serviceInfo);
                    if (kind != null && host != null && running) {
                        Service service = new Service(serviceInfo.getServiceName(), kind, host,
                                serviceInfo.getPort());
                        Log.i(TAG, "resolved " + service.key() + " -> " + service.address());
                        ThreadUtils.post(() -> {
                            if (running) {
                                listener.onServiceFound(service);
                            }
                        });
                    }
                    finishResolve();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "resolveService failed", e);
            finishResolve();
        }
    }

    private void finishResolve() {
        synchronized (resolveQueue) {
            resolving = false;
        }
        resolveNext();
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return null;
        }
        // NsdManager reports e.g. "._adb-tls-connect._tcp" or "_adb-tls-connect._tcp."
        String t = type;
        while (t.startsWith(".")) {
            t = t.substring(1);
        }
        return t;
    }

    @SuppressWarnings("deprecation")
    private static String pickHost(NsdServiceInfo info) {
        List<InetAddress> addresses = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                addresses.addAll(info.getHostAddresses());
            } catch (Throwable ignored) {
            }
        }
        if (info.getHost() != null) {
            addresses.add(info.getHost());
        }
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
        }
        for (InetAddress address : addresses) {
            if (address != null && !address.isLinkLocalAddress()) {
                return "[" + address.getHostAddress() + "]";
            }
        }
        return null;
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("scrcpy-adb-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "multicast lock unavailable", e);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {
        }
        multicastLock = null;
    }
}
