package com.netsentinel.service;

import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.NetworkPort;
import com.netsentinel.entity.ScanJob;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NmapParserService {

    private static final Pattern HOST_BLOCK_SPLITTER = Pattern.compile("Nmap scan report for ");
    private static final Pattern IP_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static final Pattern OS_PATTERN = Pattern.compile("OS details: ([^\n]+)");
    private static final Pattern PORT_LINE_PATTERN = Pattern.compile("(\\d+)/(tcp|udp)\\s+(\\w+)\\s+(.*)");
    private static final Pattern MAC_PATTERN = Pattern.compile("MAC Address: ([0-9A-Fa-f:]+) \\(([^)]+)\\)");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^([^\\d]+?)\\s+(\\d+\\.\\d+[.\\d]*)");

    public List<NetworkHost> parse(String rawOutput, ScanJob scanJob) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return List.of();
        }

        List<NetworkHost> hosts = new ArrayList<>();
        String[] blocks = HOST_BLOCK_SPLITTER.split(rawOutput);

        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            NetworkHost host = parseHostBlock(block, scanJob);
            if (host != null) {
                hosts.add(host);
            }
        }

        return hosts;
    }

    private NetworkHost parseHostBlock(String block, ScanJob scanJob) {
        NetworkHost host = new NetworkHost();
        host.setScanJob(scanJob);

        String firstToken = block.split("\\s")[0];
        if (firstToken.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            host.setIp(firstToken);
        } else {
            host.setHostname(firstToken);
            Matcher ipMatcher = IP_PATTERN.matcher(block);
            if (ipMatcher.find()) {
                host.setIp(ipMatcher.group(1));
            }
        }

        if (host.getIp() == null || host.getIp().isBlank()) {
            return null;
        }

        Matcher osMatcher = OS_PATTERN.matcher(block);
        if (osMatcher.find()) {
            host.setOs(osMatcher.group(1).trim());
        }

        Matcher macMatcher = MAC_PATTERN.matcher(block);
        if (macMatcher.find()) {
            host.setMacAddress(macMatcher.group(1));
            host.setVendor(macMatcher.group(2));
        }

        List<NetworkPort> ports = parsePorts(block, host);
        host.setPorts(ports);

        return host;
    }

    private List<NetworkPort> parsePorts(String block, NetworkHost host) {
        List<NetworkPort> ports = new ArrayList<>();
        Matcher portMatcher = PORT_LINE_PATTERN.matcher(block);

        while (portMatcher.find()) {
            NetworkPort port = new NetworkPort();
            port.setHost(host);
            port.setPortNumber(Integer.parseInt(portMatcher.group(1)));
            port.setProtocol(portMatcher.group(2));
            port.setState(portMatcher.group(3));

            String serviceInfo = portMatcher.group(4).trim();
            if (!serviceInfo.isBlank()) {
                Matcher versionMatcher = VERSION_PATTERN.matcher(serviceInfo);
                if (versionMatcher.find()) {
                    port.setService(versionMatcher.group(1).trim());
                    port.setVersion(versionMatcher.group(2).trim());
                } else {
                    port.setService(serviceInfo);
                }
            }

            ports.add(port);
        }

        return ports;
    }
}
