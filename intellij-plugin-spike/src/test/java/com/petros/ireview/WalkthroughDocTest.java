package com.petros.ireview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WalkthroughDocTest {

    private static final String JSON = """
        {"question":"how sharing is gated","kind":"explain","generated_ts":1784720471,
         "steps":[
           {"id":1,"title":"Entry point","file":"src/Api.java","line":42,
            "snippet":"return service.share(id);","role":"context","markdown":"REST entry."},
           {"id":2,"title":"The gate","file":"src/Engine.java","line":114,
            "snippet":"var failures = preconditions.evaluate(p);","role":"seam","markdown":"Runs beans."},
           {"id":3,"title":"Where yours goes","file":"src/precondition/","line":1,
            "snippet":"package com.montblanc.precondition;","role":"edit-site","markdown":"Add here."}]}
        """;

    @Test void parsesAllFields() {
        WalkthroughDoc doc = WalkthroughDoc.parse(JSON);
        assertEquals("how sharing is gated", doc.question());
        assertEquals("explain", doc.kind());
        assertEquals(1784720471L, doc.generatedTs());
        assertEquals(3, doc.steps().size());
        WalkthroughStep s = doc.steps().get(1);
        assertEquals(2, s.id());
        assertEquals("The gate", s.title());
        assertEquals("src/Engine.java", s.file());
        assertEquals(114, s.line());
        assertEquals("var failures = preconditions.evaluate(p);", s.snippet());
        assertEquals(WalkthroughStep.Role.SEAM, s.role());
        assertEquals("Runs beans.", s.markdown());
    }

    @Test void mapsEditSiteRole() {
        assertEquals(WalkthroughStep.Role.EDIT_SITE, WalkthroughDoc.parse(JSON).steps().get(2).role());
    }

    @Test void unknownRoleFallsBackToContext() {
        String json = """
            {"question":"q","kind":"explain","generated_ts":1,"steps":[
              {"id":1,"title":"t","file":"a.java","line":1,"snippet":"x","role":"wishful","markdown":"m"}]}
            """;
        assertEquals(WalkthroughStep.Role.CONTEXT, WalkthroughDoc.parse(json).steps().get(0).role());
    }

    @Test void anchorIsStepId() {
        assertEquals("step:2", WalkthroughDoc.parse(JSON).steps().get(1).anchor());
    }

    @Test void byIdAndIndexOfId() {
        WalkthroughDoc doc = WalkthroughDoc.parse(JSON);
        assertEquals("The gate", doc.byId(2).orElseThrow().title());
        assertTrue(doc.byId(99).isEmpty());
        assertEquals(1, doc.indexOfId(2));
        assertEquals(-1, doc.indexOfId(99));
    }

    @Test void garbageParsesToEmpty() {
        assertTrue(WalkthroughDoc.parse("{not json").isEmpty());
        assertTrue(WalkthroughDoc.parse("").isEmpty());
        assertTrue(WalkthroughDoc.parse("[]").isEmpty());
        assertTrue(WalkthroughDoc.parse(null).isEmpty());
    }

    @Test void emptyStepListIsEmptyDoc() {
        String json = """
            {"question":"q","kind":"explain","generated_ts":1,"steps":[]}
            """;
        assertTrue(WalkthroughDoc.parse(json).isEmpty());
    }

    @Test void malformedStepsAreSkippedNotFatal() {
        String json = """
            {"question":"q","kind":"explain","generated_ts":1,"steps":[
              {"id":1,"title":"ok","file":"a.java","line":1,"snippet":"x","role":"context","markdown":"m"},
              {"id":0,"title":"bad id","file":"a.java","line":1,"snippet":"x","role":"context","markdown":"m"},
              {"id":3,"title":"no file","line":1,"snippet":"x","role":"context","markdown":"m"},
              "not-an-object"]}
            """;
        WalkthroughDoc doc = WalkthroughDoc.parse(json);
        assertEquals(1, doc.steps().size());
        assertEquals("ok", doc.steps().get(0).title());
    }

    @Test void stepsKeepDocumentOrder() {
        String json = """
            {"question":"q","kind":"explain","generated_ts":1,"steps":[
              {"id":5,"title":"five","file":"a.java","line":1,"snippet":"x","role":"context","markdown":"m"},
              {"id":2,"title":"two","file":"a.java","line":2,"snippet":"y","role":"context","markdown":"m"}]}
            """;
        WalkthroughDoc doc = WalkthroughDoc.parse(json);
        assertEquals(5, doc.steps().get(0).id());
        assertEquals(2, doc.steps().get(1).id());
    }
}
