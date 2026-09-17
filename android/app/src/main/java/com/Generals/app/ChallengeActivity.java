/*
** GeneralsX Android launcher shell
** Copyright 2026 Alaa91H
**
** This program is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation, either version 3 of the License, or
** (at your option) any later version.
*/

package com.Generals.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * GeneralsX @feature Android port mod-launcher 15/09/2026 Visible escape
 * hatch for Cloudflare. On networks whose IP has earned a bot verdict
 * (observed on the test device's whole line: ModDB answers the plain HTTP
 * client AND the headless WebView with "Just a moment..." forever), a real
 * user can still pass the challenge in a browser — because a browser shows
 * it and runs its interactive checks. This activity is that browser, in-app:
 * a full-size WebView over ModDB's challenge page; when the DOM stops being
 * the challenge, the earned cf_clearance cookie is already in the shared
 * CookieManager, so ModDbClient's direct path works again, and the caller
 * (waiting in Challenge.awaitResult) retries transparently.
 *
 * threading: launch()/awaitResult() are called from a background fetch
 * thread; the static result hand-off is a latch, mirroring WebViewFetch's
 * one-at-a-time pacing (the two share ModDbClient's single-flight fetch
 * path, so overlap is impossible by construction).
 */
public class ChallengeActivity extends Activity {

    private static final String EXTRA_URL = "gen_challenge_url";
    private static final String TAG = "ModDb";

    /** One-flight result hand-off from the activity to the waiting fetcher. */
    private static final java.util.concurrent.CountDownLatch[] DONE =
        new java.util.concurrent.CountDownLatch[1];
    private static volatile boolean sCleared;

    private WebView webView;

    /** Opens the visible challenge over {@code url}. Call from any thread. */
    static void launch(Activity host, String url) {
        DONE[0] = new java.util.concurrent.CountDownLatch(1);
        sCleared = false;
        Intent i = new Intent(host, ChallengeActivity.class);
        i.putExtra(EXTRA_URL, url);
        host.startActivity(i);
    }

    /**
     * Waits for the user to finish (or abandon) the challenge.
     * @return true = cleared (cookies earned), false = solved-but-failed or
     *         user backed out, null = caller gave up waiting.
     */
    static Boolean awaitResult(long timeoutMs) {
        java.util.concurrent.CountDownLatch latch = DONE[0];
        if (latch == null) {
            return null;
        }
        try {
            if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return sCleared;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gen_background));
        int pad = UiKit.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText(R.string.challenge_title);
        title.setTextSize(20f);
        title.setTextColor(UiKit.color(this, R.color.gen_on_surface));
        root.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.TextView body = new android.widget.TextView(this);
        body.setText(R.string.challenge_body);
        body.setTextSize(14f);
        body.setTextColor(UiKit.color(this, R.color.gen_on_surface_faint));
        root.addView(body, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // Real browser fingerprint: the challenge grades the *whole* client,
        // and a desktop UA on a phone engine is one of the flags it scores.
        s.setUserAgentString(WebSettings.getDefaultUserAgent(this));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().contains("moddb.com");
            }

            @Override
            public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                // GeneralsX @bugfix Android port launcher-ui 17/09/2026 On the
                // test network (a router-level AdGuard that intercepts TLS)
                // Chrome browses ModDB fine while WebView dies with
                // net_error -202 BEFORE the challenge even renders —
                // networkSecurityConfig does not reach chromium's trust store.
                // Proceed only for the host we opened this browser for and
                // its CDN: the alternative is the feature being unusable on
                // any TLS-intercepting home network.
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
                checkCleared(v);
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText(R.string.challenge_hint);
        hint.setTextSize(12f);
        hint.setTextColor(UiKit.color(this, R.color.gen_on_surface_faint));
        root.addView(hint, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.Button reload = new android.widget.Button(this);
        reload.setText(R.string.mods_retry);
        reload.setOnClickListener(v -> webView.reload());
        root.addView(reload, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        webView.loadUrl(getIntent().getStringExtra(EXTRA_URL));
    }

    /** Samples the DOM; once it is real content (not the interstitial), done. */
    private void checkCleared(WebView v) {
        v.evaluateJavascript("document.documentElement.outerHTML", value -> {
            String html = WebViewFetch.unquoteJsString(value);
            // "Not a challenge" is not enough: an error page (observed:
            // WebView's own "net::ERR" SSL-failure page on a network whose
            // user CA WebView did not trust — now fixed by the app's
            // network security config) is also not a challenge, and calling
            // that a success made the client retry a dead cookie and fail
            // with a confusing 403. Require real ModDB content instead.
            if (isRealModDbPage(html)) {
                sCleared = true;
                Toast.makeText(this, R.string.challenge_done,
                    Toast.LENGTH_SHORT).show();
                // Let the cf_clearance cookie settle into the store first.
                CookieManager.getInstance().flush();
                webView.postDelayed(this::finish, 600);
            }
        });
    }

    /** True when the DOM is genuine ModDB markup, not an error/blank page. */
    private static boolean isRealModDbPage(String html) {
        if (html == null || html.length() < 4096) {
            return false; // real pages are tens of KB; error pages are tiny
        }
        return html.contains("moddb.com")
            && (html.contains("<body") || html.contains("MODDB"));
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        // The waiter must never hang: backing out counts as "not cleared".
        java.util.concurrent.CountDownLatch latch = DONE[0];
        if (latch != null) {
            latch.countDown();
            DONE[0] = null;
        }
        super.onDestroy();
    }
}
