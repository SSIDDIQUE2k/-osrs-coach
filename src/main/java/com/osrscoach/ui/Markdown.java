package com.osrscoach.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal, safe Markdown → HTML for chat bubbles. Escapes everything first, so model output can't inject markup. */
public final class Markdown
{
	private static final Pattern LINK = Pattern.compile("\\[([^\\]]{1,200})\\]\\((https?://[^\\s)]{1,500})\\)");
	private static final Pattern BARE_URL = Pattern.compile("(?<![\"=>])(https?://[^\\s<)\\]]{1,500})");
	private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
	private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])_(.+?)_(?![\\w*])");
	private static final Pattern CODE = Pattern.compile("`([^`]{1,200})`");
	private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s*(.+)$");
	private static final Pattern BULLET = Pattern.compile("(?m)^\\s*[-*]\\s+");

	private Markdown()
	{
	}

	public static String toHtml(String md)
	{
		if (md == null)
		{
			return "";
		}
		String s = escape(md.replace("\r", ""));
		s = HEADING.matcher(s).replaceAll("<b>$1</b>");
		s = BULLET.matcher(s).replaceAll("• ");
		// Links: convert [text](url) first, protecting them from the bare-URL pass with a placeholder.
		StringBuffer out = new StringBuffer();
		Matcher m = LINK.matcher(s);
		while (m.find())
		{
			m.appendReplacement(out, Matcher.quoteReplacement("<a href=\"" + m.group(2) + "\">" + m.group(1) + "</a>"));
		}
		m.appendTail(out);
		s = out.toString();
		out = new StringBuffer();
		m = BARE_URL.matcher(s);
		while (m.find())
		{
			String url = m.group(1);
			m.appendReplacement(out, Matcher.quoteReplacement("<a href=\"" + url + "\">" + shorten(url) + "</a>"));
		}
		m.appendTail(out);
		s = out.toString();
		s = BOLD.matcher(s).replaceAll("<b>$1</b>");
		s = ITALIC.matcher(s).replaceAll("<i>$1</i>");
		s = CODE.matcher(s).replaceAll("<tt>$1</tt>");
		return s.replace("\n", "<br>");
	}

	public static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	static String shorten(String url)
	{
		String u = url.replaceFirst("^https?://(www\\.)?", "");
		return u.length() > 42 ? u.substring(0, 40) + "…" : u;
	}
}
