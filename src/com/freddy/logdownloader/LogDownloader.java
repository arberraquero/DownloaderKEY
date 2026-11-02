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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@DesignerComponent(
    version = 1,
    description = "Descarga archivos HTTP en la carpeta Descargas de Android conservando el nombre del servidor.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    iconName = "images/extension.png"
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET")
public class LogDownloader extends AndroidNonvisibleComponent {
  private final android.content.Context context;

  public LogDownloader(ComponentContainer container) {
    super(container.$form());
    context = container.$context();
  }

  @SimpleFunction(description = "Descarga la URL y guarda el archivo en Descargas. Usa el nombre Content-Disposition del servidor.")
  public void Descargar(final String direccion) {
    if (direccion == null || direccion.trim().isEmpty()) {
      ErrorDescarga("La dirección está vacía.");
      return;
    }
    final String url = direccion.trim();
    new Thread(new Runnable() {
      @Override
      public void run() {
        descargarEnSegundoPlano(url);
      }
    }).start();
  }

  private void descargarEnSegundoPlano(String direccion) {
    HttpURLConnection conexion = null;
    Uri destino = null;
    try {
      conexion = (HttpURLConnection) new URL(direccion).openConnection();
      conexion.setRequestMethod("GET");
      conexion.setConnectTimeout(20000);
      conexion.setReadTimeout(90000);
      conexion.setInstanceFollowRedirects(true);
      conexion.setRequestProperty("Accept", "*/*");
      conexion.connect();

      int codigo = conexion.getResponseCode();
      if (codigo < 200 || codigo >= 300) {
        throw new Exception("Respuesta HTTP " + codigo);
      }

      String nombre = obtenerNombre(conexion.getHeaderField("Content-Disposition"), direccion);
      nombre = limpiarNombre(nombre);
      nombre = nombreUnico(nombre);

      String mime = conexion.getContentType();
      if (mime == null || mime.trim().isEmpty() || mime.toLowerCase(Locale.ROOT).contains("text/html")) {
        mime = "text/plain";
      }

      ContentValues valores = new ContentValues();
      valores.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre);
      valores.put(MediaStore.MediaColumns.MIME_TYPE, mime);
      valores.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
      valores.put(MediaStore.MediaColumns.IS_PENDING, 1);

      ContentResolver resolver = context.getContentResolver();
      destino = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores);
      if (destino == null) {
        throw new Exception("No se pudo crear el archivo en Descargas.");
      }

      try (InputStream entrada = new BufferedInputStream(conexion.getInputStream());
           OutputStream salida = resolver.openOutputStream(destino, "w")) {
        if (salida == null) throw new Exception("No se pudo abrir el archivo de destino.");
        byte[] buffer = new byte[8192];
        int leidos;
        while ((leidos = entrada.read(buffer)) != -1) {
          salida.write(buffer, 0, leidos);
        }
        salida.flush();
      }

      ContentValues finalizado = new ContentValues();
      finalizado.put(MediaStore.MediaColumns.IS_PENDING, 0);
      resolver.update(destino, finalizado, null, null);

      final String nombreFinal = nombre;
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          DescargaCompletada(nombreFinal);
        }
      });
    } catch (Exception e) {
      if (destino != null) {
        try { context.getContentResolver().delete(destino, null, null); } catch (Exception ignorado) {}
      }
      final String error = e.getMessage() == null ? e.toString() : e.getMessage();
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          ErrorDescarga(error);
        }
      });
    } finally {
      if (conexion != null) conexion.disconnect();
    }
  }

  private String obtenerNombre(String disposition, String direccion) {
    if (disposition != null) {
      Matcher extendido = Pattern.compile("(?i)filename\\*\\s*=\\s*UTF-8''([^;]+)").matcher(disposition);
      if (extendido.find()) {
        try { return URLDecoder.decode(extendido.group(1).trim().replace("\"", ""), "UTF-8"); }
        catch (Exception ignorado) {}
      }
      Matcher normal = Pattern.compile("(?i)filename\\s*=\\s*\"?([^\";]+)\"?").matcher(disposition);
      if (normal.find()) return normal.group(1).trim();
    }
    try {
      String path = new URL(direccion).getPath();
      int barra = path.lastIndexOf('/');
      String ultimo = barra >= 0 ? path.substring(barra + 1) : path;
      if (ultimo == null || ultimo.isEmpty() || ultimo.equals("download.html")) return "LOG.txt";
      return URLDecoder.decode(ultimo, "UTF-8");
    } catch (Exception e) {
      return "LOG.txt";
    }
  }

  private String limpiarNombre(String nombre) {
    String limpio = nombre == null ? "" : nombre.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    if (limpio.isEmpty() || limpio.equals(".") || limpio.equals("..")) return "LOG.txt";
    return limpio;
  }

  private String nombreUnico(String original) {
    ContentResolver resolver = context.getContentResolver();
    int punto = original.lastIndexOf('.');
    String base = punto > 0 ? original.substring(0, punto) : original;
    String extension = punto > 0 ? original.substring(punto) : "";
    String candidato = original;
    int n = 1;
    while (existeNombre(resolver, candidato)) {
      candidato = base + " (" + n++ + ")" + extension;
    }
    return candidato;
  }

  private boolean existeNombre(ContentResolver resolver, String nombre) {
    Cursor cursor = null;
    try {
      cursor = resolver.query(
          MediaStore.Downloads.EXTERNAL_CONTENT_URI,
          new String[]{MediaStore.MediaColumns._ID},
          MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
              MediaStore.MediaColumns.RELATIVE_PATH + "=?",
          new String[]{nombre, Environment.DIRECTORY_DOWNLOADS + "/"},
          null);
      return cursor != null && cursor.moveToFirst();
    } catch (Exception e) {
      return false;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  @SimpleEvent(description = "Se dispara al terminar la descarga. Devuelve el nombre guardado.")
  public void DescargaCompletada(String nombreArchivo) {
    EventDispatcher.dispatchEvent(this, "DescargaCompletada", nombreArchivo);
  }

  @SimpleEvent(description = "Se dispara cuando falla la descarga.")
  public void ErrorDescarga(String mensaje) {
    EventDispatcher.dispatchEvent(this, "ErrorDescarga", mensaje);
  }
}
