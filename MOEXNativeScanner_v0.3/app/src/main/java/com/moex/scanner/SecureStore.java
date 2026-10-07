package com.moex.scanner;
import java.security.KeyStore;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

public final class SecureStore {
    private static final String PREF="moex_secure";
    private static final String KEY="algopack_token";
    private static final String KS="moex_algopack_key";
    private final SharedPreferences p;
    public SecureStore(Context c){p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private SecretKey key() throws Exception {
        KeyStore ks=java.security.KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(ks.containsAlias(KS)) return ((java.security.KeyStore.SecretKeyEntry)ks.getEntry(KS,null)).getSecretKey();
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return kg.generateKey();
    }
    public void put(String value) throws Exception {
        if(value==null)value=""; Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key());
        p.edit().putString(KEY,Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP)).apply();
    }
    public String get(){try{String s=p.getString(KEY,""); if(s.isEmpty())return ""; String[] a=s.split(":",2); Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(a[0],Base64.NO_WRAP))); return new String(c.doFinal(Base64.decode(a[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}catch(Exception e){return "";}}
}
