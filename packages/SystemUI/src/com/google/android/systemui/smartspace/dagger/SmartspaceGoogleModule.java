package com.google.android.systemui.smartspace.dagger;

import com.android.systemui.dagger.SysUISingleton;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.BcSmartspaceDataProvider;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

@Module
public abstract class SmartspaceGoogleModule {
    @Binds
    @SysUISingleton
    abstract BcSmartspaceDataPlugin bindBcSmartspaceDataPlugin(
            BcSmartspaceDataProvider provider);

    @Provides
    @SysUISingleton
    static BcSmartspaceDataProvider provideGlanceableHubBcSmartspaceDataPlugin() {
        return new BcSmartspaceDataProvider();
    }
}
