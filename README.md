# DownloaderKEY — extensión LogDownloader

Este repositorio contiene el código fuente y el flujo de GitHub Actions para generar `LogDownloader.aix`.

## Archivos
- `src/com/freddy/logdownloader/LogDownloader.java`: componente Android.
- `.github/workflows/build.yml`: compilación automática y publicación del artefacto.
- `README.md`: instrucciones.

## Compilación
1. Sube todos los archivos manteniendo exactamente las carpetas.
2. En GitHub abre la pestaña **Actions**.
3. Entra en **Compilar extension AIX**.
4. Abre la ejecución más reciente que haya terminado correctamente.
5. En **Artifacts**, descarga `LogDownloader-aix`.
6. Dentro del ZIP descargado encontrarás `LogDownloader.aix`.

## Uso en MIT App Inventor
1. Importa `LogDownloader.aix` desde **Extensiones > Importar extensión**.
2. Añade el componente no visible `LogDownloader1`.
3. En el evento `WifiConnector1.Connected`, llama a:

   `LogDownloader1.Descargar`

   con esta URL:

   `http://192.168.4.1/download.html?spage=1&npage=100&action=download`

4. En `LogDownloader1.DescargaCompletada(nombreArchivo)`, muestra un aviso con el nombre.
5. En `LogDownloader1.ErrorDescarga(mensaje)`, muestra el error.

## Comportamiento de guardado
- Guarda en la carpeta pública **Descargas** de Android usando MediaStore.
- Intenta conservar el nombre recibido en la cabecera HTTP `Content-Disposition`.
- Si el nombre ya existe, genera un nombre nuevo como `registro (1).txt`.
- No necesita permisos antiguos de almacenamiento en Android 10 o posterior.

## Nota técnica importante
La descarga se ejecuta después de `WifiConnector1.Connected`. La extensión WiFi existente debe haber asociado la red del dispositivo al proceso de la aplicación. Si el servidor devuelve HTML en vez del archivo en esta petición HTTP directa, la extensión no puede convertir ese HTML en el LOG; habrá que inspeccionar las cabeceras y el comportamiento real del servidor.
