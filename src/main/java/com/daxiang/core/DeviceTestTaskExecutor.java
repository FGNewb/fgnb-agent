package com.daxiang.core;

import com.alibaba.fastjson.JSONObject;
import com.daxiang.App;
import com.daxiang.server.ServerClient;
import com.daxiang.core.javacompile.JavaCompiler;
import com.daxiang.core.testng.TestNGCodeConvertException;
import com.daxiang.core.testng.TestNGCodeConverter;
import com.daxiang.core.testng.TestNGRunner;
import com.daxiang.model.devicetesttask.DeviceTestTask;
import com.daxiang.service.MobileService;
import com.daxiang.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.dvare.dynamic.exceptions.DynamicCompilerException;
import org.springframework.util.StringUtils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by jiangyitao.
 */
@Slf4j
public class DeviceTestTaskExecutor {
    /**
     * 执行测试任务队列
     */
    private final BlockingQueue<DeviceTestTask> testTaskQueue = new LinkedBlockingQueue<>();
    /**
     * 执行测试任务线程
     */
    private Thread executeTestTaskThread;

    private MobileDevice mobileDevice;
    private String deviceId;

    public DeviceTestTaskExecutor(MobileDevice mobileDevice) {
        this.mobileDevice = mobileDevice;
        this.deviceId = mobileDevice.getId();

        executeTestTaskThread = new Thread(() -> {
            while (true) {
                DeviceTestTask deviceTestTask;
                try {
                    deviceTestTask = testTaskQueue.take(); // 没有测试任务，线程阻塞在此
                } catch (InterruptedException e) {
                    // 调用executeTestTaskThread.interrupt()可以执行到这里
                    log.info("[自动化测试][{}]停止获取测试任务", deviceId);
                    break;
                }

                try {
                    executeTestTask(deviceTestTask);
                } catch (Throwable e) {
                    log.error("[自动化测试][{}]执行测试任务出错, deviceTestTaskId: {}", deviceId, deviceTestTask.getId(), e);
                }
            }
        });
        executeTestTaskThread.start();
    }

    /**
     * 提交测试任务
     *
     * @param deviceTestTask
     */
    public void commitTestTask(DeviceTestTask deviceTestTask) {
        if (!testTaskQueue.offer(deviceTestTask)) {
            throw new RuntimeException("提交测试任务失败, deviceTestTaskId: " + deviceTestTask.getId());
        }
    }

    /**
     * 执行测试任务
     *
     * @param deviceTestTask
     */
    private void executeTestTask(DeviceTestTask deviceTestTask) {
        log.info("[自动化测试][{}]开始执行测试任务, deviceTestTaskId: {}", deviceId, deviceTestTask.getId());

        MobileService mobileService = App.getBean(MobileService.class);

        // 设备变为使用中
        mobileDevice.getDevice().setUsername(deviceTestTask.getTestPlan().getName());
        mobileService.saveUsingDeviceToServer(mobileDevice);

        try {
            String className = "Test_" + UUIDUtil.getUUID();
            String code = new TestNGCodeConverter().convert(deviceTestTask, className);
            log.info("[自动化测试][{}]deviceTestTaskId: {}, 转换代码: {}", deviceId, deviceTestTask.getId(), code);
            updateDeviceTestTaskCode(deviceTestTask.getId(), code);

            Class clazz = JavaCompiler.compile(className, code);

            JSONObject caps = new JSONObject();
            try {
                if (StringUtils.hasText(deviceTestTask.getCapabilities())) {
                    caps = JSONObject.parseObject(deviceTestTask.getCapabilities());
                }
            } catch (Exception e) {
                log.warn("parse capabilities fail, deviceTestTask: {}", deviceTestTask, e);
            }
            mobileDevice.freshAppiumDriver(caps);

            TestNGRunner.runTestCases(new Class[]{clazz}, deviceTestTask.getTestPlan().getFailRetryCount());
        } catch (TestNGCodeConvertException | DynamicCompilerException e) {
            log.error("[自动化测试][{}]deviceTestTaskId: {}", deviceId, deviceTestTask.getId(), e);
            updateDeviceTestTaskStatusAndErrMsg(deviceTestTask.getId(), DeviceTestTask.ERROR_STATUS, ExceptionUtils.getStackTrace(e));
        } finally {
            mobileDevice.quitAppiumDriver();
            mobileService.saveIdleDeviceToServer(mobileDevice);
        }
    }

    private void updateDeviceTestTaskCode(Integer deviceTestTaskId, String code) {
        DeviceTestTask deviceTestTask = new DeviceTestTask();
        deviceTestTask.setId(deviceTestTaskId);
        deviceTestTask.setCode(code);
        ServerClient.getInstance().updateDeviceTestTask(deviceTestTask);
    }

    private void updateDeviceTestTaskStatusAndErrMsg(Integer deviceTestTaskId, Integer status, String errMsg) {
        DeviceTestTask deviceTestTask = new DeviceTestTask();
        deviceTestTask.setId(deviceTestTaskId);
        deviceTestTask.setStatus(status);
        deviceTestTask.setErrMsg(errMsg);
        ServerClient.getInstance().updateDeviceTestTask(deviceTestTask);
    }
}
