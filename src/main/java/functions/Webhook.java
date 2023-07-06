package functions;

import com.google.api.client.http.HttpHeaders;

public class Webhook {

    private String url;
    private HttpHeaders headers;
    private String body;
    private Integer timeout;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public void setHeaders(HttpHeaders headers) {
        this.headers = headers;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Integer getTimeout() {
        return this.timeout == null ? Integer.valueOf(0) : this.timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }
}
