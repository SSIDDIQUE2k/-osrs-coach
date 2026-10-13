package com.osrscoach.ai;

import lombok.Getter;

public class CoachException extends Exception
{
	public enum Kind
	{
		NO_CREDENTIALS("Missing API key"),
		BAD_CONFIG("Settings problem"),
		AUTH("API key rejected"),
		RATE_LIMITED("Rate limited / out of credit"),
		NETWORK("Network problem"),
		SERVER("AI service error"),
		INVALID_RESPONSE("Unreadable AI response"),
		NOT_LOGGED_IN("Not logged in");

		@Getter
		private final String title;

		Kind(String title)
		{
			this.title = title;
		}
	}

	@Getter
	private final Kind kind;
	@Getter
	private final boolean retryable;
	/** Server-suggested wait before retrying, in seconds; negative when none was given. */
	@Getter
	private double retryAfterSeconds = -1;
	/** True for transient 5xx errors, false for rate limits. */
	@Getter
	private boolean serverBusy;

	CoachException withRetryAfter(double seconds, boolean busy)
	{
		this.retryAfterSeconds = seconds;
		this.serverBusy = busy;
		return this;
	}

	public CoachException(Kind kind, String message, boolean retryable)
	{
		super(message);
		this.kind = kind;
		this.retryable = retryable;
	}

	public CoachException(Kind kind, String message, boolean retryable, Throwable cause)
	{
		super(message, cause);
		this.kind = kind;
		this.retryable = retryable;
	}

	/** Maps an HTTP status to a player-friendly error. Never includes the request (which holds the key). */
	public static CoachException fromStatus(int code, String providerName, String detail)
	{
		String d = detail == null || detail.isEmpty() ? "" : " Details: " + detail;
		if (code == 401 || code == 403)
		{
			return new CoachException(Kind.AUTH, providerName + " rejected the API key (HTTP " + code + "). Check it in the plugin settings." + d, false);
		}
		if (code == 404)
		{
			return new CoachException(Kind.BAD_CONFIG, providerName + " returned 404. Check the model name and base URL." + d, false);
		}
		if (code == 400 || code == 422)
		{
			return new CoachException(Kind.BAD_CONFIG, providerName + " refused the request (HTTP " + code + "). Often a wrong model name or a model without tool support." + d, false);
		}
		if (code == 402)
		{
			return new CoachException(Kind.RATE_LIMITED, providerName + " says your account is out of credit (HTTP 402). Waiting won't help: add credit in that provider's billing page, or switch Provider/key in settings." + d, false);
		}
		if (code == 429)
		{
			return new CoachException(Kind.RATE_LIMITED, providerName + " says you're rate limited or out of credit (HTTP " + code + "). Wait a minute or check billing." + d, true);
		}
		if (code >= 500)
		{
			return new CoachException(Kind.SERVER, providerName + " had a server problem (HTTP " + code + "). Try again shortly." + d, true);
		}
		return new CoachException(Kind.SERVER, providerName + " returned HTTP " + code + "." + d, true);
	}
}
