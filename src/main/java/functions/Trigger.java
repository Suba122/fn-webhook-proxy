package functions;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.*;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Base64;

public class Trigger implements HttpFunction {

    com.google.api.client.http.HttpRequest urlRequest;

    @Override
    public void service(HttpRequest httpRequest, HttpResponse httpResponse) throws IOException {

        try {
            validateHTTPMethodType(httpRequest);
            JsonObject requestBody = extractRequestBody(httpRequest);
            validateHttpRequest(requestBody);
            Webhook webhook = extractDataFromBody(requestBody);
            urlRequest = buildRequest(webhook);
            UrlResponse urlResponse = new UrlResponse().executeRequest(urlRequest);
            writeResponse(urlResponse, httpResponse);
        } catch (IOException | ValidationException | GeneralSecurityException e) {
            handleException(e, httpResponse);
        } catch (Exception e) {
            handleException(e, httpResponse);
        }
    }

    private void handleException(Exception e, HttpResponse httpResponse) throws IOException {
        httpResponse.setStatusCode(500);
        httpResponse.getWriter().write("Http request failed: " + e.getMessage());
        httpResponse.getWriter().flush();
        httpResponse.getWriter().close();
    }

    private void writeResponse(UrlResponse urlResponse, HttpResponse httpResponse) throws IOException {

        if(urlResponse.exception != null) {
            if (!(urlResponse.exception instanceof HttpResponseException)) {
                httpResponse.setStatusCode(400);
            } else {
                httpResponse.setStatusCode(((HttpResponseException) urlResponse.exception).getStatusCode());
            }
            httpResponse.getWriter().write("Http request failed: " + urlResponse.exception.getMessage());
        } else {
            com.google.api.client.http.HttpResponse response = urlResponse.urlResponse;
            httpResponse.setStatusCode(response.getStatusCode());
            httpResponse.getWriter().write(response.parseAsString());
        }
        httpResponse.getWriter().flush();
        httpResponse.getWriter().close();
    }

    private com.google.api.client.http.HttpRequest buildRequest(Webhook entity)
            throws IOException, GeneralSecurityException {

        HttpRequestFactory requestFactory = GoogleNetHttpTransport.newTrustedTransport().createRequestFactory();
        GenericUrl genericUrl = new GenericUrl(entity.getUrl());
        com.google.api.client.http.HttpRequest httpRequest =
                requestFactory.buildPostRequest(genericUrl, ByteArrayContent.fromString(null, entity.getBody()));

        if(entity.getHeaders() != null) {
            httpRequest.setHeaders(entity.getHeaders());
        }
        httpRequest.setReadTimeout(entity.getTimeout() * 1000);

        return httpRequest;
    }

    private Webhook extractDataFromBody(JsonObject requestBody) {

        Webhook webhook = new Webhook();
        webhook.setUrl(requestBody.get("url").getAsString());

        String bodyBase64 = requestBody.get("body").getAsString();
        byte[] bodyBytes = Base64.getDecoder().decode(bodyBase64);
        webhook.setBody(new String(bodyBytes));

        if(requestBody.has("headers")) {
            JsonObject requestHeaders = requestBody.getAsJsonObject("headers");
            HttpHeaders httpHeaders = new HttpHeaders();
            for (String headerKey : requestHeaders.keySet()) {
                String headerValue = requestHeaders.get(headerKey).getAsString().trim();
                httpHeaders.set(headerKey, headerValue);
            }
            webhook.setHeaders(httpHeaders);
        }
        if(requestBody.has("options")) {
            JsonObject optionsJson = requestBody.getAsJsonObject("options");
            if(optionsJson.has("timeout")) {
                webhook.setTimeout(requestBody.getAsJsonObject("options").get("timeout").getAsInt());
            }
        }
        return webhook;
    }

    private JsonObject extractRequestBody(HttpRequest request) throws IOException, ValidationException {

        BufferedReader reader = request.getReader();
        StringBuilder requestBody = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            requestBody.append(line);
        }

        String requestBodyJson = requestBody.toString();
        try {
            return JsonParser.parseString(requestBodyJson).getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new ValidationException("Invalid Request Body");
        }
    }

    private void validateHTTPMethodType(HttpRequest request) throws ValidationException {

        if(!request.getMethod().equals("POST")) {
            throw new ValidationException("Method not allowed");
        }
    }

    private void validateHttpRequest(JsonObject requestBody) throws ValidationException {

        validateUrl(requestBody);
        validateBody(requestBody);
        validateHeaders(requestBody);
        validateOptions(requestBody);
    }

    private void validateUrl(JsonObject requestBody) throws ValidationException {

        if (!requestBody.has("url")) {
            throw new ValidationException("Url is missing");
        }
        String url = requestBody.get("url").getAsString();
        String URL_REGEX = "^(http|https)://([^:/\\s]+)(:\\d+)?(/[^/\\s]*)*$";
        if (!url.matches(URL_REGEX)) {
            throw new ValidationException("Invalid Request Url");
        }
    }

    private void validateBody(JsonObject requestBody) throws ValidationException {

        if (!requestBody.has("body")) {
            throw new ValidationException("Body is missing");
        }
        try {
            String bodyBase64 = requestBody.get("body").getAsString();
            Base64.getDecoder().decode(bodyBase64);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid Request Body");
        }
    }

    private void validateHeaders(JsonObject requestBody) throws ValidationException {

        if (requestBody.has("headers")) {
            try {
                requestBody.getAsJsonObject("headers");
            } catch (ClassCastException e) {
                throw new ValidationException("Invalid Request Headers");
            }
        }
    }

    private void validateOptions(JsonObject requestBody) throws ValidationException {

        if (requestBody.has("options")) {
            try {
                JsonObject options = requestBody.getAsJsonObject("options");
                validateTimeoutOption(options);
            } catch (ClassCastException e) {
                throw new ValidationException("Invalid Request Options");
            }
        }
    }

    private void validateTimeoutOption(JsonObject options) throws ValidationException {

        if (options.has("timeout")) {
            try {
                Integer timeout = options.get("timeout").getAsInt();
                if (timeout < 0 || timeout > 3600) {
                    throw new ValidationException("Invalid Timeout Value");
                }
            } catch (NumberFormatException e) {
                throw new ValidationException("Invalid Timeout Value");
            }
        }
    }
}


