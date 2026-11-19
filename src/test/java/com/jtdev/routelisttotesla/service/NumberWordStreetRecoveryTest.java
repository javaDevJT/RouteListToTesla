package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.model.LocationResolution;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class NumberWordStreetRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INPUT = "22506 W 11 MILE RD, SOUTHFIELD, MI";
    private record Request(String phase, Map<String,String> query) { }
    private record Probe(PlaceCandidate input, PlaceCandidate result, List<Request> requests) { }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> fixture() {
        try (var in = NumberWordStreetRecoveryTest.class.getResourceAsStream("/geocoding/number-word-rooftop.json")) {
            return JSON.readValue(in, Map.class);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> result(Map<String,Object> body) {
        return ((List<Map<String,Object>>)body.get("results")).getFirst();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> component(Map<String,Object> body, String type) {
        return ((List<Map<String,Object>>)result(body).get("address_components")).stream()
                .filter(c -> ((List<String>)c.get("types")).contains(type)).findFirst().orElseThrow();
    }
    private static Map<String,Object> route(String longName,String shortName) {
        var b=fixture();component(b,"route").put("long_name",longName);component(b,"route").put("short_name",shortName);return b;
    }
    private static Probe run(String text, Function<Request,Map<String,Object>> responses) throws Exception {
        List<Request> requests=new CopyOnWriteArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/geocode",exchange->{
            Map<String,String> query=new LinkedHashMap<>();
            for(String pair:exchange.getRequestURI().getRawQuery().split("&")){
                String[] p=pair.split("=",2);query.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),URLDecoder.decode(p[1],StandardCharsets.UTF_8));
            }
            Request request=new Request(query.containsKey("latlng")?"reverse":query.containsKey("components")?"structured":"original",query);
            requests.add(request);byte[] bytes=JSON.writeValueAsString(responses.apply(request)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try{
            PlaceCandidate input=new PlaceCandidate(text,text,"numbered-street.jpg",2,0,0,null,2,false,List.of("source OCR"));
            var client=new GeocodingClient("fixture-only","http://127.0.0.1:"+server.getAddress().getPort()+"/geocode","us",100,100,1,1,0);
            return new Probe(input,client.batchGeocode("owner",List.of(input)).getFirst(),requests);
        } finally {server.stop(0);}
    }
    private static void held(Probe p) {
        assertNotNull(p.result.resolution());assertFalse(p.result.resolution().navigable());
        assertNull(p.result.pid());assertEquals(0,p.result.lat());assertEquals(0,p.result.lon());
        assertEquals(p.input.text(),p.result.text());
    }
    @Test void realElevenMileRooftopNeedsThreeUnchangedAddressProofs() throws Exception {
        Probe p=run(INPUT,r->fixture());
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING,p.result.resolution().status());
        assertEquals("corroborated_number_word_street",p.result.resolution().strategy());
        assertEquals(3,p.result.resolution().attempts());assertEquals(3,p.requests.size());
        assertEquals(INPUT,p.requests.getFirst().query.get("address"));
        assertEquals("22506 W 11 MILE RD",p.requests.get(1).query.get("address"));
        assertEquals("locality:SOUTHFIELD|administrative_area:MI|country:US",p.requests.get(1).query.get("components"));
        assertEquals("reverse",p.requests.getLast().phase);
        assertEquals("ChIJrR6s2MO3JIgRxP66zkF3MHQ",p.result.pid());
        assertEquals(42.4871726,p.result.lat());assertEquals(-83.26664699999999,p.result.lon());
        assertEquals(p.input.text(),p.result.text());assertEquals(p.input.normalized(),p.result.normalized());
        assertEquals(p.input.sourceImage(),p.result.sourceImage());assertEquals(p.input.lineIndex(),p.result.lineIndex());
        assertEquals(p.input.ocrAgreement(),p.result.ocrAgreement());assertEquals(p.input.ocrReviewRequired(),p.result.ocrReviewRequired());
        assertEquals(p.input.ocrAlternatives(),p.result.ocrAlternatives());
    }
    @Test void numberWordsWorkBeyondMileRoadsAndInBothDirections() throws Exception {
        for(String[] sample:List.of(new String[]{"W 9 PINES AVE","West Nine Pines Avenue","W Nine Pines Ave"},
                new String[]{"N NINETEEN OAKS DR","North 19 Oaks Drive","N 19 Oaks Dr"},
                new String[]{"E 21ST ST","East Twenty First Street","E Twenty First St"})){
            Probe p=run("22506 "+sample[0]+", SOUTHFIELD, MI",r->route(sample[1],sample[2]));
            assertTrue(p.result.resolution().verified());assertEquals(3,p.result.resolution().attempts());
        }
    }
    @Test void providerMayAlternateEquivalentRepresentationsOnlyInsideTheProofChain() throws Exception {
        Probe p=run(INPUT,r->r.phase.equals("structured")?route("West 11 Mile Road","W 11 Mile Rd"):fixture());
        assertTrue(p.result.resolution().verified());assertEquals(3,p.requests.size());
        Probe mixed=run(INPUT,r->route("West Eleven Mile Road","W 11 Mile Rd"));
        assertTrue(mixed.result.resolution().verified());assertEquals(3,mixed.requests.size());
    }
    @Test void missingOrConflictingRooftopIdentityNeverBecomesAStreetAlias() throws Exception {
        for(String phase:List.of("original","structured","reverse")){
            for(String field:List.of("empty","pid","house","city","state","country","zip","partial","interpolation","point","unit","direction","number","suffix")){
                Probe p=run(INPUT,r->{
                    var b=fixture();if(!r.phase.equals(phase))return b;
                    switch(field){
                        case "empty" -> {return Map.of("status","ZERO_RESULTS","results",List.of());}
                        case "pid" -> result(b).put("place_id","different-rooftop");
                        case "house" -> {component(b,"street_number").put("long_name","22507");component(b,"street_number").put("short_name","22507");}
                        case "city" -> {component(b,"locality").put("long_name","Detroit");component(b,"locality").put("short_name","Detroit");}
                        case "state" -> {component(b,"administrative_area_level_1").put("long_name","Ohio");component(b,"administrative_area_level_1").put("short_name","OH");}
                        case "country" -> {component(b,"country").put("long_name","Canada");component(b,"country").put("short_name","CA");}
                        case "zip" -> {component(b,"postal_code").put("long_name","48035");component(b,"postal_code").put("short_name","48035");}
                        case "partial" -> result(b).put("partial_match",true);
                        case "interpolation" -> ((Map<String,Object>)result(b).get("geometry")).put("location_type","RANGE_INTERPOLATED");
                        case "point" -> ((Map<String,Object>)result(b).get("geometry")).put("location",Map.of("lat",42.488,"lng",-83.266));
                        case "unit" -> result(b).put("formatted_address","22506 W Eleven Mile Rd Apt 2, Southfield, MI 48034, USA");
                        case "direction" -> {component(b,"route").put("long_name","East Eleven Mile Road");component(b,"route").put("short_name","E Eleven Mile Rd");}
                        case "number" -> {component(b,"route").put("long_name","West Twelve Mile Road");component(b,"route").put("short_name","W Twelve Mile Rd");}
                        case "suffix" -> {component(b,"route").put("long_name","West Eleven Mile Avenue");component(b,"route").put("short_name","W Eleven Mile Ave");}
                    }return b;
                });held(p);
            }
        }
    }
    @SuppressWarnings("unchecked")
    @Test void competingRooftopsAndContradictoryComponentsStayHeld() throws Exception {
        held(run(INPUT,r->{var b=fixture();var other=result(fixture());other.put("place_id","other-building");
            other.put("geometry",Map.of("location_type","ROOFTOP","location",Map.of("lat",42.488,"lng",-83.266)));
            ((List<Map<String,Object>>)b.get("results")).add(other);return b;}));
        held(run(INPUT,r->{var b=fixture();((List<Map<String,Object>>)result(b).get("address_components"))
                .add(Map.of("long_name","22507","short_name","22507","types",List.of("street_number")));return b;}));
        held(run(INPUT,r->route("West Eleven Mile Road","W Twelve Mile Rd")));
        held(run("22506 W 11 MILE RD APT 4, SOUTHFIELD, MI",r->{var b=fixture();
            result(b).put("formatted_address","22506 W Eleven Mile Rd Apt 5, Southfield, MI 48034, USA");
            ((List<Map<String,Object>>)result(b).get("address_components"))
                .add(Map.of("long_name","5","short_name","5","types",List.of("subpremise")));return b;}));
    }
    @Test void aVerifiedBuildingDoesNotInventVerificationOfTheSuppliedApartment() throws Exception {
        String supplied="22506 W 11 MILE RD APT 4, SOUTHFIELD, MI";
        Probe p=run(supplied,r->fixture());
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING,p.result.resolution().status());
        assertEquals("unit_not_confirmed",p.result.resolution().reasonCode());
        assertEquals(supplied,p.result.text());
        assertEquals(5,p.requests.size());
    }
}
