package com.Generals.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GeneralsX @bugfix Android port mod-launcher 15/09/2026 Cloudflare harder
 * than the direct client can pass. ModDB sits behind Cloudflare, and on
 * networks whose IP has earned a bot verdict (observed on the whole
 * 176.0.59.176 line: ModDB returns its "Just a moment..." challenge to
 * HttpURLConnection from this device AND from a desktop curl alike) the
 * plain HTTP path in ModDbClient.fetchPage() gets 403 forever. A real
 * browser passes the same challenge in a second — it runs the challenge's
 * JavaScript. So: run it. This class loads the URL in a small off-screen
 * WebView (a genuine Chrome engine, with the site's own cookies jar) and
 * hands back the final DOM once the page settles. ModDbClient falls back
 * here on 403/challenge and parses the returned HTML exactly as before;
 * the WebView's cf_clearance cookie then usually lets the direct path work
 * again for a while (CookieManager shares it with the app's HttpURLConnection
 * via the CookieHandler, and ModDbClient echoes it when present).
 *
 * threading: callers are background threads; the WebView itself must be
 * created on the UI thread, hence the Handler dance. One fetch at a time
 * (a static lock) — a challenged site wants pacing, not parallelism.
 */
final class WebViewFetch {
    private static final String TAG = "ModDb";
    private static final Object LOCK = new Object();
    private static final int TIMEOUT_SECONDS = 45;
    /** onPageFinished DOM samples before giving the caller the challenge page. */
    private static final int MAX_CHALLENGE_SAMPLES = 12;

    private WebViewFetch() {
    }

    /** True when the HTML we got still IS the challenge page (not real content). */
    static boolean looksLikeChallenge(String html) {
        return html == null
            || (html.contains("Just a moment...") && html.contains("challenge-platform"))
            || html.contains("Attention Required! | Cloudflare")
            || html.contains("Enable JavaScript and cookies to continue");
    }

    /**
     * Loads {@code url} in a headless WebView and returns the final HTML.
     * @throws IOException on timeout or when the engine cannot start.
     */
    static String fetch(final Activity activity, final String url) throws IOException {
        synchronized (LOCK) {
            final AtomicReference<String> result = new AtomicReference<>(null);
            final AtomicReference<Throwable> error = new AtomicReference<>(null);
            final CountDownLatch done = new CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger samples =
                new java.util.concurrent.atomic.AtomicInteger(0);
            final Handler ui = new Handler(Looper.getMainLooper());

            Runnable uiTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        android.util.Log.i(TAG, "webview fetch starting: " + url);
                        WebView wv = new WebView(activity);
                        // Never rendered: 1x1, off the view hierarchy's visual
                        // layer but still attached so the engine runs.
                        wv.setLayoutParams(new ViewGroup.LayoutParams(1, 1));
                        wv.setAlpha(0f);
                        activity.addContentView(wv,
                            new ViewGroup.LayoutParams(1, 1));

                        WebSettings s = wv.getSettings();
                        s.setJavaScriptEnabled(true);
                        s.setDomStorageEnabled(true);
                        s.setDatabaseEnabled(true);
                        // Match ModDbClient's desktop UA so the site serves the
                        // same markup the regex parser expects.
                        s.setUserAgentString(ModDbClient.USER_AGENT);
                        s.setBlockNetworkImage(true);   // pages parse as text; skip image cost
                        s.setLoadsImagesAutomatically(false);
                        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

                        // Periodic DOM sampler: the challenge usually runs
                        // in place (no navigation), so re-check every 3 s
                        // until it clears; onPageFinished alone would only
                        // sample once for that shape of challenge.
                        final Runnable[] sampler = new Runnable[1];
                        sampler[0] = () -> wv.evaluateJavascript(
                            "document.documentElement.outerHTML",
                            value -> {
                                String html = unquoteJsString(value);
                                if (looksLikeChallenge(html)
                                    && samples.incrementAndGet()
                                        < MAX_CHALLENGE_SAMPLES) {
                                    android.util.Log.i(TAG,
                                        "webview challenge still up (sample "
                                            + samples.get() + "), waiting");
                                    ui.postDelayed(sampler[0], 3000);
                                    return;
                                }
                                if (looksLikeChallenge(html)) {
                                    android.util.Log.w(TAG,
                                        "webview challenge never cleared");
                                } else {
                                    android.util.Log.i(TAG,
                                        "webview dom " + html.length() + " chars");
                                }
                                result.set(html);
                                done.countDown();
                            });

                        wv.setWebViewClient(new WebViewClient() {
                            @Override
                            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                                // The challenge may bounce through a URL or two;
                                // stay on moddb.com, let everything else go.
                                return !r.getUrl().toString().contains("moddb.com");
                            }

                            @Override
                            public void onReceivedSslError(WebView wv2,
                                    android.webkit.SslErrorHandler handler,
                                    android.net.http.SslError error) {
                                // Same TLS-intercepting-network accommodation as
                                // the visible challenge: proceed for ModDB/its
                                // challenge CDN only, cancel everywhere else.
                                String host = error.getUrl() != null
                                    ? android.net.Uri.parse(error.getUrl()).getHost() : null;
                                if (host != null && (host.equals("www.moddb.com")
                                        || host.endsWith(".moddb.com")
                                        || host.endsWith("challenges.cloudflare.com")
                                        || host.endsWith("cloudflare.com"))) {
                                    handler.proceed();
                                } else {
                                    handler.cancel();
                                }
                            }

                            @Override
                            public void onPageFinished(WebView v, String u) {
                                android.util.Log.i(TAG, "webview onPageFinished: " + u);
                                // The challenge page itself finishes loading
                                // BEFORE its JavaScript has run and redirected
                                // -- grabbing the DOM here would freeze the
                                // interstitial as the "result" (observed:
                                // 28.5 KB challenge DOM reported as "challenge
                                // did not clear"). Sample instead; the sampler
                                // keeps re-checking until real content lands.
                                sampler[0].run();
                            }

                            @Override
                            public void onReceivedError(WebView v, WebResourceRequest r,
                                                        android.webkit.WebResourceError e) {
                                if (r.isForMainFrame() && error.get() == null) {
                                    error.set(new IOException(
                                        "webview: " + e.getDescription()));
                                    done.countDown();
                                }
                            }
                        });

                        wv.loadUrl(url);
                    } catch (Throwable t) {
                        error.set(t);
                        done.countDown();
                    }
                }
            };
            ui.post(uiTask);

            try {
                if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    android.util.Log.w(TAG, "webview fetch timed out after "
                        + TIMEOUT_SECONDS + "s");
                    error.set(new IOException("webview fetch timed out"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("cancelled");
            }

            // Tear down regardless of outcome.
            final WebView[] holder = new WebView[1];
            ui.post(() -> {
                // Find and remove any webviews we added (cheap: this activity
                // hosts at most the one).
                ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
                if (root != null) {
                    for (int i = root.getChildCount() - 1; i >= 0; i--) {
                        if (root.getChildAt(i) instanceof WebView) {
                            holder[0] = (WebView) root.getChildAt(i);
                            root.removeViewAt(i);
                        }
                    }
                }
                if (holder[0] != null) {
                    holder[0].destroy();
                }
            });

            if (error.get() != null) {
                IOException e = new IOException(error.get().getMessage(), error.get());
                throw e;
            }
            String html = result.get();
            if (html == null || html.isEmpty()) {
                throw new IOException("webview returned no content");
            }
            return html;
        }
    }

    /**
     * evaluateJavascript returns a JSON string literal ("...\n...").
     * Decode it back to the raw HTML the DOM produced.
     */
    /** Package-visible: ChallengeActivity decodes its own DOM probes too. */
    static String unquoteJsString(String jsonLiteral) {
        if (jsonLiteral == null) {
            return "";
        }
        String s = jsonLiteral.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            out.append((char) Integer.parseInt(
                                s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } else {
                            out.append(n);
                        }
                        break;
                    default: out.append(n); break;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
