package com.example.mcp.SonarqubeMcpDemo.client;

import com.example.mcp.SonarqubeMcpDemo.config.SonarQubeProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * Simple HTTP client for the SonarQube Web API.
 * Uses token-based Basic authentication (token as username, empty password).
 */
@Component
public class SonarQubeClient {

    private final SonarQubeProperties props;
    private final HttpClient httpClient;

    public SonarQubeClient(SonarQubeProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Performs a GET request against the SonarQube API.
     *
     * @param path API path including query parameters, e.g. "/api/projects/search?ps=20"
     * @return raw JSON response body
     */
    public String get(String path) {
        try {
            // SonarQube token auth: Basic base64(token:)
            String credentials = props.getToken() + ":";
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.getUrl() + path))
                    .header("Authorization", "Basic " + encoded)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                return "{\"error\": \"Authentication failed. Check your SONARQUBE_TOKEN.\"}";
            }
            if (response.statusCode() == 404) {
                return "{\"error\": \"Resource not found: " + path + "\"}";
            }
            if (response.statusCode() >= 400) {
                return "{\"error\": \"SonarQube API returned HTTP " + response.statusCode() + "\", \"body\": " + response.body() + "}";
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\": \"Request interrupted\"}";
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
