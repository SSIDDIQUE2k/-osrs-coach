package com.osrscoach.ai;

import com.osrscoach.research.Source;
import java.util.List;
import lombok.Value;

@Value
public class CoachReply
{
	String text;
	/** Pages the plugin itself fetched while answering (verified links, not model-written). */
	List<Source> lookedUp;
	/** Problems the player should know about, e.g. "wiki lookups failed". */
	List<String> warnings;
	/** False for mock mode. The UI labels non-live replies loudly. */
	boolean live;
	String providerLabel;
}
