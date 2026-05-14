import unittest
from skills.annotate.render import render_with_block_ids


class RenderBlockIdsTests(unittest.TestCase):
    def test_empty_input_yields_empty_output(self):
        self.assertEqual(render_with_block_ids(""), "")

    def test_single_paragraph_gets_b0(self):
        html = render_with_block_ids("Hello world.")
        self.assertIn('data-block-id="b-0"', html)
        self.assertIn("Hello world.", html)

    def test_two_paragraphs_get_sequential_ids(self):
        md = "First paragraph.\n\nSecond paragraph."
        html = render_with_block_ids(md)
        self.assertIn('data-block-id="b-0"', html)
        self.assertIn('data-block-id="b-1"', html)

    def test_heading_gets_block_id(self):
        html = render_with_block_ids("## Title\n\nBody.")
        self.assertIn('data-block-id="b-0"', html)
        self.assertIn("Title", html)
        self.assertIn('data-block-id="b-1"', html)
        self.assertIn("Body.", html)

    def test_each_list_item_gets_its_own_block_id(self):
        md = "- one\n- two\n- three"
        html = render_with_block_ids(md)
        self.assertIn('data-block-id="b-0"', html)
        self.assertIn('data-block-id="b-1"', html)
        self.assertIn('data-block-id="b-2"', html)

    def test_code_block_gets_block_id(self):
        md = "```\nprint('hi')\n```"
        html = render_with_block_ids(md)
        self.assertIn('data-block-id="b-0"', html)
        self.assertIn("print(&#x27;hi&#x27;)", html)

    def test_mixed_blocks_are_sequential(self):
        md = "## H\n\npara\n\n- a\n- b\n\n```\ncode\n```"
        html = render_with_block_ids(md)
        for expected in ('b-0', 'b-1', 'b-2', 'b-3', 'b-4'):
            self.assertIn(f'data-block-id="{expected}"', html)

    def test_heading_without_blank_line_below_is_split_from_paragraph(self):
        html = render_with_block_ids("## Title\nBody text here.")
        self.assertIn('<h2 data-block-id="b-0">Title</h2>', html)
        self.assertIn('<p data-block-id="b-1">Body text here.</p>', html)

    def test_render_is_idempotent_for_same_input(self):
        md = "para one\n\npara two"
        self.assertEqual(render_with_block_ids(md), render_with_block_ids(md))


if __name__ == "__main__":
    unittest.main()
