package com.netsentinel.service;

import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.NetworkPort;
import com.netsentinel.entity.ScanJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class NmapParserService {

    private static final Logger log = LoggerFactory.getLogger(NmapParserService.class);

    private static final DocumentBuilderFactory XML_FACTORY;

    static {
        XML_FACTORY = DocumentBuilderFactory.newInstance();
        try {
            XML_FACTORY.setFeature("http://xml.org/sax/features/external-general-entities", false);
            XML_FACTORY.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            XML_FACTORY.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            XML_FACTORY.setXIncludeAware(false);
            XML_FACTORY.setExpandEntityReferences(false);
        } catch (Exception e) {
            LoggerFactory.getLogger(NmapParserService.class).warn("Could not harden XML factory: {}", e.getMessage());
        }
    }

    public List<NetworkHost> parse(String xmlOutput, ScanJob scanJob) {
        if (xmlOutput == null || xmlOutput.isBlank()) {
            return List.of();
        }

        try {
            DocumentBuilder builder = XML_FACTORY.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            Document doc = builder.parse(new ByteArrayInputStream(xmlOutput.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();

            List<NetworkHost> hosts = new ArrayList<>();
            NodeList hostNodes = doc.getElementsByTagName("host");

            for (int i = 0; i < hostNodes.getLength(); i++) {
                Element hostEl = (Element) hostNodes.item(i);
                NetworkHost host = parseHost(hostEl, scanJob);
                if (host != null) {
                    hosts.add(host);
                }
            }

            return hosts;

        } catch (Exception e) {
            log.error("Failed to parse nmap XML: {}", e.getMessage());
            return List.of();
        }
    }

    private NetworkHost parseHost(Element hostEl, ScanJob scanJob) {
        NodeList statusNodes = hostEl.getElementsByTagName("status");
        if (statusNodes.getLength() > 0) {
            String state = ((Element) statusNodes.item(0)).getAttribute("state");
            if (!"up".equals(state)) {
                return null;
            }
        }

        NetworkHost host = new NetworkHost();
        host.setScanJob(scanJob);

        NodeList addresses = hostEl.getElementsByTagName("address");
        for (int i = 0; i < addresses.getLength(); i++) {
            Element addr = (Element) addresses.item(i);
            String type = addr.getAttribute("addrtype");
            if ("ipv4".equals(type) || "ipv6".equals(type)) {
                host.setIp(addr.getAttribute("addr"));
            } else if ("mac".equals(type)) {
                host.setMacAddress(addr.getAttribute("addr"));
                String vendor = addr.getAttribute("vendor");
                if (!vendor.isBlank()) {
                    host.setVendor(vendor);
                }
            }
        }

        if (host.getIp() == null || host.getIp().isBlank()) {
            return null;
        }

        NodeList hostnameNodes = hostEl.getElementsByTagName("hostname");
        if (hostnameNodes.getLength() > 0) {
            String name = ((Element) hostnameNodes.item(0)).getAttribute("name");
            if (!name.isBlank()) {
                host.setHostname(name);
            }
        }

        NodeList osMatches = hostEl.getElementsByTagName("osmatch");
        if (osMatches.getLength() > 0) {
            String osName = ((Element) osMatches.item(0)).getAttribute("name");
            if (!osName.isBlank()) {
                host.setOs(osName);
            }
        }

        host.setPorts(parsePorts(hostEl, host));
        return host;
    }

    private List<NetworkPort> parsePorts(Element hostEl, NetworkHost host) {
        List<NetworkPort> ports = new ArrayList<>();

        NodeList portsContainers = hostEl.getElementsByTagName("ports");
        if (portsContainers.getLength() == 0) {
            return ports;
        }

        Element portsEl = (Element) portsContainers.item(0);
        NodeList portNodes = portsEl.getElementsByTagName("port");

        for (int i = 0; i < portNodes.getLength(); i++) {
            Element portEl = (Element) portNodes.item(i);

            NetworkPort port = new NetworkPort();
            port.setHost(host);

            String portId = portEl.getAttribute("portid");
            try {
                port.setPortNumber(Integer.parseInt(portId));
            } catch (NumberFormatException e) {
                log.warn("Invalid portid: {}", portId);
                continue;
            }
            port.setProtocol(portEl.getAttribute("protocol"));

            NodeList stateNodes = portEl.getElementsByTagName("state");
            if (stateNodes.getLength() > 0) {
                port.setState(((Element) stateNodes.item(0)).getAttribute("state"));
            }

            NodeList serviceNodes = portEl.getElementsByTagName("service");
            if (serviceNodes.getLength() > 0) {
                Element svc = (Element) serviceNodes.item(0);
                String name = svc.getAttribute("name");
                if (!name.isBlank()) {
                    port.setService(name);
                }
                String version = buildVersion(
                        svc.getAttribute("product"),
                        svc.getAttribute("version"),
                        svc.getAttribute("extrainfo")
                );
                if (!version.isBlank()) {
                    port.setVersion(version);
                }
            }

            ports.add(port);
        }

        return ports;
    }

    private String buildVersion(String product, String version, String extra) {
        StringBuilder sb = new StringBuilder();
        if (!product.isBlank()) sb.append(product);
        if (!version.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(version);
        }
        if (!extra.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(extra);
        }
        return sb.toString().trim();
    }
}
