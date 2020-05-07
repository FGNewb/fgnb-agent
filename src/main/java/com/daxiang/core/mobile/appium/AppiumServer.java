package com.daxiang.core.mobile.appium;

import com.daxiang.App;
import com.daxiang.core.PortProvider;
import com.daxiang.utils.Terminal;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.ExecuteWatchdog;
import org.openqa.selenium.net.UrlChecker;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Created by jiangyitao.
 */
@Slf4j
public class AppiumServer {

    private static final String APPIUM_JS = App.getProperty("appiumJs");
    private static String version;

    public static String getVersion() throws IOException {
        if (version == null) {
            if (StringUtils.isEmpty(APPIUM_JS)) {
                version = Terminal.execute("appium -v");
            } else {
                version = Terminal.execute("node " + APPIUM_JS + " -v");
            }
        }
        return version;
    }

    private ExecuteWatchdog watchdog;
    private boolean isRunning = false;
    private int port;
    private URL url;

    public synchronized void start() {
        if (isRunning) {
            return;
        }

        port = PortProvider.getAppiumServerAvailablePort();
        String cmd = " -p " + port + " --session-override";

        if (StringUtils.isEmpty(APPIUM_JS)) {
            cmd = "appium" + cmd;
        } else {
            cmd = "node " + APPIUM_JS + cmd;
        }

        try {
            watchdog = Terminal.executeAsyncAndGetWatchdog(cmd);
            String url = "http://localhost:" + port + "/wd/hub";
            this.url = new URL(url);
            new UrlChecker().waitUntilAvailable(60, TimeUnit.SECONDS, new URL(url + "/status"));
            isRunning = true;
        } catch (Exception e) {
            throw new RuntimeException("启动appium失败", e);
        }

    }

    public URL getUrl() {
        if (isRunning) {
            return url;
        } else {
            throw new RuntimeException("appium未启动");
        }
    }

    public void stop() {
        if (isRunning) {
            watchdog.destroyProcess();
        } else {
            throw new RuntimeException("appium未启动");
        }
    }
}
