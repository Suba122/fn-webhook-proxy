package functions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.net.ssl.SSLException;
import java.io.*;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TriggerTest {

    private WireMockServer wireMockServer;

    @Mock
    private HttpRequest httpRequest;

    @Mock
    private HttpResponse httpResponse;

    private Trigger trigger;
    private StringWriter stringWriter;

    @BeforeEach
    void setUp() throws IOException {

        MockitoAnnotations.openMocks(this);

        startServer();
        configureWireMock();

        stringWriter = new StringWriter();
        BufferedWriter writer = new BufferedWriter(stringWriter);
        trigger = new Trigger();

        when(httpRequest.getMethod()).thenReturn("POST");
        when(httpResponse.getWriter()).thenReturn(writer);
    }

    @Test
    @DisplayName("Valid Request and Response")
    void success_response() throws IOException {

        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{" +
                "\"url\":\""+wireMockServer.baseUrl()+"\", " +
                "\"headers\":{\"Content-Type\": \"application/json\"}, " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": {\"timeout\": 30}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(200);
        assertEquals("<html><body>Successful response</body></html>", stringWriter.toString());
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/")));
    }

    @ParameterizedTest
    @MethodSource("headersProvider")
    @DisplayName("Test the Request, calls the URL with the given headers")
    void HeaderTest(Map<String, String> headers) throws IOException {

        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{" +
                "\"url\":\""+wireMockServer.baseUrl()+"\", " +
                "\"headers\":" + new Gson().toJson(headers) + ", " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": {\"timeout\": 30}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(200);

        RequestPatternBuilder requestPatternBuilder = WireMock.postRequestedFor(WireMock.urlEqualTo("/"));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            requestPatternBuilder.withHeader(header.getKey(), WireMock.equalTo(header.getValue()));
        }
        WireMock.verify(1, requestPatternBuilder);
    }

    @ParameterizedTest
    @MethodSource("bodyProvider")
    @DisplayName("Test the Request, calls the URL with the given body")
    void BodyTest(String body) throws IOException {
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{" +
                "\"url\":\""+wireMockServer.baseUrl()+"\", " +
                "\"headers\":{\"Content-Type\": \"application/json\"}, " +
                "\"body\": \"" + Base64.getEncoder().encodeToString(body.getBytes()) + "\", " +
                "\"options\": {\"timeout\": 30}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(200);

        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/"))
                .withRequestBody(WireMock.equalTo(body)));
    }

    @Test
    @DisplayName("Requested URL giving 500 Internal Server")
    void failure_response() throws IOException {

        String url = wireMockServer.baseUrl()+"/throw-exception";

        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\":\""+url+"\", " +
                "\"headers\":{\"Content-Type\": \"application/json\"}, " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": {\"timeout\": 30}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: 500 Server Error\n" +
                "POST "+url+"\n" +
                "<html><body>Internal Server Error</body></html>", stringWriter.toString());
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/throw-exception")));
    }

    @Test
    @DisplayName("SSL Exception while Requesting URL")
    void url_request_throws_sslException() throws NoSuchMethodException {

        Method writeResponse = Trigger.class.getDeclaredMethod("writeResponse", UrlResponse.class, HttpResponse.class);
        writeResponse.setAccessible(true);

        UrlResponse urlResponse = new UrlResponse();
        urlResponse.exception = new SSLException("SslException");

        assertDoesNotThrow(() -> writeResponse.invoke(trigger, urlResponse, httpResponse));
        verify(httpResponse).setStatusCode(400);
        assertEquals("Http request failed: SslException", stringWriter.toString());
    }

    @Test
    @DisplayName("Unknown Exception thrown by urlRequest execution")
    public void url_request_execution_throws_unknown_exception() throws Exception {

        com.google.api.client.http.HttpRequest urlRequest = mock(com.google.api.client.http.HttpRequest.class);

        when(urlRequest.execute()).thenThrow(new RuntimeException("Test exception"));

        UrlResponse response = new UrlResponse().executeRequest(urlRequest);
        assertNotNull(response.exception);
        assertEquals("Test exception", response.exception.getMessage());
    }


    @Test
    @DisplayName("Unknown Exception during the process")
    public void process_getting_unknownException() throws Exception {

        when(httpRequest.getReader()).thenThrow(new RuntimeException("Unknown exception"));

        trigger.service(httpRequest, httpResponse);
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Unknown exception", stringWriter.toString());
    }

    @Test
    @DisplayName("Getting fault response from the requested url")
    void fault_response() throws IOException {
        String url = wireMockServer.baseUrl()+"/fault-response";
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{" +
                "\"url\":\""+url+"\", " +
                "\"headers\":{\"Content-Type\": \"application/json\"}, " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": {\"timeout\": 30}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Premature EOF", stringWriter.toString());
    }

    @Test
    @DisplayName("Invalid Coming Request")
    void invalid_request() throws IOException {
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("Invalid JSON")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Invalid Request Body", stringWriter.toString());
    }

    @Test
    @DisplayName("Request body without url parameter")
    void request_without_url() throws IOException {
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader(
                "{\"body\":\"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\"}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Url is missing", stringWriter.toString());
    }

    @Test
    @DisplayName("Request body with invalid url")
    void request_with_invalid_url() throws IOException {
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\":\"invalid url\"," +
                "\"body\":\"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\"}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Invalid Request Url", stringWriter.toString());
    }

    @Test
    @DisplayName("Request without body parameter")
    void request_without_body() throws IOException {
        String url = wireMockServer.baseUrl()+"/throw-exception";
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\":\""+url+"\"}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Body is missing", stringWriter.toString());
    }

    @Test
    @DisplayName("Request with invalid body (Not a json)")
    void request_with_invalid_body() throws IOException {
        String url = wireMockServer.baseUrl()+"/throw-exception";
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\":\""+url+"\"," +
                "\"body\":\"Invalid request body\"}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Invalid Request Body", stringWriter.toString());
    }

    @Test
    @DisplayName("Request with invalid timeout (Not an Integer)")
    void request_with_invalid_timeout() throws IOException {
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\": \""+wireMockServer.baseUrl()+"\", " +
                "\"headers\":{\"Content-Type\": \"application/json\"}, " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": {\"timeout\": \"Invalid\"}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Invalid Timeout Value", stringWriter.toString());
    }

    @Test
    @DisplayName("Request with lesser timeout value")
    void request_with_lesser_timeout_value() throws IOException {
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\": \""+wireMockServer.baseUrl()+"\", " +
                "\"headers\":{\"Content-Type\": \"application/json\"}, " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": {\"timeout\": -1}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Invalid Timeout Value", stringWriter.toString());
    }

    @Test
    @DisplayName("Request with greater timeout value")
    void request_with_greater_timeout_value() throws IOException {
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\": \""+wireMockServer.baseUrl()+"\", " +
                "\"headers\":{\"Content-Type\": \"application/json\"}, " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": {\"timeout\": 5000}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Invalid Timeout Value", stringWriter.toString());
    }

    @Test
    @DisplayName("Request with invalid options (Not a json)")
    void request_with_invalid_options() throws IOException {
        String url = wireMockServer.baseUrl()+"/throw-exception";
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\": \""+url+"\", " +
                "\"headers\":{\"Content-Type\": \"application/json\"}, \"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": \"invalid json\"}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Invalid Request Options", stringWriter.toString());
    }

    @Test
    @DisplayName("Request without headers")
    void request_without_headers() throws IOException {

        String url = wireMockServer.baseUrl()+"/throw-exception";
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\":\""+url+"\", " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": {\"timeout\": 30}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        assertEquals("Http request failed: 500 Server Error\n" +
                "POST "+url+"\n" +
                "<html><body>Internal Server Error</body></html>", stringWriter.toString());
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/throw-exception")));
    }

    @Test
    @DisplayName("Request with empty headers")
    void request_with_empty_headers() throws IOException {

        String url = wireMockServer.baseUrl()+"/throw-exception";
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\":\""+url+"\", " +
                "\"headers\": {}, " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": {\"timeout\": 30}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        assertEquals("Http request failed: 500 Server Error\n" +
                "POST "+url+"\n" +
                "<html><body>Internal Server Error</body></html>", stringWriter.toString());
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/throw-exception")));
    }

    @Test
    @DisplayName("Request body with invalid headers (Not a json)")
    void request_with_invalid_headers() throws IOException {
        String url = wireMockServer.baseUrl()+"/throw-exception";
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\": \""+url+"\", " +
                "\"headers\":\"Invalid header\", " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\", " +
                "\"options\": {\"retry\": 3, \"timeout\": 30}}")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Invalid Request Headers", stringWriter.toString());
    }

    @Test
    @DisplayName("Request body without options")
    void request_without_options() throws IOException {

        String url = wireMockServer.baseUrl()+"/throw-exception";
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\":\""+url+"\", " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\" }")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        assertEquals("Http request failed: 500 Server Error\n" +
                "POST "+url+"\n" +
                "<html><body>Internal Server Error</body></html>", stringWriter.toString());
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/throw-exception")));
    }

    @Test
    @DisplayName("Request body with options")
    void request_with_empty_options() throws IOException {

        String url = wireMockServer.baseUrl()+"/throw-exception";
        when(httpRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"url\":\""+url+"\", " +
                "\"body\": \"ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0=\"," +
                "\"options\": {} }")));

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        assertEquals("Http request failed: 500 Server Error\n" +
                "POST "+url+"\n" +
                "<html><body>Internal Server Error</body></html>", stringWriter.toString());
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/throw-exception")));
    }

    @Test
    @DisplayName("Coming Request with invalid HTTP Method")
    void request_with_invalid_http_method() {

        when(httpRequest.getMethod()).thenReturn("GET");

        assertDoesNotThrow(() -> trigger.service(httpRequest, httpResponse));
        verify(httpResponse).setStatusCode(500);
        assertEquals("Http request failed: Method not allowed", stringWriter.toString());
    }

    private void startServer() {
        WireMockConfiguration config = WireMockConfiguration.options().dynamicPort();
        this.wireMockServer = new WireMockServer(config);
        wireMockServer.start();
        WireMock.configureFor(wireMockServer.port());
    }

    private void configureWireMock() {
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withBody("<html><body>Successful response</body></html>")));

        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/throw-exception"))
                .willReturn(WireMock.aResponse()
                        .withStatus(500)
                        .withBody("<html><body>Internal Server Error</body></html>")));

        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/http-response-exception"))
                .willReturn(WireMock.aResponse()
                        .withStatus(500)
                        .withBody("Bad Request")));

        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/fault-response"))
                .willReturn(WireMock.aResponse()
                        .withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
    }

    static Stream<Arguments> headersProvider() {
        return Stream.of(
                Arguments.of(Map.of("Content-Type", "application/json", "Accept", "application/json")),
                Arguments.of(Map.of("Content-Type", "application/xml", "Accept", "application/xml")),
                Arguments.of(Map.of("Content-Type", "text/plain"))
        );
    }

    static Stream<Arguments> bodyProvider() {
        return Stream.of(
                Arguments.of("ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04iCn0="),
                Arguments.of("ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04yIgp9"),
                Arguments.of("ewogICAgImJvZHkiIDogImNsb3VkRlVOQ1RJT04zIgp9")
        );
    }

}