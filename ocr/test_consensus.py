import unittest
import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from PIL import Image
from consensus import Line, align_rows, consensus, vote, tesseract_lines, easy_lines


def row(engine, text, top=100):
    return Line(engine, text, 50, top, 300, 20)


class ConsensusTest(unittest.TestCase):
    def test_easyocr_bounds_large_tall_and_square_inputs_without_changing_boxes(self):
        box = [[100, 200], [300, 200], [300, 230], [100, 230]]
        calls = []
        reader = SimpleNamespace(readtext=lambda path, **kwargs: (
            calls.append(kwargs) or [(box, '123 MAIN ST', .99)]))
        modules = {
            'torch': SimpleNamespace(set_num_threads=lambda _: None, set_num_interop_threads=lambda _: None),
            'easyocr': SimpleNamespace(Reader=lambda *args, **kwargs: reader),
        }
        with tempfile.TemporaryDirectory() as directory, patch.dict('sys.modules', modules):
            path = str(Path(directory) / 'input.png')
            for size in [(600, 800), (1260, 2736), (2000, 2000), (2736, 1260)]:
                with Image.new('RGB', size) as source:
                    source.save(path)
                lines = easy_lines(path, {'easyocr_model_directory': directory})
                scale = min(1, calls[-1]['canvas_size'] / max(size))
                self.assertLessEqual(size[0] * size[1] * scale ** 2, 800_000)
                self.assertLessEqual(max(size) * scale, 1280)
                if size == (600, 800):
                    self.assertEqual(scale, 1)
                self.assertEqual((lines[0].left, lines[0].top), (100, 200))

    def test_small_tesseract_input_maps_boxes_back_and_preserves_literal_text(self):
        header = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
        with tempfile.TemporaryDirectory() as directory:
            for width, scale in [(600, 1.5), (900, 1.0)]:
                source = Path(directory) / "source.png"
                Image.new("RGB", (width, 800), "black").save(source)
                captured = []

                def run(command, **kwargs):
                    captured.append(Path(command[1]))
                    with Image.open(command[1]) as supplied:
                        self.assertEqual((round(width * scale), round(800 * scale)), supplied.size)
                    values = [5, 1, 1, 1, 1, 1, 10 * scale, 20 * scale, 300 * scale, 20 * scale, 90,
                              "116 S 2ND ST APT 330"]
                    return SimpleNamespace(stdout=header + "\t".join(map(str, values)) + "\n")

                with patch("consensus.subprocess.run", side_effect=run):
                    lines = tesseract_lines(str(source))
                self.assertEqual([Line("tesseract", "116 S 2ND ST APT 330", 10, 20, 300, 20)], lines)
                self.assertEqual(scale == 1, captured[0].exists())

    def test_stop_badges_cannot_steal_an_address_vote_or_interrupt_its_city(self):
        lines = [Line("tesseract", "13", 43, 365, 19, 13),
                 Line("tesseract", "LJ", 44, 376, 26, 9),
                 Line("tesseract", "24703 OLD MILL RD APT 308", 104, 374, 218, 12),
                 Line("paddleocr", "3 24703 OLD MILL RD APT 308", 45, 360, 281, 30),
                 Line("easyocr", "24703 OLD MILL RD APT 308", 101, 369, 224, 20)]
        lines += [Line(engine, "SOUTHFIELD", 100, 399, 100, 12)
                  for engine in ("tesseract", "paddleocr", "easyocr")]
        result = consensus(lines)
        readings = [line.split("\t")[-1] for line in result["tsv"].splitlines()[1:]]
        self.assertEqual(1, readings.count("24703 OLD MILL RD APT 308"))
        self.assertIn("13", readings)
        self.assertIn("LJ", readings)
        address_index = str(readings.index("24703 OLD MILL RD APT 308") + 1)
        self.assertEqual(3, len(result["lineEvidence"][address_index]["alternatives"]))

    def test_single_engine_unit_on_the_right_stays_visible(self):
        lines = [Line(engine, "123 MAIN ST", 100, 100, 150, 20)
                 for engine in ("tesseract", "paddleocr", "easyocr")]
        lines.append(Line("tesseract", "3B", 270, 100, 25, 20))
        self.assertTrue(any(line.endswith("3B") for line in consensus(lines)["tsv"].splitlines()))

    def test_left_side_unit_and_close_repeated_row_stay_visible(self):
        lines = [Line(engine, "123 MAIN ST", 100, 100, 150, 20)
                 for engine in ("paddleocr", "easyocr")]
        lines.append(Line("tesseract", "3B", 45, 100, 20, 20))
        self.assertTrue(any(line.endswith("3B") for line in consensus(lines)["tsv"].splitlines()))
        lines[-1] = Line("tesseract", "123 MAIN ST", 100, 115, 150, 20)
        self.assertEqual(2, len(align_rows(lines)))
    def test_attached_directional_needs_an_independent_separated_reading(self):
        self.assertEqual(("1 N MAIN ST", 2, False), vote([
            row("tesseract", "1N MAIN ST"), row("paddleocr", "1 N MAIN ST"),
            row("easyocr", "N MAIN ST")]))
        self.assertEqual(("1N MAIN ST", 2, False), vote([
            row("tesseract", "1N MAIN ST"), row("paddleocr", "1N MAIN ST")]))
    def test_user_confirmed_apartment_330_wins_two_independent_votes(self):
        self.assertEqual(("24111 CIVIC CENTER DR APT 330", 2, False), vote([
            row("tesseract", "24111 CIVIC CENTER DR APT 330"),
            row("paddleocr", "24111 CIVIC CENTER DR APT 350"),
            row("easyocr", "24111 CIVIC CENTER DR APT 330")]))

    def test_different_errors_can_be_resolved_only_with_literal_token_majorities(self):
        result = vote([row("tesseract", "123 MAIN ST APT 38"),
                       row("paddleocr", "123 MA1N ST APT 3B"),
                       row("easyocr", "123 MAIN ST APT 3B, DETROIT")])
        self.assertEqual(("123 MAIN ST APT 3B", 2, True), result)

    def test_three_different_house_numbers_require_review(self):
        text, support, review = vote([row("tesseract", "123 MAIN ST"),
                                      row("paddleocr", "128 MAIN ST"),
                                      row("easyocr", "129 MAIN ST")])
        self.assertIn(text, ["123 MAIN ST", "128 MAIN ST", "129 MAIN ST"])
        self.assertEqual(1, support)
        self.assertTrue(review)

    def test_missing_engine_abstains_and_single_engine_rows_remain_visible(self):
        self.assertEqual(("123 MAIN ST", 2, False), vote([
            row("tesseract", "123 MAIN ST"), row("easyocr", "123 MAIN ST")]))
        self.assertEqual(("123 MAIN ST", 1, True), vote([row("tesseract", "123 MAIN ST")]))

    def test_repeated_stops_and_adjacent_city_lines_do_not_collapse(self):
        lines = [row(engine, text, top) for engine in ("tesseract", "paddleocr", "easyocr")
                 for top, text in [(100, "123 MAIN ST"), (125, "DETROIT"),
                                   (220, "123 MAIN ST"), (245, "DETROIT")]]
        self.assertEqual(4, len(align_rows(lines)))
        result = consensus(lines)
        self.assertEqual(4, len(result["lineEvidence"]))
        self.assertEqual(2, sum(line.endswith("123 MAIN ST") for line in result["tsv"].splitlines()))
        self.assertTrue(all(value["agreement"] == 3 for value in result["lineEvidence"].values()))

    def test_legitimate_words_and_alphanumeric_units_are_not_corrected(self):
        literal = "23673 FOREST DR S APT 3B"
        self.assertEqual((literal, 3, False), vote([
            row(engine, literal) for engine in ("tesseract", "paddleocr", "easyocr")]))

    def test_a_single_engine_cannot_vote_twice(self):
        with self.assertRaises(ValueError):
            vote([row("tesseract", "123 MAIN ST"), row("tesseract", "123 MAIN ST")])

    def test_insertion_requires_two_votes_and_one_engine_extra_city_is_not_invented(self):
        self.assertEqual(("123 MAIN ST APT 3B", 2, True), vote([
            row("tesseract", "123 MAIN ST"), row("paddleocr", "123 MAIN ST APT 3B"),
            row("easyocr", "123 MAIN ST APT 3B, DETROIT")]))

    def test_single_engine_extra_words_do_not_overrule_two_absences(self):
        self.assertEqual(("123 MAIN ST", 2, True), vote([
            row("tesseract", "123 X MAIN ST"), row("paddleocr", "123 MAIN ST APT 3B"),
            row("easyocr", "123 MAIN ST")]))

    def test_ambiguous_alphanumeric_unit_splits_require_review(self):
        _text, agreement, review = vote([
            row("tesseract", "123 MAIN ST APT 3B"), row("paddleocr", "123 MAIN ST APT 3 B"),
            row("easyocr", "123 MAIN ST APT 38")])
        self.assertEqual(1, agreement)
        self.assertTrue(review)

    def test_serialized_partial_detection_has_only_real_engine_evidence(self):
        result = consensus([row("tesseract", "123 MAIN ST"), row("easyocr", "123 MAIN ST")])
        evidence = result["lineEvidence"]["1"]
        self.assertEqual(list(("tesseract", "paddleocr", "easyocr")), result["engines"])
        self.assertEqual(2, len(evidence["alternatives"]))
        self.assertEqual(2, evidence["agreement"])
        self.assertFalse(evidence["reviewRequired"])

    def test_two_omissions_cannot_silently_remove_a_visible_unit(self):
        self.assertEqual(("24111 CIVIC CENTER DR", 2, True), vote([
            row("tesseract", "24111 CIVIC CENTER DR APT 330"),
            row("paddleocr", "24111 CIVIC CENTER DR"),
            row("easyocr", "24111 CIVIC CENTER DR")]))

    def test_omitted_directional_or_house_number_requires_review(self):
        for complete, shortened in [("123 N MAIN ST", "123 MAIN ST"),
                                    ("123 MAIN ST", "MAIN ST")]:
            self.assertTrue(vote([row("tesseract", complete), row("paddleocr", shortened),
                                  row("easyocr", shortened)])[2])


if __name__ == "__main__":
    unittest.main()
