package com.osrscoach.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class MarkdownTest
{
	@Test
	void boldLinksAndBreaks()
	{
		String h = Markdown.toHtml("**Next step:** see [Cook's Assistant](https://oldschool.runescape.wiki/w/Cook's_Assistant)\n- bring an egg");
		assertTrue(h.contains("<b>Next step:</b>"), h);
		assertTrue(h.contains("<a href=\"https://oldschool.runescape.wiki/w/Cook's_Assistant\">Cook's Assistant</a>"), h);
		assertTrue(h.contains("<br>• bring an egg"), h);
	}

	@Test
	void bareUrlsLinkedOnce()
	{
		String h = Markdown.toHtml("Sources: https://oldschool.runescape.wiki/w/Fishing");
		assertEquals(1, h.split("<a ").length - 1, h);
	}

	@Test
	void modelCannotInjectHtml()
	{
		String h = Markdown.toHtml("<img src=x onerror=alert(1)><script>x</script>[click](javascript:alert(1))");
		assertFalse(h.contains("<img"));
		assertFalse(h.contains("<script"));
		assertFalse(h.contains("href=\"javascript"));
	}
}
