Image → Place IDs (Spring Boot)

Minimal REST service:
1.	Accept 1..N images via multipart/form-data.
2.	OCR addresses with Tesseract.
3.	De-overlap text boxes, group lines into address candidates.
4.	Call Google Geocoding API.
5.	Return unique place_ids in input order where possible.

⸻

Quick start

# Java 21+ recommended
export GOOGLE_MAPS_API_KEY=YOUR_KEY
./mvnw spring-boot:run

# Request
curl -X POST "http://localhost:8080/route/places" \
-F images=@/path/route1.jpg \
-F images=@/path/route2.png

Response

{
"candidates": [
{"text":"123 Main St, Ann Arbor MI","normalized":"123 main st ann arbor mi","placeId":"ChIJ..."},
{"text":"2500 Grand Blvd, Detroit MI","normalized":"2500 grand blvd detroit mi","placeId":"ChIJ..."}
]
}


⸻

Maven pom.xml

<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
<modelVersion>4.0.0</modelVersion>
<groupId>app</groupId>
<artifactId>image-places</artifactId>
<version>0.0.1</version>
<properties>
<java.version>21</java.version>
<spring-boot.version>3.3.3</spring-boot.version>
</properties>
<dependencyManagement>
<dependencies>
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-dependencies</artifactId>
<version>${spring-boot.version}</version>
<type>pom</type>
<scope>import</scope>
</dependency>
</dependencies>
</dependencyManagement>
<dependencies>
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
<groupId>org.apache.httpcomponents.client5</groupId>
<artifactId>httpclient5</artifactId>
</dependency>
<dependency>
<groupId>org.bytedeco</groupId>
<artifactId>tesseract-platform</artifactId>
<version>5.4.0-1.5.10</version>
</dependency>
<dependency>
<groupId>org.bytedeco</groupId>
<artifactId>leptonica-platform</artifactId>
<version>1.84.1-1.5.10</version>
</dependency>
<dependency>
<groupId>com.fasterxml.jackson.core</groupId>
<artifactId>jackson-databind</artifactId>
</dependency>
<dependency>
<groupId>org.projectlombok</groupId>
<artifactId>lombok</artifactId>
<optional>true</optional>
</dependency>
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-test</artifactId>
<scope>test</scope>
</dependency>
</dependencies>
<build>
<plugins>
<plugin>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-maven-plugin</artifactId>
</plugin>
</plugins>
</build>
</project>

The bytedeco Tesseract bundles native libs. No OS package needed.

⸻

src/main/resources/application.yml

server:
port: 8080
app:
google:
apiKey: ${GOOGLE_MAPS_API_KEY:}
geocodeUrl: "https://maps.googleapis.com/maps/api/geocode/json"
regionBias: "us"


⸻

src/main/java/app/ImagePlacesApplication.java

package app;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ImagePlacesApplication {
public static void main(String[] args) { SpringApplication.run(ImagePlacesApplication.class, args); }
}


⸻

Controller RouteController.java

package app.web;

import app.service.AddressOcrService;
import app.service.GeocodingClient;
import app.model.PlaceCandidate;
import app.model.PlaceCandidatesResponse;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/route")
@Validated
public class RouteController {
private final AddressOcrService ocr;
private final GeocodingClient geocoder;

public RouteController(AddressOcrService ocr, GeocodingClient geocoder) {
this.ocr = ocr; this.geocoder = geocoder;
}

@PostMapping(value = "/places", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public PlaceCandidatesResponse places(@RequestPart("images") @NotEmpty MultipartFile[] images) throws Exception {
// 1) OCR all images
List<PlaceCandidate> candidates = new ArrayList<>();
for (MultipartFile f : images) {
candidates.addAll(ocr.extractAddressCandidates(f.getBytes(), Objects.requireNonNull(f.getOriginalFilename())));
}

    // 2) Normalize + de-duplicate by normalized text
    LinkedHashMap<String, PlaceCandidate> uniq = new LinkedHashMap<>();
    for (PlaceCandidate c : candidates) uniq.putIfAbsent(c.normalized(), c);

    // 3) Geocode each candidate to place_id
    List<PlaceCandidate> resolved = geocoder.batchGeocode(new ArrayList<>(uniq.values()));

    return new PlaceCandidatesResponse(resolved);
}
}


⸻

Model DTOs

PlaceCandidate.java

package app.model;

public record PlaceCandidate(String text, String normalized, String sourceImage, int lineIndex, String placeId) {
public PlaceCandidate withPlaceId(String pid) { return new PlaceCandidate(text, normalized, sourceImage, lineIndex, pid); }
}

PlaceCandidatesResponse.java

package app.model;
import java.util.List;

public record PlaceCandidatesResponse(List<PlaceCandidate> candidates) {}


⸻

OCR service AddressOcrService.java

package app.service;

import app.model.PlaceCandidate;
import app.util.AddressExtractor;
import org.bytedeco.leptonica.PIX;
import org.bytedeco.tesseract.TessBaseAPI;
import org.springframework.stereotype.Service;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.imageio.ImageIO;

@Service
public class AddressOcrService {
// English; provide your own tessdata on classpath if you want custom.
private static final String LANG = "eng";

public List<PlaceCandidate> extractAddressCandidates(byte[] imageBytes, String name) throws Exception {
BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
if (src == null) return List.of();

    // Simple preproc: grayscale + binarize handled implicitly by Leptonica wrapper per bytedeco.
    try (TessBaseAPI api = new TessBaseAPI()) {
      if (api.Init((String) null, LANG) != 0) throw new IllegalStateException("Tesseract init failed");

      PIX pix = org.bytedeco.leptonica.global.lept.pixReadMem(imageBytes, imageBytes.length);
      api.SetImage(pix);
      api.SetPageSegMode(1); // PSM_AUTO

      String hocr = api.GetHOCRText(0).getString();
      List<AddressExtractor.LineBox> lines = AddressExtractor.linesFromHOCR(hocr);

      List<PlaceCandidate> out = new ArrayList<>();
      int idx = 0;
      for (AddressExtractor.LineBox lb : AddressExtractor.mergeOverlaps(lines)) {
        String text = AddressExtractor.clean(lb.text());
        if (AddressExtractor.looksLikeAddress(text)) {
          out.add(new PlaceCandidate(text, AddressExtractor.normalize(text), name, idx++, null));
        }
      }
      // Fallback: whole-text sweep when HOCR is unreliable.
      if (out.isEmpty()) {
        String plain = api.GetUTF8Text().getString();
        for (String line : AddressExtractor.addressLinesFromPlain(plain)) {
          out.add(new PlaceCandidate(line, AddressExtractor.normalize(line), name, idx++, null));
        }
      }
      return out;
    }
}
}


⸻

Address parsing utils AddressExtractor.java

package app.util;

import java.util.*;
import java.util.regex.*;

public class AddressExtractor {
// Heuristic street patterns. US focused.
private static final String STREET_TYPES = "st|street|rd|road|ave|avenue|blvd|boulevard|dr|drive|ln|lane|ct|court|cir|circle|trl|trail|hwy|highway|pkwy|parkway|way|terr|terrace|pl|place";
private static final Pattern LINE_ADDR = Pattern.compile(
"(?i)^(?:\\d{1,6}[A-Za-z]?)\\s+[A-Za-z0-9'\\.-]+(?:\\s+[A-Za-z0-9'\\.-]+)*\\s+(?:" + STREET_TYPES + ")\\b.*");

private static final Pattern CITY_STATE_ZIP = Pattern.compile("(?i)([A-Za-z .'-]+),?\\s+([A-Z]{2})(?:\\s+\\d{5}(?:-\\d{4})?)?");

public static String normalize(String s) {
return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
}

public static String clean(String s) {
return s.replaceAll("[|]{2,}", " ").replaceAll("\\s+", " ").trim();
}

public static boolean looksLikeAddress(String line) {
if (line == null) return false;
String s = line.trim();
if (!LINE_ADDR.matcher(s).find()) return false;
return true;
}

public static List<String> addressLinesFromPlain(String text) {
List<String> out = new ArrayList<>();
for (String raw : text.split("\\r?\\n")) {
String s = clean(raw);
if (looksLikeAddress(s)) out.add(s);
}
return out;
}

// === HOCR line clustering ===
public record LineBox(int x1,int y1,int x2,int y2,String text){}

public static List<LineBox> linesFromHOCR(String hocr) {
// Parse bbox like: bbox 123 456 789 012;
Pattern p = Pattern.compile("bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
Pattern span = Pattern.compile("<span class=\\"ocr_line\\"[^>]*>(.*?)</span>", Pattern.DOTALL);
List<LineBox> out = new ArrayList<>();
Matcher m = span.matcher(hocr);
while (m.find()) {
String block = m.group(1);
Matcher bb = p.matcher(block);
if (bb.find()) {
int x1 = Integer.parseInt(bb.group(1));
int y1 = Integer.parseInt(bb.group(2));
int x2 = Integer.parseInt(bb.group(3));
int y2 = Integer.parseInt(bb.group(4));
String text = block.replaceAll("<[^>]+>", " ");
text = clean(text);
out.add(new LineBox(x1,y1,x2,y2,text));
}
}
return out;
}

public static List<LineBox> mergeOverlaps(List<LineBox> lines) {
// Non-maximum suppression by IOU on y-overlap to collapse duplicates from overlapping scans.
List<LineBox> sorted = new ArrayList<>(lines);
sorted.sort(Comparator.comparingInt(lb -> lb.y1));
boolean[] used = new boolean[sorted.size()];
List<LineBox> out = new ArrayList<>();
for (int i=0;i<sorted.size();i++) if (!used[i]) {
LineBox a = sorted.get(i);
StringBuilder combined = new StringBuilder(a.text);
int ax1=a.x1, ay1=a.y1, ax2=a.x2, ay2=a.y2;
for (int j=i+1;j<sorted.size();j++) if (!used[j]) {
LineBox b = sorted.get(j);
if (overlapY(a,b) > 0.6 && iou(a,b) > 0.3) {
used[j]=true;
combined.append(' ').append(b.text);
ax1 = Math.min(ax1,b.x1); ay1 = Math.min(ay1,b.y1); ax2 = Math.max(ax2,b.x2); ay2 = Math.max(ay2,b.y2);
}
}
out.add(new LineBox(ax1,ay1,ax2,ay2, clean(combined.toString())));
}
return out;
}

private static double overlapY(LineBox a, LineBox b) {
int top = Math.max(a.y1,b.y1), bot = Math.min(a.y2,b.y2);
int inter = Math.max(0, bot-top);
int ha = a.y2-a.y1, hb = b.y2-b.y1;
return ha==0||hb==0?0.0: (double)inter / Math.min(ha,hb);
}
private static double iou(LineBox a, LineBox b) {
int x1=Math.max(a.x1,b.x1), y1=Math.max(a.y1,b.y1), x2=Math.min(a.x2,b.x2), y2=Math.min(a.y2,b.y2);
int inter = Math.max(0,x2-x1) * Math.max(0,y2-y1);
int areaA=(a.x2-a.x1)*(a.y2-a.y1), areaB=(b.x2-b.x1)*(b.y2-b.y1);
int uni = areaA+areaB-inter;
return uni<=0?0.0: (double)inter/uni;
}
}


⸻

Geocoding client GeocodingClient.java

package app.service;

import app.model.PlaceCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;

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
String pid = null;
if ("OK".equals(status) && root.path("results").isArray() && root.path("results").size() > 0) {
pid = root.path("results").get(0).path("place_id").asText(null);
}
out.add(c.withPlaceId(pid));
// Simple pacing to respect QPS. Tune as needed or use Google Batch Geocoding.
Thread.sleep(60);
}
}
return out;
}
}


⸻

Dockerfile (optional)

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/image-places-0.0.1.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]


⸻

Notes
•	This uses heuristic US address detection. It sends any likely lines to Geocoding which handles messy input well.
•	Overlapping text is reduced via HOCR bbox merging.
•	For higher OCR quality, pre-process images (deskew, denoise) or swap to Google Cloud Vision.
•	To add Tesla route posting later, reuse the placeId list in your own API call.