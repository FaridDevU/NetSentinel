package com.netsentinel.service;

import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NetworkService {

    public List<Map<String, String>> getLocalNetworks() throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp() || ni.isLoopback()) continue;

            String displayName = ni.getDisplayName().toLowerCase();
            if (displayName.contains("hyper-v") || displayName.contains("wsl")
                    || displayName.contains("virtual") || displayName.contains("tunnel")
                    || displayName.contains("loopback") || displayName.contains("pseudo")) {
                continue;
            }

            for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                if (!(addr.getAddress() instanceof Inet4Address)) continue;
                String ip = addr.getAddress().getHostAddress();
                if (ip.startsWith("169.254")) continue;

                int prefixLen = addr.getNetworkPrefixLength();
                String subnet = calculateSubnet(ip, prefixLen);

                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("name", ni.getDisplayName());
                entry.put("ip", ip);
                entry.put("subnet", subnet + "/" + prefixLen);
                result.add(entry);
            }
        }
        return result;
    }

    private String calculateSubnet(String ip, int prefixLen) {
        String[] parts = ip.split("\\.");
        int ipInt = (Integer.parseInt(parts[0]) << 24)
                  | (Integer.parseInt(parts[1]) << 16)
                  | (Integer.parseInt(parts[2]) << 8)
                  | Integer.parseInt(parts[3]);
        int mask = prefixLen == 0 ? 0 : (0xFFFFFFFF << (32 - prefixLen));
        int network = ipInt & mask;
        return ((network >> 24) & 0xFF) + "."
             + ((network >> 16) & 0xFF) + "."
             + ((network >> 8) & 0xFF) + "."
             + (network & 0xFF);
    }
}
