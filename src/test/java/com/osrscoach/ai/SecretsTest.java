package com.osrscoach.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.Gson;
import com.osrscoach.FakeHttp;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class SecretsTest
{
	@Test
	void cleansPastedJunk()
	{
		assertEquals("AIzaSyABCDEF", Secrets.cleanKey(" AIzaSy​ABC\nDEF  "));
		assertEquals("AIzaXYZ", Secrets.cleanKey("AIza•XYZ"));
		assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", Secrets.cleanUrl(" https://generativelanguage.googleapis.com/v1beta/ openai/ \n"));
		assertEquals("", Secrets.cleanKey(null));
	}

	@Test
	void keyWithHiddenCharsStillWorks() throws Exception
	{
		FakeHttp ai = new FakeHttp().respond(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
		CoachRequest req = CoachRequest.builder().systemPrompt("s").history(Collections.emptyList()).userMessage("q").model("gemini-3.8-flash").maxTokens(100).build();
		CoachReply r = new OpenAiCompatibleProvider(ai.client(), new Gson(), "AIza​SECRET\n", "https://generativelanguage.googleapis.com/v1beta/ openai/").respond(req, new CoachTools(null));
		assertEquals("ok", r.getText());
		assertEquals("Bearer AIzaSECRET", ai.requests.get(0).header("Authorization"));
		assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", ai.requests.get(0).url().toString());
	}

	@Test
	void unsendableValueBecomesReadableErrorWithoutLeakingIt()
	{
		CoachException e = assertThrows(CoachException.class, () -> HttpJson.post(new FakeHttp().client(), new Gson(), "https://x.test/v1",
			Collections.singletonMap("X-Test", "SECRETéVALUE"), new com.google.gson.JsonObject(), "x.test"));
		assertEquals(CoachException.Kind.BAD_CONFIG, e.getKind());
		assertTrue(e.getMessage().contains("paste it again"));
		assertFalse(e.getMessage().contains("SECRET"));
		assertEquals(null, e.getCause());
	}
}
