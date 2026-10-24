package com.dshpet.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Credentials and chat memory are encrypted with an app-scoped Android Keystore key. */
final class EncryptedVault {
  private static final Object LOCK = new Object();
  private static final String ALIAS = "dsh-pet-private-v1";
  private final File directory;

  EncryptedVault(Context context) {
    directory = new File(context.getNoBackupFilesDir(), "private");
  }

  private File file(String name) {
    if (!name.matches("[a-z0-9-]{1,100}"))
      throw new IllegalArgumentException("Invalid private document");
    return new File(directory, name + ".enc");
  }

  private static javax.crypto.SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(ALIAS)) return (javax.crypto.SecretKey) store.getKey(ALIAS, null);
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
    return generator.generateKey();
  }

  JSONObject read(String name) throws Exception {
    synchronized (LOCK) {
      File file = file(name);
      AtomicFile atomic = new AtomicFile(file);
      if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return new JSONObject();
      try (InputStream input = atomic.openRead()) {
        byte[] packed = JsonFiles.read(input, 512 * 1024);
        if (packed.length < 30 || packed[0] != 1) throw new IOException("安全存储格式损坏，请保留数据并检查安装版本");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            new GCMParameterSpec(128, java.util.Arrays.copyOfRange(packed, 1, 13)));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new JSONObject(
            new String(cipher.doFinal(packed, 13, packed.length - 13), StandardCharsets.UTF_8));
      } catch (java.security.GeneralSecurityException error) {
        throw new IOException("安全存储无法解密，请重置该项设置或从备份恢复");
      }
    }
  }

  void write(String name, JSONObject value) throws Exception {
    synchronized (LOCK) {
      byte[] plain = value.toString().getBytes(StandardCharsets.UTF_8);
      if (plain.length > 480 * 1024) throw new IOException("私密数据超过存储限制");
      if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建安全存储");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key());
      cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(plain);
      AtomicFile atomic = new AtomicFile(file(name));
      FileOutputStream output = null;
      try {
        output = atomic.startWrite();
        output.write(1);
        output.write(cipher.getIV());
        output.write(encrypted);
        atomic.finishWrite(output);
      } catch (IOException error) {
        if (output != null) atomic.failWrite(output);
        throw error;
      }
    }
  }

  void delete(String name) {
    synchronized (LOCK) {
      new AtomicFile(file(name)).delete();
    }
  }
}
