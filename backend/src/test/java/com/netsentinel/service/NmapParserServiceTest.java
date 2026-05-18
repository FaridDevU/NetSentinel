package com.netsentinel.service;

import com.netsentinel.entity.NetworkHost;
import com.netsentinel.entity.NetworkPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NmapParserServiceTest {

    private NmapParserService parser;

    @BeforeEach
    void setUp() {
        parser = new NmapParserService();
    }

    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private String minimal(String hostXml) {
        return XML_HEADER +
               "<nmaprun scanner=\"nmap\">\n" +
               hostXml +
               "</nmaprun>";
    }

    private String hostUp(String ip, String portsXml) {
        return "<host>\n" +
               "  <status state=\"up\" reason=\"echo-reply\"/>\n" +
               "  <address addr=\"" + ip + "\" addrtype=\"ipv4\"/>\n" +
               portsXml +
               "</host>\n";
    }

    private String openPort(int port, String service) {
        return "<ports><port protocol=\"tcp\" portid=\"" + port + "\">" +
               "<state state=\"open\" reason=\"syn-ack\"/>" +
               "<service name=\"" + service + "\"/>" +
               "</port></ports>";
    }

    @Test
    void xmlNulo_retornaListaVacia() {
        assertThat(parser.parse(null, null)).isEmpty();
    }

    @Test
    void xmlVacio_retornaListaVacia() {
        assertThat(parser.parse("", null)).isEmpty();
        assertThat(parser.parse("   ", null)).isEmpty();
    }

    @Test
    void xmlMalformado_retornaListaVacia() {
        assertThat(parser.parse("<esto no es xml valido<<<", null)).isEmpty();
    }

    @Test
    void hostDown_noSeIncluye() {
        String xml = minimal(
            "<host>\n" +
            "  <status state=\"down\" reason=\"no-response\"/>\n" +
            "  <address addr=\"192.168.1.1\" addrtype=\"ipv4\"/>\n" +
            "</host>\n"
        );

        assertThat(parser.parse(xml, null)).isEmpty();
    }

    @Test
    void hostSinIp_noSeIncluye() {
        String xml = minimal(
            "<host>\n" +
            "  <status state=\"up\" reason=\"echo-reply\"/>\n" +
            "  <address addr=\"AA:BB:CC:DD:EE:FF\" addrtype=\"mac\"/>\n" +
            "</host>\n"
        );

        assertThat(parser.parse(xml, null)).isEmpty();
    }

    @Test
    void hostSimple_parseaIpYCantidadDePuertos() {
        String xml = minimal(hostUp("10.0.0.1", openPort(80, "http")));

        List<NetworkHost> hosts = parser.parse(xml, null);

        assertThat(hosts).hasSize(1);
        assertThat(hosts.get(0).getIp()).isEqualTo("10.0.0.1");
        assertThat(hosts.get(0).getPorts()).hasSize(1);
    }

    @Test
    void hostConHostname_parseaHostname() {
        String xml = minimal(
            "<host>\n" +
            "  <status state=\"up\"/>\n" +
            "  <address addr=\"192.168.1.1\" addrtype=\"ipv4\"/>\n" +
            "  <hostnames><hostname name=\"router.local\" type=\"PTR\"/></hostnames>\n" +
            openPort(443, "https") +
            "</host>\n"
        );

        NetworkHost host = parser.parse(xml, null).get(0);
        assertThat(host.getHostname()).isEqualTo("router.local");
    }

    @Test
    void hostConMac_parseaMacAddressYVendor() {
        String xml = minimal(
            "<host>\n" +
            "  <status state=\"up\"/>\n" +
            "  <address addr=\"192.168.1.10\" addrtype=\"ipv4\"/>\n" +
            "  <address addr=\"AA:BB:CC:DD:EE:FF\" addrtype=\"mac\" vendor=\"Apple\"/>\n" +
            "</host>\n"
        );

        NetworkHost host = parser.parse(xml, null).get(0);
        assertThat(host.getMacAddress()).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(host.getVendor()).isEqualTo("Apple");
    }

    @Test
    void hostConOsmatch_parseaSistemaOperativo() {
        String xml = minimal(
            "<host>\n" +
            "  <status state=\"up\"/>\n" +
            "  <address addr=\"192.168.1.5\" addrtype=\"ipv4\"/>\n" +
            "  <os><osmatch name=\"Linux 5.4\" accuracy=\"95\"/></os>\n" +
            "</host>\n"
        );

        NetworkHost host = parser.parse(xml, null).get(0);
        assertThat(host.getOs()).isEqualTo("Linux 5.4");
    }

    @Test
    void puertoCerrado_seIncluye() {
        String xml = minimal(
            "<host>\n" +
            "  <status state=\"up\"/>\n" +
            "  <address addr=\"10.0.0.2\" addrtype=\"ipv4\"/>\n" +
            "  <ports><port protocol=\"tcp\" portid=\"22\">" +
            "    <state state=\"closed\"/>" +
            "    <service name=\"ssh\"/>" +
            "  </port></ports>\n" +
            "</host>\n"
        );

        NetworkHost host = parser.parse(xml, null).get(0);
        assertThat(host.getPorts()).hasSize(1);
        assertThat(host.getPorts().get(0).getState()).isEqualTo("closed");
    }

    @Test
    void puerto_parseaNumeroProtocoloYServicio() {
        String xml = minimal(hostUp("192.168.1.1", openPort(22, "ssh")));

        NetworkPort port = parser.parse(xml, null).get(0).getPorts().get(0);
        assertThat(port.getPortNumber()).isEqualTo(22);
        assertThat(port.getProtocol()).isEqualTo("tcp");
        assertThat(port.getService()).isEqualTo("ssh");
        assertThat(port.getState()).isEqualTo("open");
    }

    @Test
    void puerto_construyeVersionCompleta() {
        String xml = minimal(
            "<host>\n" +
            "  <status state=\"up\"/>\n" +
            "  <address addr=\"10.0.0.3\" addrtype=\"ipv4\"/>\n" +
            "  <ports><port protocol=\"tcp\" portid=\"80\">" +
            "    <state state=\"open\"/>" +
            "    <service name=\"http\" product=\"Apache httpd\" version=\"2.4.51\" extrainfo=\"(Ubuntu)\"/>" +
            "  </port></ports>\n" +
            "</host>\n"
        );

        NetworkPort port = parser.parse(xml, null).get(0).getPorts().get(0);
        assertThat(port.getVersion()).isEqualTo("Apache httpd 2.4.51 (Ubuntu)");
    }

    @Test
    void puerto_versionParcial_soloProducto() {
        String xml = minimal(
            "<host>\n" +
            "  <status state=\"up\"/>\n" +
            "  <address addr=\"10.0.0.4\" addrtype=\"ipv4\"/>\n" +
            "  <ports><port protocol=\"tcp\" portid=\"443\">" +
            "    <state state=\"open\"/>" +
            "    <service name=\"https\" product=\"nginx\" version=\"\" extrainfo=\"\"/>" +
            "  </port></ports>\n" +
            "</host>\n"
        );

        NetworkPort port = parser.parse(xml, null).get(0).getPorts().get(0);
        assertThat(port.getVersion()).isEqualTo("nginx");
    }

    @Test
    void puertoSinVersion_versionEsNull() {
        String xml = minimal(hostUp("10.0.0.5", openPort(3306, "mysql")));

        NetworkPort port = parser.parse(xml, null).get(0).getPorts().get(0);
        assertThat(port.getVersion()).isNull();
    }

    @Test
    void dosHostsUp_parsea2Hosts() {
        String xml = minimal(
            hostUp("192.168.1.1", openPort(80, "http")) +
            hostUp("192.168.1.2", openPort(22, "ssh"))
        );

        List<NetworkHost> hosts = parser.parse(xml, null);
        assertThat(hosts).hasSize(2);
        assertThat(hosts).extracting(NetworkHost::getIp)
                .containsExactlyInAnyOrder("192.168.1.1", "192.168.1.2");
    }

    @Test
    void hostDownMezclado_soloRetornaHostsUp() {
        String xml = minimal(
            hostUp("192.168.1.1", openPort(80, "http")) +
            "<host>\n" +
            "  <status state=\"down\"/>\n" +
            "  <address addr=\"192.168.1.2\" addrtype=\"ipv4\"/>\n" +
            "</host>\n"
        );

        assertThat(parser.parse(xml, null)).hasSize(1);
        assertThat(parser.parse(xml, null).get(0).getIp()).isEqualTo("192.168.1.1");
    }

    @Test
    void xmlConDoctypeDeNmap_parseaSinError() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                     "<!DOCTYPE nmaprun>\n" +
                     "<nmaprun scanner=\"nmap\">\n" +
                     hostUp("10.10.0.1", openPort(8080, "http-proxy")) +
                     "</nmaprun>";

        List<NetworkHost> hosts = parser.parse(xml, null);
        assertThat(hosts).hasSize(1);
        assertThat(hosts.get(0).getIp()).isEqualTo("10.10.0.1");
    }

    @Test
    void hostSinPuertos_listaPortsVacia() {
        String xml = minimal(
            "<host>\n" +
            "  <status state=\"up\"/>\n" +
            "  <address addr=\"172.16.0.1\" addrtype=\"ipv4\"/>\n" +
            "</host>\n"
        );

        NetworkHost host = parser.parse(xml, null).get(0);
        assertThat(host.getPorts()).isEmpty();
    }
}
