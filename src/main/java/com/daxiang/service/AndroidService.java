package com.daxiang.service;

import com.daxiang.core.mobile.MobileDevice;
import com.daxiang.core.MobileDeviceHolder;
import com.daxiang.core.mobile.android.AndroidDevice;
import com.daxiang.core.mobile.android.AndroidUtil;
import com.daxiang.model.Response;
import com.daxiang.utils.HttpUtil;
import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

/**
 * Created by jiangyitao.
 */
@Slf4j
@Service
public class AndroidService {

    public Response startAdbKit(String mobileId) {
        MobileDevice mobileDevice = MobileDeviceHolder.getConnectedDevice(mobileId);
        if (mobileDevice == null) {
            return Response.fail("设备未连接");
        }

        try {
            int port = ((AndroidDevice) mobileDevice).getAdbKit().start();
            return Response.success(ImmutableMap.of("port", port));
        } catch (IOException e) {
            log.error("启动adbkit失败", e);
            return Response.fail(e.getMessage());
        }
    }

    public Response stopAdbKit(String mobileId) {
        MobileDevice mobileDevice = MobileDeviceHolder.getConnectedDevice(mobileId);
        if (mobileDevice == null) {
            return Response.fail("设备未连接");
        }

        ((AndroidDevice) mobileDevice).getAdbKit().stop();
        return Response.success("停止完成");
    }

    public Response aaptDumpBadging(String apkDownloadUrl) {
        File apkFile = null;

        try {
            apkFile = HttpUtil.downloadFile(apkDownloadUrl);
            String result = AndroidUtil.aaptDumpBadging(apkFile.getAbsolutePath());
            return Response.success("ok", result);
        } catch (IOException e) {
            log.error("io error", e);
            return Response.fail(e.getMessage());
        } finally {
            FileUtils.deleteQuietly(apkFile);
        }
    }

    public Response getImeList(String mobileId) {
        MobileDevice device = MobileDeviceHolder.getConnectedDevice(mobileId);
        if (device == null) {
            return Response.fail("设备未连接");
        }

        try {
            return Response.success(AndroidUtil.getImeList(((AndroidDevice) device).getIDevice()));
        } catch (Exception e) {
            log.error("[{}]获取输入法列表异常", device.getId(), e);
            return Response.fail(e.getMessage());
        }
    }

    public Response setIme(String mobileId, String ime) {
        MobileDevice device = MobileDeviceHolder.getConnectedDevice(mobileId);
        if (device == null) {
            return Response.fail("设备未连接");
        }

        try {
            AndroidUtil.setIme(((AndroidDevice) device).getIDevice(), ime);
            return Response.success("设置成功");
        } catch (Exception e) {
            log.error("[{}]设置输入法异常", device.getId(), e);
            return Response.fail(e.getMessage());
        }
    }
}