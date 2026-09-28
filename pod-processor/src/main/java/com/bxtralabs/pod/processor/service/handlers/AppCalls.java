package com.bxtralabs.pod.processor.service.handlers;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Instant;

// Sends an app API request and says what a failure means for doing it again:
//   the request never left (no connection, unknown host): IOException, safe to retry
//   it left but no answer came back: for a call that creates something, UncertainStepException
//   (retried, but the next attempt checks first); for other calls, IOException
public final class AppCalls {

    private AppCalls() {
    }

    public static HttpResponse<String> send(HttpClient http, HttpRequest request, String app, boolean creates)
            throws IOException, UncertainStepException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + app);
        } catch (IOException e) {
            if (neverSent(e) || !creates) {
                throw new IOException("Couldn't reach " + app + ": " + describe(e), e);
            }
            throw new UncertainStepException("No answer from " + app + " (" + describe(e)
                    + "); it may have gone through, so the next try checks before doing it again");
        }
    }

    // A 5xx answer to a create: the app may have created it before failing.
    public static UncertainStepException uncertainServerError(String app, int status, String what) {
        return new UncertainStepException(app + " had a problem (HTTP " + status + ") while creating " + what
                + "; it may have been created anyway, so the next try checks before creating it again");
    }

    // Where to look for an earlier attempt's work: a minute before it started, for clock skew.
    public static String since(long firstStartedAt) {
        return Instant.ofEpochMilli(firstStartedAt - 60_000).toString();
    }

    private static boolean neverSent(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                    || t instanceof UnresolvedAddressException || t instanceof java.net.UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    private static String describe(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
