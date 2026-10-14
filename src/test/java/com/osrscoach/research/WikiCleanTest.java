package com.osrscoach.research;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.osrscoach.ai.CoachPrompt;
import org.junit.jupiter.api.Test;

class WikiCleanTest
{
	@Test
	void stripsNoiseKeepsFactsAndCoordinates()
	{
		String raw = "{{External|rs}}\n{{Has quick guide}}\n{{Infobox Quest\n|image = [[File:Pip.png|300px]]\n|release = [[1 Jan]]\n|members = Yes\n}}\n"
			+ "'''Priest in Peril''' is a [[quest]] in [[Varrock|the city]].<ref>Patch notes</ref>\n<!-- hidden -->\n\n\n\n"
			+ "|startmap = 3221,3473\n{{Map|x:3423,y:3461}}\n[[Category:Quests]]";
		String c = WikiClient.cleanWikitext(raw);
		assertFalse(c.contains("External") || c.contains("File:") || c.contains("Patch notes") || c.contains("hidden") || c.contains("Category:"), c);
		assertFalse(c.contains("release ="), c);
		assertTrue(c.contains("Priest in Peril is a quest in the city."), c);
		assertTrue(c.contains("startmap = 3221,3473") && c.contains("x:3423,y:3461"), "coordinates must survive for map markers");
		assertTrue(c.contains("members = Yes"));
		assertFalse(c.contains("\n\n\n"));
	}

	@Test
	void stepByStepPromptSwitch()
	{
		assertTrue(CoachPrompt.system("ctx", true, false, true).contains("STEP BY STEP"));
		assertFalse(CoachPrompt.system("ctx", true, false, false).contains("STEP BY STEP"));
	}
}
