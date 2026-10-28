package fr.mohae.hygiene;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.util.Base64;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQ_SAVE_BACKUP = 1;
    private static final int REQ_OPEN_BACKUP = 2;
    private static final int REQ_SAVE_PDF = 3;

    private WebView web;
    private String pendingBackup;
    private byte[] pendingPdf;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#F6EEE3"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // records live in localStorage
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    /** Methods the page calls as window.Android.* */
    private class Bridge {

        /** Prints the month's sheets (already prepared in #printarea) – user picks "Save as PDF" and a folder, e.g. Google Drive. */
        @JavascriptInterface
        public void savePdf(final String name) {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                PrintDocumentAdapter adapter = web.createPrintDocumentAdapter(name);
                PrintAttributes attrs = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                        .build();
                pm.print(name, adapter, attrs);
            });
        }

        /** Receives a finished PDF (base64) from the page and asks where to save it (Google Drive shows up there). */
        @JavascriptInterface
        public void savePdfData(final String base64, final String fileName) {
            try {
                pendingPdf = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception e) {
                pendingPdf = null;
                js("window.onPdfResult && window.onPdfResult(false)");
                return;
            }
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/pdf");
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                startActivityForResult(i, REQ_SAVE_PDF);
            });
        }

        /** Opens the system "save as" screen (Google Drive shows up there) for a backup file. */
        @JavascriptInterface
        public void saveBackup(final String json, final String fileName) {
            pendingBackup = json;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                startActivityForResult(i, REQ_SAVE_BACKUP);
            });
        }

        /** Opens the system file picker to choose a backup file. */
        @JavascriptInterface
        public void openBackup() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_OPEN_BACKUP);
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;

        if (requestCode == REQ_SAVE_PDF) {
            boolean ok = false;
            if (uri != null && pendingPdf != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(pendingPdf);
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
            }
            pendingPdf = null;
            js("window.onPdfResult && window.onPdfResult(" + ok + ")");
        } else if (requestCode == REQ_SAVE_BACKUP) {
            boolean ok = false;
            if (uri != null && pendingBackup != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
            }
            pendingBackup = null;
            js("window.onBackupResult && window.onBackupResult(" + ok + ")");
        } else if (requestCode == REQ_OPEN_BACKUP && uri != null) {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                js("window.restoreBackup && window.restoreBackup(" + JSONObject.quote(text) + ")");
            } catch (Exception e) {
                js("window.restoreBackup && window.restoreBackup('')");
            }
        }
    }

    private void js(final String code) {
        web.post(() -> web.evaluateJavascript(code, null));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }
}
