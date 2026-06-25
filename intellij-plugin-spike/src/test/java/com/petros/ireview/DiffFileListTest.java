package com.petros.ireview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffFileListTest {

    @Test void modificationHasEqualBaseAndWorkPaths() {
        String patch = """
            diff --git a/src/Foo.java b/src/Foo.java
            index 1111111..2222222 100644
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -1 +1 @@
            -old
            +new
            """;
        List<DiffFileList.ChangedFile> files = DiffFileList.parse(patch);
        assertEquals(1, files.size());
        assertEquals("src/Foo.java", files.get(0).basePath());
        assertEquals("src/Foo.java", files.get(0).workPath());
    }

    @Test void renameKeepsOldPathAsBaseAndNewPathAsWork() {
        String patch = """
            diff --git a/src/Old.java b/src/New.java
            similarity index 75%
            rename from src/Old.java
            rename to src/New.java
            --- a/src/Old.java
            +++ b/src/New.java
            @@ -1,3 +1,2 @@
             a
            -b
             c
            """;
        List<DiffFileList.ChangedFile> files = DiffFileList.parse(patch);
        assertEquals(1, files.size());
        assertEquals("src/Old.java", files.get(0).basePath());
        assertEquals("src/New.java", files.get(0).workPath());
    }

    @Test void additionHasNoBasePath() {
        String patch = """
            diff --git a/src/New.java b/src/New.java
            new file mode 100644
            index 0000000..3333333
            --- /dev/null
            +++ b/src/New.java
            @@ -0,0 +1 @@
            +hello
            """;
        List<DiffFileList.ChangedFile> files = DiffFileList.parse(patch);
        assertEquals(1, files.size());
        assertNull(files.get(0).basePath());
        assertEquals("src/New.java", files.get(0).workPath());
    }

    @Test void deletionHasNoWorkPath() {
        String patch = """
            diff --git a/src/Gone.java b/src/Gone.java
            deleted file mode 100644
            index 4444444..0000000
            --- a/src/Gone.java
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
            """;
        List<DiffFileList.ChangedFile> files = DiffFileList.parse(patch);
        assertEquals(1, files.size());
        assertEquals("src/Gone.java", files.get(0).basePath());
        assertNull(files.get(0).workPath());
    }

    @Test void parsesMultipleFilesInOrder() {
        String patch = """
            diff --git a/a/Add.java b/a/Add.java
            new file mode 100644
            --- /dev/null
            +++ b/a/Add.java
            @@ -0,0 +1 @@
            +x
            diff --git a/w/Old.java b/w/New.java
            rename from w/Old.java
            rename to w/New.java
            --- a/w/Old.java
            +++ b/w/New.java
            @@ -1 +1 @@
            -o
            +n
            """;
        List<DiffFileList.ChangedFile> files = DiffFileList.parse(patch);
        assertEquals(2, files.size());
        assertNull(files.get(0).basePath());
        assertEquals("a/Add.java", files.get(0).workPath());
        assertEquals("w/Old.java", files.get(1).basePath());
        assertEquals("w/New.java", files.get(1).workPath());
    }

    @Test void emptyPatchYieldsNoFiles() {
        assertTrue(DiffFileList.parse("").isEmpty());
        assertTrue(DiffFileList.parse(null).isEmpty());
    }
}
