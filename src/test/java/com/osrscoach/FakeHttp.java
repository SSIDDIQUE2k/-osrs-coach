package com.osrscoach;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** OkHttp client whose responses are scripted. No network, no extra test dependencies. */
public class FakeHttp
{
	public final List<Request> requests = new ArrayList<>();
	public final List<String> bodies = new ArrayList<>();
	private final Deque<Object> script = new ArrayDeque<>();

	public FakeHttp respond(int code, String body)
	{
		script.add(new Object[]{code, body});
		return this;
	}

	public FakeHttp fail(IOException e)
	{
		script.add(e);
		return this;
	}

	public OkHttpClient client()
	{
		return new OkHttpClient.Builder().addInterceptor(chain ->
		{
			Request r = chain.request();
			requests.add(r);
			if (r.body() != null)
			{
				Buffer b = new Buffer();
				r.body().writeTo(b);
				bodies.add(b.readUtf8());
			}
			else
			{
				bodies.add(null);
			}
			Object next = script.poll();
			if (next == null)
			{
				throw new IOException("FakeHttp: no scripted response for " + r.url());
			}
			if (next instanceof IOException)
			{
				throw (IOException) next;
			}
			Object[] cb = (Object[]) next;
			return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code((Integer) cb[0]).message("x")
				.body(ResponseBody.create(MediaType.parse("application/json"), (String) cb[1])).build();
		}).build();
	}
}
