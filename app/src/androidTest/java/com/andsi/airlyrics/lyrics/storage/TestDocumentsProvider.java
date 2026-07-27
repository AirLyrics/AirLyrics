package com.andsi.airlyrics.lyrics.storage;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Test-APK-only DocumentsProvider backed by the test package's cache directory.
 *
 * <p>Each test receives a uniquely named directory below {@link #ROOT_DOCUMENT_ID}. A session
 * whose name starts with {@link #DENY_WRITE_PREFIX} rejects output streams so the storage failure
 * contract can be exercised without permission races.
 */
public final class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.andsi.airlyrics.test.documents";
    public static final String ROOT_DOCUMENT_ID = "root";
    public static final String DENY_WRITE_PREFIX = "deny-write-";

    static final String PROVIDER_DIRECTORY = "airlyrics-test-documents";

    private static final String ROOT_ID = "airlyrics-test-root";
    private static final String[] DEFAULT_ROOT_PROJECTION = {
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_FLAGS,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED
    };

    @Override
    public boolean onCreate() {
        providerRoot().mkdirs();
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(resolveProjection(projection, DEFAULT_ROOT_PROJECTION));
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
        row.add(Root.COLUMN_TITLE, "AirLyrics Test Documents");
        row.add(Root.COLUMN_SUMMARY, "Instrumentation-only SAF fixture");
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_MIME_TYPES, "*/*");
        row.add(Root.COLUMN_AVAILABLE_BYTES, providerRoot().getUsableSpace());
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        MatrixCursor cursor =
                new MatrixCursor(resolveProjection(projection, DEFAULT_DOCUMENT_PROJECTION));
        includeDocument(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(
            String parentDocumentId,
            String[] projection,
            String sortOrder
    ) throws FileNotFoundException {
        File parent = fileForDocumentId(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Not a directory: " + parentDocumentId);
        }

        MatrixCursor cursor =
                new MatrixCursor(resolveProjection(projection, DEFAULT_DOCUMENT_PROJECTION));
        File[] children = parent.listFiles();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) {
                includeDocument(cursor, documentIdForFile(child));
            }
        }
        return cursor;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = fileForDocumentId(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Not a directory: " + parentDocumentId);
        }
        if (!isValidName(displayName)) {
            throw new FileNotFoundException("Invalid display name");
        }

        File child = new File(parent, displayName);
        boolean created;
        try {
            created = Document.MIME_TYPE_DIR.equals(mimeType)
                    ? child.mkdir()
                    : child.createNewFile();
        } catch (IOException error) {
            throw fileNotFound("Unable to create " + displayName, error);
        }
        if (!created) {
            throw new FileNotFoundException("Unable to create " + displayName);
        }
        return documentIdForFile(child);
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId,
            String mode,
            CancellationSignal signal
    ) throws FileNotFoundException {
        File file = fileForDocumentId(documentId);
        if (!file.isFile()) {
            throw new FileNotFoundException("Not a file: " + documentId);
        }
        if (mode.contains("w") && isWriteDeniedSession(file)) {
            throw new FileNotFoundException("Controlled test write failure: " + documentId);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            throw new FileNotFoundException("The provider root cannot be deleted");
        }
        File file = fileForDocumentId(documentId);
        if (!deleteRecursively(file)) {
            throw new FileNotFoundException("Unable to delete " + documentId);
        }
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            File parent = fileForDocumentId(parentDocumentId).getCanonicalFile();
            File child = fileForDocumentId(documentId).getCanonicalFile();
            return !child.equals(parent)
                    && child.getPath().startsWith(parent.getPath() + File.separator);
        } catch (IOException error) {
            return false;
        }
    }

    private void includeDocument(MatrixCursor cursor, String documentId)
            throws FileNotFoundException {
        File file = fileForDocumentId(documentId);
        int flags = file.isDirectory()
                ? Document.FLAG_DIR_SUPPORTS_CREATE | Document.FLAG_SUPPORTS_DELETE
                : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE;

        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName().isEmpty() ? "root" : file.getName());
        row.add(
                Document.COLUMN_MIME_TYPE,
                file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"
        );
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, file.isFile() ? file.length() : 0L);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private File providerRoot() {
        if (getContext() == null) {
            throw new IllegalStateException("Provider context is unavailable");
        }
        return new File(getContext().getCacheDir(), PROVIDER_DIRECTORY);
    }

    private File fileForDocumentId(String documentId) throws FileNotFoundException {
        try {
            File root = providerRoot().getCanonicalFile();
            if (ROOT_DOCUMENT_ID.equals(documentId)) {
                return root;
            }
            String prefix = ROOT_DOCUMENT_ID + "/";
            if (!documentId.startsWith(prefix)) {
                throw new FileNotFoundException("Unknown document id: " + documentId);
            }

            File file = new File(root, documentId.substring(prefix.length())).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.exists()) {
                throw new FileNotFoundException("Unknown document id: " + documentId);
            }
            return file;
        } catch (IOException error) {
            throw fileNotFound("Unable to resolve document id: " + documentId, error);
        }
    }

    private String documentIdForFile(File file) throws FileNotFoundException {
        try {
            File root = providerRoot().getCanonicalFile();
            File child = file.getCanonicalFile();
            if (child.equals(root)) {
                return ROOT_DOCUMENT_ID;
            }
            String rootPrefix = root.getPath() + File.separator;
            if (!child.getPath().startsWith(rootPrefix)) {
                throw new FileNotFoundException("Document is outside the provider root");
            }
            String relative = child.getPath().substring(rootPrefix.length())
                    .replace(File.separatorChar, '/');
            return ROOT_DOCUMENT_ID + "/" + relative;
        } catch (IOException error) {
            throw fileNotFound("Unable to create document id", error);
        }
    }

    private boolean isWriteDeniedSession(File file) throws FileNotFoundException {
        try {
            File root = providerRoot().getCanonicalFile();
            String relative =
                    file.getCanonicalPath().substring(root.getPath().length() + 1);
            String sessionName = relative.contains("/")
                    ? relative.substring(0, relative.indexOf('/'))
                    : relative;
            return sessionName.startsWith(DENY_WRITE_PREFIX);
        } catch (IOException | IndexOutOfBoundsException error) {
            throw fileNotFound("Unable to resolve write-failure session", error);
        }
    }

    private static String[] resolveProjection(String[] requested, String[] fallback) {
        return requested == null ? fallback : requested;
    }

    private static boolean isValidName(String name) {
        return name != null
                && !name.trim().isEmpty()
                && !name.contains("/")
                && !name.contains("\\");
    }

    static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    private static FileNotFoundException fileNotFound(String message, Throwable cause) {
        FileNotFoundException error = new FileNotFoundException(message);
        error.initCause(cause);
        return error;
    }
}
