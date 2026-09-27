package com.google.android.systemui.smartspace.dagger;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.smartspace.SmartspaceTargetFilter;
import com.android.systemui.smartspace.dagger.SmartspaceModule;
import com.android.systemui.smartspace.filters.LockscreenTargetFilter;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.BcSmartspaceDataProvider;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

import javax.inject.Named;

@Module
public abstract class SmartspaceGoogleModule {
    @Binds
    @Named(SmartspaceModule.LOCKSCREEN_SMARTSPACE_TARGET_FILTER)
    abstract SmartspaceTargetFilter bindLockscreenSmartspaceTargetFilter(
            LockscreenTargetFilter impl);

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
