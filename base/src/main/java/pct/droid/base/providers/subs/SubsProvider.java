package pct.droid.base.providers.subs;

import android.content.Context;

import com.squareup.okhttp.Call;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import pct.droid.base.PopcornApplication;
import pct.droid.base.preferences.Prefs;
import pct.droid.base.providers.BaseProvider;
import pct.droid.base.providers.media.models.Media;
import pct.droid.base.providers.media.models.Movie;
import pct.droid.base.providers.media.models.Show;
import pct.droid.base.subs.FatalParsingException;
import pct.droid.base.subs.FormatASS;
import pct.droid.base.subs.FormatSRT;
import pct.droid.base.subs.TimedTextObject;
import pct.droid.base.utils.FileUtils;
import pct.droid.base.utils.PrefUtils;
import pct.droid.base.utils.StorageUtils;

public abstract class SubsProvider extends BaseProvider {
    public static final String SUBS_CALL = "subs_http_call";

    private static List<String> SUB_EXTENSIONS = Arrays.asList("srt", "ssa", "ass");

    public abstract void getList(Movie movie, Callback callback);

    public abstract void getList(Show media, Show.Episode episode, Callback callback);

    public interface Callback {
        public void onSuccess(Map<String, String> items);

        public void onFailure(Exception e);
    }

    public static File getStorageLocation(Context context) {
        return new File(PrefUtils.get(context, Prefs.STORAGE_LOCATION, StorageUtils.getIdealCacheDirectory(context).toString()) + "/subs/");
    }

    /**
     * @param context      Context
     * @param media        Media data
     * @param languageCode Code of language
     * @param callback     Network callback
     * @return Call
     */
    public static Call download(final Context context, final Media media, final String languageCode, final com.squareup.okhttp.Callback callback) {
        OkHttpClient client = PopcornApplication.getHttpClient();
        if (media.subtitles != null && media.subtitles.containsKey(languageCode)) {
            try {
                Request request = new Request.Builder().url(media.subtitles.get(languageCode)).build();
                Call call = client.newCall(request);

                final File subsDirectory = getStorageLocation(context);
                final String fileName = media.videoId + "-" + languageCode;
                final File srtPath = new File(subsDirectory, fileName + ".srt");

                if (srtPath.exists()) {
                    callback.onResponse(null);
                    return call;
                }

                call.enqueue(new com.squareup.okhttp.Callback() {
                    @Override
                    public void onFailure(Request request, IOException e) {
                        callback.onFailure(request, e);
                    }

                    @Override
                    public void onResponse(Response response) throws IOException {
                        if (response.isSuccessful()) {
                            InputStream inputStream = null;
                            boolean failure = false;
                            try {


                                subsDirectory.mkdirs();
                                if (srtPath.exists()) {
                                    File to = new File(subsDirectory, "temp" + System.currentTimeMillis());
                                    srtPath.renameTo(to);
                                    to.delete();
                                }

                                inputStream = response.body().byteStream();
                                String urlString = response.request().urlString();

                                if (urlString.contains(".zip") || urlString.contains(".gz")) {
                                    SubsProvider.unpack(inputStream, srtPath);
                                } else if (SubsProvider.isSubFormat(urlString)) {
                                    parseFormatAndSave(urlString, srtPath, inputStream);
                                } else {
                                    callback.onFailure(response.request(), new IOException("FatalParsingException"));
                                    failure = true;
                                }
                            } catch (FatalParsingException e) {
                                e.printStackTrace();
                                callback.onFailure(response.request(), new IOException("FatalParsingException"));
                                failure = true;
                            } catch (FileNotFoundException e) {
                                e.printStackTrace();
                                callback.onFailure(response.request(), e);
                                failure = true;
                            } catch (IOException e) {
                                e.printStackTrace();
                                callback.onFailure(response.request(), e);
                                failure = true;
                            } finally {
                                if (inputStream != null)
                                    inputStream.close();

                                if (!failure) callback.onResponse(response);
                            }
                        } else {
                            callback.onFailure(response.request(), new IOException("Unknown error"));
                        }
                    }
                });

                return call;
            } catch (RuntimeException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        callback.onFailure(null, new IOException("Wrong media"));
        return null;
    }

    /**
     * Unpack ZIP and save SRT
     *
     * @param is      InputStream from network
     * @param srtPath Path where SRT should be saved
     * @throws IOException
     * @throws FatalParsingException
     */
    private static void unpack(InputStream is, File srtPath) throws IOException, FatalParsingException {
        String filename;
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is));
        ZipEntry ze;

        while ((ze = zis.getNextEntry()) != null) {
            filename = ze.getName();
            if (filename.contains("_MACOSX")) continue;

            if (isSubFormat(filename)) {
                parseFormatAndSave(filename, srtPath, zis);
                try {
                    zis.closeEntry();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return;
            }
        }

        zis.close();
    }

    /**
     * Test if file is subtitle format
     *
     * @param filename Name of file
     * @return is subtitle?
     */
    private static boolean isSubFormat(String filename) {
        for (String ext : SUB_EXTENSIONS) {
            if (filename.contains("." + ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parse the text, convert and save to SRT file
     *
     * @param inputUrl    Original network location
     * @param srtPath     Place where SRT should be saved
     * @param inputStream InputStream of data
     * @throws IOException
     */
    private static void parseFormatAndSave(String inputUrl, File srtPath, InputStream inputStream) throws IOException {
        TimedTextObject subtitleObject = null;

        String inputString = FileUtils.inputstreamToCharsetString(inputStream);
        String[] inputText = inputString.split("\n|\r\n");

        if (inputUrl.contains(".ass") || inputUrl.contains(".ssa")) {
            FormatASS formatASS = new FormatASS();
            subtitleObject = formatASS.parseFile(inputUrl, inputText);
        } else if (inputUrl.contains(".srt")) {
            FormatSRT formatSRT = new FormatSRT();
            subtitleObject = formatSRT.parseFile(inputUrl, inputText);
        }

        if (subtitleObject != null) {
            FileUtils.saveStringFile(subtitleObject.toSRT(), srtPath);
        }
    }
}