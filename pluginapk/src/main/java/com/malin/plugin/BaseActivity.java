package com.malin.plugin;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;

import java.lang.reflect.Field;

public class BaseActivity extends AppCompatActivity {

    protected Context mContext;

    protected boolean pluginInHostRunning = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Class<?> hostAppClazz = null;
        try {
            hostAppClazz = Class.forName("com.malin.hook.MApplication");
        } catch (Throwable ignore) {
        }

        pluginInHostRunning = hostAppClazz != null;
        if (pluginInHostRunning) {
            // 插件apk在宿主中运行
            Resources resource = PluginResourceUtil.getResource(getApplication());
            mContext = new ContextThemeWrapper(getBaseContext(), 0);
            Class<?> contextClazz = mContext.getClass();
            try {
                Field mResourcesField = contextClazz.getDeclaredField("mResources");
                mResourcesField.setAccessible(true);
                mResourcesField.set(mContext, resource);

                Class<?> rClazz = Class.forName("com.google.android.material.R$style");
                Field themeField = rClazz.getDeclaredField("Theme_MaterialComponents_DayNight");
                themeField.setAccessible(true);
                Object themeObj = themeField.get(null);
                if (themeObj != null) {
                    int theme = (int) themeObj;
                    Field mThemeResourceField = contextClazz.getDeclaredField("mThemeResource");
                    mThemeResourceField.setAccessible(true);
                    mThemeResourceField.set(mContext, theme);
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        } else {
            // 插件作为独立的apk运行
            mContext = null;
        }
    }
}
