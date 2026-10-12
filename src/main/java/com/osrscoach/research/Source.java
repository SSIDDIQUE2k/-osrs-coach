package com.osrscoach.research;

import lombok.Value;

@Value
public class Source
{
	String title;
	String url;
	/** e.g. "price checked 14:02 UTC"; may be null. */
	String note;
}
