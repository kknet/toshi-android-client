/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.util;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.v4.content.FileProvider;
import android.webkit.MimeTypeMap;

import com.toshi.BuildConfig;
import com.toshi.model.local.Attachment;
import com.toshi.model.sofa.OutgoingAttachment;
import com.toshi.view.BaseApplication;

import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.UUID;
import java.util.zip.CRC32;

import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import rx.Single;
import rx.schedulers.Schedulers;

import static org.whispersystems.signalservice.api.messages.SignalServiceAttachment.newStreamBuilder;

public class FileUtil {

    private FileUtil() {}

    public static final int MAX_SIZE = 1024 * 1024;
    public static final String FILE_PROVIDER_NAME = ".fileProvider";

    public static Single<File> saveFileFromUri(final Context context, final Uri uri) {
        return Single.fromCallable(() -> {
            final String mimeType = context.getContentResolver().getType(uri);
            final MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
            final String fileExtension = mimeTypeMap.getExtensionFromMimeType(mimeType);
            final String fileName = String.format("%s.%s", UUID.randomUUID().toString(), fileExtension);
            final File destFile = new File(BaseApplication.get().getFilesDir(), fileName);
            final InputStream inputStream = BaseApplication.get()
                    .getContentResolver()
                    .openInputStream(uri);
            return writeToFileFromInputStream(destFile, inputStream);
        })
        .subscribeOn(Schedulers.io());
    }

    private static File writeToFileFromInputStream(final File file, final InputStream inputStream) throws IOException {
        final BufferedSink sink = Okio.buffer(Okio.sink(file));
        final Source source = Okio.source(inputStream);
        sink.writeAll(source);
        sink.close();
        return file;
    }

    //TODO Add checksum to filename so we can compare files
    public @Nullable static File writeAttachmentToFileFromMessageReceiver(
            final SignalServiceAttachmentPointer attachment,
            final SignalServiceMessageReceiver messageReceiver,
            final String fileId) {
        File file = null;
        try {
            final String tempName = String.format("%d", attachment.getId());
            file = new File(BaseApplication.get().getCacheDir(), tempName);
            final int maxFileSize = 20 * 1024 * 1024;
            final InputStream inputStream = messageReceiver.retrieveAttachment(attachment, file, maxFileSize);

            final File destFile = constructAttachmentFile(attachment.getContentType(), fileId);
            return writeToFileFromInputStream(destFile, inputStream);
        } catch (IOException | InvalidMessageException e) {
            LogUtil.exception(FileUtil.class, "Error during writing attachment to file", e);
            return null;
        } finally {
            if (file != null) {
                file.delete();
            }
        }
    }

    private static File constructAttachmentFile(final String contentType, final String fileId) throws IOException {
        final File baseDirectory = BaseApplication.get().getFilesDir();
        final String directoryPath = contentType.startsWith("image/") ? "images" : "files";
        final File outputDirectory = new File(baseDirectory, directoryPath);

        if (!outputDirectory.exists()) {
            outputDirectory.mkdir();
        }

        final String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType);
        final String fileName = String.format("%s.%s", fileId, extension);
        return new File(outputDirectory, fileName);
    }

    public static File createImageFileWithRandomName() {
        final String filename = UUID.randomUUID().toString() + ".jpg";
        return new File(BaseApplication.get().getFilesDir(), filename);
    }

    public static String getMimeTypeFromFilename(final String filename) {
        if (filename == null) return null;
        final String strippedFilename = filename.replaceAll("\\s","");
        final String fileExtension = MimeTypeMap.getFileExtensionFromUrl(strippedFilename);
        return  MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension);
    }

    public static Single<File> compressImage(final long maxSize, final File file) {
        return Single.fromCallable(() -> {
            if (file.length() <= maxSize) return file;
            final int compressPercentage = (int)(((double)maxSize / file.length()) * 100);
            final Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            final OutputStream outputStream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, compressPercentage, outputStream);
            return file;
        })
        .subscribeOn(Schedulers.io());
    }

    public static Attachment getNameAndSizeFromUri(final Uri uri) {
        final String [] projection = { MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE };
        final Cursor cursor =
                BaseApplication.get()
                .getContentResolver()
                .query(
                        uri,
                        projection,
                        null,
                        null,
                        null
                );

        if (cursor == null) return null;
        final int columnIndexDisplayName = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
        final int columnIndexSize = cursor.getColumnIndex(MediaStore.Images.Media.SIZE);
        cursor.moveToFirst();
        final String displayName = cursor.getString(columnIndexDisplayName);
        final long size = cursor.getLong(columnIndexSize);
        cursor.close();

        return new Attachment()
                .setFilename(displayName)
                .setSize(size);
    }

    public static String getFilenameFromPath(final String path) {
        final File file = new File(path);
        return file.exists() ? file.getName() : "";
    }

    public static long getFileSize(final String path) {
        final File file = new File(path);
        return file.exists() ? file.length() : 0;
    }

    public static Uri getUriFromFile(final File file) {
        return FileProvider
                .getUriForFile(
                        BaseApplication.get(),
                        BuildConfig.APPLICATION_ID + FILE_PROVIDER_NAME,
                        file
                );
    }

    public static SignalServiceAttachment buildSignalServiceAttachment(final OutgoingAttachment attachment) throws FileNotFoundException, IllegalStateException {
        final File attachmentFile = attachment.getOutgoingAttachment();
        final FileInputStream attachmentStream = new FileInputStream(attachmentFile);
        return newStreamBuilder()
                .withStream(attachmentStream)
                .withContentType(attachment.getMimeType())
                .withLength(attachmentFile.length())
                .build();
    }

    public static SignalServiceAttachmentStream buildSignalServiceAttachment(final Bitmap bitmap) {
        final byte[] bytes = ImageUtil.toByteArray(bitmap);
        return buildSignalServiceAttachment(bytes);
    }

    public static SignalServiceAttachmentStream buildSignalServiceAttachment(final byte[] bytes) {
        return SignalServiceAttachmentStream.newStreamBuilder()
                .withContentType("image/png")
                .withStream(new ByteArrayInputStream(bytes))
                .withLength(bytes.length)
                .build();
    }
}
