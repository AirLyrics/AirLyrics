package com.andsi.airlyrics.lyrics.storage;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.InstrumentationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Test-APK-only bootstrap channel for the protected {@link TestDocumentsProvider}.
 *
 * <p>The shell calls this provider only to create, revoke, and delete an isolated root and have
 * the provider owner grant its tree URI to the target app. Storage operations never use this
 * authority.
 */
public final class TestDocumentsControlProvider extends ContentProvider {
    public static final String AUTHORITY = "com.andsi.airlyrics.test.documents.control";
    public static final String METHOD_CREATE_SESSION = "createSession";
    public static final String METHOD_REVOKE_SESSION = "revokeSession";
    public static final String METHOD_DELETE_SESSION = "deleteSession";
    public static final String RESULT_DOCUMENT_URI = "documentUri";
    public static final String RESULT_TREE_URI = "treeUri";
    public static final String RESULT_OPERATION_SUCCEEDED = "airlyricsTestControlSuccess";

    private static final String TARGET_PACKAGE = "com.andsi.airlyrics";
    private static final int SHELL_APP_ID = 2000;
    private static final Pattern SESSION_NAME_PATTERN =
            Pattern.compile(
                    "^(?:session-|deny-write-)"
                            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            );
    private static final int URI_READ_WRITE_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final int URI_GRANT_FLAGS =
            URI_READ_WRITE_FLAGS
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(@NonNull String method, String arg, Bundle extras) {
        requireShellCaller();
        requireInstalledInstrumentationTarget();
        return switch (method) {
            case METHOD_CREATE_SESSION -> createSession(requireArgument(arg));
            case METHOD_REVOKE_SESSION -> revokeSession(requireArgument(arg));
            case METHOD_DELETE_SESSION -> deleteSession(requireArgument(arg));
            default ->
                    throw new IllegalArgumentException("Unsupported test control method: " + method);
        };
    }

    private Bundle createSession(String sessionName) {
        requireValidSessionName(sessionName);
        File root = providerRoot();
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IllegalStateException("Unable to create test provider root");
        }
        File sessionDir = new File(root, sessionName);
        if (!sessionDir.mkdir()) {
            throw new IllegalStateException(
                    "Unable to create isolated test session: " + sessionName);
        }

        String documentId = TestDocumentsProvider.ROOT_DOCUMENT_ID + "/" + sessionName;
        Uri documentUri =
                DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, documentId);
        Uri treeUri =
                DocumentsContract.buildTreeDocumentUri(
                        TestDocumentsProvider.AUTHORITY,
                        documentId
                );
        if (getContext() == null) {
            throw new IllegalStateException("Provider context is unavailable");
        }
        try {
            getContext().grantUriPermission(TARGET_PACKAGE, treeUri, URI_GRANT_FLAGS);
        } catch (RuntimeException error) {
            TestDocumentsProvider.deleteRecursively(sessionDir);
            throw error;
        }

        Bundle result = new Bundle();
        result.putString(RESULT_DOCUMENT_URI, documentUri.toString());
        result.putString(RESULT_TREE_URI, treeUri.toString());
        result.putString(RESULT_OPERATION_SUCCEEDED, sessionName);
        return result;
    }

    private Bundle revokeSession(String sessionName) {
        requireValidSessionName(sessionName);
        if (getContext() == null) {
            throw new IllegalStateException("Provider context is unavailable");
        }
        getContext().revokeUriPermission(treeUri(sessionName), URI_READ_WRITE_FLAGS);
        return successBundle(sessionName);
    }

    private Bundle deleteSession(String sessionName) {
        requireValidSessionName(sessionName);
        try {
            File root = providerRoot().getCanonicalFile();
            File sessionDir = new File(root, sessionName).getCanonicalFile();
            if (!root.equals(sessionDir.getParentFile())) {
                throw new IllegalArgumentException("Session escaped the provider root");
            }
            if (sessionDir.exists() && !TestDocumentsProvider.deleteRecursively(sessionDir)) {
                throw new IllegalStateException("Unable to delete test session: " + sessionName);
            }
            if (sessionDir.exists()) {
                throw new IllegalStateException(
                        "Test session still exists after deletion: " + sessionName);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to resolve test session", error);
        }
        return successBundle(sessionName);
    }

    private static Bundle successBundle(String sessionName) {
        Bundle result = new Bundle();
        result.putString(RESULT_OPERATION_SUCCEEDED, sessionName);
        return result;
    }

    private static Uri treeUri(String sessionName) {
        return DocumentsContract.buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY,
                TestDocumentsProvider.ROOT_DOCUMENT_ID + "/" + sessionName
        );
    }

    private File providerRoot() {
        if (getContext() == null) {
            throw new IllegalStateException("Provider context is unavailable");
        }
        return new File(
                getContext().getCacheDir(),
                TestDocumentsProvider.PROVIDER_DIRECTORY
        );
    }

    private static String requireArgument(String argument) {
        if (argument == null) {
            throw new IllegalArgumentException("Missing test control argument");
        }
        return argument;
    }

    private static void requireValidSessionName(String sessionName) {
        if (!SESSION_NAME_PATTERN.matcher(sessionName).matches()) {
            throw new IllegalArgumentException("Invalid test session name");
        }
    }

    private static void requireShellCaller() {
        if (Binder.getCallingUid() != SHELL_APP_ID) {
            throw new SecurityException(
                    "Only the Android shell may use the SAF test control provider");
        }
    }

    private void requireInstalledInstrumentationTarget() {
        if (getContext() == null) {
            throw new IllegalStateException("Provider context is unavailable");
        }
        String testPackage = getContext().getPackageName();
        List<InstrumentationInfo> instrumentations =
                getContext().getPackageManager().queryInstrumentation(TARGET_PACKAGE, 0);
        for (InstrumentationInfo instrumentation : instrumentations) {
            if (testPackage.equals(instrumentation.packageName)
                    && TARGET_PACKAGE.equals(instrumentation.targetPackage)) {
                return;
            }
        }
        throw new SecurityException(
                "Test control target is not the installed instrumentation target: "
                        + TARGET_PACKAGE);
    }

    @Override
    public Cursor query(
            @NonNull Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        return null;
    }

    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(
            @NonNull Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        return 0;
    }
}
