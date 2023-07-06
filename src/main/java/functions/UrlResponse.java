package functions;

import com.google.api.client.http.HttpResponseException;

import javax.net.ssl.SSLException;
import java.net.SocketException;
import java.net.UnknownHostException;

public class UrlResponse {

    com.google.api.client.http.HttpResponse urlResponse;
    Exception exception;

    public UrlResponse executeRequest(com.google.api.client.http.HttpRequest urlRequest) {

        UrlResponse response = new UrlResponse();
        try {
            response.urlResponse = urlRequest.execute();
        } catch (HttpResponseException | SSLException | SocketException | UnknownHostException e) {
            response.exception = e;
        } catch (Exception e) {
            response.exception = e;
        }
        return response;
    }
}
