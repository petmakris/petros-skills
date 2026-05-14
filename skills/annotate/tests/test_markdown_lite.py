import unittest
from skills.annotate.markdown_lite import render


class RenderTests(unittest.TestCase):
    def test_empty_input(self):
        self.assertEqual(render(""), "")

    def test_plain_text_is_escaped(self):
        self.assertEqual(render("a < b & c > d"), "a &lt; b &amp; c &gt; d")

    def test_double_quote_is_escaped(self):
        self.assertEqual(render('say "hi"'), "say &quot;hi&quot;")

    def test_inline_code(self):
        self.assertEqual(render("use `print()` here"), "use <code>print()</code> here")

    def test_inline_code_does_not_process_inner_markdown(self):
        self.assertEqual(render("`**not bold**`"), "<code>**not bold**</code>")

    def test_bold(self):
        self.assertEqual(render("hello **world**"), "hello <strong>world</strong>")

    def test_italic_with_underscores_around_word(self):
        self.assertEqual(render("very _important_ message"), "very <em>important</em> message")

    def test_italic_does_not_match_inside_word(self):
        # snake_case_var should not be italicized
        self.assertEqual(render("snake_case_var"), "snake_case_var")

    def test_fenced_code_block(self):
        src = "before\n```\ncode line 1\ncode line 2\n```\nafter"
        out = render(src)
        self.assertIn("<pre><code>code line 1\ncode line 2</code></pre>", out)
        self.assertTrue(out.startswith("before"))
        self.assertTrue(out.endswith("after"))

    def test_fenced_code_block_escapes_html(self):
        src = "```\n<script>x</script>\n```"
        out = render(src)
        self.assertIn("&lt;script&gt;x&lt;/script&gt;", out)
        self.assertNotIn("<script>", out)

    def test_fenced_code_block_does_not_process_inner_markdown(self):
        src = "```\n**not bold** _not italic_\n```"
        out = render(src)
        self.assertIn("**not bold** _not italic_", out)
        self.assertNotIn("<strong>", out)

    def test_bulleted_list(self):
        out = render("- one\n- two\n- three")
        self.assertEqual(out, "<ul><li>one</li><li>two</li><li>three</li></ul>")

    def test_numbered_list(self):
        out = render("1. one\n2. two\n3. three")
        self.assertEqual(out, "<ol><li>one</li><li>two</li><li>three</li></ol>")

    def test_list_items_can_contain_inline_markdown(self):
        out = render("- use `print()`\n- and **bold**")
        self.assertEqual(out, "<ul><li>use <code>print()</code></li><li>and <strong>bold</strong></li></ul>")

    def test_safe_link(self):
        self.assertEqual(
            render("see [docs](https://example.com)"),
            'see <a href="https://example.com">docs</a>',
        )

    def test_mailto_link(self):
        self.assertEqual(
            render("[me](mailto:me@example.com)"),
            '<a href="mailto:me@example.com">me</a>',
        )

    def test_unsafe_javascript_link_is_rendered_as_text(self):
        # Should NOT produce an <a> tag
        out = render("[click](javascript:alert(1))")
        self.assertNotIn("<a", out)
        self.assertIn("[click](javascript:alert(1))", out)

    def test_link_text_is_html_escaped(self):
        out = render("[<b>x</b>](https://example.com)")
        self.assertIn("&lt;b&gt;x&lt;/b&gt;", out)
        self.assertNotIn("<b>x</b>", out)

    def test_combined_inline(self):
        out = render("**bold and `code`**")
        # Bold wraps; inside, the inline code should still render
        self.assertIn("<strong>", out)
        self.assertIn("<code>code</code>", out)

    def test_nul_bytes_in_input_do_not_collide_with_placeholders(self):
        # User input containing the internal placeholder pattern must not corrupt output.
        # Render `code` first to populate placeholder index 0, then ensure literal NUL
        # bytes don't get rewritten with the stashed code.
        out = render("`code` then \x00CODEBLOCK0\x00")
        self.assertIn("<code>code</code>", out)
        # The literal NUL-marker should not be replaced — it should be stripped.
        self.assertNotIn("\x00", out)


if __name__ == "__main__":
    unittest.main()
