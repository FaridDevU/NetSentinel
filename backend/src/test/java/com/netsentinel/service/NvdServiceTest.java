package com.netsentinel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NvdServiceTest {

    private final NvdService service = new NvdService();

    @Test
    void parseaRespuestaConMetricaV31() {
        String body = """
                {
                  "vulnerabilities": [
                    {
                      "cve": {
                        "id": "CVE-2024-1234",
                        "published": "2024-01-15T00:00:00",
                        "descriptions": [{"lang": "en", "value": "Vulnerabilidad de prueba"}],
                        "metrics": {
                          "cvssMetricV31": [{
                            "cvssData": {"baseScore": 8.8, "vectorString": "CVSS:3.1/AV:N"}
                          }]
                        }
                      }
                    }
                  ]
                }
                """;

        List<NvdService.CveCacheData> result = service.parseCveResponse(body);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cveId()).isEqualTo("CVE-2024-1234");
        assertThat(result.get(0).cvssScore()).isEqualTo(8.8);
        assertThat(result.get(0).cvssVector()).isEqualTo("CVSS:3.1/AV:N");
        assertThat(result.get(0).description()).isEqualTo("Vulnerabilidad de prueba");
        assertThat(result.get(0).nvdUrl()).contains("CVE-2024-1234");
    }

    @Test
    void parseaRespuestaCaeABackV30CuandoV31Ausente() {
        String body = """
                {
                  "vulnerabilities": [
                    {
                      "cve": {
                        "id": "CVE-2020-0001",
                        "descriptions": [{"lang": "en", "value": "Solo V3.0"}],
                        "metrics": {
                          "cvssMetricV30": [{
                            "cvssData": {"baseScore": 5.5, "vectorString": "CVSS:3.0/AV:L"}
                          }]
                        }
                      }
                    }
                  ]
                }
                """;

        List<NvdService.CveCacheData> result = service.parseCveResponse(body);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cvssScore()).isEqualTo(5.5);
    }

    @Test
    void respuestaVaciaDevuelveListaVacia() {
        String body = "{\"vulnerabilities\":[]}";
        assertThat(service.parseCveResponse(body)).isEmpty();
    }

    @Test
    void respuestaSinCampoVulnerabilitiesDevuelveListaVacia() {
        String body = "{\"resultsPerPage\":0}";
        assertThat(service.parseCveResponse(body)).isEmpty();
    }

    @Test
    void cveSinIdSeIgnora() {
        String body = """
                {
                  "vulnerabilities": [
                    { "cve": { "descriptions": [{"value": "sin id"}] } },
                    { "cve": { "id": "CVE-2022-9999", "descriptions": [] } }
                  ]
                }
                """;

        List<NvdService.CveCacheData> result = service.parseCveResponse(body);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cveId()).isEqualTo("CVE-2022-9999");
    }

    @Test
    void jsonInvalidoDevuelveListaVacia() {
        assertThat(service.parseCveResponse("no es json")).isEmpty();
    }
}
