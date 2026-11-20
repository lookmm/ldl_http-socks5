package com.liuyunli.proxy;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import java.util.HashMap;
import java.net.InterfaceAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.Notification;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

/**
 * libbox 平台回调的 Android 侧实现。
 * v2：补齐 getInterfaces / registerMyInterface / 默认接口监视，
 * 否则 sing-box 判定 "missing default interface"，所有出站拨号报
 * "no available network interface"。
 */
public class PlatformImpl implements PlatformInterface {

    private final ProxyVpnService service;

    public PlatformImpl(ProxyVpnService service) {
        this.service = service;
    }

    @Override
    public void autoDetectInterfaceControl(int fd) throws Exception {
        if (!service.protectFd(fd)) {
            throw new IllegalStateException("protect fd failed: " + fd);
        }
    }

    @Override
    public int openTun(TunOptions options) throws Exception {
        return service.establishTun(options);
    }

    @Override
    public void sendNotification(Notification notification) {
    }

    @Override
    public void cancelNotification(String identifier, int typeID) {
    }

    @Override
    public void clearDNSCache() {
    }

    @Override
    public void checkPlatformShell() throws Exception {
    }

    @Override
    public void registerMyInterface(String name) {
        service.setMyInterfaceName(name);
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        final List<io.nekohasekai.libbox.NetworkInterface> list = new ArrayList<>();
        final String myName = service.getMyInterfaceName();
        final HashMap<String, LinkProperties> lpMap = new HashMap<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) service.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network network : cm.getAllNetworks()) {
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp != null && lp.getInterfaceName() != null) lpMap.put(lp.getInterfaceName(), lp);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Enumeration<java.net.NetworkInterface> all = java.net.NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                java.net.NetworkInterface nif = all.nextElement();
                try {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    String name = nif.getName();
                    if (name == null) continue;
                    if (myName != null && name.equals(myName)) continue;
                    if (name.startsWith("tun")) continue;

                    List<String> addrs = new ArrayList<>();
                    for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        InetAddress a = ia.getAddress();
                        if (a == null) continue;
                        short prefix = ia.getNetworkPrefixLength();
                        if (prefix <= 0) continue;
                        if (a instanceof Inet4Address) {
                            addrs.add(a.getHostAddress() + "/" + prefix);
                        } else if (a instanceof Inet6Address) {
                            Inet6Address v6 = (Inet6Address) a;
                            if (v6.isLinkLocalAddress() || v6.isLoopbackAddress()) continue;
                            addrs.add(a.getHostAddress() + "/" + prefix);
                        }
                    }
                    if (addrs.isEmpty()) continue;

                    io.nekohasekai.libbox.NetworkInterface itf = new io.nekohasekai.libbox.NetworkInterface();
                    itf.setName(name);
                    itf.setIndex(nif.getIndex());
                    int mtu = 1500;
                    try {
                        if (nif.getMTU() > 0) mtu = nif.getMTU();
                    } catch (Throwable ignored) {
                    }
                    itf.setMTU(mtu);
                    int flags = 0x01; // UP
                    try {
                        if (nif.supportsMulticast()) flags |= 0x20;
                        if (nif.isPointToPoint()) flags |= 0x10;
                    } catch (Throwable ignored) {
                    }
                    flags |= 0x40; // RUNNING
                    itf.setFlags(flags);
                    itf.setType(typeOf(name));
                    itf.setMetered(isMeteredName(name));
                    LinkProperties lp = lpMap.get(name);
                    if (lp != null) {
                        List<String> gateways = new ArrayList<>();
                        List<String> dnsServers = new ArrayList<>();
                        for (RouteInfo route : lp.getRoutes()) {
                            if (!route.isDefaultRoute()) continue;
                            InetAddress gw = route.getGateway();
                            if (gw == null || gw.isAnyLocalAddress() || gw.isLoopbackAddress()) continue;
                            gateways.add(gw.getHostAddress());
                        }
                        for (InetAddress dns : lp.getDnsServers()) {
                            if (dns != null) dnsServers.add(dns.getHostAddress());
                        }
                        if (!gateways.isEmpty()) itf.setGateway(new StringListIterator(gateways));
                        if (!dnsServers.isEmpty()) itf.setDNSServer(new StringListIterator(dnsServers));
                    }
                    itf.setAddresses(new StringListIterator(addrs));
                    list.add(itf);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return new NetworkInterfaceIterator() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < list.size();
            }

            @Override
            public io.nekohasekai.libbox.NetworkInterface next() {
                return list.get(index++);
            }
        };
    }

    private static int typeOf(String name) {
        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
            return Libbox.InterfaceTypeWIFI;
        }
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
                || name.startsWith("v4-") || name.startsWith("clat")) {
            return Libbox.InterfaceTypeCellular;
        }
        if (name.startsWith("eth")) {
            return Libbox.InterfaceTypeEthernet;
        }
        return Libbox.InterfaceTypeOther;
    }

    private static boolean isMeteredName(String name) {
        return name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp");
    }

    private static final class StringListIterator implements StringIterator {
        private final List<String> values;
        private int index = 0;

        StringListIterator(List<String> values) {
            this.values = values;
        }

        @Override
        public boolean hasNext() {
            return index < values.size();
        }

        @Override
        public int len() {
            return values.size();
        }

        @Override
        public String next() {
            return values.get(index++);
        }
    }

    @Override
    public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress, int sourcePort,
                                               String destinationAddress, int destinationPort) {
        // libbox 包装层会直接解引用返回值，必须返回非 null 对象
        ConnectionOwner owner = new ConnectionOwner();
        owner.setUserId(android.os.Process.myUid());
        owner.setUserName("");
        owner.setProcessPath("");
        return owner;
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public WIFIState readWIFIState() {
        return null;
    }

    @Override
    public String lookupSFTPServer() {
        return null;
    }

    @Override
    public PlatformUser lookupUser(String username) {
        return null;
    }

    @Override
    public ShellSession openShellSession(PlatformUser user, String command, StringIterator args,
                                         String env, int termWidth, int termHeight) {
        return null;
    }

    @Override
    public String readSystemSSHHostKey() {
        return null;
    }

    @Override
    public BridgeSession createBridge(BridgeOptions options) {
        return null;
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        service.startDefaultInterfaceMonitor(listener);
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        service.closeDefaultInterfaceMonitor(listener);
    }

    @Override
    public void startNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public void closeNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public boolean usePlatformShell() {
        return false;
    }

    @Override
    public boolean usePlatformBridge() {
        return false;
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return true;
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Override
    public String tailscaleHostname() {
        return null;
    }
}
