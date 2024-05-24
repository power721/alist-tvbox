package cn.har01d.alist_tvbox.youtube;

import com.github.kiulian.downloader.Config;
import com.github.kiulian.downloader.YoutubeException;
import com.github.kiulian.downloader.downloader.Downloader;
import com.github.kiulian.downloader.downloader.YoutubeCallback;
import com.github.kiulian.downloader.downloader.YoutubeProgressCallback;
import com.github.kiulian.downloader.downloader.request.Request;
import com.github.kiulian.downloader.downloader.request.RequestVideoFileDownload;
import com.github.kiulian.downloader.downloader.request.RequestVideoStreamDownload;
import com.github.kiulian.downloader.downloader.request.RequestWebpage;
import com.github.kiulian.downloader.downloader.response.ResponseImpl;
import com.github.kiulian.downloader.model.videos.formats.Format;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;

import static com.github.kiulian.downloader.model.Utils.closeSilently;

public class MyDownloader implements Downloader {
    private static final Logger logger = LoggerFactory.getLogger(MyDownloader.class);

    private final InheritableThreadLocal<HttpServletResponse> httpServletResponse = new InheritableThreadLocal<>();

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int PART_LENGTH = 2 * 1024 * 1024;

    private final Config config;

    public MyDownloader(Config config) {
        this.config = config;
    }

    public void setHttpServletResponse(HttpServletResponse response) {
        httpServletResponse.set(response);
    }

    @Override
    public ResponseImpl<String> downloadWebpage(RequestWebpage request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<String> result = executorService.submit(() -> download(request));
            return ResponseImpl.fromFuture(result);
        }
        try {
            String result = download(request);
            return ResponseImpl.from(result);
        } catch (IOException | YoutubeException e) {
            return ResponseImpl.error(e);
        }
    }

    private String download(RequestWebpage request) throws IOException, YoutubeException {
        String downloadUrl = request.getDownloadUrl();
        Map<String, String> headers = request.getHeaders();
        YoutubeCallback<String> callback = request.getCallback();
        int maxRetries = request.getMaxRetries() != null ? request.getMaxRetries() : config.getMaxRetries();
        Proxy proxy = request.getProxy();

        IOException exception;
        StringBuilder result = new StringBuilder();
        do {
            try {
                HttpURLConnection urlConnection = openConnection(downloadUrl, headers, proxy, config.isCompressionEnabled());
                urlConnection.setRequestMethod(request.getMethod());
                if (request.getBody() != null) {
                    urlConnection.setDoOutput(true);
                    try (OutputStreamWriter outputWriter = new OutputStreamWriter(urlConnection.getOutputStream(), "UTF-8")) {
                        outputWriter.write(request.getBody());
                        outputWriter.flush();
                    }
                }
                int responseCode = urlConnection.getResponseCode();
                if (responseCode != 200 && responseCode != 206) {
                    YoutubeException.DownloadException e = new YoutubeException.DownloadException("Failed to download: HTTP " + responseCode);
                    if (callback != null) {
                        callback.onError(e);
                    }
                    throw e;
                }

                int contentLength = urlConnection.getContentLength();
                if (contentLength == 0) {
                    YoutubeException.DownloadException e = new YoutubeException.DownloadException("Failed to download: Response is empty");
                    if (callback != null) {
                        callback.onError(e);
                    }
                    throw e;
                }

                BufferedReader br = null;
                try {
                    InputStream in = urlConnection.getInputStream();
                    if (config.isCompressionEnabled() && "gzip".equals(urlConnection.getHeaderField("content-encoding"))) {
                        in = new GZIPInputStream(in);
                    }
                    br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    String inputLine;
                    while ((inputLine = br.readLine()) != null)
                        result.append(inputLine).append('\n');
                } finally {
                    closeSilently(br);
                }
                // reset error in case of successful retry
                exception = null;
            } catch (IOException e) {
                exception = e;
                maxRetries--;
            }
        } while (exception != null && maxRetries > 0);

        if (exception != null) {
            if (callback != null) {
                callback.onError(exception);
            }
            throw exception;
        }

        String resultString = result.toString();
        if (callback != null) {
            callback.onFinished(resultString);
        }
        return resultString;
    }

    @Override
    public ResponseImpl<File> downloadVideoAsFile(RequestVideoFileDownload request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<File> result = executorService.submit(() -> download(request));
            return ResponseImpl.fromFuture(result);
        }
        try {
            File result = download(request);
            return ResponseImpl.from(result);
        } catch (IOException e) {
            return ResponseImpl.error(e);
        }
    }

    @Override
    public ResponseImpl<Void> downloadVideoAsStream(RequestVideoStreamDownload request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<Void> result = executorService.submit(() -> download(request));
            return ResponseImpl.fromFuture(result);
        }
        try {
            download(request);
            return ResponseImpl.from(null);
        } catch (IOException e) {
            return ResponseImpl.error(e);
        }
    }

    private File download(RequestVideoFileDownload request) throws IOException {
        Format format = request.getFormat();
        File outputFile = request.getOutputFile();
        YoutubeCallback<File> callback = request.getCallback();
        OutputStream os = new FileOutputStream(outputFile);

        download(request, format, os);
        if (callback != null) {
            callback.onFinished(outputFile);
        }
        return outputFile;
    }

    private Void download(RequestVideoStreamDownload request) throws IOException {
        Format format = request.getFormat();
        YoutubeCallback<Void> callback = request.getCallback();
        OutputStream os = request.getOutputStream();

        download(request, format, os);
        if (callback != null) {
            callback.onFinished(null);
        }
        return null;
    }

    private void download(Request<?, ?> request, Format format, OutputStream os) throws IOException {
        Map<String, String> headers = request.getHeaders();
        YoutubeCallback<?> callback = request.getCallback();
        int maxRetries = request.getMaxRetries() != null ? request.getMaxRetries() : config.getMaxRetries();
        Proxy proxy = request.getProxy();

        IOException exception;
        do {
            try {
//                if (format.isAdaptive() && format.contentLength() != null) {
//                    downloadByPart(format, os, headers, proxy, callback);
//                } else
                {
                    downloadStraight(format, os, headers, proxy, callback);
                }
                // reset error in case of successful retry
                exception = null;
            } catch (IOException e) {
                exception = e;
            } finally {
                closeSilently(os);
            }
        } while (exception != null && maxRetries > 0);

        if (exception != null) {
            if (callback != null) {
                callback.onError(exception);
            }
            throw exception;
        }
    }

    // Downloads the format in one single request
    private void downloadStraight(Format format, OutputStream os, Map<String, String> headers, Proxy proxy, YoutubeCallback<?> callback) throws IOException {
        HttpURLConnection urlConnection = openConnection(format.url(), headers, proxy, false);
        int responseCode = urlConnection.getResponseCode();
        if (responseCode != 200 && responseCode != 206) {
            throw new RuntimeException("Failed to download: HTTP " + responseCode);
        }
        var response = httpServletResponse.get();
        if (response != null) {
            response.setStatus(responseCode);
            urlConnection.getHeaderFields().forEach((key, value) -> response.setHeader(key, value.get(0)));
        }
        int contentLength = urlConnection.getContentLength();
        InputStream is = urlConnection.getInputStream();

        byte[] buffer = new byte[BUFFER_SIZE];
        if (callback == null) {
            copyAndCloseInput(is, os, buffer);
        } else {
            copyAndCloseInput(is, os, buffer, 0, contentLength, callback);
        }
    }

    // Downloads the format part by part, with as many requests as needed
    private void downloadByPart(Format format, OutputStream os, Map<String, String> headers, Proxy proxy, YoutubeCallback<?> listener) throws IOException {
        long done = 0;
        int partNumber = 0;

        final String pathPrefix = "&cver=" + format.clientVersion() + "&range=";
        final long contentLength = format.contentLength();
        byte[] buffer = new byte[BUFFER_SIZE];

        while (done < contentLength) {
            long toRead = PART_LENGTH;
            if (done + toRead > contentLength) {
                toRead = (int) (contentLength - done);
            }

            partNumber++;
            String partUrl = format.url() + pathPrefix
                    + done + "-" + (done + toRead - 1)    // range first-last byte positions
                    + "&rn=" + partNumber;                // part number

            HttpURLConnection urlConnection = openConnection(partUrl, headers, proxy, false);
            int responseCode = urlConnection.getResponseCode();
            if (responseCode != 200 && responseCode != 206) {
                throw new RuntimeException("Failed to download: HTTP " + responseCode);
            }

            InputStream is = urlConnection.getInputStream();
            if (listener == null) {
                done += copyAndCloseInput(is, os, buffer);
            } else {
                done += copyAndCloseInput(is, os, buffer, done, contentLength, listener);
            }
        }
    }

    // Copies as many bytes as possible then closes input stream
    private static long copyAndCloseInput(InputStream is, OutputStream os, byte[] buffer, long offset, long totalLength, final YoutubeCallback<?> listener) throws IOException {
        long done = 0;

        try {
            int read = 0;
            long lastProgress = offset == 0 ? 0 : (offset * 100) / totalLength;

            while ((read = is.read(buffer)) != -1) {
                if (Thread.interrupted()) {
                    throw new CancellationException();
                }
                os.write(buffer, 0, read);
                done += read;
                long progress = ((offset + done) * 100) / totalLength;
                if (progress > lastProgress) {
                    if (listener instanceof YoutubeProgressCallback) {
                        ((YoutubeProgressCallback<?>) listener).onDownloading((int) progress);
                    }
                    lastProgress = progress;
                }
            }
        } finally {
            closeSilently(is);
        }
        return done;
    }

    private static long copyAndCloseInput(InputStream is, OutputStream os, byte[] buffer) throws IOException {
        long done = 0;

        try {
            int count = 0;
            while ((count = is.read(buffer)) != -1) {
                if (Thread.interrupted()) {
                    throw new CancellationException();
                }
                os.write(buffer, 0, count);
                done += count;
            }
        } finally {
            closeSilently(is);
        }
        return done;
    }


    private HttpURLConnection openConnection(String httpUrl, Map<String, String> headers, Proxy proxy, boolean acceptCompression) throws IOException {
        URL url = new URL(httpUrl);

        HttpURLConnection urlConnection;
        if (proxy != null) {
            urlConnection = (HttpURLConnection) url.openConnection(proxy);
        } else if (config.getProxy() != null) {
            urlConnection = (HttpURLConnection) url.openConnection(config.getProxy());
        } else {
            urlConnection = (HttpURLConnection) url.openConnection();
        }
        for (Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
            urlConnection.setRequestProperty(entry.getKey(), entry.getValue());
        }
        if (acceptCompression) {
            urlConnection.setRequestProperty("Accept-Encoding", "gzip");
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                urlConnection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        return urlConnection;
    }
}
