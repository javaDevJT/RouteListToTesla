package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddressBoundaryRecoveryTest {
    @Test
    void preservesSuffixlessStreetAndKnownMultiwordCityAsSeparateFields() throws Exception {
        assertEquals(List.of("5337 WOODLAND, WHITE LAKE, MI"), extract(
                "5337 WOODLAND", "WHITE LAKE", "Deliver 7 packages"));
        assertEquals(List.of("123 CEDAR, SILVER SPRING, MD"), extractInState("MD",
                "123 CEDAR", "SILVER SPRING", "Deliver 1 package"));
        assertEquals(List.of("5337 WOODLAND, WHITE LAKE, MI"), extract(
                "5337 WOODLAND", "WHITE LAKE", "Age verification"));
    }

    @Test
    void keepsWrappedStreetNamesWhenAnotherLocalityLineFollows() throws Exception {
        assertEquals(List.of("5337 WOODLAND WHITE LAKE, DETROIT, MI"), extract(
                "5337 WOODLAND", "WHITE LAKE", "DETROIT", "Deliver 1 package"));
        assertEquals(List.of("5337 WOODLAND WHITE LAKE, DETROIT MI"), extract(
                "5337 WOODLAND", "WHITE LAKE", "DETROIT MI", "Deliver 1 package"));
        assertEquals(List.of("5337 WOODLAND WHITE LAKE, DETROIT, MI 48201"), extract(
                "5337 WOODLAND", "WHITE LAKE", "DETROIT, MI 48201", "Deliver 1 package"));
        assertEquals(List.of("5337 WOODLAND DRIVE, WHITE LAKE, MI"), extract(
                "5337 WOODLAND", "DRIVE", "WHITE LAKE", "Deliver 1 package"));
        assertEquals(List.of("2316 WEST HIGHLAND MEADOWS BOULEVARD APARTMENT 12B, COLORADO SPRINGS, MI"),
                extract("2316 WEST HIGHLAND MEADOWS", "BOULEVARD APARTMENT 12B", "COLORADO SPRINGS"));
    }

    @Test
    void retainsSuppliedOrderUnitsAndEachSourceLine() throws Exception {
        List<String> result = extract("5055 Carina's Way Court", "COMMERCE TOWNSHIP", "Deliver 11 packages",
                "11123 SUGDEN LAKE RD", "WHITE LAKE", "Deliver 8 packages",
                "5337 WOODLAND", "WHITE LAKE", "Deliver 7 packages");
        assertEquals(List.of("5055 Carina's Way Court, COMMERCE TOWNSHIP, MI",
                "11123 SUGDEN LAKE RD, WHITE LAKE, MI", "5337 WOODLAND, WHITE LAKE, MI"), result);
    }

    private static List<String> extract(String... lines) throws Exception {
        return extractInState("MI", lines);
    }

    private static List<String> extractInState(String state, String... lines) throws Exception {
        StringBuilder tsv = new StringBuilder("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n");
        for (int i = 0; i < lines.length; i++) {
            tsv.append("5\t1\t1\t1\t").append(i + 1).append("\t1\t20\t")
                    .append(i * 30).append("\t300\t20\t95\t").append(lines[i]).append('\n');
        }
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", image);
        AddressOcrService service = new AddressOcrService("mock", "eng",
                command -> new AddressOcrService.ProcessResult(0, tsv.toString(), ""));
        return service.extractAddressCandidates(image.toByteArray(), "source.png", state)
                .stream().map(PlaceCandidate::text).toList();
    }
}
