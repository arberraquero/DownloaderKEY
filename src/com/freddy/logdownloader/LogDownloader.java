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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@DesignerComponent(
    version = 2,
    description = "Descarga el LOG del AirDrive y lo guarda en Descargas conservando el nombre del servidor.",
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

  @SimpleFunction(description = "Abre primero la página de descarga para obtener sus cookies y después solicita el LOG.")
  public void Descargar(final String direccion) {
    if (direccion == null || direccion.trim().length() == 0) {
      ErrorDescarga("La dirección está vacía.");
      return;
    }
    final String url = direccion.trim();
    Thread hilo = new Thread(new Runnable() {
      @Override
      public void run() {
        descargarEnSegundoPlano(url);
      }
    });
    hilo.start();
  }

  private void descargarEnSegundoPlano(String direccion) {
    HttpURLConnection pagina = null;
    HttpURLConnection descarga = null;
    Uri destino = null;
    try {
      URL urlDescarga = new URL(direccion);
      String urlPagina = new StringBuilder(urlDescarga.getProtocol())
          .append("://").append(urlDescarga.getAuthority())
          .append(urlDescarga.getPath()).toString();

      pagina = (HttpURLConnection) new URL(urlPagina).openConnection();
      configurarConexion(pagina, urlPagina);
      pagina.setRequestMethod("GET");
      int codigoPagina = pagina.getResponseCode();
      if (codigoPagina < 200 || codigoPagina >= 400) {
        throw new Exception(new StringBuilder("No se pudo abrir la página AirDrive. HTTP ").append(codigoPagina).toString());
      }

      StringBuilder cookies = new StringBuilder();
      Map<String, List<String>> cabeceras = pagina.getHeaderFields();
      if (cabeceras != null) {
        List<String> valoresCookie = cabeceras.get("Set-Cookie");
        if (valoresCookie == null) valoresCookie = cabeceras.get("set-cookie");
        if (valoresCookie != null) {
          int i;
          for (i = 0; i < valoresCookie.size(); i++) {
            String cookie = valoresCookie.get(i);
            if (cookie != null) {
              int puntoComa = cookie.indexOf(';');
              if (cookies.length() > 0) cookies.append("; ");
              cookies.append(puntoComa >= 0 ? cookie.substring(0, puntoComa) : cookie);
            }
          }
        }
      }
      pagina.disconnect();
      pagina = null;

      descarga = (HttpURLConnection) new URL(direccion).openConnection();
      configurarConexion(descarga, urlPagina);
      descarga.setRequestMethod("GET");
      if (cookies.length() > 0) descarga.setRequestProperty("Cookie", cookies.toString());
      descarga.connect();

      int codigo = descarga.getResponseCode();
      if (codigo < 200 || codigo >= 300) {
        throw new Exception(new StringBuilder("Respuesta HTTP ").append(codigo).toString());
      }

      String tipo = descarga.getContentType();
      InputStream entrada = new BufferedInputStream(descarga.getInputStream());
      byte[] muestra = new byte[512];
      int cantidad = entrada.read(muestra);
      if (cantidad < 0) cantidad = 0;
      String inicio = new String(muestra, 0, cantidad, "ISO-8859-1").trim().toLowerCase(Locale.US);
      if ((tipo != null && tipo.toLowerCase(Locale.US).contains("text/html")) ||
          inicio.startsWith("<!doctype html") || inicio.startsWith("<html") || inicio.startsWith("<head") || inicio.startsWith("<body")) {
        entrada.close();
        throw new Exception("AirDrive ha devuelto la página HTML, no el LOG. La petición no ha generado el archivo.");
      }

      String nombre = obtenerNombre(descarga.getHeaderField("Content-Disposition"), direccion);
      nombre = limpiarNombre(nombre);
      nombre = nombreUnico(nombre);
      String mime = tipo;
      if (mime == null || mime.trim().length() == 0) mime = "text/plain";

      ContentValues valores = new ContentValues();
      valores.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre);
      valores.put(MediaStore.MediaColumns.MIME_TYPE, mime);
      valores.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
      valores.put(MediaStore.MediaColumns.IS_PENDING, 1);
      ContentResolver resolver = context.getContentResolver();
      destino = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores);
      if (destino == null) {
        entrada.close();
        throw new Exception("No se pudo crear el archivo en Descargas.");
      }

      OutputStream salida = resolver.openOutputStream(destino, "w");
      if (salida == null) {
        entrada.close();
        throw new Exception("No se pudo abrir el archivo de destino.");
      }
      try {
        if (cantidad > 0) salida.write(muestra, 0, cantidad);
        byte[] buffer = new byte[8192];
        int leidos;
        while ((leidos = entrada.read(buffer)) != -1) {
          salida.write(buffer, 0, leidos);
        }
        salida.flush();
      } finally {
        try { entrada.close(); } catch (Exception ignorado) { }
        try { salida.close(); } catch (Exception ignorado) { }
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
        try { context.getContentResolver().delete(destino, null, null); } catch (Exception ignorado) { }
      }
      final String error = e.getMessage() == null ? e.toString() : e.getMessage();
      form.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          ErrorDescarga(error);
        }
      });
    } finally {
      if (pagina != null) pagina.disconnect();
      if (descarga != null) descarga.disconnect();
    }
  }

  private void configurarConexion(HttpURLConnection conexion, String referente) {
    conexion.setConnectTimeout(20000);
    conexion.setReadTimeout(90000);
    conexion.setInstanceFollowRedirects(true);
    conexion.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
    conexion.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    conexion.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
    conexion.setRequestProperty("Referer", referente);
    conexion.setRequestProperty("Connection", "close");
    conexion.setRequestProperty("Cache-Control", "no-cache");
  }

  private String obtenerNombre(String disposition, String direccion) {
    if (disposition != null) {
      Matcher extendido = Pattern.compile("(?i)filename\\*\\s*=\\s*UTF-8''([^;]+)").matcher(disposition);
      if (extendido.find()) {
        try { return URLDecoder.decode(extendido.group(1).trim().replace("\"", ""), "UTF-8"); }
        catch (Exception ignorado) { }
      }
      Matcher normal = Pattern.compile("(?i)filename\\s*=\\s*\"?([^\";]+)\"?").matcher(disposition);
      if (normal.find()) return normal.group(1).trim();
    }
    return "AirDrive_LOG.txt";
  }

  private String limpiarNombre(String nombre) {
    String limpio = nombre == null ? "" : nombre.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    if (limpio.length() == 0 || limpio.equals(".") || limpio.equals("..")) return "AirDrive_LOG.txt";
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
      candidato = new StringBuilder(base).append(" (").append(n++).append(")").append(extension).toString();
    }
    return candidato;
  }

  private boolean existeNombre(ContentResolver resolver, String nombre) {
    Cursor cursor = null;
    try {
      cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
          new String[] { MediaStore.MediaColumns._ID },
          new StringBuilder(MediaStore.MediaColumns.DISPLAY_NAME).append("=? AND ")
              .append(MediaStore.MediaColumns.RELATIVE_PATH).append("=?").toString(),
          new String[] { nombre, new StringBuilder(Environment.DIRECTORY_DOWNLOADS).append("/").toString() }, null);
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
