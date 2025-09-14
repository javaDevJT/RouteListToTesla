package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Service
public class GeocodingClient {
    private final String apiKey;
    private final String geocodeUrl;
    private final String regionBias;
    private final ObjectMapper om = new ObjectMapper();

    public GeocodingClient(
            @Value("${app.google.apiKey}") String apiKey,
            @Value("${app.google.geocodeUrl}") String geocodeUrl,
            @Value("${app.google.regionBias:us}") String regionBias) {
        this.apiKey = apiKey; this.geocodeUrl = geocodeUrl; this.regionBias = regionBias;
    }

    public List<PlaceCandidate> batchGeocode(List<PlaceCandidate> inputs) throws Exception {
        List<PlaceCandidate> out = new ArrayList<>();
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            for (PlaceCandidate c : inputs) {
                URI uri = new URIBuilder(geocodeUrl)
                        .addParameter("address", c.text())
                        .addParameter("region", regionBias)
                        .addParameter("key", apiKey)
                        .build();
                var req = new HttpGet(uri);
                var resp = http.execute(req);
                var body = new String(resp.getEntity().getContent().readAllBytes());
                JsonNode root = om.readTree(body);
                String status = root.path("status").asText();
                double lat = 0;
                double lon = 0;
                String pid = null;
                if ("OK".equals(status) && root.path("results").isArray() && root.path("results").size() > 0) {
                    lat = root.path("results").get(0).path("geometry").path("location").path("lat").asDouble(0);
                    lon = root.path("results").get(0).path("geometry").path("location").path("lng").asDouble(0);
                    pid = root.path("results").get(0).path("place_id").asText();
                }
                out.add(c.withLatLonPid(lat, lon, pid));
// Simple pacing to respect QPS. Tune as needed or use Google Batch Geocoding.
                Thread.sleep(60);
            }
        }
        return out;
    }
}
