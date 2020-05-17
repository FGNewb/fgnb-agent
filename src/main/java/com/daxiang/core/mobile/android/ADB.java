package com.daxiang.core.mobile.android;

import com.android.ddmlib.AndroidDebugBridge;
import com.daxiang.utils.Terminal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;

/**
 * Created by jiangyitao.
 */
@Slf4j
public class ADB {

    public static void addDeviceChangeListener(AndroidDebugBridge.IDeviceChangeListener deviceChangeListener) {
        AndroidDebugBridge.init(false);
        AndroidDebugBridge.createBridge(getPath(), false);
        AndroidDebugBridge.addDeviceChangeListener(deviceChangeListener);
    }

    public static void killServer() throws IOException {
        Terminal.execute("adb kill-server");
    }

    public static void startServer() throws IOException {
        Terminal.execute("adb start-server");
    }

    /**
     * 获取adb路径
     *
     * @return
     */
    private static String getPath() {
        String androidHome = System.getenv("ANDROID_HOME");
        log.info("环境变量ANDROID_HOME: {}", androidHome);

        if (StringUtils.isEmpty(androidHome)) {
            throw new RuntimeException("未获取到ANDROID_HOME，请配置ANDROID_HOME环境变量");
        }

        String adbPath = androidHome + File.separator + "platform-tools" + File.separator;
        if (Terminal.IS_WINDOWS) {
            adbPath = adbPath + "adb.exe";
        } else {
            adbPath = adbPath + "adb";
        }
        log.info("adb路径: {}", adbPath);
        return adbPath;
    }
}