package com.osrscoach.research;

import java.util.List;
import lombok.Value;

@Value
public class LookupResult
{
	/** Text handed to the model as reference material. */
	String text;
	List<Source> sources;
}
