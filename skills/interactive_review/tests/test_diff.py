import pytest
from skills.interactive_review.diff import parse_unified_diff, FileChange, Hunk, Line


def test_parse_empty_diff_returns_empty_list():
    assert parse_unified_diff("") == []


def test_parse_single_file_single_hunk():
    diff = """diff --git a/src/foo.py b/src/foo.py
index abc..def 100644
--- a/src/foo.py
+++ b/src/foo.py
@@ -1,3 +1,4 @@
 def existing():
     pass
+def new():
+    return 1
"""
    files = parse_unified_diff(diff)
    assert len(files) == 1
    assert files[0].path == "src/foo.py"
    assert len(files[0].hunks) == 1
    h = files[0].hunks[0]
    assert h.old_start == 1 and h.old_lines == 3
    assert h.new_start == 1 and h.new_lines == 4
    sides = [l.side for l in h.lines]
    assert sides == ["context", "context", "added", "added"]


def test_parse_handles_removals():
    diff = """diff --git a/x b/x
--- a/x
+++ b/x
@@ -1,3 +1,2 @@
 a
-b
 c
"""
    files = parse_unified_diff(diff)
    assert len(files[0].hunks[0].lines) == 3
    sides = [l.side for l in files[0].hunks[0].lines]
    assert sides == ["context", "removed", "context"]


def test_parse_assigns_old_and_new_line_numbers():
    diff = """diff --git a/x b/x
--- a/x
+++ b/x
@@ -10,3 +10,4 @@
 a
+new
 b
 c
"""
    files = parse_unified_diff(diff)
    lines = files[0].hunks[0].lines
    assert (lines[0].old, lines[0].new) == (10, 10)
    assert (lines[1].old, lines[1].new) == (None, 11)
    assert (lines[2].old, lines[2].new) == (11, 12)
    assert (lines[3].old, lines[3].new) == (12, 13)


def test_parse_multi_file():
    diff = """diff --git a/a b/a
--- a/a
+++ b/a
@@ -1 +1,2 @@
 x
+y
diff --git a/b b/b
--- a/b
+++ b/b
@@ -1 +1 @@
-old
+new
"""
    files = parse_unified_diff(diff)
    assert [f.path for f in files] == ["a", "b"]
    assert len(files[1].hunks[0].lines) == 2
