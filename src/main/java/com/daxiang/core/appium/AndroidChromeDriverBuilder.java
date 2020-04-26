package com.daxiang.core.appium;

import com.daxiang.core.MobileDevice;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.remote.DesiredCapabilities;

/**
 * Created by jiangyitao.
 */
class AndroidChromeDriverBuilder extends AppiumDriverBuilder {

    public AndroidChromeDriverBuilder(MobileDevice mobileDevice) {
        super(mobileDevice);
    }

    @Override
    public AppiumDriver build() {
        MobileDevice mobileDevice = getMobileDevice();

        DesiredCapabilities capabilities = new DesiredCapabilitiesBuilder(mobileDevice)
                .androidBasic()
                .androidSkip()
                .androidChrome()
                .build();
        return new AndroidDriver(mobileDevice.getAppiumServer().getUrl(), capabilities);
    }
}
