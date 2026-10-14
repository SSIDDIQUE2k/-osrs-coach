package com.osrscoach.ai;

/** Cleans values pasted into settings. Copy-paste often brings hidden characters that HTTP headers reject. */
public final class Secrets
{
	private Secrets()
	{
	}

	/** Keeps only visible ASCII (keys never contain anything else); drops spaces, line breaks, zero-width chars, bullets. */
	public static String cleanKey(String key)
	{
		if (key == null)
		{
			return "";
		}
		StringBuilder b = new StringBuilder(key.length());
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			if (c >= 0x21 && c <= 0x7E)
			{
				b.append(c);
			}
		}
		return b.toString();
	}

	/** Removes whitespace and hidden characters anywhere in a URL, plus trailing slashes. */
	public static String cleanUrl(String url)
	{
		return cleanKey(url).replaceAll("/+$", "");
	}
}
