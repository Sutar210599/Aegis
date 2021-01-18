package com.beemdevelopment.aegis;

import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackupDataOutput;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.beemdevelopment.aegis.util.IOUtils;
import com.beemdevelopment.aegis.vault.VaultManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class AegisBackupAgent extends BackupAgent {
    private static final String TAG = BackupAgent.class.getSimpleName();

    private Preferences _prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        _prefs = new Preferences(this);
    }

    @Override
    public synchronized void onFullBackup(FullBackupDataOutput data) throws IOException {
        Log.i(TAG, String.format("onFullBackup() called: flags=%d, quota=%d",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? data.getTransportFlags() : -1,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? data.getQuota() : -1));

        if (!_prefs.isAndroidBackupsEnabled()) {
            Log.i(TAG, "onFullBackup() skipped: Android backups disabled in preferences");
            return;
        }

        // first copy the vault to the files/backup directory
        createBackupDir();
        File vaultBackupFile = getVaultBackupFile();
        try (FileInputStream inStream = VaultManager.getAtomicFile(this).openRead();
             FileOutputStream outStream = new FileOutputStream(vaultBackupFile)) {
            IOUtils.copy(inStream, outStream);
        } catch (IOException e) {
            Log.e(TAG, String.format("onFullBackup() failed: %s", e));
            deleteBackupDir();
            throw e;
        }

        // then call the original implementation so that fullBackupContent specified in AndroidManifest is read
        try {
            super.onFullBackup(data);
        } catch (IOException e) {
            Log.e(TAG, String.format("onFullBackup() failed: %s", e));
            throw e;
        } finally {
            deleteBackupDir();
        }

        Log.i(TAG, "onFullBackup() finished");
    }

    @Override
    public synchronized void onRestoreFile(ParcelFileDescriptor data, long size, File destination, int type, long mode, long mtime) throws IOException {
        Log.i(TAG, String.format("onRestoreFile() called: dest=%s", destination));
        super.onRestoreFile(data, size, destination, type, mode, mtime);

        File vaultBackupFile = getVaultBackupFile();
        if (destination.getCanonicalFile().equals(vaultBackupFile.getCanonicalFile())) {
            try (InputStream inStream = new FileInputStream(vaultBackupFile)) {
                VaultManager.writeToFile(this, inStream);
            } catch (IOException e) {
                Log.e(TAG, String.format("onRestoreFile() failed: dest=%s, error=%s", destination, e));
                throw e;
            } finally {
                deleteBackupDir();
            }
        }

        Log.i(TAG, String.format("onRestoreFile() finished: dest=%s", destination));
    }

    @Override
    public synchronized void onQuotaExceeded(long backupDataBytes, long quotaBytes) {
        super.onQuotaExceeded(backupDataBytes, quotaBytes);
        Log.e(TAG, String.format("onQuotaExceeded() called: backupDataBytes=%d, quotaBytes=%d", backupDataBytes, quotaBytes));
    }

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data, ParcelFileDescriptor newState) throws IOException {

    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState) throws IOException {

    }

    private void createBackupDir() throws IOException {
        File dir = getVaultBackupFile().getParentFile();
        if (!dir.exists() && !dir.mkdir()) {
            throw new IOException(String.format("Unable to create backup directory: %s", dir.toString()));
        }
    }

    private void deleteBackupDir() {
        File dir = getVaultBackupFile().getParentFile();
        IOUtils.clearDirectory(dir, true);
    }

    private File getVaultBackupFile() {
        return new File(new File(getFilesDir(), "backup"), VaultManager.FILENAME);
    }
}
