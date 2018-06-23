package devliving.online.securedpreferencestore;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.NoSuchPaddingException;

/**
 * Created by Mehedi on 8/21/16.
 */
public class SecuredPreferenceStore implements SharedPreferences {
    private final static int VERSION = 600;
    final static String VERSION_KEY = "VERSION";
    private final static String DEFAULT_PREF_FILE_NAME = "SPS_file";

    private SharedPreferences mPrefs;
    private EncryptionManager mEncryptionManager;
    private int mRunningVersion;

    private static RecoveryHandler mRecoveryHandler;

    private static SecuredPreferenceStore mInstance;

    private SecuredPreferenceStore(Context appContext, String storeName, String keyPrefix, String fallbackShiftingKey) throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException, UnrecoverableEntryException, InvalidAlgorithmParameterException, NoSuchPaddingException, InvalidKeyException, NoSuchProviderException {
        Logger.d("Creating store instance");
        mPrefs = appContext.getSharedPreferences(storeName, Context.MODE_PRIVATE);

        mEncryptionManager = new EncryptionManager(appContext, mPrefs, keyPrefix, fallbackShiftingKey, new KeyStoreRecoveryNotifier() {
            @Override
            public boolean onRecoveryRequired(Exception e, KeyStore keyStore, List<String> keyAliases) {
                if (mRecoveryHandler != null)
                    return mRecoveryHandler.recover(e, keyStore, keyAliases, mPrefs);
                else throw new RuntimeException(e);
            }
        });


        if(!mPrefs.contains(VERSION_KEY) && mPrefs.getAll().size() != 0) {
            mPrefs.edit().putInt(VERSION_KEY, 500).apply();
        } else if(!mPrefs.contains(VERSION_KEY)) {
            mPrefs.edit().putInt(VERSION_KEY, VERSION).apply();
            return;
        }
        mRunningVersion = mPrefs.getInt(VERSION_KEY, 500);
    }

    public static void setRecoveryHandler(RecoveryHandler recoveryHandler) {
        SecuredPreferenceStore.mRecoveryHandler = recoveryHandler;
    }

    synchronized public static SecuredPreferenceStore getSharedInstance() {
        if ( mInstance == null ) {
            throw new IllegalStateException("Must call init() before using the store");
        }

        return mInstance;
    }

    /**
     * Must be called once before using the SecuredPreferenceStore to initialize the shared instance.
     * You may call it in @code{onCreate} method of your application class or launcher activity
     *
     * @param appContext
     * @param seed Seed to use while generating keys
     * @param recoveryHandler
     *
     * @throws IOException
     * @throws CertificateException
     * @throws NoSuchAlgorithmException
     * @throws KeyStoreException
     * @throws UnrecoverableEntryException
     * @throws InvalidAlgorithmParameterException
     * @throws NoSuchPaddingException
     * @throws InvalidKeyException
     * @throws NoSuchProviderException
     */
    public static void init( Context appContext, String storeName, String keyPrefix, String fallbackShiftingKey,
                             RecoveryHandler recoveryHandler ) throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException, UnrecoverableEntryException, InvalidAlgorithmParameterException, NoSuchPaddingException, InvalidKeyException, NoSuchProviderException {

        if(mInstance != null){
            Logger.w("init called when there already is a non-null instance of the class");
            return;
        }

        setRecoveryHandler(recoveryHandler);
        mInstance = new SecuredPreferenceStore(appContext, storeName, keyPrefix, fallbackShiftingKey);
    }

    /**
     * @see #init(Context, String, String, String, RecoveryHandler)
     */
    public static void init( Context appContext, String storeName, String keyPrefix, RecoveryHandler recoveryHandler) throws IOException, CertificateException, NoSuchAlgorithmException, InvalidKeyException, UnrecoverableEntryException, InvalidAlgorithmParameterException, NoSuchPaddingException, NoSuchProviderException, KeyStoreException {
        init(appContext, storeName, keyPrefix, null, recoveryHandler);
    }

    /**
     * @see #init(Context, String, String, String, RecoveryHandler)
     */
    public static void init( Context appContext, String storeName, RecoveryHandler recoveryHandler) throws IOException, CertificateException, NoSuchAlgorithmException, InvalidKeyException, UnrecoverableEntryException, InvalidAlgorithmParameterException, NoSuchPaddingException, NoSuchProviderException, KeyStoreException {
        init(appContext, storeName, null, recoveryHandler);
    }

    /**
     * @see #init(Context, String, String, String, RecoveryHandler)
     * @deprecated This contructor does not provide the shifted encryption on older Android versions,
     * and has thus been deprecated. It is there for people who do not want to change their storage method
     */
    public static void init( Context appContext, RecoveryHandler recoveryHandler) throws IOException, CertificateException, NoSuchAlgorithmException, InvalidKeyException, UnrecoverableEntryException, InvalidAlgorithmParameterException, NoSuchPaddingException, NoSuchProviderException, KeyStoreException {
        init(appContext, DEFAULT_PREF_FILE_NAME, null, "", recoveryHandler);
    }

    public EncryptionManager getEncryptionManager() {
        return mEncryptionManager;
    }

    public static void migrate(Context appContext, String storeName, MigrationHandler migrationHandler) throws MigrationHandler.MigrationFailedException {
        SharedPreferences mPrefs = appContext.getSharedPreferences(storeName, Context.MODE_PRIVATE);
        migrationHandler.migrate(appContext, mPrefs);
    }

    @Override
    public Map<String, Object> getAll() {
        Map<String, ?> all = mPrefs.getAll();
        Map<String, Object> dAll = new HashMap<>(all.size());

        if (all.size() > 0) {
            for (String key : all.keySet()) {
                if(key.equals(VERSION_KEY)) continue;
                try {
                    Object value = all.get(key);
                    if(value.getClass().equals(String.class) && ((String)value).contains("]"))
                        dAll.put(key, mEncryptionManager.decrypt((String) value));
                    else
                        dAll.put(key, value);
                } catch (Exception e) {
                    Logger.e(e);
                }
            }
        }
        return dAll;
    }

    @Override
    public String getString(String key, String defValue) {
        try {
            String hashedKey = EncryptionManager.getHashed(key);
            String value = mPrefs.getString(hashedKey, null);
            if (value != null) return mEncryptionManager.decrypt(value);
        } catch (Exception e) {
            Logger.e(e);
        }

        return defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        try {
            String hashedKey = EncryptionManager.getHashed(key);
            Set<String> eSet = mPrefs.getStringSet(hashedKey, null);

            if (eSet != null) {
                Set<String> dSet = new HashSet<>(eSet.size());

                for (String val : eSet) {
                    dSet.add(mEncryptionManager.decrypt(val));
                }

                return dSet;
            }

        } catch (Exception e) {
            Logger.e(e);
        }

        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        String value = getString(key, null);
        if (value != null) {
            return Integer.parseInt(value);
        }
        return defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        String value = getString(key, null);
        if (value != null) {
            return Long.parseLong(value);
        }
        return defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        String value = getString(key, null);
        if (value != null) {
            return Float.parseFloat(value);
        }
        return defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        String value = getString(key, null);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defValue;
    }

    public byte[] getBytes(String key) {
        String val = getString(key, null);
        if (val != null) {
            return EncryptionManager.base64Decode(val);
        }

        return null;
    }

    @Override
    public boolean contains(String key) {
        try {
            String hashedKey = EncryptionManager.getHashed(key);
            return mPrefs.contains(hashedKey);
        } catch (Exception e) {
            Logger.e(e);
        }

        return false;
    }

    @Override
    public Editor edit() {
        return new Editor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
        if (mPrefs != null)
            mPrefs.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
        if (mPrefs != null)
            mPrefs.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
    }

    public class Editor implements SharedPreferences.Editor {
        SharedPreferences.Editor mEditor;

        public Editor() {
            mEditor = mPrefs.edit();
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            try {
                String hashedKey = EncryptionManager.getHashed(key);
                String evalue = mEncryptionManager.encrypt(value);
                mEditor.putString(hashedKey, evalue);
            } catch (Exception e) {
                Logger.e(e);
            }

            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            try {
                String hashedKey = EncryptionManager.getHashed(key);
                Set<String> eSet = new HashSet<String>(values.size());

                for (String val : values) {
                    eSet.add(mEncryptionManager.encrypt(val));
                }

                mEditor.putStringSet(hashedKey, eSet);
            } catch (Exception e) {
                Logger.e(e);
            }

            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            String val = Integer.toString(value);
            return putString(key, val);
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            String val = Long.toString(value);
            return putString(key, val);
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            String val = Float.toString(value);
            return putString(key, val);
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            String val = Boolean.toString(value);
            return putString(key, val);
        }

        public SharedPreferences.Editor putBytes(String key, byte[] bytes) {
            if (bytes != null) {
                String val = EncryptionManager.base64Encode(bytes);
                return putString(key, val);
            } else return remove(key);
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            try {
                String hashedKey = EncryptionManager.getHashed(key);
                mEditor.remove(hashedKey);
            } catch (Exception e) {
                Logger.e(e);
            }

            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            mEditor.clear();

            return this;
        }

        @Override
        public boolean commit() {
            return mEditor.commit();
        }

        @Override
        public void apply() {
            mEditor.apply();
        }
    }

    public interface KeyStoreRecoveryNotifier{
        /**
         *
         * @param e
         * @param keyStore
         * @param keyAliases
         * @return true if the error could be resolved
         */
        boolean onRecoveryRequired(Exception e, KeyStore keyStore, List<String> keyAliases);
    }
}
