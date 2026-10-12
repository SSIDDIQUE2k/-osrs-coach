package com.osrscoach.research;

public class LookupException extends Exception
{
	public LookupException(String message)
	{
		super(message);
	}

	public LookupException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
