package com.netsentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netsentinel.dto.AgentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class AgentServiceTest {

    private static final String URL = "https://api.anthropic.com/v1/messages";

    private ScanService scanService;
    private NetworkService networkService;
    private MockRestServiceServer server;
    private AgentService service;

    @BeforeEach
    void setUp() {
        scanService = mock(ScanService.class);
        networkService = mock(NetworkService.class);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new AgentService(scanService, networkService, new ObjectMapper(), builder);
    }

    private AgentRequest request() {
        return new AgentRequest("key-123", List.of(new AgentRequest.Message("user", "hola")));
    }

    @Test
    void apiKeyInvalidaEmiteErrorYCompleta() {
        server.expect(requestTo(URL)).andRespond(withUnauthorizedRequest());

        RecordingEmitter emitter = new RecordingEmitter();
        service.streamChat(request(), emitter);

        assertThat(emitter.buffer.toString()).contains("error").contains("API key invalida");
        assertThat(emitter.completed).isTrue();
        server.verify();
    }

    @Test
    void demasiadasPeticionesEmite429() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        RecordingEmitter emitter = new RecordingEmitter();
        service.streamChat(request(), emitter);

        assertThat(emitter.buffer.toString()).contains("error").contains("429");
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void errorDeServidorEmiteMensajeTemporal() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        RecordingEmitter emitter = new RecordingEmitter();
        service.streamChat(request(), emitter);

        assertThat(emitter.buffer.toString()).contains("error").contains("temporal");
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void respuestaDeTextoEmiteTextoYDone() {
        String body = """
                {
                  "content": [{"type": "text", "text": "Tu red se ve bien"}],
                  "stop_reason": "end_turn"
                }
                """;
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        RecordingEmitter emitter = new RecordingEmitter();
        service.streamChat(request(), emitter);

        String out = emitter.buffer.toString();
        assertThat(out).contains("Tu red se ve bien");
        assertThat(out).contains("done");
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void contentNoArrayEmiteErrorInesperado() {
        String body = "{\"content\": \"no es array\", \"stop_reason\": \"end_turn\"}";
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        RecordingEmitter emitter = new RecordingEmitter();
        service.streamChat(request(), emitter);

        assertThat(emitter.buffer.toString()).contains("error").contains("inesperada");
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void loopDeToolUseEjecutaHerramientaYContinua() throws Exception {
        when(networkService.getLocalNetworks()).thenReturn(List.of(Map.of("name", "eth0", "subnet", "192.168.0.0/24")));

        String first = """
                {
                  "content": [{"type": "tool_use", "id": "t1", "name": "detect_networks", "input": {}}],
                  "stop_reason": "tool_use"
                }
                """;
        String second = """
                {
                  "content": [{"type": "text", "text": "Encontre tu red"}],
                  "stop_reason": "end_turn"
                }
                """;
        server.expect(requestTo(URL)).andRespond(withSuccess(first, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(second, MediaType.APPLICATION_JSON));

        RecordingEmitter emitter = new RecordingEmitter();
        service.streamChat(request(), emitter);

        String out = emitter.buffer.toString();
        assertThat(out).contains("tool_use").contains("detect_networks");
        assertThat(out).contains("tool_result");
        assertThat(out).contains("Encontre tu red");
        assertThat(out).contains("done");
        assertThat(emitter.completed).isTrue();
        verify(networkService).getLocalNetworks();
        server.verify();
    }

    private static class RecordingEmitter extends SseEmitter {
        final StringBuilder buffer = new StringBuilder();
        boolean completed = false;

        @Override
        public void send(SseEmitter.SseEventBuilder builder) {
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                buffer.append(entry.getData());
            }
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}
