package com.freddy.logdownloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.UsesPermissions;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@DesignerComponent(version = 1, description = "Descarga archivos HTTP en Descargas de Android conservando el nombre del servidor.", category = ComponentCategory.EXTENSION, nonVisible = true, iconName = "images/extension.png")
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET")
public class LogDownloader extends AndroidNonvisibleComponent {
  private final android.content.Context context;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public LogDownloader(ComponentContainer container) {
    super(container.$form());
    context = container.$context();
  }

  @SimpleFunction(description = "Descarga la URL y guarda el archivo en Descargas. Usa el nombre enviado por el servidor y evita sobrescribir archivos anteriores.")
  public void Descargar(String url) {
    if (url == null || url.trim().isEmpty()) {
      ErrorDescarga("La URL está vacía.");
      return;
    }
    executor.execute(() -> download(url.trim()));
  }

  private void download(String address) {
    HttpURLConnection conn = null;
    Uri outputUri = null;
    try {
      conn = (HttpURLConnection) new URL(address).openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(20000);
      conn.setReadTimeout(60000);
      conn.setInstanceFollowRedirects(true);
      conn.setRequestProperty("Accept", "*/*");
      conn.connect();
      int status = conn.getResponseCode();
      if (status < 200 || status >= 300) throw new Exception("Respuesta HTTP " + status);

      String filename = fromDisposition(conn.getHeaderField("Content-Disposition"));
      if (filename == null || filename.isEmpty()) filename = fromUrl(address);
      filename = safeName(filename);
      filename = uniqueName(filename);
      String mime = conn.getContentType();
      if (mime == null || mime.toLowerCase(Locale.ROOT).contains("text/html")) mime = "text/plain";

      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
      values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
      values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
      ContentResolver resolver = context.getContentResolver();
      outputUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
      if (outputUri == null) throw new Exception("No se pudo crear el archivo en Descargas.");

      try (InputStream in = new BufferedInputStream(conn.getInputStream());
           OutputStream out = resolver.openOutputStream(outputUri, "w")) {
        if (out == null) throw new Exception("No se pudo abrir el archivo de destino.");
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        out.flush();
      }
      ContentValues done = new ContentValues();
      done.put(MediaStore.MediaColumns.IS_PENDING, 0);
      resolver.update(outputUri, done, null, null);
      final String saved = filename;
      form.runOnUiThread(() -> DescargaCompletada(saved));
    } catch (Exception e) {
      if (outputUri != null) {
        try { context.getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
      }
      final String message = e.getMessage() == null ? e.toString() : e.getMessage();
      form.runOnUiThread(() -> ErrorDescarga(message));
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private String fromDisposition(String header) {
    if (header == null) return null;
    Matcher ext = Pattern.compile("(?i)filename\\*\\s*=\\s*UTF-8''([^;]+)").matcher(header);
    if (ext.find()) {
      try { return URLDecoder.decode(ext.group(1).trim().replace("\"", ""), "UTF-8"); } catch (Exception ignored) {}
    }
    Matcher normal = Pattern.compile("(?i)filename\\s*=\\s*\"?([^\";]+)\"?").matcher(header);
    return normal.find() ? normal.group(1).trim() : null;
  }

  private String fromUrl(String address) {
    try {
      String path = new URL(address).getPath();
      String name = path.substring(path.lastIndexOf('/') + 1);
      return name.isEmpty() ? "LOG.txt" : URLDecoder.decode(name, "UTF-8");
    } catch (Exception e) { return "LOG.txt"; }
  }

  private String safeName(String name) {
    String result = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    return result.isEmpty() ? "LOG.txt" : result;
  }

  private String uniqueName(String original) {
    String stem = original, ext = "";
    int dot = original.lastIndexOf('.');
    if (dot > 0) { stem = original.substring(0, dot); ext = original.substring(dot); }
    String candidate = original;
    int i = 1;
    while (exists(candidate)) candidate = stem + " (" + i++ + ")" + ext;
    return candidate;
  }

  private boolean exists(String name) {
    Cursor c = null;
    try {
      c = context.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
          new String[]{MediaStore.MediaColumns._ID},
          MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
          new String[]{name, Environment.DIRECTORY_DOWNLOADS + "/"}, null);
      return c != null && c.moveToFirst();
    } catch (Exception e) { return false; }
    finally { if (c != null) c.close(); }
  }

  @SimpleEvent(description = "Descarga terminada. Devuelve el nombre del archivo guardado.")
  public void DescargaCompletada(String nombreArchivo) {
    EventDispatcher.dispatchEvent(this, "DescargaCompletada", nombreArchivo);
  }

  @SimpleEvent(description = "Error durante la descarga.")
  public void ErrorDescarga(String mensaje) {
    EventDispatcher.dispatchEvent(this, "ErrorDescarga", mensaje);
  }
}
