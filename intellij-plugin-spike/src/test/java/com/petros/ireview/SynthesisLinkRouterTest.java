package com.petros.ireview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SynthesisLinkRouterTest {

    @Test
    void navScheme() {
        var a = SynthesisLinkRouter.classify("ireview-nav://src/Foo.java:18");
        assertEquals(SynthesisLinkRouter.Kind.NAV, a.kind());
        assertEquals("src/Foo.java:18", a.payload());
    }

    @Test
    void symScheme() {
        var a = SynthesisLinkRouter.classify("ireview-sym://Foo");
        assertEquals(SynthesisLinkRouter.Kind.SYM, a.kind());
        assertEquals("Foo", a.payload());
    }

    @Test
    void httpIsExternal() {
        var a = SynthesisLinkRouter.classify("https://example.com/x");
        assertEquals(SynthesisLinkRouter.Kind.EXTERNAL, a.kind());
        assertEquals("https://example.com/x", a.payload());
    }

    @Test
    void unknownIsNone() {
        assertEquals(SynthesisLinkRouter.Kind.NONE, SynthesisLinkRouter.classify("mailto:x@y.z").kind());
        assertEquals(SynthesisLinkRouter.Kind.NONE, SynthesisLinkRouter.classify(null).kind());
    }

    @Test
    void httpAlsoExternal() {
        var a = SynthesisLinkRouter.classify("http://example.com");
        assertEquals(SynthesisLinkRouter.Kind.EXTERNAL, a.kind());
        assertEquals("http://example.com", a.payload());
    }
}
