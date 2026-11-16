package com.a4print.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintDocumentInfo;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 7;
    private static final int REQ_PDF = 8;
    private WebView web;
    private WebView printView;
    private ValueCallback<Uri[]> fileCb;
    private Uri camUri;
    private final ArrayList<JSONObject> shared = new ArrayList<JSONObject>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public String takeShared() {
                synchronized (shared) {
                    JSONArray a = new JSONArray();
                    for (JSONObject o : shared) a.put(o);
                    shared.clear();
                    return a.toString();
                }
            }

            @JavascriptInterface
            public void pickPdf() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent g = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        g.addCategory(Intent.CATEGORY_OPENABLE);
                        g.setType("application/pdf");
                        try { startActivityForResult(g, REQ_PDF); } catch (Exception e) { }
                    }
                });
            }

            @JavascriptInterface
            public void print(final String b64, final boolean bw) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { doPrint(b64, bw); }
                });
            }
        }, "Android");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                camUri = null;
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, "scan_" + System.currentTimeMillis() + ".jpg");
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    camUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                } catch (Exception e) { camUri = null; }
                Intent cam = null;
                if (camUri != null) {
                    cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    cam.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
                }
                Intent it;
                if (p.isCaptureEnabled() && cam != null) {
                    it = cam;
                } else {
                    Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                    g.addCategory(Intent.CATEGORY_OPENABLE);
                    g.setType("image/*");
                    it = Intent.createChooser(g, getString(R.string.pick));
                    if (cam != null) it.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                }
                try {
                    startActivityForResult(it, REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        handle(getIntent());
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
        web.evaluateJavascript("typeof pullShared==='function'&&pullShared()", null);
    }

    @SuppressWarnings("deprecation")
    private void handle(Intent i) {
        if (i == null || i.getAction() == null) return;
        String a = i.getAction(), t = i.getType();
        try {
            if (Intent.ACTION_SEND.equals(a)) {
                if (t != null && t.equals("application/pdf")) {
                    askPdf((Uri) i.getParcelableExtra(Intent.EXTRA_STREAM));
                } else if (t != null && t.startsWith("image/")) {
                    addImage((Uri) i.getParcelableExtra(Intent.EXTRA_STREAM));
                } else {
                    addText(i.getStringExtra(Intent.EXTRA_TEXT));
                }
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
                ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (l != null) for (Uri u : l) addImage(u);
            } else if (Intent.ACTION_VIEW.equals(a)) {
                if ("application/pdf".equals(t)) askPdf(i.getData());
                else addImage(i.getData());
            } else if (Intent.ACTION_PROCESS_TEXT.equals(a)) {
                CharSequence c = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                if (c != null) addText(c.toString());
            }
        } catch (Exception e) { }
        setIntent(new Intent());
    }

    private void addText(String s) throws Exception {
        if (s == null || s.trim().length() == 0) return;
        JSONObject j = new JSONObject();
        j.put("type", "text");
        j.put("text", s.trim());
        synchronized (shared) { shared.add(j); }
    }

    private void addImage(Uri u) throws Exception {
        if (u == null) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(u);
        BitmapFactory.decodeStream(in, null, o);
        in.close();
        int s = Math.max(o.outWidth, o.outHeight), ss = 1;
        while (s / ss > 3000) ss *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = ss;
        in = getContentResolver().openInputStream(u);
        Bitmap bm = BitmapFactory.decodeStream(in, null, o);
        in.close();
        if (bm == null) return;
        int deg = 0;
        try {
            in = getContentResolver().openInputStream(u);
            ExifInterface ex = new ExifInterface(in);
            int or = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            in.close();
            if (or == 6) deg = 90; else if (or == 3) deg = 180; else if (or == 8) deg = 270;
        } catch (Exception e) { }
        if (deg != 0) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 92, bo);
        JSONObject j = new JSONObject();
        j.put("type", "image");
        j.put("data", "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
        synchronized (shared) { shared.add(j); }
    }

    private void doPrint(String b64, final boolean bw) {
        final WebView pw = new WebView(this);
        printView = pw;
        pw.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                PrintDocumentAdapter ad = v.createPrintDocumentAdapter("A4");
                PrintAttributes at = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .setColorMode(bw ? PrintAttributes.COLOR_MODE_MONOCHROME : PrintAttributes.COLOR_MODE_COLOR)
                        .build();
                pm.print("A4", ad, at);
            }
        });
        String html = "<html><head><meta charset=\"utf-8\"><style>@page{size:A4;margin:0}"
                + "html,body{margin:0;padding:0}img{width:210mm;height:296.5mm;display:block}</style></head>"
                + "<body><img src=\"data:image/jpeg;base64," + b64 + "\"></body></html>";
        pw.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private File copyPdf(Uri u) throws Exception {
        File f = new File(getCacheDir(), "in.pdf");
        InputStream in = getContentResolver().openInputStream(u);
        FileOutputStream out = new FileOutputStream(f);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.close();
        in.close();
        return f;
    }

    private void askPdf(Uri u) {
        if (u == null) return;
        File tmp = null;
        try { tmp = copyPdf(u); } catch (Exception e) { return; }
        final File f = tmp;
        int pages = 0;
        try {
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer r = new PdfRenderer(fd);
            pages = r.getPageCount();
            r.close();
            fd.close();
        } catch (Exception e) { }
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText("1");
        et.setHint(getString(R.string.page_hint));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(48, 16, 48, 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder d = new AlertDialog.Builder(this);
        d.setTitle(getString(R.string.pdf_title) + (pages > 0 ? " (" + pages + ")" : ""));
        d.setView(box);
        d.setPositiveButton(R.string.pdf_print, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface x, int w) { printPdf(f); }
        });
        d.setNegativeButton(R.string.pdf_edit, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface x, int w) {
                int p = 1;
                try { p = Integer.parseInt(et.getText().toString().trim()); } catch (Exception e) { }
                editPdfPage(f, p);
            }
        });
        d.setNeutralButton(R.string.pdf_cancel, null);
        d.show();
    }

    private void printPdf(File f) {
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        PrintAttributes at = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build();
        pm.print("PDF", new PdfAdapter(f), at);
    }

    private void editPdfPage(final File f, final int page) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                    PdfRenderer r = new PdfRenderer(fd);
                    int idx = Math.max(0, Math.min(r.getPageCount() - 1, page - 1));
                    PdfRenderer.Page pg = r.openPage(idx);
                    float k = Math.min(4f, 3000f / Math.max(pg.getWidth(), pg.getHeight()));
                    Bitmap bm = Bitmap.createBitmap((int) (pg.getWidth() * k), (int) (pg.getHeight() * k), Bitmap.Config.ARGB_8888);
                    bm.eraseColor(Color.WHITE);
                    pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    pg.close();
                    r.close();
                    fd.close();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.JPEG, 92, bo);
                    JSONObject j = new JSONObject();
                    j.put("type", "image");
                    j.put("fill", true);
                    j.put("data", "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
                    synchronized (shared) { shared.add(j); }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            web.evaluateJavascript("typeof pullShared==='function'&&pullShared()", null);
                        }
                    });
                } catch (Exception e) { }
            }
        }).start();
    }

    static class PdfAdapter extends PrintDocumentAdapter {
        private final File file;
        PdfAdapter(File f) { file = f; }

        @Override
        public void onLayout(PrintAttributes o, PrintAttributes n, CancellationSignal c, LayoutResultCallback cb, Bundle e) {
            if (c.isCanceled()) { cb.onLayoutCancelled(); return; }
            cb.onLayoutFinished(new PrintDocumentInfo.Builder("document.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true);
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal c, WriteResultCallback cb) {
            try {
                InputStream in = new FileInputStream(file);
                OutputStream out = new FileOutputStream(dest.getFileDescriptor());
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                out.flush();
                in.close();
                cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception ex) {
                cb.onWriteFailed(ex.toString());
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PDF && res == RESULT_OK && data != null && data.getData() != null) {
            askPdf(data.getData());
        }
        if (req == REQ_FILE && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK) {
                if (data != null && data.getData() != null) r = new Uri[]{data.getData()};
                else if (camUri != null) r = new Uri[]{camUri};
            }
            if (camUri != null && (r == null || r[0] != camUri)) {
                try { getContentResolver().delete(camUri, null, null); } catch (Exception e) { }
            }
            fileCb.onReceiveValue(r);
            fileCb = null;
            camUri = null;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.evaluateJavascript("typeof pullShared==='function'&&pullShared()", null);
    }
}